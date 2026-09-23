package com.lanlinju.server

import com.lanlinju.animius.data.remote.api.AnimeApiImpl
import com.lanlinju.animius.data.remote.dandanplay.DandanplayClient
import com.lanlinju.animius.data.remote.dandanplay.dto.toDanmakuItemOrNull
import com.lanlinju.animius.data.remote.parse.AnimeSource
import com.lanlinju.animius.data.remote.parse.util.CaptchaCookieManager
import com.lanlinju.animius.util.DefaultUserAgent
import com.lanlinju.animius.util.SettingsStore
import com.lanlinju.animius.util.SourceHolder
import com.lanlinju.animius.util.SourceMode
import com.lanlinju.animius.util.createHttpClient
import com.lanlinju.animius.util.trustAllCerts
import com.lanlinju.animius.util.trustAllContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.http.content.staticResources
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.request.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.Base64

private val logger = LoggerFactory.getLogger("animius")

private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

private val animeApi = AnimeApiImpl()

private val danmakuHttpClient by lazy { createHttpClient() }

private fun ddpClient(): DandanplayClient = DandanplayClient(
    danmakuHttpClient,
    appId = SettingsStore.get("ddpAppId") ?: "",
    appSecret = SettingsStore.get("ddpSecret") ?: "",
)

