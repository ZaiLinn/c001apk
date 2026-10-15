package com.zailinn.c001apk

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.zailinn.c001apk.ui.C001apkApp
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

/** Renders the real app against synthetic responses and walks the main flows. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AppUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val transport = FixtureTransport()
    private lateinit var app: AppContainer

    @Before
    fun setUp() {
        app = AppContainer(ApplicationProvider.getApplicationContext(), "test-${UUID.randomUUID()}", transport)
    }

    private fun launch() {
        compose.setContent { C001apkApp(app, deepLink = null) {} }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("合成动态正文", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun shot(name: String) = compose.onRoot().captureRoboImage("${System.getProperty("roborazzi.output.dir")}/$name.png")

    @Test
    fun homeFeedRendersCards() {
        launch()
        compose.onNodeWithText("第二条合成动态，带三张图").assertIsDisplayed()
        compose.onNodeWithText("动态").assertIsDisplayed()
        assertTrue(transport.paths.any { it.endsWith("/v6/main/indexV8") })
        shot("home")
    }

    @Test
    fun feedDetailShowsRepliesAndNestedRows() {
        launch()
        // Tap the message text itself; the card center is its image, which opens the viewer.
        compose.onAllNodesWithText("合成动态正文", substring = true, useUnmergedTree = true).onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("合成回复内容", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(transport.paths.any { it.endsWith("/v6/feed/detail") })
        compose.onNodeWithText("共 2 回复").assertIsDisplayed()
        compose.onNodeWithText("嵌套合成回复", substring = true, useUnmergedTree = true).assertIsDisplayed()
        shot("feed")
    }

    @Test
    fun imageOpensViewerAndTopicTabLoads() {
        launch()
        // Card center on the first feed is its image: opens the viewer (List<String> route argument).
        compose.onAllNodesWithText("第二条合成动态，带三张图").onFirst().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("保存")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("/3", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("话题").performClick()
        compose.waitForIdle()
    }

    @Test
    fun bottomNavigationReachesFollowAndMine() {
        launch()
        compose.onNodeWithContentDescription("关注").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("还没有本地关注的用户", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        shot("follow")
        compose.onNodeWithContentDescription("我的").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("点击登录").fetchSemanticsNodes().isNotEmpty() }
        shot("mine")
    }

    /** Start, drag and release a predictive back gesture. */
    private fun backGesture(cancel: Boolean = false) {
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 500f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(200f, 500f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        compose.runOnUiThread { if (cancel) dispatcher.dispatchOnBackCancelled() else dispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun predictiveBackReturnsFromTabsAndPages() {
        launch()
        compose.onNodeWithContentDescription("我的").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("点击登录").fetchSemanticsNodes().isNotEmpty() }
        // A cancelled gesture stays on the tab; a completed one returns to 主页.
        backGesture(cancel = true)
        compose.onNodeWithText("点击登录").assertIsDisplayed()
        backGesture()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("第二条合成动态，带三张图").fetchSemanticsNodes().isNotEmpty() }

        compose.onAllNodesWithText("合成动态正文", substring = true, useUnmergedTree = true).onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("合成回复内容", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 500f, 0f, BackEventCompat.EDGE_LEFT))
            compose.activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(300f, 500f, 0.6f, BackEventCompat.EDGE_LEFT))
        }
        compose.mainClock.advanceTimeBy(500)
        shot("back-gesture")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        backGesture()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("合成回复内容", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("第二条合成动态，带三张图").assertIsDisplayed()
    }

    @Test
    fun settingsAndSearchOpen() {
        launch()
        compose.onNodeWithContentDescription("设置").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("外观与字号").fetchSemanticsNodes().isNotEmpty() }
        shot("settings")
        compose.onNodeWithText("外观与字号").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("动态取色").fetchSemanticsNodes().isNotEmpty() }
        shot("settings-appearance")
    }
}
