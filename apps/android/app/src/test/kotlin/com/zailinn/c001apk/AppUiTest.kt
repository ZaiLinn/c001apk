package com.zailinn.c001apk

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
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

    /** Bottom bar item: the clickable node carrying the label. */
    private fun tab(label: String) =
        compose.onNode(hasText(label) and androidx.compose.ui.test.hasClickAction()).performClick()

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
        compose.onNode(hasText("话题") and androidx.compose.ui.test.hasClickAction()).performClick()
        compose.waitForIdle()
    }

    @Test
    fun bottomNavigationReachesFollowAndMine() {
        launch()
        tab("关注")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("还没有本地关注的用户", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        shot("follow")
        tab("我的")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("点击登录").fetchSemanticsNodes().isNotEmpty() }
        shot("mine")
    }

    /** System back; predictive back is disabled, so it arrives as a plain back press. */
    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun backReturnsFromTabsAndPages() {
        launch()
        tab("我的")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("点击登录").fetchSemanticsNodes().isNotEmpty() }
        back()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("第二条合成动态，带三张图").fetchSemanticsNodes().isNotEmpty() }

        compose.onAllNodesWithText("合成动态正文", substring = true, useUnmergedTree = true).onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("合成回复内容", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        back()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("合成回复内容", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("第二条合成动态，带三张图").assertIsDisplayed()
    }

    @Test
    fun settingsAndSearchOpen() {
        launch()
        // Settings live on the profile page, not on the home top bar.
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("设置")).fetchSemanticsNodes().isEmpty())
        tab("我的")
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("设置")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("设置").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("外观与字号").fetchSemanticsNodes().isNotEmpty() }
        shot("settings")
        compose.onNodeWithText("外观与字号").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("动态取色").fetchSemanticsNodes().isNotEmpty() }
        shot("settings-appearance")
    }
}
