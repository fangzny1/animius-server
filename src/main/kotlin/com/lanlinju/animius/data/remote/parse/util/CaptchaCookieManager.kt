package com.lanlinju.animius.data.remote.parse.util

import com.lanlinju.animius.util.SourceHolder
import com.lanlinju.animius.util.SettingsStore

/**
 * 验证码 Cookie 管理器（JVM 版）
 * 服务端没有 WebView，syncFromWebView 无从同步，留作 no-op；
 * 若某站点弹出验证码，可手动把抓到的 Cookie 存进来。
 */
object CaptchaCookieManager {
    val CUR_KEY_COOKIE: String
        get() {
            return SourceHolder.currentSourceMode.name + "_Cookie"
        }

    /**
     * 检测到需要验证码时的 URL
     */
    var captchaUrl: String = ""

    fun saveCookies(key: String, cookies: String) {
        SettingsStore.put("captcha_$key", cookies)
    }

    fun getCookies(key: String): String {
        return SettingsStore.get("captcha_$key") ?: ""
    }

    fun clearCookies(key: String) {
        SettingsStore.remove("captcha_$key")
    }

    fun syncFromWebView(url: String) {
        // no-op on JVM
    }
}
