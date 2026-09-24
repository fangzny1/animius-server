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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

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
                    sslSocketFactory(
                        com.lanlinju.animius.util.trustAllContext.socketFactory,
                        com.lanlinju.animius.util.trustAllCerts[0] as javax.net.ssl.X509TrustManager
                    )
                    hostnameVerifier { _, _ -> true }
                }
            }
            install(io.ktor.client.plugins.HttpTimeout) { requestTimeoutMillis = 120_000 }
        }
    }

    // 字幕拉取：直连/代理双路自动选（有的字幕 CDN 必须走代理，有的走代理会被 403），按域名记住走哪条
    private fun vttClientOf(useProxy: Boolean) = HttpClient(OkHttp) {
        engine {
            config {
                sslSocketFactory(
                    com.lanlinju.animius.util.trustAllContext.socketFactory,
                    com.lanlinju.animius.util.trustAllCerts[0] as javax.net.ssl.X509TrustManager
                )
                hostnameVerifier { _, _ -> true }
                if (useProxy) {
                    runCatching {
                        SettingsStore.get("outboundProxy")?.takeIf { it.isNotBlank() }?.let { spec ->
                            val uri = java.net.URI(spec.trim())
                            val type = if (uri.scheme.startsWith("socks")) java.net.Proxy.Type.SOCKS else java.net.Proxy.Type.HTTP
                            proxy(java.net.Proxy(type, java.net.InetSocketAddress(uri.host, if (uri.port > 0) uri.port else 8080)))
                        }
                    }
                }
            }
        }
        followRedirects = true
    }

    private val vttDirect by lazy { vttClientOf(useProxy = false) }
    private val vttProxied by lazy { vttClientOf(useProxy = true) }
    private val vttRouteMemo = ConcurrentHashMap<String, Boolean>() // host -> 需走代理

    private fun vttClientOrder(url: String): List<HttpClient> {
        val host = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")
        return if (vttRouteMemo[host] == true) listOf(vttProxied, vttDirect) else listOf(vttDirect, vttProxied)
    }

    // VTT 内存缓存：字幕 URL 的签名是一次性的，同 URL 只回源一次
    private val vttCache = ConcurrentHashMap<String, String>()

    // 翻译进度: hash -> [已完成批次, 总批次]；errors: hash -> 错误信息
    private val progressMap = ConcurrentHashMap<String, IntArray>()
    private val errorMap = ConcurrentHashMap<String, String>()
    // 正在翻译的 hash（去重用，防止同集并发触发多个翻译任务）
    private val inflight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun configured(): Boolean =
        !SettingsStore.get("llmBaseUrl").isNullOrBlank() &&
            !SettingsStore.get("llmModel").isNullOrBlank()

    /** 规范化 VTT：清洗内联标签，统一格式（Artplayer 兼容） */
    fun normalizeVtt(original: String): String {
        val cues = parseVtt(original)
        if (cues.isEmpty()) return original
        return buildVtt(cues) { null }
    }

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

    fun hashOf(u: String): String =
        MessageDigest.getInstance("SHA-256").digest(u.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)

    private fun cachePath(key: String, dataDir: Path): Path {
        val dir = dataDir.resolve("subtitles")
        dir.toFile().mkdirs()
        return dir.resolve("${key.replace(Regex("[^a-zA-Z0-9]"), "").take(40)}.vtt")
    }

    /**
     * 统一缓存键（再哈希成十六进制）：显式 cacheKey 必须含集数标识（label|lang|集URL，由 /api/video 生成），
     * 跨重新解析稳定但**按集区分**；否则按源 URL 哈希
     */
    fun keyOf(url: String, cacheKey: String?): String = hashOf(cacheKey ?: url)

    /** 翻译进度快照（半成品），完成后删除；只认最终 .vtt 为成品 */
    private fun partPath(key: String, dataDir: Path): Path {
        val c = cachePath(key, dataDir)
        return c.resolveSibling(c.fileName.toString() + ".part.json")
    }

    /** 已缓存的完整双语字幕；没有（或读失败）返回 null */
    fun cachedBilingual(key: String, dataDir: Path): String? =
        cachePath(key, dataDir).toFile().takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    /** 原文（规范化 VTT）缓存：同集同轨只回源一次，签名过期后原文/翻译都能继续用 */
    private fun origPath(key: String, dataDir: Path): Path {
        val c = cachePath(key, dataDir)
        return c.resolveSibling(c.fileName.toString().removeSuffix(".vtt") + ".orig")
    }

    fun cachedOriginal(key: String, dataDir: Path): String? =
        origPath(key, dataDir).toFile().takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    fun saveOriginal(key: String, dataDir: Path, vtt: String) {
        runCatching { origPath(key, dataDir).toFile().writeText(vtt) }
    }

    private class Part(val done: Int, val total: Int, val t: List<String?>)

    private fun readPart(file: java.io.File): Part? = runCatching {
        val o = json.parseToJsonElement(file.readText()).jsonObject
        Part(
            o["done"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            o["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            o["t"]!!.jsonArray.map { el -> if (el is JsonNull) null else el.jsonPrimitive.content },
        )
    }.getOrNull()

    private fun writePart(file: java.io.File, done: Int, total: Int, t: List<String?>) {
        runCatching {
            file.writeText(buildJsonObject {
                put("done", done)
                put("total", total)
                put("t", JsonArray(t.map { s -> if (s.isNullOrBlank()) JsonNull else JsonPrimitive(s) }))
            }.toString())
        }
    }

    /**
     * 后台启动翻译任务（幂等：已缓存返回 done，进行中返回 running）。
     */
    fun prepareAsync(url: String, referer: String?, dataDir: Path, cacheKey: String? = null): JsonObject {
        val hash = keyOf(url, cacheKey)
        // 只认完整成品；半成品不算 done，允许续翻
        cachedBilingual(hash, dataDir)?.let { return buildJsonObject { put("status", "done") } }
        errorMap.remove(hash)
        val existing = progressMap[hash]
        if (existing != null && existing[0] < existing[1]) {
            return buildJsonObject { put("status", "running"); put("done", existing[0]); put("total", existing[1]) }
        }
        if (!configured()) {
            return buildJsonObject { put("status", "error"); put("error", "LLM 未配置") }
        }
        // 同一集的翻译任务去重，防止重复点击/换轨来回触发并发翻译
        if (!inflight.add(hash)) {
            return buildJsonObject { put("status", "running"); put("done", 0); put("total", 0) }
        }
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                // 优先用已缓存原文（签名过期也能翻）；没有才回源并落缓存
                val original = cachedOriginal(hash, dataDir)
                    ?: fetchVtt(url, referer).also { saveOriginal(hash, dataDir, normalizeVtt(it)) }
                bilingualVtt(url, referer, original, dataDir, cacheKey)
            } catch (it: Throwable) {
                errorMap[hash] = it.message ?: it.javaClass.simpleName
                progressMap.remove(hash)
            } finally {
                inflight.remove(hash)
            }
        }
        return buildJsonObject { put("status", "started") }
    }

    fun progressStatus(url: String, dataDir: Path, cacheKey: String? = null): JsonObject {
        val hash = keyOf(url, cacheKey)
        if (cachedBilingual(hash, dataDir) != null) {
            val p = progressMap[hash]
            return buildJsonObject { put("status", "done"); put("done", p?.get(0) ?: 1); put("total", p?.get(1) ?: 1) }
        }
        errorMap[hash]?.let { return buildJsonObject { put("status", "error"); put("error", it.take(150)) } }
        progressMap[hash]?.let { return buildJsonObject { put("status", "running"); put("done", it[0]); put("total", it[1]) } }
        // 有进度快照或任务在跑（正在拉 VTT、进度尚未登记）都算 running，前端别当 idle 死等
        if (inflight.contains(hash)) {
            val part = readPart(partPath(hash, dataDir).toFile())
            return buildJsonObject {
                put("status", "running"); put("done", part?.done ?: 0); put("total", part?.total ?: 0)
            }
        }
        return buildJsonObject { put("status", "idle") }
    }

    suspend fun fetchVtt(url: String, referer: String?): String = withContext(Dispatchers.IO) {
        vttCache[url]?.let { return@withContext it }
        var lastError: Exception? = null
        // CDN 反盗链只认播放页/embed 站来源，自来源或无来源会 403 —— 依次试候选 Referer
        val self = runCatching {
            val u = java.net.URI(url)
            "${u.scheme}://${u.host}/"
        }.getOrDefault("")
        val candidates = listOfNotNull(
            referer?.takeIf { it.isNotBlank() },
            self.takeIf { it.isNotBlank() && it != referer },
            "",
        ).distinct()
        for (ref in candidates) {
            for (client in vttClientOrder(url)) {
                try {
                    val body = client.get(url) {
                        headers {
                            append(HttpHeaders.UserAgent, com.lanlinju.animius.util.DefaultUserAgent)
                            if (ref.isNotBlank()) append("Referer", ref)
                        }
                    }.bodyAsText().let { raw ->
                        // 剥 UTF-8 BOM（Kotlin 的 trimStart 不认 BOM，需显式指定）
                        val bom = 0xFEFF.toChar()
                        if (raw.firstOrNull() == bom) raw.substring(1) else raw
                    }
                    // CDN 限流/签名过期时可能返回 200 + HTML 错误页，绝不能缓存或端给播放器；
                    // 外挂字幕库给的是 .ass/.srt，顺手转成 VTT，后续翻译/缓存管线全复用
                    val vtt = toVtt(body)
                        ?: throw IllegalStateException("字幕内容异常 head=「" + body.take(40) + "」")
                    val host = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")
                    vttRouteMemo[host] = client === vttProxied   // 记住这个域名走哪条路
                    if (vttCache.size > 30) vttCache.clear()
                    vttCache[url] = vtt
                    return@withContext vtt
                } catch (e: Exception) {
                    lastError = e
                }
            }
            kotlinx.coroutines.delay(600L)
        }
        throw IllegalStateException(
            "字幕拉取失败（CDN 可能需要出站代理/稍后重试）: " + (lastError?.message?.take(120) ?: "unknown")
        )
    }

    fun cacheStats(dataDir: Path): Pair<Int, Long> {
        val dir = dataDir.resolve("subtitles").toFile()
        val files = dir.listFiles { f -> f.extension == "vtt" } ?: return 0 to 0
        return files.size to files.sumOf { it.length() }
    }

    fun clearCache(dataDir: Path): Int {
        val dir = dataDir.resolve("subtitles").toFile()
        val files = dir.listFiles { f -> f.extension == "vtt" || f.extension == "orig" || f.name.endsWith(".part.json") } ?: return 0
        val n = files.count { it.extension == "vtt" }
        files.forEach { it.delete() }
        return n
    }

    /**
     * 返回双语 VTT：翻译结果按源 URL 缓存；LLM 未配置时返回原文。
     */
    suspend fun bilingualVtt(url: String, referer: String?, original: String, dataDir: Path, cacheKey: String? = null): String {
        val pk = keyOf(url, cacheKey)
        cachedBilingual(pk, dataDir)?.let { return it }
        if (!configured()) return original

        val cues = parseVtt(original)
        if (cues.isEmpty()) return original
        val cache = cachePath(pk, dataDir)
        val part = partPath(pk, dataDir).toFile()

        val base = SettingsStore.get("llmBaseUrl")!!.trim().trimEnd('/')
        val llmKey = SettingsStore.get("llmApiKey") ?: ""
        val model = SettingsStore.get("llmModel")!!.trim()

        val batchSize = 40
        val batches = cues.indices.toList().chunked(batchSize)

        // 恢复上次中断的半成品（条数对得上才认，防止换轨/换源错位），中断的批可续翻
        val saved = readPart(part)?.takeIf { it.t.size == cues.size && it.total == batches.size }
        val translated = MutableList<String?>(cues.size) { saved?.t?.get(it)?.takeIf { s -> s.isNotBlank() } }
        progressMap[pk] = intArrayOf(saved?.done ?: 0, batches.size)

        batches.forEachIndexed { bi, batch ->
            val pending = batch.filter { translated[it].isNullOrBlank() }
            if (pending.isNotEmpty()) {
                val numbered = pending.mapIndexed { i, idx -> "${i + 1}. ${cues[idx].text.replace('\n', ' ')}" }.joinToString("\n")
                val reply = chatWithRetry(base, llmKey, model, numbered)
                pending.forEachIndexed { i, idx ->
                    if (i < reply.size && reply[i].isNotBlank()) translated[idx] = reply[i]
                }
            }
            progressMap[pk]?.let { it[0] = bi + 1 }
            // 进度只写快照文件（半成品），绝不提前落地正式缓存
            writePart(part, bi + 1, batches.size, translated)
        }

        val result = buildVtt(cues) { i -> translated.getOrNull(i)?.takeIf { it.isNotBlank() } }
        if (translated.none { !it.isNullOrBlank() }) {
            // 一条都没翻出来（模型/配置问题）：保留进度快照供重试，绝不落地假“成品”
            throw IllegalStateException("LLM 未返回任何译文（检查设置页模型配置）")
        }
        cache.toFile().writeText(result)
        part.delete()
        progressMap.remove(pk)
        return result
    }

    private suspend fun chatWithRetry(base: String, key: String, model: String, userMsg: String): List<String> {
        var lastError: String? = null
        repeat(2) { attempt ->
            try {
                val reply = chat(base, key, model, userMsg)
                    .replace(Regex("(?s)<think>.*?</think>"), "") // 去掉思考模型的思维链
                val objStr = Regex("\\{[\\s\\S]*\\}").find(reply)?.value
                    ?: throw IllegalStateException("模型未返回 JSON: ${reply.take(80)}")
                val t = runCatching { json.parseToJsonElement(objStr).jsonObject["t"]?.jsonArray }
                    .getOrElse {
                        // 兼容单引号 JSON（部分模型输出 Python 风格）
                        json.parseToJsonElement(objStr.replace('\'', '"')).jsonObject["t"]?.jsonArray
                    } ?: throw IllegalStateException("JSON 里没有 t 数组: ${objStr.take(80)}")
                return t.map { it.jsonPrimitive.content }
            } catch (e: Exception) {
                lastError = e.message?.take(150) ?: e.javaClass.simpleName
                kotlinx.coroutines.delay(500L * (attempt + 1))
            }
        }
        // 翻译失败必须抛出来让上层报错，绝不能悄悄返回空列表装成功
        throw IllegalStateException("LLM 翻译失败: ${lastError ?: "unknown"}")
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

    // ---------- 字幕格式转换（外挂字幕库多是 ASS/SRT） ----------

    /** 已是 VTT 原样返回；ASS/SRT 转 VTT；都不是返回 null */
    fun toVtt(raw: String): String? {
        val body = raw.trim()
        return when {
            body.startsWith("WEBVTT") -> raw
            "[Script Info]" in body || "Dialogue:" in body -> assToVtt(raw)
            Regex("""\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->""").containsMatchIn(body) -> srtToVtt(raw)
            else -> null
        }
    }

    private fun assToVtt(ass: String): String {
        val sb = StringBuilder("WEBVTT\r\n\r\n")
        ass.lineSequence()
            .filter { it.trimStart().startsWith("Dialogue:") }
            .forEach { line ->
                val parts = line.substringAfter("Dialogue:").split(",", limit = 10)
                if (parts.size < 10) return@forEach
                val text = parts[9]
                    .replace(Regex("""\{[^}]*\}"""), "")          // {\an8} 等样式标签
                    .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                    .lines().joinToString("\n") { it.trim() }.trim()
                if (text.isNotBlank()) {
                    sb.append(assTime(parts[1].trim())).append(" --> ").append(assTime(parts[2].trim())).append("\r\n")
                        .append(text).append("\r\n\r\n")
                }
            }
        return sb.toString()
    }

    /** ASS 时间 H:MM:SS.cc -> VTT 的 0H:MM:SS.mmm */
    private fun assTime(t: String): String {
        val p = t.split(":", ".")
        if (p.size < 3) return "00:00:00.000"
        val h = p[0].toIntOrNull() ?: 0
        val m = p[1].toIntOrNull() ?: 0
        val sec = p[2].toIntOrNull() ?: 0
        val cs = p.getOrNull(3)?.toIntOrNull() ?: 0
        return "%02d:%02d:%02d.%03d".format(h, m, sec, cs * 10)
    }

    private fun srtToVtt(srt: String): String {
        val sb = StringBuilder("WEBVTT\r\n\r\n")
        srt.replace("\r\n", "\n").split(Regex("""\n\s*\n""")).forEach { block ->
            val lines = block.lines().filter { it.isNotBlank() }
            val ti = lines.indexOfFirst { "-->" in it }
            if (ti < 0) return@forEach
            val text = lines.drop(ti + 1).joinToString("\n").trim()
            if (text.isNotBlank()) {
                sb.append(lines[ti].replace(",", ".")).append("\r\n").append(text).append("\r\n\r\n")
            }
        }
        return sb.toString()
    }

    // ---------- 外挂字幕库（Kitsunekko 日文字幕） ----------

    data class RemoteSub(val folder: String, val name: String, val url: String)

    private const val KIT_BASE = "https://kitsunekko.net"

    @Volatile private var folderCache: Pair<Long, List<String>>? = null

    private suspend fun kitGet(url: String): String {
        var last: Exception? = null
        for (client in vttClientOrder(url)) {
            try {
                return client.get(url) {
                    headers { append(HttpHeaders.UserAgent, com.lanlinju.animius.util.DefaultUserAgent) }
                }.bodyAsText()
            } catch (e: Exception) { last = e }
        }
        throw IllegalStateException("字幕库访问失败: " + (last?.message?.take(80) ?: "unknown"))
    }

    private fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** 按关键词搜日文字幕：先模糊匹配目录，再列文件（只看前三匹配，避免拉爆） */
    suspend fun searchRemote(query: String): List<RemoteSub> = withContext(Dispatchers.IO) {
        val folders = remoteFolders()
        val q = key(query)
        if (q.isBlank()) return@withContext emptyList()
        val direct = folders.filter { key(it).contains(q) || q.contains(key(it)) }
        val scored = if (direct.isNotEmpty()) direct else folders.map { f -> f to f.split(Regex("[^A-Za-z0-9]+"))
            .count { w -> w.length > 2 && q.contains(w.lowercase()) } }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(3).map { it.first }
        scored.take(3).flatMap { folder -> remoteFiles(folder) }
    }

    private suspend fun remoteFolders(): List<String> {
        folderCache?.let { (ts, list) -> if (System.currentTimeMillis() - ts < 24 * 3600_000) return list }
        val html = kitGet("$KIT_BASE/dirlist.php?dir=subtitles/japanese/")
        val list = Regex("""dirlist\.php\?dir=subtitles%2Fjapanese%2F([^"&/]+)%2F""")
            .findAll(html).map { java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }
            .distinct().filter { it.isNotBlank() }.toList()
        if (list.isNotEmpty()) folderCache = System.currentTimeMillis() to list
        return list
    }

    private suspend fun remoteFiles(folder: String): List<RemoteSub> {
        val enc = java.net.URLEncoder.encode("subtitles/japanese/$folder/", "UTF-8")
        val html = kitGet("$KIT_BASE/dirlist.php?dir=$enc")
        return Regex("href=\"([^\"]+\\.(?:ass|srt|vtt))\"", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.groupValues[1] }
            .distinct().map { href ->
                val name = java.net.URLDecoder.decode(href.substringAfterLast('/'), "UTF-8")
                val abs = if (href.startsWith("http")) href else KIT_BASE + (if (href.startsWith("/")) "" else "/") + href
                RemoteSub(folder, name, abs)
            }.toList()
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
            // 去掉内联标签（<c.xxx> 等），Artplayer 的字幕解析器不认
            val text = lines.drop(timingIdx + 1).joinToString("\n")
                .replace(Regex("<[^>]+>"), "").trim()
            if (text.isNotBlank()) cues.add(Cue(lines[timingIdx], text))
        }
        return cues
    }

    /** translation(i) 返回第 i 条 cue 的中文（null 则只显示原文）。行尾用 CRLF：与源站 VTT 一致，Artplayer 解析器按 CRLF 分块 */
    private fun buildVtt(cues: List<Cue>, translation: (Int) -> String?): String {
        val sb = StringBuilder("WEBVTT\r\n\r\n")
        cues.forEachIndexed { i, cue ->
            sb.append(cue.timing).append("\r\n")
            val cn = translation(i)
            if (!cn.isNullOrBlank() && cn != cue.text) sb.append(cue.text).append("\r\n").append(cn).append("\r\n")
            else sb.append(cue.text).append("\r\n")
            sb.append("\r\n")
        }
        return sb.toString()
    }
}
