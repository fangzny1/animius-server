package com.lanlinju.animius.data.remote.parse.util

import com.lanlinju.animius.util.DefaultUserAgent
import com.lanlinju.animius.util.DownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jsoup.Jsoup
import java.util.concurrent.TimeoutException

/**
 * JVM 版替身：原版用 Android WebView 加载页面并拦截视频请求。
 * 服务端没有 WebView，这里退化为静态抓取：
 * 1. 在页面 HTML / 内联 JS 里直接搜正则（很多站的 m3u8 就明文在页面里）
 * 2. 找不到就顺着播放器 iframe 往下钻一层，再搜一次
 * 都找不到就抛 TimeoutException，和原版超时行为一致。
 */
class WebViewUtil {

    suspend fun interceptRequest(
        url: String,
        regex: String = ".mp4|.m3u8",
        timeoutMs: Long = 10_000L,
        userAgent: String = DefaultUserAgent,
    ): String = withContext(Dispatchers.IO) {
        try {
            withTimeout(timeoutMs) {
                findVideoUrl(url, regex.toRegex(), userAgent, maxDepth = 2)
                    ?: throw TimeoutException("No matching URL found")
            }
        } catch (_: TimeoutCancellationException) {
            throw TimeoutException("Web connection timeout exception")
        }
    }

    fun clearWeb() {
        // no-op on JVM
    }

    suspend fun findVideoUrl(url: String, regex: Regex, userAgent: String, maxDepth: Int): String? {
        val headers = mapOf("User-Agent" to userAgent, "Referer" to url)
        val html = try {
            DownloadManager.getHtml(url, headers)
        } catch (e: Exception) {
            return null
        }

        // 页面/内联脚本里的明文链接
        regex.findAll(html).forEach { m ->
            m.value.takeIf { it.startsWith("http") || it.startsWith("//") }?.let { return absolutize(it, url) }
        }
        // JS 变量/JSON 里常见的 "url":"...m3u8..." 形式
        val srcPattern = Regex("""["']((?:https?:)?//[^"']{10,300}?)["']""")
        srcPattern.findAll(html).forEach { m ->
            if (regex.containsMatchIn(m.groupValues[1])) return absolutize(m.groupValues[1], url)
        }

        if (maxDepth <= 0) return null
        // 播放器 iframe 兜底
        val iframes = Jsoup.parse(html).select("iframe")
        for (iframe in iframes) {
            val src = iframe.attr("src")
            if (src.isBlank()) continue
            val abs = absolutize(src, url)
            val found = findVideoUrl(abs, regex, userAgent, maxDepth - 1)
            if (found != null) return found
        }
        return null
    }

    private fun absolutize(u: String, base: String): String = when {
        u.startsWith("http") -> u
        u.startsWith("//") -> "https:$u"
        else -> {
            val b = base.trimEnd('/')
            val p = if (u.startsWith("/")) u else "/$u"
            try {
                java.net.URI(b + p).normalize().toString()
            } catch (e: Exception) {
                b + p
            }
        }
    }
}
