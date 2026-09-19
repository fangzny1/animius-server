package com.lanlinju.animius.data.remote.parse

import com.lanlinju.animius.data.remote.dto.AnimeBean
import com.lanlinju.animius.data.remote.dto.AnimeDetailBean
import com.lanlinju.animius.data.remote.dto.EpisodeBean
import com.lanlinju.animius.data.remote.dto.HomeBean
import com.lanlinju.animius.data.remote.dto.SubtitleTrack
import com.lanlinju.animius.data.remote.dto.VideoBean
import com.lanlinju.animius.util.DownloadManager
import com.lanlinju.animius.util.getDefaultDomain
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Base64

/**
 * HiAnime 源（ani-cli 同源）：hianime.at 站点接口。
 * 播放地址取自 ZokoAnime 等 embed，blob 为 base64+XOR("otaku-embed-v1") 混淆的 JSON。
 */
object HiAnimeSource : AnimeSource {
    override val DEFAULT_DOMAIN: String = "https://hianime.at"
    override var baseUrl = getDefaultDomain()

    private val json = Json { ignoreUnknownKeys = true }
    private val BLOB_KEY = "otaku-embed-v1".toByteArray()

    private fun cardToAnime(a: org.jsoup.nodes.Element, sourceBase: String): AnimeBean? {
        val href = a.attr("href").ifBlank { return null }
        val tail = href.substringAfterLast('/').trimEnd('/')
        val m = Regex("([a-z0-9-]+)-(\\d+)$").find(tail) ?: return null
        val img = a.parents().firstOrNull { it.select("img").isNotEmpty() }
            ?.select("img")?.firstOrNull()
            ?.let { it.attr("data-src").ifBlank { it.attr("src") } } ?: ""
        return AnimeBean(
            title = a.attr("title").ifBlank { a.text() },
            img = img,
            url = "${m.groupValues[1]}-${m.groupValues[2]}",
        )
    }

    override suspend fun getHomeData(): List<HomeBean> {
        val doc = Jsoup.parse(DownloadManager.getHtml("$baseUrl/most-popular"))
        val animes = doc.select("h3.film-name > a").mapNotNull { cardToAnime(it, baseUrl) }.distinctBy { it.url }
        return if (animes.isEmpty()) emptyList() else listOf(HomeBean("人气动漫", "", animes))
    }

    override suspend fun getWeekData(): Map<Int, List<AnimeBean>> = emptyMap()

    override suspend fun getSearchData(query: String, page: Int): List<AnimeBean> {
        val kw = URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl/search?keyword=$kw" + if (page > 1) "&page=$page" else ""
        val doc = Jsoup.parse(DownloadManager.getHtml(url))
        return doc.select("h3.film-name > a").mapNotNull { cardToAnime(it, baseUrl) }.distinctBy { it.url }
    }

    override suspend fun getAnimeDetail(detailUrl: String): AnimeDetailBean {
        // detailUrl 形如 "<slug>-<id>"
        val id = detailUrl.substringAfterLast('-')
        val doc = Jsoup.parse(DownloadManager.getHtml("$baseUrl/watch/$detailUrl"))
        val title = doc.select("meta[property=og:title]").attr("content").ifBlank { doc.title() }
        val desc = doc.select("meta[property=og:description]").attr("content")
            .ifBlank { doc.select(".film-description").text() }
        val img = doc.select("img.film-poster-img").attr("data-src")
            .ifBlank { doc.select("meta[property=og:image]").attr("content") }

        val epJson = DownloadManager.getHtml("$baseUrl/api/theme/episode/list/$id")
        val epHtml = json.parseToJsonElement(epJson).jsonObject["html"]?.jsonPrimitive?.content ?: ""
        val episodes = Jsoup.parse(epHtml).select("[data-id]").map { el ->
            val number = el.attr("data-number").ifBlank { el.attr("data-id") }
            EpisodeBean(name = "第${number}集", url = el.attr("data-id"))
        }
        return AnimeDetailBean(title = title, imgUrl = img, desc = desc, relatedAnimes = emptyList(), episodes = episodes)
    }

    override suspend fun getVideoData(episodeUrl: String): VideoBean {
        // episodeUrl 为剧集的 data-id
        val serversJson = DownloadManager.getHtml("$baseUrl/api/theme/episode/servers?episodeId=$episodeUrl")
        val serversHtml = json.parseToJsonElement(serversJson).jsonObject["html"]?.jsonPrimitive?.content ?: ""
        val candidates = Jsoup.parse(serversHtml).select("[data-hash]").sortedByDescending { el ->
            (if (el.attr("data-type") == "sub") 2 else 0) +
                (if ("zoko" in (el.attr("data-server-id") + el.attr("class") + el.text()).lowercase()) 4 else 0)
        }
        var embedUrl: String? = null
        for (el in candidates) {
            val decoded = runCatching {
                Base64.getMimeDecoder().decode(el.attr("data-hash")).decodeToString()
            }.getOrNull()
            if (decoded != null && decoded.startsWith("http")) { embedUrl = decoded; break }
        }
        embedUrl ?: throw IllegalStateException("HiAnime: no usable embed server")

        val embedHtml = DownloadManager.getHtml(embedUrl, mapOf("Referer" to "$baseUrl/"))
        val blob = Regex("window\\.__P\\s*=\\s*\"([^\"]+)\"").find(embedHtml)?.groupValues?.get(1)
            ?: throw IllegalStateException("HiAnime: __P blob not found")
        val raw = Base64.getMimeDecoder().decode(blob)
        val out = ByteArray(raw.size) { i -> (raw[i].toInt() xor BLOB_KEY[i % BLOB_KEY.size].toInt()).toByte() }
        val decoded = out.decodeToString()
        val src = Regex("\"src\"\\s*:\\s*\"([^\"]+\\.(?:m3u8|mp4)[^\"]*)\"").find(decoded)?.groupValues?.get(1)
            ?: Regex("\"src\"\\s*:\\s*\"([^\"]+)\"").find(decoded)?.groupValues?.get(1)
            ?: throw IllegalStateException("HiAnime: no stream src in blob")
        val ref = Regex("^(https?://[^/]+)").find(embedUrl)?.groupValues?.get(1) ?: baseUrl

        // 字幕轨（VTT，需带 embed 站 Referer 获取）
        val subtitles = runCatching {
            val subsArr = Regex("\"subtitles\"\\s*:\\s*(\\[.*?\\])").find(decoded)?.groupValues?.get(1) ?: "[]"
            val arr = json.parseToJsonElement(subsArr).jsonArray
            arr.mapNotNull { el ->
                val o = el.jsonObject
                val u = o["src"]?.jsonPrimitive?.content ?: return@mapNotNull null
                SubtitleTrack(
                    label = o["label"]?.jsonPrimitive?.content ?: o["lang"]?.jsonPrimitive?.content ?: "字幕",
                    lang = o["lang"]?.jsonPrimitive?.content ?: "",
                    url = u,
                )
            }
        }.getOrDefault(emptyList())

        return VideoBean(videoUrl = src, headers = mapOf("Referer" to ref), subtitles = subtitles)
    }
}
