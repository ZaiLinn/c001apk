package com.zailinn.c001apk.core.net

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptchaEventTest {
    private val challenge =
        """{"code":403,"messageStatus":"err_request_captcha_v2","messageExtra":"{\"captchaId\":\"0123456789abcdef0123456789abcdef\",\"captchaField\":\"\"}","data":null}"""

    private fun harness(challengeOn: (Int) -> Boolean) = Harness { _, count ->
        200 to if (challengeOn(count)) challenge else """{"data":{"id":1}}"""
    }

    @Test
    fun recordsOutcomeEndpointAndRateWithoutQuery() = runTest {
        val h = harness { it == 3 }
        val events = mutableListOf<CaptchaEvent>()
        h.client.captchaListener = { events += it }
        h.client.captchaVerifier = { "NEC:01234567:synthetic-proof" }
        h.client.get("https://api.coolapk.com/v6/main/indexV8")
        h.now += 400
        h.client.get("https://api.coolapk.com/v6/feed/detail", listOf("id" to "123"))
        h.now += 250
        h.client.get("https://api.coolapk.com/v6/feed/replyList", listOf("id" to "123456"))
        val event = events.single()
        assertEquals("GET", event.method)
        assertEquals("/v6/feed/replyList", event.path)
        assertEquals("submitted", event.outcome)
        assertFalse(event.signedIn)
        assertTrue(event.cookieProtocol)
        assertEquals(RequestStats(sincePreviousMs = 250, inLastMinute = 3, sinceStart = 3), event.stats)
    }

    @Test
    fun recordsChallengesThatAreNotPrompted() = runTest {
        val h = harness { true }
        val events = mutableListOf<CaptchaEvent>()
        h.client.captchaListener = { events += it }
        h.client.captchaVerifier = { null }
        h.client.get("https://api.coolapk.com/v6/feed/replyList", skipCaptcha = true)
        h.client.get("https://api.coolapk.com/v6/feed/replyList")
        h.client.get("https://api.coolapk.com/v6/feed/replyList")
        assertEquals(listOf("skipped", "cancelled", "paused"), events.map { it.outcome })
    }

    @Test
    fun rejectedVerificationIsRecorded() = runTest {
        val h = harness { true }
        val events = mutableListOf<CaptchaEvent>()
        h.client.captchaListener = { events += it }
        h.client.captchaVerifier = { "NEC:01234567:synthetic-proof" }
        h.client.get("https://api.coolapk.com/v6/feed/replyList")
        assertEquals(listOf("rejected"), events.map { it.outcome })
    }

    @Test
    fun ordinaryResponsesRecordNothingAndAListenerErrorIsIgnored() = runTest {
        val h = harness { false }
        var calls = 0
        h.client.captchaListener = { calls++; error("listener failure") }
        h.client.get("https://api.coolapk.com/v6/main/indexV8")
        assertEquals(0, calls)
        // A failing listener must not break the request it reports on.
        val c = harness { true }
        c.client.captchaListener = { error("listener failure") }
        val response = c.client.get("https://api.coolapk.com/v6/feed/replyList", skipCaptcha = true)
        assertTrue(response.text.contains("err_request_captcha_v2"))
    }

    @Test
    fun longNumbersInThePathAreHidden() {
        assertEquals("/v6/user/{id}/feeds", CaptchaEvent.pathOf("https://api.coolapk.com/v6/user/12345678/feeds".toHttpUrl()))
        assertEquals("/v6/feed/replyList", CaptchaEvent.pathOf("https://api.coolapk.com/v6/feed/replyList?id=9".toHttpUrl()))
    }

    @Test
    fun rateCountsTheLastMinute() {
        var now = 0L
        val rate = RequestRate { now }
        assertNull(rate.sent().sincePreviousMs)
        now = 30_000
        assertEquals(2, rate.sent().inLastMinute)
        now = 70_000
        val stats = rate.sent()
        assertEquals(2, stats.inLastMinute)
        assertEquals(3, stats.sinceStart)
        assertEquals(40_000L, stats.sincePreviousMs)
    }
}
