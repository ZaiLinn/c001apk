package com.zailinn.c001apk.core.net

import kotlinx.coroutines.async
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestTest {
    @Test
    fun accountAndApiRequestsSelectTheirOwnHeaders() = runTest {
        val h = Harness()
        listOf(
            async { h.repo.loginPage("/auth/login?type=mobile") },
            async { h.client.get("https://api.coolapk.com/v6/main/indexV8") },
        ).awaitAll()
        val account = h.transport.requests.single { it.url.host == "account.coolapk.com" }
        val api = h.transport.requests.single { it.url.host == "api.coolapk.com" }
        assertNull(account.header("X-App-Token"))
        assertTrue(account.header("Accept")!!.contains("text/html"))
        assertTrue(api.header("X-App-Token")!!.startsWith("v3"))
        assertEquals("synthetic-test-device", api.header("X-App-Device"))
    }

    @Test
    fun topicFeedAsksForTheNewestDiscussion() = runTest {
        val h = Harness()
        h.repo.topicFeed("7", "合成 话题", Page())
        val outer = h.transport.requests.single().url.queryParameter("url").orEmpty()
        assertTrue(outer.startsWith("/page?url="))
        val inner = "https://example.invalid$outer".toHttpUrl().queryParameter("url").orEmpty().let { "https://example.invalid$it".toHttpUrl() }
        assertEquals("/topic/tagFeedList", inner.encodedPath)
        assertEquals("7", inner.queryParameter("id"))
        assertEquals("合成 话题", inner.queryParameter("title"))
        assertEquals("dateline_desc", inner.queryParameter("sortField"))
        assertTrue(h.repo.topicFeed("7&x=1", "合成", Page()) is com.zailinn.c001apk.core.model.LoadResult.Error)
        assertEquals(1, h.transport.requests.size)
    }

    @Test
    fun darkModeHeaderFollowsTheApp() {
        var dark = false
        val headers = CoolapkHeaders(SessionState(), { syntheticParams }, darkMode = { dark })
        val request = okhttp3.Request.Builder().url("https://api.coolapk.com/v6/main/indexV8").build()
        assertEquals("0", headers.sign(request).header("X-Dark-Mode"))
        dark = true
        assertEquals("1", headers.sign(request).header("X-Dark-Mode"))
    }

    @Test
    fun passwordLoginSendsUrlEncodedFields() = runTest {
        val h = Harness { _, _ -> 200 to "{}" }
        h.repo.login("synthetic-hash", "test+account", "test&password", "")
        val request = h.transport.requests.single()
        assertTrue(request.body!!.contentType().toString().startsWith("application/x-www-form-urlencoded"))
        assertEquals("XMLHttpRequest", request.header("X-Requested-With"))
        val form = formOf(h.transport.bodies.single())
        assertEquals("test&password", form["password"])
        assertEquals("test+account", form["login"])
    }

    @Test
    fun externalRequestReceivesNoCredentials() = runTest {
        val h = Harness()
        h.session.activate(AccountCredentials("1", "u", "secret"), notify = true)
        h.client.get("https://example.invalid/image")
        val request = h.transport.requests.single()
        assertNull(request.header("X-App-Token"))
        assertNull(request.header("Cookie"))
    }

    @Test
    fun feedLikesSendTraceAndCountOnlyDetail() = runTest {
        val h = Harness { _, _ -> 200 to """{"data":{"count":1}}""" }
        h.repo.postAction("/v6/feed/like", id = "123")
        val request = h.transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("123", request.url.queryParameter("id"))
        assertEquals("0", request.url.queryParameter("detail"))
        assertEquals(mapOf("trace" to ""), formOf(h.transport.bodies.single()))
    }

    @Test
    fun repliesEncodeTextWithoutMultipart() = runTest {
        val h = Harness { _, _ -> 200 to """{"data":{}}""" }
        h.repo.reply("123", "feed", listOf("message" to "合成评论 + &", "replyAndForward" to "0"))
        val request = h.transport.requests.single()
        assertEquals("123", request.url.queryParameter("id"))
        assertEquals("feed", request.url.queryParameter("type"))
        assertTrue(request.body!!.contentType().toString().startsWith("application/x-www-form-urlencoded"))
        assertEquals("合成评论 + &", formOf(h.transport.bodies.single())["message"])
    }

    @Test
    fun privateFeedUsesPublishStatusWithoutDraft() = runTest {
        val h = Harness { _, _ -> 200 to """{"data":{}}""" }
        h.repo.createFeed(listOf("type" to "feed", "message" to "合成私密内容", "status" to "-1"))
        val form = formOf(h.transport.bodies.single())
        assertEquals("1", form["status"])
        assertEquals("1", form["publish_status"])
        assertEquals("合成私密内容", form["message"])
    }

    @Test
    fun loggedInApiRequestsCarryAccountCookie() = runTest {
        val h = Harness()
        h.session.activate(AccountCredentials("10", "name", "tok"), notify = true)
        h.client.get("https://api.coolapk.com/v6/main/indexV8")
        assertEquals("uid=10; username=name; token=tok", h.transport.requests.single().header("Cookie"))
    }

    @Test
    fun publicFeedHeadersOmitCookiesEvenWhenLoggedIn() = runTest {
        val h = Harness()
        h.session.activate(AccountCredentials("10", "name", "tok"), notify = true)
        h.session.sessid = "SESSID=synthetic"
        h.client.get("https://www.coolapk.com/feed/123")
        val request = h.transport.requests.single()
        assertTrue(request.header("X-App-Token")!!.startsWith("v3"))
        assertNull(request.header("Cookie"))
    }

    @Test
    fun publicFeedSignaturesAreRestrictedToOfficialHttpsNumericGets() = runTest {
        val h = Harness()
        h.session.activate(AccountCredentials("10", "name", "tok"), notify = true)
        h.client.get("https://www.coolapk.com/account")
        h.client.get("https://www.coolapk.com/feed/not-an-id")
        h.client.get("https://www.coolapk.com.evil.invalid/feed/123")
        h.client.post("https://www.coolapk.com/feed/123")
        assertEquals(4, h.transport.requests.size)
        for (request in h.transport.requests) {
            assertNull(request.header("X-App-Token"))
            assertNull(request.header("Cookie"))
        }
    }

    @Test
    fun credentialsRequireHttpsAndAreRemovedAfterChangingHost() {
        val session = SessionState().apply { activate(AccountCredentials("10", "name", "tok"), notify = true) }
        val headers = CoolapkHeaders(session, { syntheticParams })
        val signed = headers.sign(okhttp3.Request.Builder().url("https://api.coolapk.com/v6/main/indexV8").build())
        assertEquals("uid=10; username=name; token=tok", signed.header("Cookie"))
        for (url in listOf("https://example.invalid/", "http://api.coolapk.com/v6/main/indexV8", "http://account.coolapk.com/auth/login")) {
            val redirected = headers.sign(signed.newBuilder().url(url).build())
            assertNull(redirected.header("Cookie"))
            assertNull(redirected.header("X-App-Token"))
            assertNull(redirected.header("X-App-Device"))
            assertNull(redirected.header("X-Sdk-Int"))
            assertNull(redirected.header("X-Requested-With"))
        }
        val client = CoolapkClient(session, headers)
        assertFalse(client.http.followSslRedirects)
        assertTrue(client.http.networkInterceptors.contains(headers))
    }

    @Test
    fun cleartextApiEntryIsUpgradedBeforeSigningAndSending() = runTest {
        val h = Harness()
        h.session.activate(AccountCredentials("10", "name", "tok"), notify = true)
        h.client.get("http://api.coolapk.com/v6/main/indexV8")
        val request = h.transport.requests.single()
        assertTrue(request.url.isHttps)
        assertEquals("uid=10; username=name; token=tok", request.header("Cookie"))
    }

    @Test
    fun redirectedThirdPartyResponseCannotReplaceTheApiSessionCookie() = runTest {
        val session = SessionState().apply { sessid = "SESSID=original" }
        val transport = okhttp3.Interceptor { chain ->
            okhttp3.Response.Builder()
                .request(chain.request().newBuilder().url("https://example.invalid/redirected").build())
                .protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .header("Set-Cookie", "SESSID=untrusted; Path=/")
                .body(okhttp3.ResponseBody.Companion.run { "{}".toResponseBody() }).build()
        }
        val client = CoolapkClient(session, CoolapkHeaders(session, { syntheticParams }), transport = transport)
        client.get("https://api.coolapk.com/v6/main/indexV8")
        assertEquals("SESSID=original", session.sessid)
    }

    @Test
    fun staleGenerationRequestsAreDiscarded() = runTest {
        val h = Harness()
        h.transport.beforeResponse = { h.session.activate(null, notify = true) }
        val failure = runCatching { h.client.get("https://api.coolapk.com/v6/main/indexV8") }.exceptionOrNull()
        assertEquals(ApiFailureKind.Cancelled, (failure as ApiFailure).kind)
    }
}