private val proxyClient by lazy {
    HttpClient(OkHttp) {
        engine {
            config {
                sslSocketFactory(trustAllContext.socketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
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

// ---------- 前端用的响应 DTO ----------

@Serializable
data class AnimeItemDto(val title: String, val img: String, val url: String, val episode: String = "", val source: String)

@Serializable
data class HomeSectionDto(val title: String, val animes: List<AnimeItemDto>)

@Serializable
data class EpisodeDto(val name: String, val url: String)

@Serializable
data class DetailDto(
    val source: String, val title: String, val img: String, val desc: String,
    val tags: List<String>, val related: List<AnimeItemDto>,
    val channels: Map<String, List<EpisodeDto>>, val favourited: Boolean,
)

@Serializable
data class SubtitleDto(val label: String, val lang: String, val url: String)

@Serializable
data class VideoDto(
    val playUrl: String, val upstream: String, val title: String, val episode: String,
    val subtitles: List<SubtitleDto> = emptyList(),
)

@Serializable
data class DanmakuCommentDto(val time: Double, val mode: Int, val color: Long, val text: String)

@Serializable
data class HistoryDto(
    val source: String, val animeTitle: String, val animeUrl: String, val img: String,
    val episodeName: String, val episodeUrl: String, val position: Double, val updated: Long,
)

@Serializable
data class FavouriteDto(val source: String, val title: String, val url: String, val img: String)

// ---------- 工具 ----------

private val b64enc = Base64.getUrlEncoder().withoutPadding()
private val b64dec = Base64.getUrlDecoder()

private fun b64(s: String): String = b64enc.encodeToString(s.toByteArray(Charsets.UTF_8))
private fun unb64(s: String): String = String(b64dec.decode(s), Charsets.UTF_8)

private fun proxyPath(url: String, referer: String?): String {
    var p = "/api/proxy?u=${b64(url)}"
    if (!referer.isNullOrBlank()) p += "&ref=${b64(referer)}"
    return p
}

private fun originOf(url: String): String = try {
    val u = URI(url)
    "${u.scheme ?: "https"}://${u.host ?: ""}"
} catch (e: Exception) {
    ""
}

private fun imgProxy(url: String): String =
    if (url.isBlank()) "" else if (url.startsWith("/api/")) url else proxyPath(url, originOf(url))

private suspend fun ApplicationCall.authed(): String? {
    val token = request.cookies["animius_session"] ?: return null
    return Db.sessionUser(token).also {
        if (it == null) respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
    }
}

private fun sourceOf(param: String?): AnimeSource {
    val mode = param?.takeIf { it.isNotBlank() }?.let {
        runCatching { SourceMode.valueOf(it) }.getOrNull()
    } ?: SourceHolder.currentSourceMode
    // 保持 currentSourceMode 与请求一致：解析器内部按“当前源”读取域名覆盖、验证码 Cookie 等
    if (mode != SourceHolder.currentSourceMode) SourceHolder.switchSource(mode)
    return SourceHolder.getSource(mode)
}

private fun AnimeItemDto.Companion.of(bean: com.lanlinju.animius.data.remote.dto.AnimeBean, source: SourceMode) = AnimeItemDto(
    title = bean.title, img = imgProxy(bean.img), url = bean.url,
    episode = bean.episodeName, source = source.name,
)

private fun detailJson(call: ApplicationCall, source: SourceMode, bean: com.lanlinju.animius.data.remote.dto.AnimeDetailBean): DetailDto {
    val channels = if (bean.episodes.isNotEmpty()) {
        mapOf("0" to bean.episodes.map { EpisodeDto(it.name, it.url) })
    } else {
        bean.channels.mapKeys { it.key.toString() }.mapValues { v -> v.value.map { EpisodeDto(it.name, it.url) } }
    }
    return DetailDto(
        source = source.name, title = bean.title, img = imgProxy(bean.imgUrl ?: ""), desc = bean.desc,
        tags = bean.tags, related = bean.relatedAnimes.map { AnimeItemDto.of(it, source) },
        channels = channels,
        favourited = Db.isFavourite(call.authedNoResp() ?: "", bean.title),
    )
}

/** 只读用户名、不落 401（供 detailJson 内部查收藏用） */
private fun ApplicationCall.authedNoResp(): String? {
    val token = request.cookies["animius_session"] ?: return null
    return Db.sessionUser(token)
}

// ---------- m3u8 重写 ----------

private fun rewritePlaylist(text: String, baseUrl: String, referer: String?): String {
    val lines = text.lines().map { line ->
        val t = line.trim()
        when {
            t.isEmpty() -> line
            t.startsWith("#") -> {
                // #EXT-X-KEY / #EXT-X-MAP 里的 URI="..."
                Regex("""URI="([^"]+)"""").replace(line) { m ->
                    val abs = absolutize(m.groupValues[1], baseUrl)
                    "URI=\"${proxyPath(abs, referer)}\""
                }
            }
            else -> proxyPath(absolutize(t, baseUrl), referer)
        }
    }
    return lines.joinToString("\n")
}

private fun absolutize(u: String, base: String): String = when {
    u.startsWith("http://") || u.startsWith("https://") -> u
    u.startsWith("//") -> "https:$u"
    else -> try {
        URI(base).resolve(u).toString()
    } catch (e: Exception) {
        base.trimEnd('/') + "/" + u.trimStart('/')
    }
}

// ---------- 路由 ----------

fun Application.module() {
    install(ContentNegotiation) { json(json) }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error("route error", cause)
            call.respondText(
                cause.stackTraceToString().lineSequence().take(6).joinToString("\n"),
                ContentType.Text.Plain, HttpStatusCode.InternalServerError
            )
        }
    }

    routing {
        get("/") {
            val html = javaClass.classLoader.getResourceAsStream("web/index.html")?.readBytes()
                ?.toString(Charsets.UTF_8) ?: "<h1>web/index.html missing</h1>"
            call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
            call.respondText(html, ContentType.Text.Html)
        }
        staticResources("/static", "web")

        // ---------- 认证 ----------
        post("/api/login") {
            val obj = json.parseToJsonElement(call.receiveText()).jsonObject
            val username = obj["username"]?.jsonPrimitive?.content ?: ""
            val password = obj["password"]?.jsonPrimitive?.content ?: ""
            val user = Db.verifyLogin(username, password)
            if (user == null) {
                call.respondText("""{"error":"账号或密码错误"}""", ContentType.Application.Json, HttpStatusCode.Forbidden)
            } else {
                val token = Db.createSession(user)
                call.response.cookies.append(io.ktor.http.Cookie("animius_session", token, path = "/", httpOnly = true, maxAge = 30 * 24 * 3600))
                call.respondText("""{"user":"$user"}""", ContentType.Application.Json)
            }
        }
        post("/api/logout") {
            call.request.cookies["animius_session"]?.let { Db.deleteSession(it) }
            call.response.cookies.append(io.ktor.http.Cookie("animius_session", "", path = "/", maxAge = 0))
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        }
        get("/api/me") {
            val user = call.request.cookies["animius_session"]?.let { Db.sessionUser(it) }
            call.respondText("""{"user":${if (user != null) "\"$user\"" else "null"}}""", ContentType.Application.Json)
        }

        // ---------- 数据源 ----------
        get("/api/sources") {
            call.authed() ?: return@get
            val list = SourceMode.entries.map {
                buildJsonObject {
                    put("id", it.name)
                    put("name", sourceDisplayNames[it] ?: it.name)
                    put("current", it == SourceHolder.currentSourceMode)
                }
            }
            call.respondText(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(JsonObject.serializer()), list), ContentType.Application.Json)
        }
        post("/api/source") {
            call.authed() ?: return@post
            val obj = json.parseToJsonElement(call.receiveText()).jsonObject
            val mode = runCatching { SourceMode.valueOf(obj["source"]?.jsonPrimitive?.content ?: "") }.getOrNull()
            if (mode == null) {
                call.respondText("""{"error":"unknown source"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            } else {
                SourceHolder.switchSource(mode)
                SettingsStore.put("defaultSource", mode.name)
                call.respondText("""{"ok":true,"source":"${mode.name}"}""", ContentType.Application.Json)
            }
        }

        get("/api/home") {
            call.authed() ?: return@get
            val source = sourceOf(call.request.queryParameters["source"])
            val mode = SourceHolder.currentSourceMode
            val data = withContext(Dispatchers.IO) { source.getHomeData() }
            call.respondText(Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(HomeSectionDto.serializer()),
                data.map { HomeSectionDto(it.title, it.animes.map { a -> AnimeItemDto.of(a, mode) }) }
            ), ContentType.Application.Json)
        }

        get("/api/week") {
            call.authed() ?: return@get
            val source = sourceOf(call.request.queryParameters["source"])
            val mode = SourceHolder.currentSourceMode
            val data = withContext(Dispatchers.IO) { source.getWeekData() }
            val obj = JsonObject(data.entries.associate { (day, list) ->
                day.toString() to kotlinx.serialization.json.JsonArray(
                    list.map { a -> Json.encodeToJsonElement(AnimeItemDto.serializer(), AnimeItemDto.of(a, mode)) })
            })
            call.respondText(obj.toString(), ContentType.Application.Json)
        }

        get("/api/search") {
            call.authed() ?: return@get
            val q = call.request.queryParameters["q"] ?: ""
            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
            if (q.isBlank()) { call.respondText("[]", ContentType.Application.Json); return@get }
            val source = sourceOf(call.request.queryParameters["source"])
            val mode = SourceHolder.currentSourceMode
            val data = withContext(Dispatchers.IO) { source.getSearchData(q, page) }
            call.respondText(Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(AnimeItemDto.serializer()),
                data.map { AnimeItemDto.of(it, mode) }
            ), ContentType.Application.Json)
        }

        get("/api/detail") {
            call.authed() ?: return@get
            val url = call.request.queryParameters["url"] ?: ""
            if (url.isBlank()) { call.respondText("""{"error":"missing url"}""", ContentType.Application.Json, HttpStatusCode.BadRequest); return@get }
            val source = sourceOf(call.request.queryParameters["source"])
            val mode = SourceHolder.currentSourceMode
            val bean = withContext(Dispatchers.IO) { source.getAnimeDetail(url) }
            call.respondText(Json.encodeToString(DetailDto.serializer(), detailJson(call, mode, bean)), ContentType.Application.Json)
        }

        get("/api/video") {
            call.authed() ?: return@get
            val url = call.request.queryParameters["url"] ?: ""
            if (url.isBlank()) { call.respondText("""{"error":"missing url"}""", ContentType.Application.Json, HttpStatusCode.BadRequest); return@get }
            val source = sourceOf(call.request.queryParameters["source"])
            val bean = withContext(Dispatchers.IO) { source.getVideoData(url) }
            val ref = bean.headers["Referer"] ?: bean.headers["referer"] ?: originOf(bean.videoUrl)
            val dto = VideoDto(
                playUrl = proxyPath(bean.videoUrl, ref),
                upstream = bean.videoUrl,
                title = call.request.queryParameters["title"] ?: "",
                episode = call.request.queryParameters["ep"] ?: "",
                subtitles = bean.subtitles.map {
                    SubtitleDto(it.label, it.lang, "/api/subtitle?u=${b64(it.url)}&k=${b64(it.label + "|" + it.lang)}")
                },
            )
            call.respondText(Json.encodeToString(VideoDto.serializer(), dto), ContentType.Application.Json)
        }

        // ---------- 字幕（原始 / AI 双语） ----------
        get("/api/subtitle") {
            call.authed() ?: return@get
            val u = call.request.queryParameters["u"]?.let { runCatching { unb64(it) }.getOrNull() }
                ?: return@get call.respondText("missing u", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            val referer = originOf(u)
            val translate = call.request.queryParameters["translate"] == "1"
            val cacheKey = call.request.queryParameters["k"]?.let { runCatching { unb64(it) }.getOrNull() }
            val dataDir = SettingsStore.file.parent
            val wantBilingual = translate && Subtitles.configured()
            val vttType = ContentType.parse("text/vtt; charset=utf-8")
            val cacheKeyFull = cacheKey ?: Subtitles.hashOf(u)

            // 缓存优先：签名过期/回源失败时照样能放之前翻好的双语字幕
            if (wantBilingual) {
                Subtitles.cachedBilingual(cacheKeyFull, dataDir)?.let {
                    return@get call.respondText(it, vttType)
                }
            }

            val original = runCatching { withContext(Dispatchers.IO) { Subtitles.fetchVtt(u, referer) } }
            if (original.isFailure) {
                // 回源失败：有旧缓存就用旧缓存兑底，否则报错
                val cached = if (wantBilingual) Subtitles.cachedBilingual(cacheKeyFull, dataDir) else null
                if (cached != null) return@get call.respondText(cached, vttType)
                return@get call.respondText(
                    "字幕拉取失败: ${original.exceptionOrNull()?.message?.take(150)}",
                    ContentType.Text.Plain, HttpStatusCode.BadGateway,
                )
            }
            val body = if (wantBilingual) {
                withContext(Dispatchers.IO) {
                    Subtitles.bilingualVtt(u, referer, original.getOrThrow(), dataDir, cacheKey)
                }
            } else Subtitles.normalizeVtt(original.getOrThrow())
            call.respondText(body, vttType)
        }
        get("/api/subtitle/cache") {
            call.authed() ?: return@get
            val (count, bytes) = Subtitles.cacheStats(SettingsStore.file.parent)
            call.respondText("""{"count":$count,"bytes":$bytes}""", ContentType.Application.Json)
        }
        post("/api/subtitle/cache/clear") {
            call.authed() ?: return@post
            val n = Subtitles.clearCache(SettingsStore.file.parent)
            call.respondText("""{"ok":true,"removed":$n}""", ContentType.Application.Json)
        }
        // 后台启动翻译（幂等），配合 progress 轮询显示进度
        get("/api/subtitle/prepare") {
            call.authed() ?: return@get
            val u = call.request.queryParameters["u"]?.let { runCatching { unb64(it) }.getOrNull() }
                ?: return@get call.respondText("missing u", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            val ck = call.request.queryParameters["k"]?.let { runCatching { unb64(it) }.getOrNull() }
            call.respondText(Subtitles.prepareAsync(u, originOf(u), SettingsStore.file.parent, ck).toString(), ContentType.Application.Json)
        }
        get("/api/subtitle/progress") {
            call.authed() ?: return@get
            val u = call.request.queryParameters["u"]?.let { runCatching { unb64(it) }.getOrNull() }
                ?: return@get call.respondText("missing u", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            val ck2 = call.request.queryParameters["k"]?.let { runCatching { unb64(it) }.getOrNull() }
            call.respondText(Subtitles.progressStatus(u, SettingsStore.file.parent, ck2).toString(), ContentType.Application.Json)
        }
        get("/api/subtitle/test") {
            call.authed() ?: return@get
            runCatching { Subtitles.testLlm() }
                .onSuccess { call.respondText("""{"ok":true,"reply":"${it.replace("\"", "'")}"}""", ContentType.Application.Json) }
                .onFailure { call.respondText("""{"ok":false,"error":"${it.message?.replace("\"", "'")?.take(150)}"}""", ContentType.Application.Json) }
        }

        // ---------- 弹幕 ----------
        get("/api/danmaku") {
            call.authed() ?: return@get
            val title = call.request.queryParameters["title"] ?: ""
            val episode = call.request.queryParameters["episode"] ?: ""
            val ddp = ddpClient()
            if (title.isBlank() || ddp.appId.isBlank() || ddp.appSecret.isBlank()) {
                call.respondText("""{"enabled":false,"comments":[]}""", ContentType.Application.Json); return@get
            }
            val formatted = formatEpisodeForDanmaku(episode)
            val comments = runCatching {
                if (formatted == null) emptyList() else withContext(Dispatchers.IO) {
                    val resp = ddp.searchEpisode(title, formatted)
                    if (!resp.success || resp.animes.isEmpty()) emptyList()
                    else {
                        val episodeId = resp.animes[0].episodes.firstOrNull()?.episodeId?.toLong() ?: return@withContext emptyList()
                        ddp.getDanmakuList(episodeId)
                            .mapNotNull { it.toDanmakuItemOrNull() }
                            .map { DanmakuCommentDto(it.time, it.mode, it.color.toLong(), it.text) }
                    }
                }
            }.getOrDefault(emptyList())
            val payload = buildJsonObject {
                put("enabled", comments.isNotEmpty())
                put("comments", kotlinx.serialization.json.JsonArray(comments.map { c ->
                    buildJsonObject {
                        put("time", c.time); put("mode", c.mode); put("color", c.color); put("text", c.text)
                    }
                }))
            }
            call.respondText(payload.toString(), ContentType.Application.Json)
        }

        // ---------- 历史 / 收藏 ----------
        get("/api/history") {
            val user = call.authed() ?: return@get
            val list = Db.listHistory(user).map {
                HistoryDto(it.source, it.animeTitle, it.animeUrl, imgProxy(it.img),
                    it.episodeName, it.episodeUrl, it.position, it.updated)
            }
            call.respondText(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(HistoryDto.serializer()), list), ContentType.Application.Json)
        }
        post("/api/history") {
            val user = call.authed() ?: return@post
            val obj = json.parseToJsonElement(call.receiveText()).jsonObject
            fun s(k: String) = obj[k]?.jsonPrimitive?.content ?: ""
            Db.upsertHistory(user, s("source"), s("animeTitle"), s("animeUrl"), s("img"),
                s("episodeName"), s("episodeUrl"), obj["position"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0)
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        }
        post("/api/history/delete") {
            val user = call.authed() ?: return@post
            val obj = json.parseToJsonElement(call.receiveText()).jsonObject
            Db.deleteHistory(user, obj["animeUrl"]?.jsonPrimitive?.content ?: "")
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        }
        get("/api/favourites") {
            val user = call.authed() ?: return@get
            val list = Db.listFavourites(user).map {
                FavouriteDto(it.source, it.title, it.url, imgProxy(it.img))
            }
            call.respondText(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(FavouriteDto.serializer()), list), ContentType.Application.Json)
        }
        post("/api/favourites") {
            val user = call.authed() ?: return@post
            val obj = json.parseToJsonElement(call.receiveText()).jsonObject
            fun s(k: String) = obj[k]?.jsonPrimitive?.content ?: ""
            Db.upsertFavourite(user, s("source"), s("title"), s("url"), s("img"))
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        }
        post("/api/favourites/delete") {
            val user = call.authed() ?: return@post
            val obj = json.parseToJsonElement(call.receiveText()).jsonObject
            Db.removeFavourite(user, obj["url"]?.jsonPrimitive?.content ?: "")
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        }

        // ---------- 验证码（人工过验证后回贴 Cookie） ----------
        get("/api/captcha") {
            call.authed() ?: return@get
            val url = CaptchaCookieManager.captchaUrl
            call.respondText(buildJsonObject {
                put("pending", url.isNotBlank())
                put("url", url)
                put("source", SourceHolder.currentSourceMode.name)
            }.toString(), ContentType.Application.Json)
        }
        post("/api/captcha") {
            call.authed() ?: return@post
            val obj = json.parseToJsonElement(call.receiveText()).jsonObject
            // Cookie 要存到对应源名下（解析器按当前源读取）
            val src = obj["source"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?.let { runCatching { SourceMode.valueOf(it) }.getOrNull() } ?: SourceHolder.currentSourceMode
            val raw = obj["cookies"]?.jsonPrimitive?.content ?: ""
            val cookies = normalizeCookies(raw)
            if (cookies.isBlank()) {
                call.respondText("""{"error":"empty cookies"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            } else {
                CaptchaCookieManager.saveCookies(src.name + "_Cookie", cookies)
                CaptchaCookieManager.captchaUrl = ""
                logger.info("saved {} cookies for source {}", cookies.split("; ").size, src.name)
                call.respondText("""{"ok":true,"source":"${src.name}","count":${cookies.split("; ").size}}""", ContentType.Application.Json)
            }
        }

        // ---------- 设置（弹幕凭据、出站代理、LLM 字幕等） ----------
        get("/api/settings") {
            call.authed() ?: return@get
            call.respondText(buildJsonObject {
                put("ddpAppId", SettingsStore.get("ddpAppId") ?: "")
                put("ddpSecret", SettingsStore.get("ddpSecret") ?: "")
                put("outboundProxy", SettingsStore.get("outboundProxy") ?: "")
                put("llmBaseUrl", SettingsStore.get("llmBaseUrl") ?: "")
                put("llmApiKey", SettingsStore.get("llmApiKey") ?: "")
                put("llmModel", SettingsStore.get("llmModel") ?: "")
                put("aiSubEnabled", SettingsStore.get("aiSubEnabled")?.toBooleanStrictOrNull() ?: false)
                put("subFontSize", SettingsStore.get("subFontSize") ?: "22")
                put("subBg", SettingsStore.get("subBg") ?: "shadow")
            }.toString(), ContentType.Application.Json)
        }
        post("/api/settings") {
            call.authed() ?: return@post
            val obj = json.parseToJsonElement(call.receiveText()).jsonObject
            obj["ddpAppId"]?.jsonPrimitive?.content?.let { SettingsStore.put("ddpAppId", it) }
            obj["ddpSecret"]?.jsonPrimitive?.content?.let { SettingsStore.put("ddpSecret", it) }
            obj["outboundProxy"]?.jsonPrimitive?.content?.let { SettingsStore.put("outboundProxy", it.trim()) }
            obj["llmBaseUrl"]?.jsonPrimitive?.content?.let { SettingsStore.put("llmBaseUrl", it.trim()) }
            obj["llmApiKey"]?.jsonPrimitive?.content?.let { SettingsStore.put("llmApiKey", it.trim()) }
            obj["llmModel"]?.jsonPrimitive?.content?.let { SettingsStore.put("llmModel", it.trim()) }
            obj["aiSubEnabled"]?.jsonPrimitive?.content?.let { SettingsStore.put("aiSubEnabled", it) }
            obj["subFontSize"]?.jsonPrimitive?.content?.let { SettingsStore.put("subFontSize", it.filter { c -> c.isDigit() }.ifBlank { "22" }) }
            obj["subBg"]?.jsonPrimitive?.content?.let { SettingsStore.put("subBg", it.take(10)) }
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        }

        // ---------- 流式代理（视频 / m3u8 / 图片） ----------
        get("/api/proxy") {
            call.authed() ?: return@get
            val u = call.request.queryParameters["u"]?.let { runCatching { unb64(it) }.getOrNull() }
                ?: return@get call.respondText("missing u", HttpStatusCode.UnprocessableEntity.let { ContentType.Text.Plain }, HttpStatusCode.BadRequest)
            val ref = call.request.queryParameters["ref"]?.let { runCatching { unb64(it) }.getOrNull() } ?: originOf(u)
            logger.info("proxy: {}", u)
            proxyClient.prepareGet(u) {
                headers {
                    append(HttpHeaders.UserAgent, DefaultUserAgent)
                    if (ref.isNotBlank()) append("Referer", ref)
                    call.request.header(HttpHeaders.Range)?.let { append(HttpHeaders.Range, it) }
                }
            }.execute { resp ->
                // m3u8 判定：URL 含 m3u8（含 m3u8.php 这类动态地址）或 Content-Type 为 mpegurl
                val isPlaylist = u.contains("m3u8", ignoreCase = true) ||
                        resp.contentType()?.toString()?.contains("mpegurl") == true
                if (isPlaylist) {
                    val text = withContext(Dispatchers.IO) { resp.bodyAsText() }
                    call.respondText(rewritePlaylist(text, u, ref), ContentType.parse("application/vnd.apple.mpegurl"))
                } else {
                    val channel = resp.bodyAsChannel()
                    resp.headers[HttpHeaders.AcceptRanges]?.let { call.response.headers.append(HttpHeaders.AcceptRanges, it) }
                    resp.headers[HttpHeaders.ContentRange]?.let { call.response.headers.append(HttpHeaders.ContentRange, it) }
                    call.respondBytesWriter(
                        contentType = resp.contentType(),
                        status = resp.status,
                        contentLength = resp.contentLength(),
                    ) {
                        channel.copyTo(this)
                    }
                }
            }
        }
    }
}

/**
 * 兼容两种 Cookie 粘贴格式：
 * 1. 请求头格式: "a=b; c=d"（可带 "Cookie:" 前缀，可多行）
 * 2. 浏览器插件导出的 Netscape 格式（每行 7 个 tab 分隔字段）
 */
private fun normalizeCookies(raw: String): String {
    val text = raw.trim()
    if (text.contains('\t')) {
        return text.lines().mapNotNull { ln ->
            val l = ln.trim()
            if (l.isEmpty()) return@mapNotNull null
            val body = when {
                l.startsWith("#HttpOnly_") -> l.removePrefix("#HttpOnly_")
                l.startsWith("#") || l.startsWith("#Netscape") -> return@mapNotNull null
                else -> l
            }
            val f = body.split('\t').map { it.trim() }
            if (f.size >= 7 && f[5].isNotBlank()) "${f[5]}=${f[6]}" else null
        }.joinToString("; ").ifBlank { "" }
    }
    return text.lineSequence()
        .map { it.trim().replace(Regex("(?i)^cookie:\\s*"), "") }
        .filter { it.contains("=") && !it.startsWith("#") }
        .joinToString("; ")
}

private fun formatEpisodeForDanmaku(episodeName: String): String? {
    if (episodeName.isBlank()) return null
    val moviePattern = Regex("全集|HD|正片")
    val nonDigitRegex = Regex("\\D")
    return when {
        moviePattern.containsMatchIn(episodeName) -> "movie"
        episodeName.contains("第") -> episodeName.replace(nonDigitRegex, "")
        episodeName.matches(Regex("\\d+")) -> episodeName
        else -> null
    }
}

private val sourceDisplayNames = mapOf(
    SourceMode.Silisili to "嘶哩嘶哩 Silisili",
    SourceMode.Yhdm to "樱花动漫 iYinghua",
    SourceMode.Mxdm to "满天星 Mxdm",
    SourceMode.Agedm to "AGE动漫",
    SourceMode.Girigiri to "Girigiri",
    SourceMode.Nyafun to "泥鱼 Nyafun",
    SourceMode.Cycanime to "次元城 Cycanime",
    SourceMode.Gogoanime to "Gogoanime",
    SourceMode.Xifan to "稀饭动漫 Xifan",
    SourceMode.Ntdm to "NT动漫",
    SourceMode.Gugufan to "咕咕番 Gugufan",
    SourceMode.HiAnime to "HiAnime (ani-cli 同源)",
)
