package com.zailinn.c001apk

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import com.zailinn.c001apk.ui.common.HtmlText
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
 * Taps on plain text must not open a nearby topic. With an emoticon before
 * wrapped topics, Compose's own link areas covered most of the first two lines.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class HtmlLinkTapTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val plain = "我嘞个传奇耐电王再世，1.3v给到8ee6，缩肛都跳过了，直接给电死了"
    private val html = """$plain[受虐滑稽]<a href="">#骁龙8至尊版Gen6#</a> <a href="">#荣耀Magic9ProMax#</a> <a href="">#iQOO16#</a>"""

    @Test
    fun onlyTheLinkedCharactersOpenTheLink() {
        var opened = 0
        compose.setContent {
            val context = androidx.compose.ui.platform.LocalContext.current
            val nav = androidx.navigation.compose.rememberNavController()
            val navigator = AppNavigator(context, nav, AppContainer(context, "test-${UUID.randomUUID()}").settings)
            CompositionLocalProvider(LocalNavigator provides navigator) {
                Box(Modifier.width(367.dp)) { HtmlText(html, fontSize = 16.sp, onClick = { opened++ }) }
            }
        }
        val node = compose.onNodeWithText(plain.take(5), substring = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.single()
        val text = layout.layoutInput.text.text
        assertTrue("the topics should wrap for this test", layout.lineCount >= 3)

        fun tap(offset: Int) {
            node.performTouchInput { click(layout.getBoundingBox(offset).center) }
            compose.waitForIdle()
        }
        val wrongly = plain.indices.filter { i -> opened.also { tap(i) } != opened }
        assertEquals("plain characters that opened a topic", emptyList<Int>(), wrongly)

        for (topic in listOf("#骁龙8至尊版Gen6#", "#荣耀Magic9ProMax#", "#iQOO16#")) {
            val before = opened
            tap(text.indexOf(topic) + 2)
            assertEquals(topic, before + 1, opened)
        }
    }
}
