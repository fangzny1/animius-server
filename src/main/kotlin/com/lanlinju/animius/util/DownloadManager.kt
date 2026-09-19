package com.lanlinju.animius.util

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText

/**
 * Network util
 */
internal val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
})

internal val trustAllContext = javax.net.ssl.SSLContext.getInstance("SSL").apply {
    init(null, trustAllCerts, java.security.SecureRandom())
}

fun createHttpClient(
    clientConfig: HttpClientConfig<*>.() -> Unit = {},
) = HttpClient(OkHttp) {
    engine {
        config {
            sslSocketFactory(trustAllContext.socketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
            hostnameVerifier { _, _ -> true }
            // 可选出站代理（设置页配置，形如 http://127.0.0.1:10800 或 socks://host:port），改后需重启服务
            runCatching {
                SettingsStore.get("outboundProxy")?.takeIf { it.isNotBlank() }?.let { spec ->
                    val uri = java.net.URI(spec.trim())
                    val type = if (uri.scheme.startsWith("socks")) java.net.Proxy.Type.SOCKS else java.net.Proxy.Type.HTTP
                    proxy(java.net.Proxy(type, java.net.InetSocketAddress(uri.host, if (uri.port > 0) uri.port else 8080)))
                }
            }
        }
    }
    install(HttpCookies)
    install(HttpTimeout) {
        requestTimeoutMillis = 300_000
        connectTimeoutMillis = 30_000
        socketTimeoutMillis = 30_000
    }
    install(UserAgent) { agent = DefaultUserAgent }
    followRedirects = true
    install(HttpRedirect) {
        checkHttpMethod = false
        allowHttpsDowngrade = true
    }
    clientConfig()
}

object DownloadManager {
    private val httpClient = createHttpClient()

    suspend fun getHtml(url: String, headers: Map<String, String> = emptyMap()): String {
        val html = httpClient.get(url) {
            headers {
                headers.forEach { (key, value) ->
                    append(key, value)
                }
            }
        }.bodyAsText()
        return html
    }
}
