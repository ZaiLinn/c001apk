package com.zailinn.c001apk.core.net

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptchaRetryTest {
    private var extra = """{"captchaId":"0123456789abcdef0123456789abcdef","captchaField":"_custom_token"}"""
    private var alwaysChallenge = false

    private fun harness() = Harness { _, count ->
        200 to if (count == 1 || alwaysChallenge) {
            """{"code":403,"messageStatus":"err_request_captcha_v2","messageExtra":${kotlinx.serialization.json.JsonPrimitive(extra)},"data":null}"""
        } else {
            """{"data":{"id":123}}"""
        }
    }

    @Test
    fun verifiedGetRetainsQueryAndUsesServerField() = runTest {
        val h = harness()
        h.client.captchaVerifier = { c ->
            assertEquals("0123456789abcdef0123456789abcdef", c.captchaId)
            "NEC:01234567:synthetic-proof"
        }
        val response = h.client.get("https://api.coolapk.com/v6/feed/detail", listOf("id" to "123"))
        assertTrue(response.text.contains("123"))
        assertEquals("submitted", response.captchaVerification)
        val last = h.transport.requests.last().url
        assertEquals("123", last.queryParameter("id"))
        assertEquals("NEC:01234567:synthetic-proof", last.queryParameter("_custom_token"))
        assertEquals(2, h.transport.requests.size)
    }

    @Test
    fun verifiedMultipartPostRetainsPayload() = runTest {
        val h = harness()
        h.client.captchaVerifier = { "NEC:01234567:synthetic-proof" }
        h.client.post("https://api.coolapk.com/v6/feed/reply", body = Body.Multipart(listOf("message" to "合成评论")))
        assertEquals(listOf("POST", "POST"), h.transport.requests.map { it.method })
        val body = h.transport.bodies.last()
        assertTrue(body.contains("合成评论"))
        assertTrue(body.contains("name=\"_custom_token\""))
        assertTrue(body.contains("NEC:01234567:synthetic-proof"))
        assertTrue(!h.transport.bodies.first().contains("_custom_token"))
    }

    @Test
    fun cancelledVerificationNeverRepeatsWrite() = runTest {
        val h = harness()
        h.client.captchaVerifier = { null }
        val response = h.client.post("https://api.coolapk.com/v6/feed/like")
        assertEquals("cancelled", response.captchaVerification)
        assertEquals(1, h.transport.requests.size)
    }

    @Test
    fun persistentChallengeIsRetriedOnce() = runTest {
        alwaysChallenge = true
        val h = harness()
        var prompts = 0
        h.client.captchaVerifier = { prompts++; "NEC:01234567:synthetic-proof" }
        h.client.get("https://api.coolapk.com/v6/feed/detail")
        assertEquals(1, prompts)
        assertEquals(2, h.transport.requests.size)
    }

    @Test
    fun serverCannotReplaceBusinessParameters() = runTest {
        extra = """{"captchaId":"0123456789abcdef0123456789abcdef","captchaField":"id"}"""
        val h = harness()
        h.client.captchaVerifier = { "NEC:01234567:synthetic-proof" }
        h.client.get("https://api.coolapk.com/v6/feed/detail", listOf("id" to "123"))
        val last = h.transport.requests.last().url
        assertEquals("123", last.queryParameter("id"))
        assertEquals("NEC:01234567:synthetic-proof", last.queryParameter("_v2_post_token"))
    }

    @Test
    fun externalHostsNeverInvokeVerification() = runTest {
        val h = harness()
        var prompts = 0
        h.client.captchaVerifier = { prompts++; null }
        h.client.get("https://example.invalid/v6/feed/detail")
        assertEquals(0, prompts)
        assertEquals(1, h.transport.requests.size)
    }

    @Test
    fun sessionChangeDuringVerificationPreventsReplay() = runTest {
        val h = harness()
        h.session.activate(AccountCredentials("1", "a", "synthetic-account-a"), notify = true)
        h.client.captchaVerifier = { h.session.refresh(null, "synthetic-account-b"); "NEC:01234567:synthetic-proof" }
        h.client.post("https://api.coolapk.com/v6/feed/like")
        assertEquals(1, h.transport.requests.size)
    }

    @Test
    fun sessionChangeBeforeResponsePreventsVerification() = runTest {
        val h = harness()
        h.session.activate(AccountCredentials("1", "a", "synthetic-account-a"), notify = true)
        h.transport.beforeResponse = { h.session.refresh(null, "synthetic-account-b") }
        var prompts = 0
        h.client.captchaVerifier = { prompts++; "NEC:01234567:synthetic-proof" }
        h.client.post("https://api.coolapk.com/v6/feed/like")
        assertEquals(0, prompts)
        assertEquals(1, h.transport.requests.size)
    }

    @Test
    fun accountSwitchWhileVerifyingNeverReplaysOldWrite() = runTest {
        val h = harness()
        h.client.captchaVerifier = {
            h.session.activate(AccountCredentials("2", "b", "t"), notify = true)
            h.session.activate(null, notify = true)
            "NEC:01234567:synthetic-proof"
        }
        val failure = runCatching { h.client.post("https://api.coolapk.com/v6/feed/like") }.exceptionOrNull()
        assertEquals(ApiFailureKind.Cancelled, (failure as ApiFailure).kind)
        assertEquals(1, h.transport.requests.size)
    }

    @Test
    fun challengeParsingValidatesProof() {
        val challenge = CaptchaChallenge.fromText("""{"messageStatus":"err_request_captcha_v2"}""")!!
        assertEquals(CaptchaChallenge.DEFAULT_ID, challenge.captchaId)
        assertEquals("_v2_post_token", challenge.field)
        assertNull(challenge.proof("bad value"))
        assertTrue(challenge.acceptsProof(challenge.proof("ok")!!))
        assertNull(CaptchaChallenge.fromText("""{"messageStatus":"err_other"}"""))
    }
}
