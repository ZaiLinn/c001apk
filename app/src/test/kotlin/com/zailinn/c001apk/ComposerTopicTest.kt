package com.zailinn.c001apk

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.zailinn.c001apk.ui.LocalSession
import com.zailinn.c001apk.ui.SessionInfo
import com.zailinn.c001apk.ui.nav.AppNavigator
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.screens.LocalAppContainer
import com.zailinn.c001apk.ui.screens.compose.ComposeTarget
import com.zailinn.c001apk.ui.screens.compose.ComposerSheet
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** The composer's 话题 button: search once typing pauses, insert `#话题#` at the cursor. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComposerTopicTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun waitDraining(condition: () -> Boolean) =
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); condition() }

    @Test
    fun aSearchedTopicIsInsertedAfterTheText() {
        val searches = CopyOnWriteArrayList<String>()
        val transport = Interceptor { chain ->
            val request = chain.request()
            val body = if (request.url.encodedPath.endsWith("/v6/search")) {
                searches += request.url.queryParameter("type") + ":" + request.url.queryParameter("searchValue")
                FixtureTransport.list("""{"entityType":"topic","id":"7","title":"合成话题"}""")
            } else FixtureTransport.list()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
        val app = AppContainer(ApplicationProvider.getApplicationContext(), "composer-topic-${UUID.randomUUID()}", transport)
        compose.setContent {
            val context = androidx.compose.ui.platform.LocalContext.current
            CompositionLocalProvider(
                LocalNavigator provides AppNavigator(context, rememberNavController(), app.settings),
                LocalAppContainer provides app,
                LocalSession provides SessionInfo(isLogin = true, uid = "20"),
            ) { ComposerSheet(ComposeTarget.NewFeed(), onDismiss = {}) }
        }
        compose.onNode(hasSetTextAction() and hasText("发布动态")).performTextInput("今天的照片")
        compose.onNodeWithContentDescription("话题").performClick()
        compose.onNode(hasSetTextAction() and hasText("搜索或输入话题")).performTextInput("合成")
        waitDraining { compose.onAllNodesWithText("#合成话题#").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("one search after typing, not one per character", listOf("feedTopic:合成"), searches)
        compose.onNodeWithText("#合成话题#").performClick()
        waitDraining { compose.onAllNodesWithText("今天的照片 #合成话题# ").fetchSemanticsNodes().isNotEmpty() }
    }
}
