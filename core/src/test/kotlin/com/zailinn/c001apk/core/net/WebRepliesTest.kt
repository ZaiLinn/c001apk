package com.zailinn.c001apk.core.net

import com.zailinn.c001apk.core.model.LoadResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replies fall back to the public web page when the reply API demands a captcha. */
class WebRepliesTest {
    private val captcha = """{"code":403,"messageStatus":"err_request_captcha_v2","messageExtra":"{\"captchaType\":\"NEC\",\"captchaId\":\"5fc4c10eccd64d178982bc560da43a91\",\"captchaField\":\"\"}"}"""
    private val web = """{"data":{"id":"7","entityType":"feed",
        "hotReplyRows":[{"id":"1","entityType":"feed_reply","message":"热门评论"}],
        "replyRows":[{"id":"1","entityType":"feed_reply","message":"热门评论"},{"id":"2","entityType":"feed_reply","message":"普通评论"}]}}"""

    private fun harness(api: String) = Harness { request, _ ->
        200 to if (request.url.host == "www.coolapk.com") web else api
    }

    @Test
    fun captchaOnTheFirstPageReadsTheWebPage() = runTest {
        val h = harness(captcha)
        var prompted = false
        h.client.captchaVerifier = { prompted = true; null }
        val result = h.repo.feedRepliesOrWeb("7", "lastupdate_desc", Page())
        assertTrue(result is LoadResult.Success && result.isPublic)
        assertEquals(listOf("1", "2"), (result as LoadResult.Success).value.map { it.id })
        assertFalse("no captcha dialog for a list that has a fallback", prompted)
        assertEquals(listOf("api2.coolapk.com", "www.coolapk.com"), h.transport.requests.map { it.url.host })
        // The web page itself is read without account cookies.
        assertEquals(null, h.transport.requests.last().header("Cookie"))
    }

    @Test
    fun withoutHotRepliesTheCaptchaErrorStays() = runTest {
        val h = Harness { request, _ ->
            200 to if (request.url.host == "www.coolapk.com") """{"data":{"id":"7","hotReplyRows":[],"replyRows":[]}}""" else captcha
        }
        val result = h.repo.feedRepliesOrWeb("7", "lastupdate_desc", Page())
        assertTrue(result is LoadResult.Error)
    }

    @Test
    fun laterPagesEndInsteadOfPrompting() = runTest {
        val h = harness(captcha)
        assertEquals(LoadResult.Empty, h.repo.feedRepliesOrWeb("7", "lastupdate_desc", Page(page = 2)))
        assertEquals(1, h.transport.requests.size)
    }

    @Test
    fun normalRepliesAreNotPublic() = runTest {
        val h = harness("""{"data":[{"id":"9","entityType":"feed_reply","message":"接口评论"}]}""")
        val result = h.repo.feedRepliesOrWeb("7", "lastupdate_desc", Page())
        assertTrue(result is LoadResult.Success && !result.isPublic)
        assertEquals(1, h.transport.requests.size)
    }

    @Test
    fun otherFailuresAreReportedAsBefore() = runTest {
        val h = harness("""{"status":-1,"message":"动态不存在"}""")
        val result = h.repo.feedRepliesOrWeb("7", "lastupdate_desc", Page())
        assertTrue(result is LoadResult.Error)
        assertEquals(1, h.transport.requests.size)
    }
}
