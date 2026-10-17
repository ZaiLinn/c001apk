package com.zailinn.c001apk

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.zailinn.c001apk.core.model.Datum
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
