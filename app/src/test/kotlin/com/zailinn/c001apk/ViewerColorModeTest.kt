package com.zailinn.c001apk

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.rememberNavController
import com.zailinn.c001apk.ui.nav.AppNavigator
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.screens.other.ImageViewerScreen
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Closing the image viewer leaves the window in the standard color mode, even
 * when a page restores HDR as it leaves, as telephoto does for an Ultra HDR
 * neighbour (#21).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ViewerColorModeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun closingTheViewerEndsHdr() {
        var open by mutableStateOf(true)
        val window = compose.activity.window
        compose.setContent {
            val context = androidx.compose.ui.platform.LocalContext.current
            val nav = rememberNavController()
            val navigator = AppNavigator(context, nav, AppContainer(context, "test-${UUID.randomUUID()}").settings)
            CompositionLocalProvider(LocalNavigator provides navigator) {
                if (open) {
                    ImageViewerScreen(listOf("https://image.coolapk.com/feed/a.jpg"), 0)
                    DisposableEffect(Unit) {
                        window.colorMode = ActivityInfo.COLOR_MODE_HDR
                        onDispose { window.colorMode = ActivityInfo.COLOR_MODE_HDR }
                    }
                }
            }
        }
        compose.waitForIdle()
        open = false
        compose.waitForIdle()
        assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, window.colorMode)
    }
}
