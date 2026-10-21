package com.zailinn.c001apk

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.ui.cards.FeedCard
import com.zailinn.c001apk.ui.nav.AppNavigator
import com.zailinn.c001apk.ui.nav.CopyRoute
import com.zailinn.c001apk.ui.nav.FeedRoute
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.nav.MainRoute
import com.zailinn.c001apk.ui.nav.UserRoute
import java.util.UUID
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The author row above an article, and atop a feed in a list, opens the profile on tap and ignores a long press. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArticleAuthorRowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun tapOpensTheProfileAndLongPressDoesNothing() {
        val author = Datum(buildJsonObject {
            put("id", JsonPrimitive("74399579"))
            put("uid", JsonPrimitive("2197288"))
            put("username", JsonPrimitive("酷安周报"))
            put("message", JsonPrimitive(""))
        })
        lateinit var nav: NavHostController
        compose.setContent {
            val context = androidx.compose.ui.platform.LocalContext.current
            nav = rememberNavController()
            val navigator = AppNavigator(context, nav, AppContainer(context, "test-${UUID.randomUUID()}").settings)
            CompositionLocalProvider(LocalNavigator provides navigator) {
                NavHost(nav, startDestination = MainRoute) {
                    composable<MainRoute> { FeedCard(author, detail = true, footer = false, authorOnly = true) }
                    composable<UserRoute> {}
                    composable<CopyRoute> {}
                }
            }
        }
        val row = compose.onNodeWithText("酷安周报")
        row.performTouchInput { longClick() }
        compose.waitForIdle()
        assertTrue("long press stays put", nav.currentBackStackEntry?.destination?.hasRoute(MainRoute::class) == true)

        row.performTouchInput { click() }
        compose.waitForIdle()
        assertEquals("2197288", nav.currentBackStackEntry?.let { if (it.destination.hasRoute(UserRoute::class)) it.arguments?.getString("uid") else null })
    }

    @Test
    fun inAListTheAuthorRowOpensTheProfileAndTheTextOpensTheFeed() {
        val feed = Datum(buildJsonObject {
            put("id", JsonPrimitive("74399579"))
            put("uid", JsonPrimitive("2197288"))
            put("username", JsonPrimitive("酷安周报"))
            put("message", JsonPrimitive("正文内容"))
        })
        lateinit var nav: NavHostController
        compose.setContent {
            val context = androidx.compose.ui.platform.LocalContext.current
            nav = rememberNavController()
            val navigator = AppNavigator(context, nav, AppContainer(context, "test-${UUID.randomUUID()}").settings)
            CompositionLocalProvider(LocalNavigator provides navigator) {
                NavHost(nav, startDestination = MainRoute) {
                    composable<MainRoute> { FeedCard(feed) }
                    composable<UserRoute> {}
                    composable<FeedRoute> {}
                    composable<CopyRoute> {}
                }
            }
        }
        fun at(route: kotlin.reflect.KClass<*>) = nav.currentBackStackEntry?.destination?.hasRoute(route) == true

        compose.onNodeWithText("酷安周报").performTouchInput { longClick() }
        compose.waitForIdle()
        assertTrue("long press on the author row stays put", at(MainRoute::class))

        compose.onNodeWithText("酷安周报").performTouchInput { click() }
        compose.waitForIdle()
        assertTrue("tap on the author row opens the profile", at(UserRoute::class))

        compose.runOnUiThread { nav.popBackStack() }
        compose.onNodeWithText("正文内容").performTouchInput { click() }
        compose.waitForIdle()
        assertTrue("tap on the text opens the feed", at(FeedRoute::class))
    }
}
