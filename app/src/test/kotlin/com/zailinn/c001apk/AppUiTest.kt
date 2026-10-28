package com.zailinn.c001apk

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
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
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
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
    fun homeLeavesOutSensitiveFeedsEvenWhenFolding() {
        kotlinx.coroutines.runBlocking {
            // Folding is the default, but the home tabs never show folded rows.
            assertTrue(app.settings.current.filterFold)
            addWord("三张图")
            kotlinx.coroutines.withTimeout(5_000) {
                while (!hasWord("三张图")) kotlinx.coroutines.delay(20)
            }
        }
        launch()
        assertTrue(compose.onAllNodesWithText("第二条合成动态，带三张图").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("已折叠", substring = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("合成应用").assertIsDisplayed()
    }

    @Test
    fun hiddenSensitiveFeedsLeaveNoTrace() {
        kotlinx.coroutines.runBlocking {
            app.settings.set(com.zailinn.c001apk.data.SettingKeys.filterFold, false)
            addWord("三张图")
            kotlinx.coroutines.withTimeout(5_000) {
                while (app.settings.current.filterFold || !hasWord("三张图")) kotlinx.coroutines.delay(20)
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
        // Closing the keyboard sends back part of the paste; the recognised ID stays ready to save.
        compose.onNodeWithText("粘贴官方复制的完整文本").performTextInput("设备ID: 0123")
        compose.onNodeWithText("保存并应用").assertIsEnabled()
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

    /** Opening a result and coming back neither reloads the search nor loses the scroll position. */
    @Test
    fun searchResultsKeepTheirPlaceAfterOpeningAResult() {
        launch()
        // First-page requests only: a reload asks for page 1 again, scrolling near the end asks for page 2.
        fun searches() = synchronized(transport.paths) {
            transport.urls.count { it.contains("/v6/search?") && Regex("[?&]page=1(&|$)").containsMatchIn(it) }
        }
        compose.onNodeWithText("搜索动态、应用、话题和用户").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("合成")
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performImeAction()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("搜索结果第1条").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        // The vertical result list, not the horizontal pager of result types around it.
        val list = androidx.compose.ui.test.hasScrollToIndexAction() and
            androidx.compose.ui.test.hasAnyDescendant(hasText("搜索结果第1条")) and
            androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)
        compose.onNode(list).performScrollToIndex(12)
        compose.waitForIdle()
        val loaded = searches()

        compose.onNodeWithText("搜索结果第13条").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("合成回复内容", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("搜索结果第13条").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()

        assertEquals("no reload of the results", loaded, searches())
        assertTrue("still scrolled down", compose.onAllNodesWithText("搜索结果第1条").fetchSemanticsNodes().isEmpty())
    }

    /** The result page keeps an editable search box; a new keyword searches again in place. */
    @Test
    fun resultPageSearchesAgainFromItsBox() {
        launch()
        compose.onNodeWithText("搜索动态、应用、话题和用户").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("合成")
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performImeAction()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("搜索结果第1条").fetchSemanticsNodes().isNotEmpty() }
        val box = compose.onNode(androidx.compose.ui.test.hasSetTextAction())
        box.assert(hasText("合成"))
        box.performTextReplacement("手机")
        box.performImeAction()
        compose.waitUntil(10_000) {
            synchronized(transport.paths) { transport.urls.any { it.contains("/v6/search?") && it.contains("searchValue=%E6%89%8B%E6%9C%BA") } }
        }
    }

    /** Stopping a follow asks first; starting one does not. */
    @Test
    fun unfollowAsksFirst() {
        kotlinx.coroutines.runBlocking { app.content.localFollow("10", "合成作者", "") }
        compose.setContent { C001apkApp(app, deepLink = android.net.Uri.parse("https://www.coolapk.com/u/10")) {} }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("取消本地关注").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("取消本地关注").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("取消关注 合成作者？").assertExists()
        assertTrue("still followed until confirmed", app.content.isLocalFollow("10"))
        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()
        assertTrue(app.content.isLocalFollow("10"))
    }

    /** In a list the reply count opens the feed scrolled to its replies. */
    @Test
    // A short screen, so the two synthetic replies leave room to scroll the feed away.
    @Config(sdk = [35], qualifiers = "w411dp-h480dp-xxhdpi")
    fun replyCountInAListOpensTheReplies() {
        launch()
        compose.onAllNodes(hasText("2") and androidx.compose.ui.test.hasClickAction()).onFirst().performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("共", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        // The reply header sits right under the title bar instead of below the feed and its image.
        val header = compose.onAllNodesWithText("共", substring = true).fetchSemanticsNodes().first()
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("header at ${header.boundsInRoot.top / density}dp", header.boundsInRoot.top / density < 200f)
    }

    /** Reading down slides the search row, tabs and bottom bar away; scrolling back up returns them. */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h480dp-xxhdpi")
    fun barsHideWhileScrollingDownAndReturnOnTheWayUp() {
        launch()
        val search = "搜索动态、应用、话题和用户"
        fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().any { it.boundsInRoot.height > 1f && it.boundsInRoot.bottom > 0f }
        assertTrue(shown(search) && shown("关注"))
        val list = compose.onAllNodes(androidx.compose.ui.test.hasScrollToIndexAction()).onFirst()
        list.performTouchInput { swipeUp(startY = bottom * .8f, endY = top + 10f) }
        compose.waitForIdle()
        assertTrue("search row hidden", !shown(search))
        assertTrue("bottom bar hidden", !shown("关注"))
        list.performTouchInput { swipeDown(startY = top + 10f, endY = bottom * .8f) }
        compose.waitForIdle()
        assertTrue(shown(search) && shown("关注"))
    }

    /** A blocked user's page shows the profile only; their posts are not even requested. */
    @Test
    fun blockedUserPostsAreNotRequested() {
        kotlinx.coroutines.runBlocking { app.content.block("10") }
        compose.setContent { C001apkApp(app, deepLink = android.net.Uri.parse("https://www.coolapk.com/u/10")) {} }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("已屏蔽用户", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertEquals(0, synchronized(transport.paths) { transport.paths.count { it.endsWith("/v6/user/feedList") } })
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
        assertTrue(app.settings.current.filterFold)
        compose.onNodeWithText("折叠").performClick()
        compose.waitUntil(5_000) { !app.settings.current.filterFold }
        compose.onNodeWithText("折叠").performClick()
        compose.waitUntil(5_000) { app.settings.current.filterFold }
        compose.onNodeWithText("我屏蔽的话题").assertExists()
        assertTrue(compose.onAllNodesWithText("话题黑名单").fetchSemanticsNodes().isEmpty())
        shot("content-filter")
        compose.onNode(androidx.compose.ui.test.hasScrollToNodeAction()).performScrollToNode(androidx.compose.ui.test.hasText("两性对立"))
        compose.onNodeWithText("两性对立").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("渣女").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("启用此分类").performClick()
        compose.waitUntil(5_000) { app.content.filterCategories.value.single { it.id == "gender" }.enabled.not() }
        compose.onNodeWithContentDescription("删除 渣女").performClick()
        compose.waitUntil(5_000) { "渣女" !in app.content.filterCategories.value.single { it.id == "gender" }.keywords }
        shot("category-edit")
    }
}
