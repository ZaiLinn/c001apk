package com.zailinn.c001apk.core.net

import com.zailinn.c001apk.core.model.FeedArticle
import com.zailinn.c001apk.core.model.LoadResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestDetailTest {
    private val captcha = """{"code":403,"messageStatus":"err_request_captcha_v2","message":"需要验证码"}"""
    private val publicFeed = """{"status":0,"error":0,"messageStatus":0,"data":{"id":123,"entityType":"feed","uid":10,
        "userInfo":{"uid":10,"username":"合成作者"},"message":"完整的合成动态正文","message_raw_output":"[]","replynum":0,
        "userAction":{"like":1}}}"""

    private fun harness(api: String) = Harness { request, _ ->
        200 to if (request.url.host == "www.coolapk.com") publicFeed else api
    }

    @Test
    fun guestOpensFeedThroughPublicPageWhenApiNeedsCaptcha() = runTest {
        val h = harness(captcha)
        h.session.activate(AccountCredentials("synthetic-uid", "u", "synthetic-token"), notify = true)
        val result = h.repo.feedDetail("123") as LoadResult.Success
        assertTrue(result.isPublic)
        assertEquals("完整的合成动态正文", result.value.message)
        assertNull(result.value.userAction)
        val web = h.transport.requests.single { it.url.host == "www.coolapk.com" }
        assertEquals("/feed/123", web.url.encodedPath)
        assertTrue(web.header("X-App-Token")!!.startsWith("v3"))
        assertNull(web.header("Cookie"))
        assertTrue(FeedArticle.parse(result.value).isEmpty())
    }

    @Test
    fun publicPageModelWithFeedUnderResponseDataIsRead() = runTest {
        val page = """{"requestHash":"x","SESSION":{"uid":0,"isLogin":false},"feedId":"123",
            "responseData":${publicFeed.substringAfter("\"data\":").removeSuffix("}").let { """{"data":$it}""" }}}"""
        val h = Harness { request, _ -> 200 to if (request.url.host == "www.coolapk.com") page else captcha }
        val result = h.repo.feedDetail("123") as LoadResult.Success
        assertTrue(result.isPublic)
        assertEquals("完整的合成动态正文", result.value.message)
    }

    private val appOnlyPage = "<!DOCTYPE html><html><head><title>动态分享 - 酷安</title></head><body>请用酷安手机APP扫码查看详情</body></html>"
    private val homeList = """{"data":[{"id":"123","entityType":"feed","uid":10,"username":"合成作者","message":"列表里的完整正文","issummary":"0"}]}"""

    @Test
    fun feedTheWebDoesNotServeOpensFromItsListCopy() = runTest {
        val h = Harness { request, _ ->
            200 to when {
                request.url.host == "www.coolapk.com" -> appOnlyPage
                request.url.encodedPath.endsWith("/feed/detail") -> captcha
                else -> homeList
            }
        }
        h.repo.homeFeed(Page(), 1, "0")
        val result = h.repo.feedDetail("123") as LoadResult.Success
        assertTrue(result.isPublic)
        assertEquals("列表里的完整正文", result.value.message)
    }

    @Test
    fun feedTheWebDoesNotServeWithoutListCopyExplainsWhy() = runTest {
        val h = Harness { request, _ -> 200 to if (request.url.host == "www.coolapk.com") appOnlyPage else captcha }
        val result = h.repo.feedDetail("123") as LoadResult.Error
        assertEquals(CoolapkRepository.PUBLIC_PAGE_APP_ONLY, result.message)
    }

    @Test
    fun publicPageImageLinksWithoutSchemeGetHttps() = runTest {
        val page = """{"responseData":{"data":{"id":"123","entityType":"feed","message":"x",
            "picArr":["//image.coolapk.com/feed/a@1080x240.jpg"],"pic":"http://image.coolapk.com/feed/a@1080x240.jpg"}}}"""
        val h = Harness { request, _ -> 200 to if (request.url.host == "www.coolapk.com") page else captcha }
        val result = h.repo.feedDetail("123") as LoadResult.Success
        assertEquals(listOf("https://image.coolapk.com/feed/a@1080x240.jpg"), result.value.picArr)
        assertEquals("http://image.coolapk.com/feed/a@1080x240.jpg", result.value.string("pic"))
    }

    @Test
    fun loginRequirementAlsoFallsBack() = runTest {
        val h = harness("""{"messageStatus":"err_not_login","message":"请先登录","data":null}""")
        assertTrue((h.repo.feedDetail("123") as LoadResult.Success).isPublic)
    }

    @Test
    fun successfulApiDetailDoesNotRequestPublicPage() = runTest {
        val h = harness("""{"data":{"id":"123","entityType":"feed","message":"x"}}""")
        val result = h.repo.feedDetail("123") as LoadResult.Success
        assertFalse(result.isPublic)
        assertTrue(h.transport.requests.none { it.url.host == "www.coolapk.com" })
    }

    @Test
    fun removedContentIsNotRetriedAsPublicPage() = runTest {
        val h = harness("""{"status":-1,"message":"动态不存在","data":null}""")
        assertTrue(h.repo.feedDetail("123") is LoadResult.Error)
        assertEquals(1, h.transport.requests.size)
    }

    @Test
    fun cancelledVerificationExplainsCancellation() = runTest {
        val h = harness(captcha)
        h.client.captchaVerifier = { null }
        val result = h.repo.feedDetail("123", verify = true) as LoadResult.Error
        assertTrue(result.message.contains("取消"))
        assertTrue(h.transport.requests.none { it.url.host == "www.coolapk.com" })
    }

    @Test
    fun rejectedVerificationReportsFailure() = runTest {
        val h = Harness { request, _ -> 200 to if (request.url.host == "www.coolapk.com") publicFeed else captcha }
        h.client.captchaVerifier = { "NEC:414e5c9b:proof" }
        val result = h.repo.feedDetail("123", verify = true) as LoadResult.Error
        assertEquals(CoolapkRepository.CAPTCHA_PAUSED, result.message)
    }

    @Test
    fun invalidIdIsRejectedLocally() = runTest {
        val h = harness(captcha)
        assertTrue(h.repo.feedDetail("../x") is LoadResult.Error)
        assertTrue(h.transport.requests.isEmpty())
    }

    @Test
    fun articleRendersStructuredBlocks() {
        val feed = com.zailinn.c001apk.core.model.Datum(
            lenientJson.parseToJsonElement(
                """{"title":"合成标题","message_cover":"https://image.coolapk.com/c.jpg","message_raw_output":
                "[{\"type\":\"text\",\"message\":\"开头\"},{\"type\":\"image\",\"url\":\"https://image.coolapk.com/a.jpg\"},{\"type\":\"unknown\"},{\"type\":\"text\",\"message\":\"文章末尾\"}]"}""",
            ) as kotlinx.serialization.json.JsonObject,
        )
        val blocks = FeedArticle.parse(feed)
        assertEquals(listOf("image", "title", "text", "image", "text"), blocks.map { it.type })
        assertEquals("文章末尾", blocks.last().message)
    }
}
