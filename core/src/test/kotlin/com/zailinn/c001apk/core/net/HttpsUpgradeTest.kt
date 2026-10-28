package com.zailinn.c001apk.core.net

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpsUpgradeTest {
    private fun sent(url: String): String {
        var seen = ""
        val client = OkHttpClient.Builder()
            .addInterceptor(HttpsUpgrade)
            .addInterceptor { chain ->
                seen = chain.request().url.toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("".toResponseBody()).build()
            }
            .build()
        client.newCall(Request.Builder().url(url).build()).execute().close()
        return seen
    }

    @Test
    fun cleartextRequestsMoveToHttps() {
        assertEquals("https://image.coolapk.com/feed/a.jpg?x=1", sent("http://image.coolapk.com/feed/a.jpg?x=1"))
    }

    @Test
    fun httpsRequestsStayTheSame() {
        assertEquals("https://api.coolapk.com/v6/main/indexV8", sent("https://api.coolapk.com/v6/main/indexV8"))
    }

    @Test
    fun webAndDownloadUrlsUseHttpsWithoutLosingSignedQuery() {
        assertEquals("https://example.invalid/a%2Fb.apk?sign=a%2Bb%3D#part", HttpsUpgrade.url("http://example.invalid:80/a%2Fb.apk?sign=a%2Bb%3D#part"))
        assertEquals("https://example.invalid:8443/file", HttpsUpgrade.url("https://example.invalid:8443/file"))
        assertNull(HttpsUpgrade.url("javascript:alert(1)"))
        assertNull(HttpsUpgrade.url("file:///sdcard/example"))
    }
}
