package com.zailinn.c001apk

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.LoadResult
import com.zailinn.c001apk.core.net.ReplySource
import com.zailinn.c001apk.ui.screens.feed.FeedViewModel
import com.zailinn.c001apk.ui.screens.feed.ReplyThreadViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Drives FeedViewModel/ReplyThreadViewModel against programmable responses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeedRepliesViewModelTest {
    private val captcha =
        """{"messageStatus":"err_request_captcha_v2","messageExtra":"{\"captchaId\":\"5fc4c10eccd64d178982bc560da43a91\",\"captchaField\":\"_v2_post_token\"}","message":"当前访问需要验证码"}"""

    /** Routes by path (and feedType, since feed and sub replies share /v6/feed/replyList). */
    private inner class Transport(val route: (path: String, feedType: String?) -> String) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val body = route(req.url.encodedPath, req.url.queryParameter("feedType"))
            return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
    }

    private fun app(route: (String, String?) -> String) =
        AppContainer(ApplicationProvider.getApplicationContext(), "test-${UUID.randomUUID()}", Transport(route))

    private fun detail(id: Int) =
        """{"data":{"entityType":"feed","id":"$id","uid":"10","username":"作者","message":"正文","replynum":"50","likenum":"1","userAction":{"like":0}}}"""

    private fun replyFeed(id: Int, uid: Int) =
        """{"entityType":"feed_reply","id":"$id","entityId":"r$id","fetchType":"feed_reply","uid":"$uid","feedUid":"10","username":"u$uid","message":"回复$id","dateline":1770000000,"replynum":"0","likenum":"0","userAction":{"like":0}}"""

    /** Idles the main looper and waits for [predicate], up to ~5s. */
    private fun waitFor(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("condition not met within timeout", predicate())
    }

    @Test
    fun captchaOnReplyListFallsBackToHotRepliesWithoutPrompting() {
        var prompted = false
        val app = app { path, feedType ->
            when {
                path.endsWith("/v6/feed/detail") -> detail(100)
                path.endsWith("/v6/feed/hotReplyList") -> """{"data":[${replyFeed(7, 20)}]}"""
                path.endsWith("/v6/feed/replyList") && feedType == "feed" -> captcha
                else -> """{"data":[]}"""
            }
        }
        app.client.captchaVerifier = { prompted = true; null }
        val vm = FeedViewModel(app, "100")
        waitFor { vm.repliesSource.value == ReplySource.Hot && vm.list.items.any { it.id == "7" } }
        assertEquals(ReplySource.Hot, vm.repliesSource.value)
        assertFalse("hot fallback must not pop the captcha dialog", prompted)
    }

    @Test
    fun normalReplyListStaysOnTheApiSource() {
        val app = app { path, _ ->
            when {
                path.endsWith("/v6/feed/detail") -> detail(100)
                path.endsWith("/v6/feed/replyList") -> """{"data":[${replyFeed(5, 11)}]}"""
                else -> """{"data":[]}"""
            }
        }
        val vm = FeedViewModel(app, "100")
        waitFor { vm.list.items.any { it.id == "5" } }
        assertEquals(ReplySource.Api, vm.repliesSource.value)
    }

    @Test
    fun malformedArticleBlocksStillShowTheAvailableBody() {
        val app = app { path, _ ->
            if (path.endsWith("/v6/feed/detail")) detail(100).dropLast(2) + ""","message_raw_output":"["}}"""
            else """{"data":[]}"""
        }
        val vm = FeedViewModel(app, "100")
        waitFor { !vm.loadingDetail.value }
        assertEquals("正文", (vm.feed.value as? LoadResult.Success)?.value?.message)
        assertTrue(vm.articles.value.isEmpty())
    }

    @Test
    fun detailRepliesAreVisibleWhenTheCommentEndpointIsEmpty() {
        val app = app { path, _ ->
            if (path.endsWith("/v6/feed/detail")) {
                detail(100).dropLast(2) + ""","topReplyRows":[${replyFeed(6, 12)}],"replyMeRows":[${replyFeed(7, 13)}]}}"""
            } else """{"data":[]}"""
        }
        val vm = FeedViewModel(app, "100")
        waitFor { vm.list.state.value.status != LoadResult.Loading }
        assertTrue(vm.list.state.value.status is LoadResult.Success)
        assertEquals(listOf("6", "7"), vm.list.items.map { it.id })
    }

    @Test
    fun postingTheFirstCommentReplacesTheEmptyState() {
        val app = app { path, _ -> if (path.endsWith("/v6/feed/detail")) detail(100) else """{"data":[]}""" }
        val vm = FeedViewModel(app, "100")
        waitFor { vm.list.state.value.status == LoadResult.Empty }
        vm.addReply(Datum(Json.parseToJsonElement(replyFeed(5, 11)).jsonObject), null, null)
        assertTrue(vm.list.state.value.status is LoadResult.Success)
        assertEquals(listOf("5"), vm.list.items.map { it.id })
    }

    @Test
    fun firstPageInFlightKeepsAPostedCommentAndDeduplicatesTheServerCopy() {
        val allowReplyList = CountDownLatch(1)
        val app = app { path, _ ->
            when {
                path.endsWith("/v6/feed/detail") -> detail(100)
                path.endsWith("/v6/feed/replyList") -> {
                    check(allowReplyList.await(5, TimeUnit.SECONDS))
                    """{"data":[${replyFeed(5, 11)},${replyFeed(6, 12)}]}"""
                }
                else -> """{"data":[]}"""
            }
        }
        val vm = FeedViewModel(app, "100")
        try {
            waitFor { vm.feed.value is LoadResult.Success }
            vm.addReply(Datum(Json.parseToJsonElement(replyFeed(5, 11)).jsonObject), null, null)
            vm.addReply(Datum(Json.parseToJsonElement(replyFeed(7, 11)).jsonObject), null, null)
            assertEquals(listOf("7", "5"), vm.list.items.map { it.id })
        } finally {
            allowReplyList.countDown()
        }
        waitFor { vm.list.items.any { it.id == "6" } }
        assertEquals(listOf("7", "5", "6"), vm.list.items.map { it.id })
    }

    @Test
    fun pinnedRepliesFromBlockedUsersAreHidden() {
        val pinned = replyFeed(6, 12).replace("\"entityType\":\"feed_reply\",", "")
        val author = replyFeed(7, 13).replace("\"entityType\":\"feed_reply\",", "")
        val app = app { path, _ ->
            when {
                path.endsWith("/v6/feed/detail") ->
                    detail(100).dropLast(2) + ""","topReplyRows":[$pinned],"replyMeRows":[$author]}}"""
                path.endsWith("/v6/feed/replyList") -> """{"data":[${replyFeed(5, 11)}]}"""
                else -> """{"data":[]}"""
            }
        }
        runBlocking { app.content.block("12") }
        waitFor { app.content.isUserBlocked("12") }
        val vm = FeedViewModel(app, "100")
        waitFor { vm.list.items.any { it.id == "5" } }
        assertEquals(listOf("7", "5"), vm.list.items.map { it.id })
        assertEquals(null, vm.topReply)
    }

    @Test
    fun subReplyFallbackFiltersBlockedUsers() {
        val app = app { path, feedType ->
            when {
                // Sub-reply list is gated: force the embedded-preview fallback.
                path.endsWith("/v6/feed/replyList") && feedType == "feed_reply" -> captcha
                else -> """{"data":[]}"""
            }
        }
        runBlocking { app.content.block("12") }
        val origin = Datum(
            Json.parseToJsonElement(
                """{"entityType":"feed_reply","id":"3","uid":"11","username":"原回复","message":"原回复",
                "replyRows":[${replyFeed(91, 12)},${replyFeed(92, 13)}]}"""
            ).jsonObject
        )
        val vm = ReplyThreadViewModel(app, origin)
        vm.list.start() // the screen starts the list; do it here too
        waitFor { vm.list.items.any { it.id == "3" } }
        val ids = vm.list.items.mapNotNull { it.id }
        assertTrue("origin reply shown", "3" in ids)
        assertTrue("non-blocked preview shown", "92" in ids)
        assertFalse("blocked user's preview removed", "91" in ids)
    }

    @Test
    fun subRepliesLoadFullListAfterOneVerification() {
        val blankCaptcha =
            """{"messageStatus":"err_request_captcha_v2","messageExtra":"{\"captchaId\":\"5fc4c10eccd64d178982bc560da43a91\",\"captchaField\":\"\"}","message":"需要验证码"}"""
        // Gated until the request carries the validate cookie the verification produced.
        val transport = Interceptor { chain ->
            val req = chain.request()
            val gated = req.url.encodedPath.endsWith("/v6/feed/replyList") &&
                !req.header("Cookie").orEmpty().contains("validate=")
            val body = if (gated) blankCaptcha else """{"data":[${replyFeed(5, 14)}]}"""
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
        val app = AppContainer(ApplicationProvider.getApplicationContext(), "test-${UUID.randomUUID()}", transport)
        app.client.captchaVerifier = { it.proof("validate-proof") }
        val origin = Datum(
            Json.parseToJsonElement(
                """{"entityType":"feed_reply","id":"3","uid":"11","username":"原回复","message":"原回复","replyRows":[]}"""
            ).jsonObject
        )
        val vm = ReplyThreadViewModel(app, origin)
        vm.list.start()
        // Default: gated, preview offered, no full list, no auto prompt.
        waitFor { vm.repliesSource.value == ReplySource.Web }
        assertFalse("sub-reply must not auto-prompt", vm.list.items.any { it.id == "5" })
        // Opt in: one verification, then the full sub-reply list.
        vm.loadAllReplies()
        waitFor { vm.repliesSource.value == ReplySource.Api && vm.list.items.any { it.id == "5" } }
        assertEquals(ReplySource.Api, vm.repliesSource.value)
    }
}
