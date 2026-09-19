package com.lanlinju.server

import com.lanlinju.animius.util.SettingsStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.nio.file.Path

/**
 * 字幕处理：VTT 解析、LLM 分批翻译、双语合成、磁盘缓存。
 * LLM 走 OpenAI 兼容接口（settings: llmBaseUrl / llmApiKey / llmModel）。
 */
object Subtitles {

    private val json = Json { ignoreUnknownKeys = true }

    // 直连客户端（不走出站代理；自建/内网 LLM 也能用），信任所有证书以兼容自签名端点
    private val llmClient by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    sslSocketFactory(com.lanlinju.animius.util.trustAllContext.socketFactory,
                        com.lanlinju.animius.util.trustAllCerts[0] as javax.net.ssl.X509TrustManager)
                    hostnameVerifier { _, _ -> true }
                }
            }
            install(io.ktor.client.plugins.HttpTimeout) { requestTimeoutMillis = 120_000 }
        }
    }

    fun configured(): Boolean =
        !SettingsStore.get("llmBaseUrl").isNullOrBlank() &&
            !SettingsStore.get("llmModel").isNullOrBlank()

    suspend fun testLlm(): String {
        val base = SettingsStore.get("llmBaseUrl")?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("未配置 LLM API Base")
        val reply = chat(
            base,
            SettingsStore.get("llmApiKey") ?: "",
            SettingsStore.get("llmModel") ?: throw IllegalStateException("未配置模型名"),
            "请回复：OK",
        )
        return reply.take(100)
    }

    private fun cachePath(u: String, dataDir: Path): Path {
        val hash = MessageDigest.getInstance("SHA-256").digest(u.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)
        val dir = dataDir.resolve("subtitles")
        dir.toFile().mkdirs()
        return dir.resolve("$hash.vtt")
    }

    // 字幕拉取客户端：带出站代理（字幕常在被墙 CDN 上）
    private val vttClient by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    sslSocketFactory(com.lanlinju.animius.util.trustAllContext.socketFactory,
                        com.lanlinju.animius.util.trustAllCerts[0] as javax.net.ssl.X509TrustManager)
                    hostnameVerifier { _, _ -> true }
                    runCatching {
                        SettingsStore.get("outboundProxy")?.takeIf { it.isNotBlank() }?.let { spec ->
                            val uri = java.net.URI(spec.trim())
                            val type = if (uri.scheme.startsWith("socks")) java.net.Proxy.Type.SOCKS else java.net.Proxy.Type.HTTP
                            proxy(java.net.Proxy(type, java.net.InetSocketAddress(uri.host, if (uri.port > 0) uri.port else 8080)))
                        }
                    }
                }
            }
            followRedirects = true
        }
    }

    suspend fun fetchVtt(url: String, referer: String?): String = withContext(Dispatchers.IO) {
        try {
            vttClient.get(url) {
                headers {
                    append(HttpHeaders.UserAgent, com.lanlinju.animius.util.DefaultUserAgent)
                    if (!referer.isNullOrBlank()) append("Referer", referer)
                }
            }.bodyAsText()
        } catch (e: Exception) {
            throw IllegalStateException("字幕拉取失败（CDN 可能需要出站代理）: ${e.message?.take(120)}")
        }
    }

    /**
     * 返回双语 VTT：翻译结果按源 URL 缓存；LLM 未配置时返回原文。
     */
    suspend fun bilingualVtt(url: String, referer: String?, original: String, dataDir: Path): String {
        if (!configured()) return original
        val cache = cachePath(url, dataDir)
        if (cache.toFile().exists()) return cache.toFile().readText()

        val cues = parseVtt(original)
        val texts = cues.map { it.text }
        val batchSize = 40
        var done = 0
        val translated = texts.toMutableList()
        val base = SettingsStore.get("llmBaseUrl")!!.trim().trimEnd('/')
        val key = SettingsStore.get("llmApiKey") ?: ""
        val model = SettingsStore.get("llmModel")!!.trim()

        texts.chunked(batchSize).forEach { batch ->
            val numbered = batch.mapIndexed { i, t -> "${i + 1}. ${t.replace('\n', ' ')}" }.joinToString("\n")
            val reply = chatWithRetry(base, key, model, numbered)
            reply.forEachIndexed { i, t ->
                val idx = done + i
                if (idx < translated.size && t.isNotBlank()) translated[idx] = t
            }
            done += batch.size
            // 每批写一次进度缓存，避免中途失败全丢
            cache.toFile().writeText(buildBilingualVtt(original, cues, texts.zip(translated).toMap()))
        }
        val result = buildBilingualVtt(original, cues, texts.zip(translated).toMap())
        cache.toFile().writeText(result)
        return result
    }

    private suspend fun chatWithRetry(base: String, key: String, model: String, userMsg: String): List<String> {
        repeat(2) { attempt ->
            runCatching {
                val reply = chat(base, key, model, userMsg)
                val arr = Regex("\\{[\\s\\S]*\\}").find(reply)?.value ?: return@runCatching
                val t = json.parseToJsonElement(arr).jsonObject["t"]?.jsonArray
                    ?: return@runCatching
                return t.map { it.jsonPrimitive.content }
            }
            kotlinx.coroutines.delay(500L * (attempt + 1))
        }
        return emptyList()
    }

    private suspend fun chat(base: String, apiKey: String, model: String, userMsg: String): String =
        withContext(Dispatchers.IO) {
            val endpoint = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
            llmClient.post(endpoint) {
                if (apiKey.isNotBlank()) headers { append(HttpHeaders.Authorization, "Bearer $apiKey") }
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("model", model)
                    put("temperature", 0.2)
                    put("messages", kotlinx.serialization.json.JsonArray(listOf(
                        buildJsonObject {
                            put("role", "system")
                            put("content", "你是专业的影视字幕翻译。用户给出编号的字幕台词，请将每条翻译成自然流畅的简体中文口语。" +
                                "只输出 JSON：{\"t\":[\"译文1\",\"译文2\",...]}，数组长度和顺序必须与输入一致，不要输出任何其他文字。")
                        },
                        buildJsonObject {
                            put("role", "user")
                            put("content", userMsg)
                        },
                    )))
                }.toString())
            }.bodyAsText()
                .let { resp ->
                    json.parseToJsonElement(resp).jsonObject["choices"]
                        ?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                        ?.get("content")?.jsonPrimitive?.content
                        ?: throw IllegalStateException("LLM 响应格式异常: ${resp.take(200)}")
                }
        }

    // ---------- VTT 解析 / 合成 ----------

    private class Cue(val timing: String, val text: String)

    private fun parseVtt(vtt: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        val blocks = vtt.replace("\r\n", "\n").split("\n\n")
        for (block in blocks) {
            val lines = block.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            if (lines[0].startsWith("WEBVTT") || lines[0].startsWith("NOTE")) continue
            val timingIdx = lines.indexOfFirst { " --> " in it }
            if (timingIdx < 0) continue
            val text = lines.drop(timingIdx + 1).joinToString("\n").trim()
            if (text.isNotBlank()) cues.add(Cue(lines[timingIdx], text))
        }
        return cues
    }

    private fun buildBilingualVtt(original: String, cues: List<Cue>, translations: Map<String, String>): String {
        val sb = StringBuilder("WEBVTT\n\n")
        var i = 0
        for (block in original.replace("\r\n", "\n").split("\n\n")) {
            val lines = block.lines()
            if (lines.isEmpty() || lines[0].startsWith("WEBVTT") || lines[0].startsWith("NOTE")) {
                continue
            }
            val timingIdx = lines.indexOfFirst { " --> " in it }
            if (timingIdx < 0) continue
            val text = lines.drop(timingIdx + 1).joinToString("\n").trim()
            val cn = translations[text] ?: ""
            sb.append(++i).append('\n')
            sb.append(lines[timingIdx]).append('\n')
            if (cn.isNotBlank() && cn != text) sb.append(text).append('\n').append(cn).append('\n')
            else sb.append(text).append('\n')
            sb.append('\n')
        }
        return sb.toString()
    }
}
