package com.zailinn.c001apk

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.zailinn.c001apk.ui.screens.main.onLongPress
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Only a held, still finger on a home tab opens the tab editor; a slow swipe does not. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TabLongPressTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun swipeAndTapDoNotOpenTheEditor() {
        var opened = 0
        compose.setContent { Box(Modifier.size(300.dp, 48.dp).testTag("tab").onLongPress { opened++ }) }
        val tab = compose.onNodeWithTag("tab")

        tab.performTouchInput {
            down(Offset(20f, centerY))
            repeat(20) {
                advanceEventTime(50)
                moveBy(Offset(20f, 0f))
            }
            up()
        }
        compose.waitForIdle()
        assertEquals("slow swipe", 0, opened)

        tab.performTouchInput { click() }
        compose.waitForIdle()
        assertEquals("tap", 0, opened)

        tab.performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals("long press", 1, opened)
    }
}
