package com.zailinn.c001apk.core.net

import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.LoadResult
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the reply API demands a captcha, replies come from the hot reply list,
 * and only when that fails too from the public web page.
 */
class ReplyFallbackTest {
    private val captcha = """{"code":403,"messageStatus":"err_request_captcha_v2","messageExtra":"{\"captchaType\":\"NEC\",\"captchaId\":\"5fc4c10eccd64d178982bc560da43a91\",\"captchaField\":\"\"}"}"""
    private val hot = """{"data":[{"id":"3","entityType":"feed_reply","message":"热门评论","replyRows":[{"id":"4","entityType":"feed_reply"}]}]}"""
    private val web = """{"data":{"id":"7","entityType":"feed",
        "hotReplyRows":[{"id":"1","entityType":"feed_reply","message":"热门评论"}],
        "replyRows":[{"id":"1","entityType":"feed_reply","message":"热门评论"},{"id":"2","entityType":"feed_reply","message":"普通评论"}]}}"""

    private fun Request.isHot() = url.encodedPath == "/v6/feed/hotReplyList"

    private fun harness(api: String, hotList: String = hot) = Harness { request, _ ->
        200 to when {
            request.url.host == "www.coolapk.com" -> web
            request.isHot() -> hotList
            else -> api
        }
    }

    @Test
    fun captchaReadsTheHotListWithoutPrompting() = runTest {
        val h = harness(captcha)
        var prompted = false
        h.client.captchaVerifier = { prompted = true; null }
        val (result, source) = h.repo.feedRepliesWithoutPrompt("7", "lastupdate_desc", Page())
        assertEquals(ReplySource.Hot, source)
        assertTrue(result is LoadResult.Success && result.isPublic)
        assertEquals(listOf("3"), (result as LoadResult.Success).value.map { it.id })
        assertFalse("no captcha dialog for a list that has a fallback", prompted)
        assertEquals(listOf("/v6/feed/replyList", "/v6/feed/hotReplyList"), h.transport.requests.map { it.url.encodedPath })
    }

    @Test
    fun hotListPagesWithTheCursor() = runTest {
        val h = harness(captcha)
        val result = h.repo.hotReplies("7", Page(page = 2, lastItem = "3"))
        assertTrue(result is LoadResult.Success && result.isPublic)
        val url = h.transport.requests.single().url
        assertEquals("api.coolapk.com", url.host)
        assertEquals(listOf("7", "2", "3", "1"), listOf("id", "page", "lastItem", "discussMode").map(url::queryParameter))
    }

    @Test
    fun laterPagesStayOnTheHotList() = runTest {
        val h = harness(captcha)
        val (result, source) = h.repo.feedRepliesWithoutPrompt("7", "lastupdate_desc", Page(page = 2))
        assertEquals(ReplySource.Hot, source)
        assertTrue(result is LoadResult.Success)
        assertEquals("2", h.transport.requests.last().url.queryParameter("page"))
    }

    @Test
    fun gatedHotListFallsBackToTheWebPage() = runTest {
        val h = harness(captcha, hotList = captcha)
        val (result, source) = h.repo.feedRepliesWithoutPrompt("7", "lastupdate_desc", Page())
        assertEquals(ReplySource.Web, source)
        assertTrue(result is LoadResult.Success && result.isPublic)
        assertEquals(listOf("1", "2"), (result as LoadResult.Success).value.map { it.id })
        assertEquals(listOf("api2.coolapk.com", "api.coolapk.com", "www.coolapk.com"), h.transport.requests.map { it.url.host })
        // The web page itself is read without account cookies.
        assertEquals(null, h.transport.requests.last().header("Cookie"))
    }

    @Test
    fun withoutWebRepliesTheResultIsEmptyButPublic() = runTest {
        val h = Harness { request, _ ->
            200 to if (request.url.host == "www.coolapk.com") """{"data":{"id":"7","hotReplyRows":[],"replyRows":[]}}""" else captcha
        }
        val (result, source) = h.repo.feedRepliesWithoutPrompt("7", "lastupdate_desc", Page())
        assertEquals(ReplySource.Web, source)
        assertEquals(LoadResult.Success(emptyList<Datum>(), isPublic = true), result)
    }

    @Test
    fun webPageFailureKeepsTheCaptchaError() = runTest {
        val h = Harness { request, _ -> if (request.url.host == "www.coolapk.com") 500 to "" else 200 to captcha }
        assertTrue(h.repo.feedRepliesWithoutPrompt("7", "lastupdate_desc", Page()).result is LoadResult.Error)
    }

    @Test
    fun normalRepliesAreNotPublic() = runTest {
        val h = harness("""{"data":[{"id":"9","entityType":"feed_reply","message":"接口评论"}]}""")
        val (result, source) = h.repo.feedRepliesWithoutPrompt("7", "lastupdate_desc", Page())
        assertEquals(ReplySource.Api, source)
        assertTrue(result is LoadResult.Success && !result.isPublic)
        assertEquals(1, h.transport.requests.size)
    }

    @Test
    fun otherFailuresAreReportedAsBefore() = runTest {
        val h = harness("""{"status":-1,"message":"动态不存在"}""")
        val (result, source) = h.repo.feedRepliesWithoutPrompt("7", "lastupdate_desc", Page())
        assertEquals(ReplySource.Api, source)
        assertTrue(result is LoadResult.Error)
        assertEquals(1, h.transport.requests.size)
    }

    @Test
    fun subRepliesDoNotPromptAndFlagGating() = runTest {
        val h = harness(captcha)
        var prompted = false
        h.client.captchaVerifier = { prompted = true; null }
        val (result, source) = h.repo.subReplies("3", Page())
        assertTrue(result is LoadResult.Error)
        // Gated: the caller shows the embedded preview and offers a one-shot verification.
        assertEquals(ReplySource.Web, source)
        assertFalse(prompted)
    }

    @Test
    fun verifiedSubRepliesCarryValidateCookieAndLoadFullList() = runTest {
        val replies = """{"data":[{"id":"5","entityType":"feed_reply","message":"楼中楼"}]}"""
        val h = Harness { _, count -> 200 to if (count == 1) captcha else replies }
        h.client.captchaVerifier = { it.proof("validate-proof") }
        val result = h.repo.subRepliesVerified("3", Page())
        assertTrue(result is LoadResult.Success)
        assertEquals(listOf("5"), (result as LoadResult.Success).value.map { it.id })
        // The proof rides in the validate cookie (blank captchaField protocol), not a query param.
        val last = h.transport.requests.last()
        assertTrue(last.header("Cookie").orEmpty().contains("validate=NEC%3A5fc4c10e%3Avalidate-proof"))
        assertEquals(null, last.url.queryParameter("_v2_post_token"))
    }
}
