package com.zailinn.c001apk

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.LoadResult
import com.zailinn.c001apk.core.net.AccountCredentials
import com.zailinn.c001apk.ui.LocalSession
import com.zailinn.c001apk.ui.SessionInfo
import com.zailinn.c001apk.ui.cards.ItemActions
import com.zailinn.c001apk.ui.cards.ReplyCard
import com.zailinn.c001apk.ui.common.PagedList
import com.zailinn.c001apk.ui.nav.AppNavigator
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.screens.LocalAppContainer
import com.zailinn.c001apk.ui.screens.SimpleListViewModel
import com.zailinn.c001apk.ui.screens.compose.ComposeTarget
import com.zailinn.c001apk.ui.screens.compose.ComposerSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Pull-to-refresh, likes, delete and the composer keep what the user has, and ask before losing it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UxFixesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun waitFor(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(20)
        }
        error("condition not met")
    }

    private fun datum(json: String) = Datum(Json.parseToJsonElement(json).jsonObject)

    private fun feed(id: Int, liked: Int = 0, likes: Int = 1) = datum(
        """{"entityType":"feed","id":"$id","entityId":"$id","uid":"10","username":"作者","message":"正文$id","likenum":"$likes","userAction":{"like":$liked}}""",
    )

    @Test
    fun aFailedPullToRefreshKeepsTheListAndItsPaging() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var failing = false
        val pages = mutableListOf<Int>()
        val list = PagedList(scope) { page ->
            pages += page.page
            if (failing) LoadResult.Error("网络错误") else LoadResult.Success(listOf(feed(page.page * 10), feed(page.page * 10 + 1)))
        }
        val messages = mutableListOf<String>()
        scope.launch { list.messages.collect { messages += it } }
        list.start()
        waitFor { list.state.value.items.size == 2 }
        list.loadMore()
        waitFor { list.state.value.items.size == 4 }

        failing = true
        list.refresh(showIndicator = true)
        waitFor { messages.isNotEmpty() }
        assertEquals("items kept", 4, list.state.value.items.size)
        assertFalse(list.state.value.refreshing)
        assertEquals("刷新失败：网络错误", messages.single())

        failing = false
        list.loadMore()
        waitFor { list.state.value.items.size == 6 }
        assertEquals("paging resumes after the kept pages", listOf(1, 2, 1, 3), pages)
        scope.cancel()
    }

    @Test
    fun aRefreshWithoutTheIndicatorStillShowsItsError() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var failing = false
        val list = PagedList(scope) { if (failing) LoadResult.Error("网络错误") else LoadResult.Success(listOf(feed(1))) }
        list.start()
        waitFor { list.state.value.items.size == 1 }
        // A sort change or a new filter must not leave the old items under the new choice.
        failing = true
        list.refresh()
        waitFor { list.state.value.status is LoadResult.Error }
        assertTrue(list.state.value.items.isEmpty())
        scope.cancel()
    }

    private inner class LikeTransport(val body: String) : Interceptor {
        val gate = CountDownLatch(1)
        val likes = AtomicInteger()
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (request.url.encodedPath.endsWith("/v6/feed/like")) {
                likes.incrementAndGet()
                gate.await(5, TimeUnit.SECONDS)
            }
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
    }

    private fun likeVm(transport: LikeTransport): SimpleListViewModel {
        val app = AppContainer(ApplicationProvider.getApplicationContext(), "test-${UUID.randomUUID()}", transport)
        app.session.activate(AccountCredentials("10", "u", "t"), notify = false)
        val vm = SimpleListViewModel(app) { LoadResult.Success(listOf(feed(1))) }
        vm.list.start()
        waitFor { vm.list.items.size == 1 }
        return vm
    }

    @Test
    fun aLikeShowsAtOnceAndASecondTapWaits() {
        val transport = LikeTransport("""{"data":{"count":5}}""")
        val vm = likeVm(transport)
        val item = vm.list.items.single()
        vm.like(item)
        val shown = vm.list.items.single()
        assertTrue("liked before the server answers", shown.isLiked)
        assertEquals("2", shown.string("likenum"))
        vm.like(shown)
        transport.gate.countDown()
        waitFor { vm.list.items.single().string("likenum") == "5" }
        assertEquals("one request for two quick taps", 1, transport.likes.get())
        assertTrue(vm.list.items.single().isLiked)
    }

    @Test
    fun aFailedLikeIsUndone() {
        val transport = LikeTransport("""{"message":"操作失败"}""")
        val vm = likeVm(transport)
        vm.like(vm.list.items.single())
        assertTrue(vm.list.items.single().isLiked)
        transport.gate.countDown()
        waitFor { !vm.list.items.single().isLiked }
        assertEquals("1", vm.list.items.single().string("likenum"))
    }

    private fun content(block: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            val context = androidx.compose.ui.platform.LocalContext.current
            val app = AppContainer(context, "test-${UUID.randomUUID()}")
            val navigator = AppNavigator(context, rememberNavController(), app.settings)
            CompositionLocalProvider(
                LocalNavigator provides navigator,
                LocalAppContainer provides app,
                LocalSession provides SessionInfo(isLogin = true, uid = "20"),
            ) { block() }
        }
    }

    @Test
    fun deletingAnOwnReplyAsksFirst() {
        val reply = datum(
            """{"entityType":"feed_reply","id":"7","fetchType":"feed_reply","uid":"20","feedUid":"10","username":"我","message":"我的回复","dateline":1770000000,"replynum":"0","likenum":"0"}""",
        )
        var deleted = 0
        content { ReplyCard(reply, ItemActions(onDelete = { _, _ -> deleted++ })) }
        compose.onNode(hasContentDescription("更多")).performClick()
        compose.waitForIdle()
        compose.onNode(hasText("删除") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("删除这条回复？").assertExists()
        assertEquals("nothing deleted before the confirmation", 0, deleted)
        compose.onNode(hasText("删除") and hasClickAction()).performClick()
        compose.waitForIdle()
        assertEquals(1, deleted)
    }

    @Test
    fun theComposerAsksBeforeDroppingWhatWasWritten() {
        var dismissed = 0
        content { ComposerSheet(ComposeTarget.Reply("1", "feed", "作者"), onDismiss = { dismissed++ }) }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput("写了一半")
        compose.waitForIdle()
        Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("放弃编辑？").assertExists()
        assertEquals("still open", 0, dismissed)
        compose.onNodeWithText("继续编辑").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("写了一半").assertExists()

        Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("放弃").performClick()
        compose.waitUntil(5_000) { dismissed == 1 }
    }

    @Test
    fun aPostedReplyIsCounted() {
        val route: (String) -> String = { path ->
            if (path.endsWith("/v6/feed/detail")) """{"data":{"entityType":"feed","id":"1","uid":"10","username":"作者","message":"正文","replynum":"3","likenum":"0"}}"""
            else """{"data":[]}"""
        }
        val transport = Interceptor { chain ->
            val request = chain.request()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(route(request.url.encodedPath).toResponseBody("application/json".toMediaType())).build()
        }
        val app = AppContainer(ApplicationProvider.getApplicationContext(), "test-${UUID.randomUUID()}", transport)
        val vm = com.zailinn.c001apk.ui.screens.feed.FeedViewModel(app, "1")
        waitFor { vm.feed.value is LoadResult.Success }
        vm.addReply(datum("""{"entityType":"feed_reply","id":"9","message":"新回复"}"""), null, null)
        assertEquals("4", (vm.feed.value as LoadResult.Success).value.string("replynum"))
    }

    @Test
    fun theReplyCountOnAReplyOpensItsSubReplies() {
        val reply = datum(
            """{"entityType":"feed_reply","id":"7","fetchType":"feed_reply","uid":"30","feedUid":"10","username":"他","message":"回复","dateline":1770000000,"replynum":"4","likenum":"0"}""",
        )
        var shown = 0
        var replied = 0
        content { ReplyCard(reply, ItemActions(onShowReplies = { shown++ }, onReply = { _, _ -> replied++ })) }
        compose.onNode(hasText("4") and hasClickAction()).performClick()
        compose.waitForIdle()
        assertEquals(1, shown)
        assertEquals(0, replied)
    }

    @Test
    fun aFailedImageOffersRetryAndBackClosesTheViewer() {
        var closed = 0
        compose.setContent {
            val context = androidx.compose.ui.platform.LocalContext.current
            val app = AppContainer(context, "test-${UUID.randomUUID()}")
            val nav = rememberNavController()
            CompositionLocalProvider(LocalNavigator provides AppNavigator(context, nav, app.settings), LocalAppContainer provides app) {
                NavHost(nav, startDestination = "list") {
                    composable("list") {
                        androidx.compose.runtime.LaunchedEffect(Unit) { if (closed == 0) nav.navigate("viewer") }
                    }
                    composable("viewer") {
                        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { closed++ } }
                        com.zailinn.c001apk.ui.screens.other.ImageViewerScreen(listOf("https://nonexistent.invalid/a.jpg"), 0)
                    }
                }
            }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("图片加载失败").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("点击重试").assertExists()
        // A tap reveals the controls after their idle timeout; back leaves the viewer.
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.2f, height * 0.7f)) }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitUntil(5_000) { closed == 1 }
    }

    private fun rejectingApp(): AppContainer {
        val transport = Interceptor { chain ->
            val request = chain.request()
            val body = if (request.url.encodedPath.endsWith("/v6/feed/detail")) {
                """{"status":403,"message":"检测到当前版本存在异常，暂时无法访问该内容，请使用官方APP访问(-419)"}"""
            } else {
                """{"data":[]}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
        return AppContainer(ApplicationProvider.getApplicationContext(), "test-${UUID.randomUUID()}", transport)
    }

    @Test
    fun aRejectedImportedIdIsFlaggedOnlyWhenOneIsImported() {
        val app = rejectingApp()
        kotlinx.coroutines.runBlocking { app.repository.feedDetail("1") }
        assertFalse("no imported ID, nothing to offer", app.deviceIdRejected.value)
        kotlinx.coroutines.runBlocking {
            app.settings.setSzlmId("DUsyntheticSZLM0123456789abcdef")
            app.repository.feedDetail("1")
        }
        assertTrue(app.deviceIdRejected.value)
    }

    @Test
    fun theRejectionDialogClearsTheImportedId() {
        val app = rejectingApp()
        kotlinx.coroutines.runBlocking { app.settings.setSzlmId("DUsyntheticSZLM0123456789abcdef") }
        val model = app.settings.current.protocol.model
        app.deviceIdRejected.value = true
        compose.setContent { com.zailinn.c001apk.ui.C001apkApp(app, deepLink = null) {} }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("数盟 ID 被服务器拒绝").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("清除数盟 ID").performClick()
        compose.waitUntil(5_000) { app.settings.current.protocol.szlmId.isEmpty() }
        assertEquals("the model stays", model, app.settings.current.protocol.model)
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("数盟 ID 被服务器拒绝").fetchSemanticsNodes().isEmpty())
    }
}
