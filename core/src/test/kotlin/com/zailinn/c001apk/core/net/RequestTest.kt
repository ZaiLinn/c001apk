package com.zailinn.c001apk.core.net

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        h.client.get("http://www.coolapk.com/feed/123")
        h.client.get("https://www.coolapk.com.evil.invalid/feed/123")
        h.client.post("https://www.coolapk.com/feed/123")
        assertEquals(5, h.transport.requests.size)
        for (request in h.transport.requests) {
            assertNull(request.header("X-App-Token"))
            assertNull(request.header("Cookie"))
        }
    }

    @Test
    fun staleGenerationRequestsAreDiscarded() = runTest {
        val h = Harness()
        h.transport.beforeResponse = { h.session.activate(null, notify = true) }
        val failure = runCatching { h.client.get("https://api.coolapk.com/v6/main/indexV8") }.exceptionOrNull()
        assertEquals(ApiFailureKind.Cancelled, (failure as ApiFailure).kind)
    }
}
