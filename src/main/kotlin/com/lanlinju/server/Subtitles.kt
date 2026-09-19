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
import kotlinx.coroutines.launch
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
        val model = SettingsStore.get("llmModel") ?: throw IllegalStateException("未配置模型名")
        // 用真实翻译管线格式做测试（含 JSON 解析验证），只发 2 条不浪费限额
        val reply = chatWithRetry(
            base,
            SettingsStore.get("llmApiKey") ?: "",
            model,
            "1. Good morning.\n2. Who is that girl?",
        )
        if (reply.size < 2) throw IllegalStateException("模型未按 JSON 格式返回，试试换模型或重试")
        return "双语管线 OK: ${reply.joinToString(" / ").take(80)}"
    }

    private fun cachePath(u: String, dataDir: Path): Path {
        val dir = dataDir.resolve("subtitles")
        dir.toFile().mkdirs()
        return dir.resolve("${hashOf(u)}.vtt")
    }

    fun hashOf(u: String): String =
        MessageDigest.getInstance("SHA-256").digest(u.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)

    // 翻译进度: hash -> [已完成批次, 总批次]；errors: hash -> 错误信息
    private val progressMap = java.util.concurrent.ConcurrentHashMap<String, IntArray>()
    private val errorMap = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 后台启动翻译任务（幂等：已缓存返回 done，进行中返回 running）。
     */
    fun prepareAsync(url: String, referer: String?, dataDir: Path): kotlinx.serialization.json.JsonObject {
        val hash = hashOf(url)
        if (cachePath(url, dataDir).toFile().exists()) {
            return buildJsonObject { put("status", "done") }
        }
        errorMap.remove(hash)
        val existing = progressMap[hash]
        if (existing != null && existing[0] < existing[1]) {
            return buildJsonObject { put("status", "running"); put("done", existing[0]); put("total", existing[1]) }
        }
        if (!configured()) {
            return buildJsonObject { put("status", "error"); put("error", "LLM 未配置") }
        }
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val original = fetchVtt(url, referer)
                bilingualVtt(url, referer, original, dataDir)
            }.onFailure {
                errorMap[hash] = it.message ?: it.javaClass.simpleName
                progressMap.remove(hash)
            }
        }
        return buildJsonObject { put("status", "started") }
    }

    fun progressStatus(url: String, dataDir: Path): kotlinx.serialization.json.JsonObject {
        val hash = hashOf(url)
        if (cachePath(url, dataDir).toFile().exists()) {
            val p = progressMap[hash]
            return buildJsonObject { put("status", "done"); put("done", p?.get(0) ?: 1); put("total", p?.get(1) ?: 1) }
        }
        errorMap[hash]?.let { return buildJsonObject { put("status", "error"); put("error", it.take(150)) } }
        progressMap[hash]?.let { return buildJsonObject { put("status", "running"); put("done", it[0]); put("total", it[1]) } }
        return buildJsonObject { put("status", "idle") }
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

        val batches = texts.chunked(batchSize)
        progressMap[hashOf(url)] = intArrayOf(0, batches.size)
        batches.forEachIndexed { bi, batch ->
            val numbered = batch.mapIndexed { i, t -> "${i + 1}. ${t.replace('\n', ' ')}" }.joinToString("\n")
            val reply = chatWithRetry(base, key, model, numbered)
            reply.forEachIndexed { i, t ->
                val idx = done + i
                if (idx < translated.size && t.isNotBlank()) translated[idx] = t
            }
            done += batch.size
            progressMap[hashOf(url)]?.let { it[0] = bi + 1 }
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
                    .replace(Regex("(?s)<think>.*?</think>"), "") // 去掉思考模型的思维链
                val objStr = Regex("\\{[\\s\\S]*\\}").find(reply)?.value ?: return@runCatching
                val t = runCatching { json.parseToJsonElement(objStr).jsonObject["t"]?.jsonArray }
                    .getOrElse {
                        // 兼容单引号 JSON（部分模型输出 Python 风格）
                        json.parseToJsonElement(objStr.replace('\'', '"')).jsonObject["t"]?.jsonArray
                    } ?: return@runCatching
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
