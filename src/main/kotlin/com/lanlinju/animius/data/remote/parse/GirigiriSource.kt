package com.lanlinju.animius.data.remote.parse

import com.lanlinju.animius.data.remote.dto.AnimeBean
import com.lanlinju.animius.data.remote.dto.AnimeDetailBean
import com.lanlinju.animius.data.remote.dto.EpisodeBean
import com.lanlinju.animius.data.remote.dto.HomeBean
import com.lanlinju.animius.data.remote.dto.VideoBean
import com.lanlinju.animius.data.remote.parse.util.CaptchaCookieManager
import com.lanlinju.animius.data.remote.parse.util.WebViewUtil
import com.lanlinju.animius.util.DownloadManager
import com.lanlinju.animius.util.getDefaultDomain
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.select.Elements
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object GirigiriSource : AnimeSource {

    private const val LOG_TAG = "GirigiriSource"

    override val DEFAULT_DOMAIN: String = "https://ani.girigirilove.com"
    override var baseUrl: String = getDefaultDomain()
    private val webViewUtil: WebViewUtil by lazy { WebViewUtil() }

    override fun onExit() {
        webViewUtil.clearWeb()
    }

    override suspend fun getSearchData(query: String, page: Int): List<AnimeBean> {
        val searchUrl = "${baseUrl}/search/${query}----------${page}---/"

        // 获取保存的 Cookie
        val cookies = CaptchaCookieManager.getCookies(CaptchaCookieManager.CUR_KEY_COOKIE)
        val headers = if (cookies.isNotEmpty()) {
            mapOf("Cookie" to cookies)
        } else {
            emptyMap()
        }

        val source = DownloadManager.getHtml(searchUrl, headers)
        val document = Jsoup.parse(source)

        // 检测验证码对话框: button.verify-submit + input[name=verify]
        val hasCaptcha = document.select("button.verify-submit").isNotEmpty() &&
                document.select("input[name=verify]").isNotEmpty()
        if (hasCaptcha) {
            // Cookie 已失效，清除对应 URL 的 Cookie
            CaptchaCookieManager.clearCookies(CaptchaCookieManager.CUR_KEY_COOKIE)
            // 记录需要验证码的 URL
            CaptchaCookieManager.captchaUrl = searchUrl
            return emptyList()
        }

        val animeList = mutableListOf<AnimeBean>()
        document.select("div.search-list").forEach { el ->
            val title = el.select("h3").text()
            val url = el.select("a").first()?.attr("href") ?: ""
            val imgUrl = el.select("img").attr("data-src").padDomain()
            animeList.add(AnimeBean(title = title, img = imgUrl, url = url))
        }
        return animeList
    }

    override suspend fun getWeekData(): MutableMap<Int, List<AnimeBean>> {
        val source = DownloadManager.getHtml(baseUrl)
        val document = Jsoup.parse(source)
        val elements = document.select("div.wow")[0].select("div#week-module-box")
        val weekMap = mutableMapOf<Int, List<AnimeBean>>()
        elements.select("div.public-r").forEachIndexed { index, element ->
            val dayList = getAnimeList(element.select("div.public-list-box"))
            weekMap[index] = dayList
        }
        return weekMap
    }

    override suspend fun getHomeData(): List<HomeBean> {
        val source = DownloadManager.getHtml(baseUrl)
        val document = Jsoup.parse(source)
        val elements = document.select("div.wow").apply { removeAt(0) }
        val homeBeanList = mutableListOf<HomeBean>()
        for ((i, el) in elements.withIndex()) {
            if (i == 1 || i == elements.lastIndex) continue
            val title = el.select("div.title-left > h4").text()
            val moreUrl = el.select("div.title-right > a").attr("href")
            val homeItemBeanList = getAnimeList(el.select("div.public-list-box"))
            homeBeanList.add(HomeBean(title = title, moreUrl = moreUrl, animes = homeItemBeanList))
        }

        return homeBeanList
    }

    override suspend fun getAnimeDetail(detailUrl: String): AnimeDetailBean {
        val source = DownloadManager.getHtml("${baseUrl}/$detailUrl")
        val document = Jsoup.parse(source)
        val main = document.select("div.vod-detail")
        val title = main.select("h3").text()
        val desc = main.select("div#height_limit").text()
        val imgUrl = main.select("img").attr("data-src").padDomain()
        val tags =
            main.select("div.slide-info").last()?.select("a")?.map { it.text() }?.toMutableList()
                ?.also { it.removeAt(it.lastIndex) } ?: emptyList()
        val channels = getAnimeEpisodes(document.select("div.anthology-list").select("ul"))
        val relatedAnimes =
            getAnimeList(document.select("div.public-pic-b"))
        return AnimeDetailBean(title, imgUrl, desc, tags, relatedAnimes, channels = channels)
    }

    private fun getAnimeList(elements: Elements): List<AnimeBean> {
        val animeList = mutableListOf<AnimeBean>()
        elements.forEach { el ->
            el.select("div.public-list-div > a").apply {
                val title = attr("title")
                val url = attr("href")
                val imgUrl = select("img").attr("data-src").padDomain()
                val episodeName = select("span.public-list-prb").text()
                animeList.add(
                    AnimeBean(
                        title = title,
                        img = imgUrl,
                        url = url,
                        episodeName = episodeName
                    )
                )
            }
        }
        return animeList
    }

    override suspend fun getVideoData(episodeUrl: String): VideoBean {
        val url = "${baseUrl}/$episodeUrl"
        val source = DownloadManager.getHtml(url)
        val document = Jsoup.parse(source)
        val videoUrl = getVideoUrl(document)
        return VideoBean(videoUrl)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun getVideoUrl(document: Document): String {
        val videoUrlTarget = document.select("div.player-box > div.player-left > script")[0].data()
        val videoUrlRegex = """"url":"(.*?)","url_next"""".toRegex()
        val rawVideoUrl = videoUrlRegex.find(videoUrlTarget)?.groupValues?.get(1)
            ?: throw IllegalStateException("video url is empty")

        val encodedVideoUrl = String(Base64.decode(rawVideoUrl), Charsets.UTF_8)
        return java.net.URLDecoder.decode(encodedVideoUrl, "UTF-8")
    }

    private fun getAnimeEpisodes(elements: Elements): Map<Int, List<EpisodeBean>> {
        val channels = mutableMapOf<Int, List<EpisodeBean>>()
        elements.forEachIndexed { i, e ->
            val dramaElements = e.select("li").select("a")
            val episodes = mutableListOf<EpisodeBean>()
            dramaElements.forEach { el ->
                val name = el.text()
                val url = el.attr("href")
                episodes.add(EpisodeBean(name, url))
            }
            channels[i] = episodes
        }

        return channels
    }

    private fun String.padDomain(): String {
        return "$baseUrl$this"
    }
}