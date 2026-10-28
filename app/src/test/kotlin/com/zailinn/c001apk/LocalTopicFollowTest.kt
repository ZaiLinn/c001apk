package com.zailinn.c001apk

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.zailinn.c001apk.ui.C001apkApp
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import java.util.concurrent.CopyOnWriteArrayList

/** Guests follow a topic on the device and read its newest feeds on the Follow tab. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class LocalTopicFollowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val topicFeeds = CopyOnWriteArrayList<okhttp3.HttpUrl>()
    private val transport = Interceptor { chain ->
        val request = chain.request()
        val outer = request.url.queryParameter("url").orEmpty()
        val inner = if (outer.startsWith("/page?")) "https://example.invalid$outer".toHttpUrl().queryParameter("url").orEmpty() else ""
        val body = when {
            request.url.encodedPath.endsWith("/topic/newTagDetail") -> """{"data":{"id":"7","entityType":"topic","title":"合成话题","logo":"",
                "tabList":[{"title":"讨论","url":"#/topic/tagFeedList?id=7"}]}}"""
            inner.startsWith("/topic/tagFeedList") && "sortField=dateline_desc" in inner -> {
                topicFeeds += "https://example.invalid$inner".toHttpUrl()
                if (request.url.queryParameter("page") == "1") FixtureTransport.list(FixtureTransport.feed(301, "话题里的最新帖子")) else FixtureTransport.list()
            }
            else -> FixtureTransport.list()
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }
    private val app = AppContainer(ApplicationProvider.getApplicationContext(), "topic-follow-${UUID.randomUUID()}", transport)

    /** The view model saves on the main looper, which waitUntil alone does not drain. */
    private fun waitDraining(condition: () -> Boolean) =
        compose.waitUntil(5_000) { shadowOf(android.os.Looper.getMainLooper()).idle(); condition() }

    @Test
    fun topicMenuTogglesTheLocalFollow() {
        compose.setContent { C001apkApp(app, Uri.parse("https://www.coolapk.com/t/合成话题")) {} }
        // The loaded header shows the name; the bar shows it only once the header folds away.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("合成话题").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("更多").performClick()
        // A guest has no account follow in the menu; the header's 关注 button is the local one.
        assertEquals(1, compose.onAllNodesWithText("关注").fetchSemanticsNodes().size)
        compose.onNodeWithText("本地关注").performClick()
        waitDraining { app.content.isLocalFollowTopic("7") }
        assertEquals("合成话题", app.content.localFollowTopics.value.single().title)
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("取消本地关注").performClick()
        waitDraining { !app.content.isLocalFollowTopic("7") }
    }

    @Test
    fun followTabShowsTheTopicsNewestFeeds() {
        runBlocking { app.content.localFollowTopic("7", "合成话题", "") }
        compose.setContent { C001apkApp(app, deepLink = null) {} }
        compose.onNode(hasText("关注") and hasClickAction()).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("话题里的最新帖子").fetchSemanticsNodes().isNotEmpty() }
        val first = topicFeeds.first()
        assertEquals("7", first.queryParameter("id"))
        assertEquals("合成话题", first.queryParameter("title"))
        // Blocking the topic removes it from the merged feed.
        runBlocking { app.content.blockTopic("合成话题") }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("话题里的最新帖子").fetchSemanticsNodes().isEmpty() }
        assertTrue(app.content.isLocalFollowTopic("7"))
        assertFalse(compose.onAllNodesWithText("还没有本地关注的用户", substring = true).fetchSemanticsNodes().isNotEmpty())
    }
}
