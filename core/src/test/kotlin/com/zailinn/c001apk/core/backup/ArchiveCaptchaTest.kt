package com.zailinn.c001apk.core.backup

import com.zailinn.c001apk.core.net.*
import com.zailinn.c001apk.core.protocol.ProtocolParams
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ArchiveCaptchaTest {
    @Test fun explicitArchiveVerificationCanReopenACancelledPromptWithoutWaiting(): Unit = runBlocking {
        val session = SessionState().apply { activate(AccountCredentials("7", "synthetic", "synthetic"), notify = false) }
        val transport = FakeTransport { request, _ ->
            200 to if (request.header("Cookie").orEmpty().contains("validate=NEC%3A")) """{"data":[]}"""
            else """{"messageStatus":"err_request_captcha_v2","message":"需要验证码","messageExtra":{"captchaId":"414e5c9b866a03db03f860a1a9101672","captchaField":""}}"""
        }
        val client = CoolapkClient(session, CoolapkHeaders(session, { ProtocolParams.generate() }), transport = transport)
        val repository = CoolapkRepository(client)
        var prompts = 0
        client.captchaVerifier = { prompts++; null }
        val url = "https://api2.coolapk.com/v6/feed/replyList?id=42&page=2"
        assertEquals("cancelled", client.get(url).captchaVerification)
        assertEquals("paused", client.get(url).captchaVerification)
        assertEquals(1, prompts)
        client.captchaVerifier = { prompts++; it.proof("synthetic-proof") }
        repository.verifyArchiveRequest(url)
        assertEquals(2, prompts)
        assertTrue(transport.requests.all { it.url.queryParameter("id") == "42" && it.url.queryParameter("page") == "2" })
        // An explicit successful verification also releases the old prompt cooldown.
        client.get(url)
        assertEquals(3, prompts)
    }

    @Test fun archivePausesWithoutPromptAndExplicitVerificationUsesTheOriginalEndpoint(): Unit = runBlocking {
        val session = SessionState().apply { activate(AccountCredentials("7", "synthetic", "synthetic"), notify = false) }
        val calls = mutableListOf<okhttp3.Request>()
        var prompts = 0
        val transport = Interceptor { chain ->
            val request = chain.request(); calls += request
            val verified = request.header("Cookie").orEmpty().contains("validate=NEC%3A")
            val body = if (verified) """{"data":[]}""" else """{"messageStatus":"err_request_captcha_v2","message":"需要验证码","messageExtra":{"captchaId":"414e5c9b866a03db03f860a1a9101672","captchaField":""}}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }
        val client = CoolapkClient(session, CoolapkHeaders(session, { ProtocolParams.generate() }), transport = transport)
        client.captchaVerifier = { prompts++; it.proof("synthetic-proof") }
        val repository = CoolapkRepository(client)
        val failure = runCatching { repository.collectionItems("101", Page()) }.exceptionOrNull()
        assertTrue(failure is ArchiveCaptchaException)
        assertEquals(0, prompts)
        assertEquals(1, calls.size)
        repository.verifyArchiveRequest((failure as ArchiveCaptchaException).requestUrl)
        assertEquals(1, prompts)
        assertEquals(3, calls.size)
        assertTrue(calls.all { it.url.encodedPath == "/v6/collection/itemList" && it.url.queryParameter("id") == "101" })
        assertTrue(runCatching { repository.verifyArchiveRequest("https://foreign.example.com/v6/collection/itemList") }.isFailure)
        assertEquals(3, calls.size)
    }
}
