package com.zailinn.c001apk

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.zailinn.c001apk.ui.nav.AppNavigator
import com.zailinn.c001apk.ui.nav.FeedRoute
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.nav.MainRoute
import com.zailinn.c001apk.ui.nav.UserRoute
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A page opened from a page of the same kind, such as a linked feed, stacks on top and back returns to it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NavigationStackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun host(): Pair<NavHostController, AppNavigator> {
        lateinit var nav: NavHostController
        lateinit var navigator: AppNavigator
        compose.setContent {
            val context = androidx.compose.ui.platform.LocalContext.current
            nav = rememberNavController()
            navigator = AppNavigator(context, nav, AppContainer(context, "test-${UUID.randomUUID()}").settings)
            CompositionLocalProvider(LocalNavigator provides navigator) {
                NavHost(nav, startDestination = MainRoute) {
                    composable<MainRoute> {}
                    composable<FeedRoute> {}
                    composable<UserRoute> {}
                }
            }
        }
        compose.waitForIdle()
        return nav to navigator
    }

    @Test
    fun linkedFeedStacksAndBackReturnsToTheFirst() {
        val (nav, navigator) = host()
        compose.runOnUiThread { navigator.openLink("/feed/1") }
        compose.runOnUiThread { navigator.openLink("/feed/2") }
        compose.waitForIdle()
        assertEquals("2", nav.currentBackStackEntry!!.toRoute<FeedRoute>().id)
        compose.runOnUiThread { navigator.back() }
        compose.waitForIdle()
        assertEquals("1", nav.currentBackStackEntry!!.toRoute<FeedRoute>().id)
    }

    @Test
    fun otherProfileStacksAndBackReturnsToTheFirst() {
        val (nav, navigator) = host()
        compose.runOnUiThread { navigator.openUser("10") }
        compose.runOnUiThread { navigator.openUser("20") }
        compose.runOnUiThread { navigator.back() }
        compose.waitForIdle()
        assertEquals("10", nav.currentBackStackEntry!!.toRoute<UserRoute>().uid)
    }

    @Test
    fun repeatedTapOnTheSamePageDoesNotStackIt() {
        val (nav, navigator) = host()
        compose.runOnUiThread { navigator.openLink("/feed/1"); navigator.openLink("/feed/1") }
        compose.runOnUiThread { navigator.back() }
        compose.waitForIdle()
        assertEquals(true, nav.currentBackStackEntry!!.destination.route?.contains("MainRoute"))
    }
}
