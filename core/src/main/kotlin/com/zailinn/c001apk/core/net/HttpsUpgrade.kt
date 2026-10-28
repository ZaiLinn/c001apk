package com.zailinn.c001apk.core.net

import okhttp3.Interceptor
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response

/**
 * Rewrites `http://` requests to `https://`. Cleartext traffic is blocked by the
 * network security config, so old `http://` image and link URLs from the API
 * would otherwise fail instead of loading over TLS.
 */
object HttpsUpgrade : Interceptor {
    /** Used by WebView and system downloads, outside OkHttp. */
    fun url(value: String): String? = value.toHttpUrlOrNull()?.let(::url)?.toString()

    /**
     * Coolapk's own sites all serve HTTPS, so their `http://` links can be upgraded
     * in place. Other sites may not, and open unchanged in the system browser.
     */
    fun isCoolapk(value: String): Boolean =
        value.toHttpUrlOrNull()?.host?.let { it == "coolapk.com" || it.endsWith(".coolapk.com") } == true

    private fun url(value: HttpUrl): HttpUrl =
        if (value.isHttps) value else value.newBuilder().scheme("https").build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.isHttps) return chain.proceed(request)
        return chain.proceed(request.newBuilder().url(url(request.url)).build())
    }

    /**
     * Follows an HTTPS → HTTP redirect over HTTPS. OkHttp only follows it with
     * `followSslRedirects`, and then sends the next hop in cleartext, which the
     * network security config blocks. For clients that follow redirects; GET and
     * HEAD only, since a redirected body would need the method rules of each code.
     */
    object Redirects : Interceptor {
        private const val MAX_HOPS = 5

        override fun intercept(chain: Interceptor.Chain): Response {
            var request = chain.request()
            var response = chain.proceed(request)
            var hops = 0
            while (response.isRedirect && request.method in setOf("GET", "HEAD") && hops < MAX_HOPS) {
                val target = response.header("Location")?.let { request.url.resolve(it) } ?: break
                if (target.isHttps) break
                response.close()
                request = request.newBuilder().url(url(target)).build()
                response = chain.proceed(request)
                hops++
            }
            return response
        }
    }
}
