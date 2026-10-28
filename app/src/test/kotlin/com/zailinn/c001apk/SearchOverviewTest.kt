package com.zailinn.c001apk

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.zailinn.c001apk.ui.nav.AppNavigator
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.screens.LocalAppContainer
import com.zailinn.c001apk.ui.screens.search.SearchResultScreen
import androidx.compose.runtime.CompositionLocalProvider
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** 综合 puts products and topics above the feeds, as the official app does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SearchOverviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun waitText(text: String) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    /** 数码 and 话题 often arrive while the feeds are still loading; the page still starts at its top. */
    @Test
    fun sectionsArrivingBeforeTheFeedsStayOnScreen() {
        val feedsMayLoad = java.util.concurrent.CountDownLatch(1)
        val transport = Interceptor { chain ->
            val request = chain.request()
            val body = if (request.url.encodedPath.endsWith("/v6/search") && request.url.queryParameter("page") == "1") {
                when (request.url.queryParameter("type")) {
                    "product" -> FixtureTransport.list(*Array(5) {
                        """{"entityType":"product","id":"${10 + it}","title":"合成手机$it","url":"/product/${10 + it}"}"""
                    })
                    "feedTopic" -> FixtureTransport.list("""{"entityType":"topic","id":"7","title":"合成话题","url":"/t/合成话题"}""")
                    else -> { feedsMayLoad.await(10, java.util.concurrent.TimeUnit.SECONDS); FixtureTransport.list(FixtureTransport.feed(900, "关于合成手机的动态")) }
                }
            } else FixtureTransport.list()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
        val app = AppContainer(ApplicationProvider.getApplicationContext(), "search-overview-${UUID.randomUUID()}", transport)
        compose.setContent {
            val context = androidx.compose.ui.platform.LocalContext.current
            CompositionLocalProvider(
                LocalAppContainer provides app,
                LocalNavigator provides AppNavigator(context, rememberNavController(), app.settings),
            ) { SearchResultScreen("合成手机", null, null, null) }
        }
        waitText("合成话题")
        compose.waitForIdle()
        compose.onNodeWithText("合成手机0").assertIsDisplayed()
        feedsMayLoad.countDown()
        waitText("关于合成手机的动态")
        compose.onNodeWithText("合成手机0").assertIsDisplayed()
    }

    @Test
    fun overviewShowsProductsTopicsAndSortableFeeds() {
        val searches = CopyOnWriteArrayList<String>()
        val transport = Interceptor { chain ->
            val request = chain.request()
            val body = if (request.url.encodedPath.endsWith("/v6/search") && request.url.queryParameter("page") == "1") {
                val type = request.url.queryParameter("type").orEmpty()
                searches += type + ":" + request.url.queryParameter("sort")
                when (type) {
                    "product" -> FixtureTransport.list(*Array(7) {
                        """{"entityType":"product","id":"${10 + it}","title":"合成手机$it","url":"/product/${10 + it}","star_average_score":"9.$it","hot_num_txt":"1万","feed_comment_num_txt":"2千"}"""
                    })
                    "feedTopic" -> FixtureTransport.list("""{"entityType":"topic","id":"7","title":"合成话题","url":"/t/合成话题","hot_num_txt":"5千","commentnum_txt":"30"}""")
                    else -> FixtureTransport.list(FixtureTransport.feed(900, "关于合成手机的动态"))
                }
            } else FixtureTransport.list()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
        val app = AppContainer(ApplicationProvider.getApplicationContext(), "search-overview-${UUID.randomUUID()}", transport)
        compose.setContent {
            val context = androidx.compose.ui.platform.LocalContext.current
            CompositionLocalProvider(
                LocalAppContainer provides app,
                LocalNavigator provides AppNavigator(context, rememberNavController(), app.settings),
            ) { SearchResultScreen("合成手机", null, null, null) }
        }
        waitText("合成手机0")
        compose.onNodeWithText("9.0").assertIsDisplayed()
        // Five per section; the rest are one tap away.
        assertTrue(compose.onAllNodesWithText("合成手机5").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("合成话题").assertIsDisplayed()
        waitText("关于合成手机的动态")
        compose.onNode(hasText("热度") and hasClickAction()).performClick()
        compose.waitUntil(10_000) { searches.contains("feed:hot") }
        compose.onNodeWithText("更多相关数码").performClick()
        waitText("合成手机6")
        compose.onNode(hasText("数码") and hasClickAction()).assertIsSelected()
    }
}
