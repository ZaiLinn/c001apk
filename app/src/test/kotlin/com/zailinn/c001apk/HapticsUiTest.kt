package com.zailinn.c001apk

import androidx.activity.ComponentActivity
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.zailinn.c001apk.ui.C001apkApp
import com.zailinn.c001apk.ui.common.AppAlertDialog
import com.zailinn.c001apk.ui.common.AppBottomSheet
import com.zailinn.c001apk.ui.common.AppDialog
import com.zailinn.c001apk.ui.common.AppDropdownMenu
import com.zailinn.c001apk.ui.common.hapticClick
import com.zailinn.c001apk.ui.common.rememberAppHapticFeedback
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HapticsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<HapticFeedbackType>()
    private val platform = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            events += hapticFeedbackType
        }
    }

    @Test
    fun realSettingsSwitchDisablesNavigationAndTabFeedback() {
        val app = AppContainer(ApplicationProvider.getApplicationContext(), "haptics-${UUID.randomUUID()}", FixtureTransport())
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides platform) { C001apkApp(app, deepLink = null) {} }
        }
        compose.onNode(hasText("我的") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(1, events.size) }
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("交互").performScrollTo().performClick()
        // The 震动反馈 row; the page also holds other switches.
        val vibration = isToggleable() and hasAnySibling(hasText("震动反馈"))
        compose.onNode(vibration, useUnmergedTree = true).assertIsOn().performClick()
        compose.waitUntil(5_000) { !app.settings.current.hapticFeedback }
        compose.onNode(vibration, useUnmergedTree = true).assertIsOff()
        compose.runOnIdle { events.clear() }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNode(hasText("主页") and hasClickAction()).performClick()
        compose.onNode(hasText("热门") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(0, events.size) }
    }

    @Test
    fun clicksAndBuiltInLongPressesRespectLiveSwitchEvenWithRetainedCallback() {
        val enabled = mutableStateOf(true)
        var retainedClick: (() -> Unit)? = null
        var clicks = 0
        var longPresses = 0
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides platform) {
                CompositionLocalProvider(LocalHapticFeedback provides rememberAppHapticFeedback(enabled.value)) {
                    val click = hapticClick { clicks++ }
                    if (retainedClick == null) retainedClick = click
                    Column {
                        Button(onClick = retainedClick, modifier = Modifier.testTag("click")) { Text("点击") }
                        Button(onClick = click, enabled = false, modifier = Modifier.testTag("disabled")) { Text("禁用") }
                        Box(Modifier.size(100.dp).testTag("long").combinedClickable(onClick = {}, onLongClick = { longPresses++ }))
                    }
                }
            }
        }
        compose.onNodeWithTag("click").performTouchInput { down(center); cancel() }
        compose.onNodeWithTag("disabled").performClick()
        compose.runOnIdle { assertEquals(0, events.size) }

        compose.onNodeWithTag("click").performClick()
        compose.onNodeWithTag("long").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(listOf(HapticFeedbackType.ContextClick, HapticFeedbackType.LongPress), events)
            enabled.value = false
        }
        compose.onNodeWithTag("click").performClick()
        compose.onNodeWithTag("long").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(2, events.size)
            assertEquals(2, clicks)
            assertEquals(2, longPresses)
            enabled.value = true
        }
        compose.onNodeWithTag("click").performClick()
        compose.runOnIdle { assertEquals(3, events.size) }
    }

    @Test
    fun dialogsSheetsAndMenusKeepTheAppFeedbackSetting() {
        val enabled = mutableStateOf(true)
        val window = mutableStateOf(0)
        var clicks = 0
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides platform) {
                CompositionLocalProvider(LocalHapticFeedback provides rememberAppHapticFeedback(enabled.value)) {
                    val button = @androidx.compose.runtime.Composable {
                        Button(onClick = hapticClick { clicks++ }, modifier = Modifier.testTag("window-click")) { Text("操作") }
                    }
                    when (window.value) {
                        0 -> AppAlertDialog(onDismissRequest = {}, confirmButton = button)
                        1 -> AppDialog(onDismissRequest = {}, content = button)
                        2 -> AppBottomSheet(onDismissRequest = {}) { button() }
                        3 -> Box { AppDropdownMenu(expanded = true, onDismissRequest = {}) { button() } }
                    }
                }
            }
        }
        repeat(4) { index ->
            compose.runOnIdle { window.value = index; enabled.value = true }
            compose.onNodeWithTag("window-click").performClick()
            compose.runOnIdle {
                assertEquals(index + 1, events.size)
                enabled.value = false
            }
            compose.onNodeWithTag("window-click").performClick()
            compose.runOnIdle { assertEquals(index + 1, events.size) }
        }
        compose.runOnIdle { assertEquals(8, clicks) }
    }
}
