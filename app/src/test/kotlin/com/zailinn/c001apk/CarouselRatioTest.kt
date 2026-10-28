package com.zailinn.c001apk

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.ui.cards.CarouselCard
import com.zailinn.c001apk.ui.nav.AppNavigator
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.screens.LocalAppContainer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Home banners use the official client's fixed 4.5:1 box, whatever size the image names. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarouselRatioTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun bannerBoxIsFixedAtFourAndAHalfToOne() {
        val app = AppContainer(ApplicationProvider.getApplicationContext(), "carousel-${UUID.randomUUID()}")
        val banner = Datum(Json.parseToJsonElement("""{"title":"横幅","url":"","pic":"https://image.coolapk.com/test/banner.jpg@1200x300.jpg"}""").jsonObject)
        compose.setContent {
            val context = androidx.compose.ui.platform.LocalContext.current
            CompositionLocalProvider(
                LocalAppContainer provides app,
                LocalNavigator provides AppNavigator(context, rememberNavController(), app.settings),
            ) {
                androidx.compose.foundation.layout.Box(Modifier.width(400.dp).testTag("banner")) { CarouselCard(listOf(banner)) }
            }
        }
        val bounds = compose.onNodeWithTag("banner").getBoundsInRoot()
        assertEquals(4.5f, (bounds.right - bounds.left).value / (bounds.bottom - bounds.top).value, .05f)
    }
}
