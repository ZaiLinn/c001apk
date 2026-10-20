package com.zailinn.c001apk

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.zailinn.c001apk.ui.C001apkApp
import org.junit.Assert.assertEquals
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

    private suspend fun addWord(word: String) = app.content.updateCategories {
        it + com.zailinn.c001apk.core.model.SensitiveCategory("test", "自定义", keywords = listOf(word))
    }

    private fun hasWord(word: String) = app.content.filterCategories.value.any { word in it.keywords }

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
    fun longPressHomeTabOpensTabEditor() {
        launch()
        compose.onNode(hasText("热门") and androidx.compose.ui.test.hasClickAction())
            .performTouchInput { longClick() }
        compose.onNodeWithText("首页标签").assertIsDisplayed()
        shot("home-tab-editor")
        // Drag 应用 two rows down by its handle.
        compose.onNodeWithContentDescription("拖动应用").performTouchInput {
            down(center)
            // The icon is half as tall as its 48dp row: move two rows and a bit.
            repeat(20) { moveBy(androidx.compose.ui.geometry.Offset(0f, height * 4.4f / 20)) }
            up()
        }
        compose.onNodeWithText("保存").performClick()
        // The long press only opened the editor; the feed tab stayed selected.
        compose.onNodeWithText("第二条合成动态，带三张图").assertIsDisplayed()
        compose.waitUntil(5_000) { app.settings.current.homeTabs.isNotEmpty() }
        assertEquals(listOf("Feed", "Hot", "Apps", "Topic", "Product", "Coolpic"), app.settings.current.homeTabs)
        shot("home-tabs")
    }

    @Test
    fun sensitiveFeedsFoldUntilTapped() {
        kotlinx.coroutines.runBlocking {
            app.settings.set(com.zailinn.c001apk.data.SettingKeys.sensitiveFold, true)
            addWord("三张图")
            kotlinx.coroutines.withTimeout(5_000) {
                while (!app.settings.current.sensitiveFold || !hasWord("三张图")) kotlinx.coroutines.delay(20)
            }
        }
        launch()
        compose.onNodeWithText("已折叠 · 自定义 · 三张图").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("第二条合成动态，带三张图").fetchSemanticsNodes().isEmpty())
        shot("folded")
        compose.onNodeWithText("已折叠 · 自定义 · 三张图").performClick()
        compose.onNodeWithText("第二条合成动态，带三张图").assertIsDisplayed()
    }

    @Test
    fun hiddenSensitiveFeedsLeaveNoTrace() {
        kotlinx.coroutines.runBlocking {
            // Hiding wins when both switches are on.
            app.settings.set(com.zailinn.c001apk.data.SettingKeys.sensitiveFold, true)
            app.settings.set(com.zailinn.c001apk.data.SettingKeys.sensitiveHide, true)
            addWord("三张图")
            kotlinx.coroutines.withTimeout(5_000) {
                while (!app.settings.current.sensitiveHide || !hasWord("三张图")) kotlinx.coroutines.delay(20)
            }
        }
        launch()
        assertTrue(compose.onAllNodesWithText("第二条合成动态，带三张图").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("已折叠", substring = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("合成应用").assertIsDisplayed()
    }

    /** First-page home requests only; later pages load while scrolling. */
    private fun homeLoads() = synchronized(transport.paths) {
        transport.urls.count { it.contains("/v6/main/indexV8?") && Regex("[?&]page=1(&|$)").containsMatchIn(it) }
    }

    private fun returnHomeTwice() {
        tab("关注")
        compose.waitForIdle()
        tab("主页")
        compose.waitForIdle()
        // Open a feed and come back with the system back button.
        compose.onAllNodesWithText("合成动态正文", substring = true, useUnmergedTree = true).onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("合成回复内容", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("合成动态正文", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    @Test
    fun returningHomeRefreshesOnlyWhenEnabled() {
        launch()
        // Tapping 主页 and 关注 while selected refreshes or scrolls that tab once...
        tab("主页")
        compose.waitForIdle()
        compose.waitUntil(10_000) { homeLoads() >= 2 }
        tab("关注")
        compose.waitForIdle()
        tab("关注")
        compose.waitForIdle()
        tab("主页")
        compose.waitForIdle()
        val first = homeLoads()
        assertEquals("one refresh for the tap on 主页", 2, first)
        // ...and is not replayed when 主页 is shown again with the switch off.
        returnHomeTwice()
        assertEquals("switch off: no extra loads", first, homeLoads())

        kotlinx.coroutines.runBlocking {
            app.settings.set(com.zailinn.c001apk.data.SettingKeys.refreshOnReturn, true)
            kotlinx.coroutines.withTimeout(5_000) { while (!app.settings.current.refreshOnReturn) kotlinx.coroutines.delay(20) }
        }
        compose.waitForIdle()
        returnHomeTwice()
        compose.waitUntil(10_000) { homeLoads() >= first + 2 }
        assertEquals("one refresh per return", first + 2, homeLoads())
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
    fun feedCanBeCollectedIntoACloudCollection() {
        launch()
        kotlinx.coroutines.runBlocking { app.accounts.saveLogin("10", "synthetic", "synthetic-token") }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("合成动态正文", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("合成动态正文", substring = true, useUnmergedTree = true).onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("更多").fetchSemanticsNodes().isNotEmpty() }
        // The top bar's menu, not a reply's.
        val menus = compose.onAllNodesWithContentDescription("更多")
        val top = menus.fetchSemanticsNodes().withIndex().minBy { it.value.boundsInRoot.top }.index
        menus[top].performClick()
        // The dropdown opens in a popup; wait for it before checking its items.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("收藏到收藏夹").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("本地收藏").assertIsDisplayed()
        compose.onNodeWithText("收藏到收藏夹").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("合成收藏夹").fetchSemanticsNodes().isNotEmpty() }
        shot("collection-picker")
        compose.onNodeWithText("合成收藏夹").performClick()
        compose.waitUntil(5_000) { synchronized(transport.posts) { transport.posts.any { it.first.endsWith("/v6/collection/addItem") } } }
        val body = synchronized(transport.posts) { transport.posts.first { it.first.endsWith("/v6/collection/addItem") }.second }
        assertEquals(mapOf("id" to "2", "cancelId" to "", "targetId" to "100", "type" to "feed"), form(body))
        assertTrue(transport.urls.any { it.startsWith("/v6/collection/list?") && "id=100" in it && "type=feed" in it })
    }

    private fun form(body: String) = body.split("&").filter { it.isNotEmpty() }.associate {
        val (k, v) = (it.split("=", limit = 2) + "").take(2)
        java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
    }

    @Test
    fun officialDeviceIdIsRecognisedFromTheCopiedText() {
        launch()
        tab("我的")
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("协议与设备").performClick()
        compose.onNodeWithText("导入官方设备标识").performClick()
        compose.onNodeWithText("粘贴官方复制的完整文本").performTextInput("OAID: 0b5c6a8e-1f2d-4c3b-9a7e-6d5f4e3c2b1a")
        compose.onNodeWithText("未识别到 ShuzlmID", substring = true).assertIsDisplayed()
        compose.onNodeWithText("粘贴官方复制的完整文本").performTextClearance()
        compose.onNodeWithText("粘贴官方复制的完整文本")
            .performTextInput("设备ID: 0123456789abcdef\nShuzlmID: DUsyntheticSZLM0123456789abcdef\nAndroidID: fedcba9876543210")
        compose.onNodeWithText("已识别数盟 ID", substring = true).assertIsDisplayed()
        // Hidden by default; the input stays for another paste.
        compose.onNodeWithText("DUsyntheticSZLM0123456789abcdef", substring = true).assertDoesNotExist()
        compose.onNodeWithText("粘贴官方复制的完整文本").assertIsDisplayed()
        shot("device-identity")
        val model = app.settings.current.protocol.model
        compose.onNodeWithText("保存并应用").performClick()
        compose.waitUntil(5_000) { app.settings.current.protocol.szlmId == "DUsyntheticSZLM0123456789abcdef" }
        assertEquals("DUsyntheticSZLM0123456789abcdef", app.settings.current.protocol.deviceId)
        assertEquals(model, app.settings.current.protocol.model)
        compose.onNodeWithText("已导入数盟 ID", substring = true).assertIsDisplayed()
        compose.onNodeWithText("DUsyntheticSZLM0123456789abcdef", substring = true).assertDoesNotExist()
        compose.onNodeWithText("•", substring = true).assertDoesNotExist()
    }

    @Test
    fun appOpensOnTheChosenHomeTab() {
        kotlinx.coroutines.runBlocking {
            app.settings.set(com.zailinn.c001apk.data.SettingKeys.homeStartTab, "Hot")
            kotlinx.coroutines.withTimeout(5_000) { while (app.settings.current.homeStartTab != "Hot") kotlinx.coroutines.delay(20) }
        }
        compose.setContent { C001apkApp(app, deepLink = null) {} }
        // 热门 reads /page/dataList; 动态 (indexV8) is not the open page.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("数据列表合成动态").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("数据列表合成动态").assertIsDisplayed()
        assertTrue(transport.urls.any { it.startsWith("/v6/page/dataList?") && "V9_HOME_TAB_RANKING" in it })
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

    @Test
    fun contentFilterEditsCategories() {
        launch()
        tab("我的")
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("设置")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("设置").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("内容过滤").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("内容过滤").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("用户黑名单").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("直接隐藏").performClick()
        compose.waitUntil(5_000) { app.settings.current.sensitiveHide }
        shot("content-filter")
        compose.onNodeWithText("两性对立").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("舔狗").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("启用此分类").performClick()
        compose.waitUntil(5_000) { app.content.filterCategories.value.single { it.id == "gender" }.enabled.not() }
        compose.onNodeWithContentDescription("删除 舔狗").performClick()
        compose.waitUntil(5_000) { "舔狗" !in app.content.filterCategories.value.single { it.id == "gender" }.keywords }
        shot("category-edit")
    }
}
