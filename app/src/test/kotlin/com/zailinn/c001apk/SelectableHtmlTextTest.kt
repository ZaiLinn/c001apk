package com.zailinn.c001apk

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zailinn.c001apk.ui.common.ContentPress
import com.zailinn.c001apk.ui.common.HtmlText
import com.zailinn.c001apk.ui.common.LocalContentPress
import com.zailinn.c001apk.ui.nav.AppNavigator
import com.zailinn.c001apk.ui.nav.LocalNavigator
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * In a detail page, a long press on the content selects text in place while a
 * long press beside it still opens the card's copy page. A tap on the text
 * does nothing; links still open.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// API 27: Robolectric's Magnifier crashes when the selection dismisses it.
@Config(sdk = [27], qualifiers = "w411dp-h891dp-xxhdpi")
class SelectableHtmlTextTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val plain = "下载链接说明文字"
    private val html = """$plain<a href="">#话题#</a>"""

    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun longPressInsideSelectsAndOutsideOpensTheCopyPage() {
        var cardLongPress = 0
        var cardTap = 0
        var link = 0
        compose.setContent {
            val context = androidx.compose.ui.platform.LocalContext.current
            val nav = androidx.navigation.compose.rememberNavController()
            val navigator = AppNavigator(context, nav, AppContainer(context, "test-${UUID.randomUUID()}").settings)
            val press = androidx.compose.runtime.remember { ContentPress() }
            CompositionLocalProvider(LocalNavigator provides navigator, LocalContentPress provides press) {
                Column(
                    Modifier.width(367.dp).testTag("card")
                        .combinedClickable(onClick = { cardTap++ }, onLongClick = { if (!press.active) cardLongPress++ }),
                ) {
                    Spacer(Modifier.fillMaxWidth().height(80.dp))
                    HtmlText(html, fontSize = 16.sp, onClick = { link++ }, selectable = true)
                }
            }
        }
        val node = compose.onNodeWithText(plain, substring = true, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.single()
        val text = layout.layoutInput.text.text

        node.performTouchInput { longClick(layout.getBoundingBox(2).center) }
        compose.waitForIdle()
        assertEquals("long press on the content", 0, cardLongPress)

        node.performTouchInput { click(layout.getBoundingBox(2).center) }
        compose.waitForIdle()
        assertEquals("tap on the content", 0, cardTap)

        node.performTouchInput { click(layout.getBoundingBox(text.indexOf("#话题#") + 1).center) }
        compose.waitForIdle()
        assertEquals(1, link)
        assertEquals(0, cardTap)

        compose.onNodeWithTag("card").performTouchInput { longClick(androidx.compose.ui.geometry.Offset(20f, 20f)) }
        compose.waitForIdle()
        assertEquals("long press beside the content", 1, cardLongPress)
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test
    fun copyingTheSecondOfTwoSameLabelLinksKeepsItsOwnAddress() {
        val clips = mutableListOf<String>()
        val fake = object : androidx.compose.ui.platform.Clipboard {
            override val nativeClipboard: androidx.compose.ui.platform.NativeClipboard
                get() = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
            override suspend fun getClipEntry(): androidx.compose.ui.platform.ClipEntry? = null
            override suspend fun setClipEntry(clipEntry: androidx.compose.ui.platform.ClipEntry?) {
                clipEntry?.clipData?.getItemAt(0)?.text?.let { clips += it.toString() }
            }
        }
        val two = """网盘A：<a href="https://pan.example.com/a">查看链接</a> 网盘B：<a href="https://pan.example.com/b">查看链接</a>"""
        compose.setContent {
            val context = androidx.compose.ui.platform.LocalContext.current
            val nav = androidx.navigation.compose.rememberNavController()
            val navigator = AppNavigator(context, nav, AppContainer(context, "test-${UUID.randomUUID()}").settings)
            CompositionLocalProvider(LocalNavigator provides navigator, androidx.compose.ui.platform.LocalClipboard provides fake) {
                HtmlText(two, Modifier.width(367.dp), fontSize = 16.sp, selectable = true)
            }
        }
        val node = compose.onNodeWithText("网盘A", substring = true, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.single()
        val text = layout.layoutInput.text.text
        // Long press on the plain "网盘B" and drag across the second link.
        node.performTouchInput {
            down(layout.getBoundingBox(text.indexOf("网盘B") + 1).center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(layout.getBoundingBox(text.lastIndex).center)
            up()
        }
        compose.waitForIdle()
        node.performKeyInput { withKeyDown(androidx.compose.ui.input.key.Key.CtrlLeft) { pressKey(androidx.compose.ui.input.key.Key.C) } }
        compose.waitForIdle()
        val copied = clips.lastOrNull().orEmpty()
        assertTrue("copied: $copied", copied.contains("https://pan.example.com/b") && !copied.contains("https://pan.example.com/a"))
    }
}
