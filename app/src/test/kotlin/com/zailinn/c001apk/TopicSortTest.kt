package com.zailinn.c001apk

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.zailinn.c001apk.ui.C001apkApp
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Official sortSelectCard URLs are opaque: nested encoding and query fields must survive. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class TopicSortTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun topicSortsUseServerUrlsAndRestartAtTheFirstPage() {
        val requests = CopyOnWriteArrayList<okhttp3.HttpUrl>()
        val transport = Interceptor { chain ->
            val request = chain.request()
            val outerUrl = request.url.queryParameter("url").orEmpty()
            val sort = if (outerUrl.startsWith("/page?")) {
                "https://example.invalid$outerUrl".toHttpUrl().queryParameter("url").orEmpty()
            } else outerUrl
            val label = when {
                "sortField=dateline_desc" in sort -> "最新"
                "sortField=rank_score" in sort -> "热度"
                else -> "默认"
            }
            val body = when {
                request.url.encodedPath.endsWith("/topic/newTagDetail") -> """{"data":{"id":"7","entityType":"topic","title":"合成话题",
                    "tabList":[{"title":"讨论","page_name":"lastupdate_desc","url":"#/topic/tagFeedList?withSortCard=1&id=7&title=%E5%90%88%E6%88%90%E8%AF%9D%E9%A2%98&sortField=lastupdate_desc"}],"selectedTab":"lastupdate_desc"}}"""
                request.url.encodedPath.endsWith("/page/dataList") -> {
                    requests += request.url
                    if (request.url.queryParameter("page") != "1") FixtureTransport.list()
                    else FixtureTransport.list(
                        """{"entityType":"card","entityTemplate":"sortSelectCard","entityId":997,
                            "extraData":"{\"selectedTab\":\"$label\"}","entities":[
                            {"entityType":"page","title":"默认","url":"/page?url=%2Ftopic%2FtagFeedList%3Fid%3D7%26title%3D%25E5%2590%2588%25E6%2588%2590%25E8%25AF%259D%25E9%25A2%2598"},
                            {"entityType":"page","title":"最新","url":"/page?url=%2Ftopic%2FtagFeedList%3Fid%3D7%26title%3D%25E5%2590%2588%25E6%2588%2590%25E8%25AF%259D%25E9%25A2%2598%26sortField%3Ddateline_desc"},
                            {"entityType":"page","title":"热度","url":"/page?url=%2Ftopic%2FtagFeedList%3Fid%3D7%26title%3D%25E5%2590%2588%25E6%2588%2590%25E8%25AF%259D%25E9%25A2%2598%26sortField%3Drank_score"}]}""",
                        FixtureTransport.feed(if (label == "最新") 201 else if (label == "热度") 202 else 200, "$label 排序的帖子"),
                    )
                }
                else -> FixtureTransport.list()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
        val app = AppContainer(ApplicationProvider.getApplicationContext(), "topic-sort-${UUID.randomUUID()}", transport)
        compose.setContent { C001apkApp(app, Uri.parse("https://www.coolapk.com/t/合成话题")) {} }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("默认 排序的帖子").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("默认 排序的帖子").assertIsDisplayed()
        compose.onNodeWithText("默认").assertIsSelected()

        for (label in listOf("最新", "热度", "默认")) {
            compose.onNodeWithText(label).performClick().assertIsSelected()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("$label 排序的帖子").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("$label 排序的帖子").assertIsDisplayed()
            for (other in listOf("默认", "最新", "热度") - label) {
                compose.onNodeWithText("$other 排序的帖子").assertDoesNotExist()
            }
            val first = requests.last { it.queryParameter("page") == "1" }
            assertNull(first.queryParameter("firstItem"))
            assertNull(first.queryParameter("lastItem"))
            val inner = "https://example.invalid${first.queryParameter("url")}".toHttpUrl().queryParameter("url")!!
            val query = "https://example.invalid$inner".toHttpUrl()
            assertEquals("合成话题", query.queryParameter("title"))
            assertEquals(when (label) { "最新" -> "dateline_desc"; "热度" -> "rank_score"; else -> null }, query.queryParameter("sortField"))
        }
    }
}
