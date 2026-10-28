package com.zailinn.c001apk

import android.app.DownloadManager
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.rememberNavController
import com.zailinn.c001apk.ui.nav.AppNavigator
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.screens.LocalAppContainer
import com.zailinn.c001apk.ui.screens.web.WebScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDownloadManager.ShadowRequest
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WebTransportTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun launch(): WebView {
        val app = AppContainer(compose.activity.applicationContext, "web-transport-${UUID.randomUUID()}", FixtureTransport())
        compose.setContent {
            val navigator = AppNavigator(compose.activity, rememberNavController(), app.settings)
            CompositionLocalProvider(LocalAppContainer provides app, LocalNavigator provides navigator) {
                WebScreen("http://www.coolapk.com/start?x=1", isLogin = false)
            }
        }
        compose.waitForIdle()
        fun find(view: View): WebView? {
            if (view is WebView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        return checkNotNull(find(compose.activity.window.decorView))
    }

    private fun request(url: String, method: String = "GET", mainFrame: Boolean = true) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun getMethod() = method
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = true
        override fun hasGesture() = true
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    @Test
    fun pagesUpgradeToHttpsAndUnsafePostsAndMixedScriptsAreBlocked() {
        val web = launch()
        compose.runOnIdle {
            val shadow = shadowOf(web)
            assertEquals("https://www.coolapk.com/start?x=1", shadow.lastLoadedUrl)
            // Mixed images may load (Coolapk's own over https); mixed scripts never do.
            assertEquals(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE, web.settings.mixedContentMode)
            val client = shadow.webViewClient!!
            // Only Coolapk's http subresources are fetched by the app; everything else is left to WebView.
            assertNull(client.shouldInterceptRequest(web, request("http://example.invalid/a.png", mainFrame = false)))
            assertNull(client.shouldInterceptRequest(web, request("https://image.coolapk.com/a.png", mainFrame = false)))
            assertNull(client.shouldInterceptRequest(web, request("http://image.coolapk.com/a.png", "POST", mainFrame = false)))
            assertNull(client.shouldInterceptRequest(web, request("http://www.coolapk.com/next")))
            assertTrue(client.shouldOverrideUrlLoading(web, request("http://www.coolapk.com/next")))
            assertEquals("https://www.coolapk.com/next", shadow.lastLoadedUrl)
            assertTrue(client.shouldOverrideUrlLoading(web, request("http://account.coolapk.com/login", "POST")))
            assertEquals("https://www.coolapk.com/next", shadow.lastLoadedUrl)
        }
    }

    @Test
    fun systemDownloaderReceivesOnlyUpgradedWebUrls() {
        val web = launch()
        compose.runOnIdle {
            val listener = shadowOf(web).downloadListener!!
            val manager = shadowOf(compose.activity.getSystemService(DownloadManager::class.java))
            listener.onDownloadStart("http://example.invalid/file.apk?sign=a%2Bb", "", "", "application/vnd.android.package-archive", 1)
            assertEquals(1, manager.requestCount)
            val download = Shadow.extract<ShadowRequest>(manager.getRequest(0))
            assertEquals("https://example.invalid/file.apk?sign=a%2Bb", download.uri.toString())
            listener.onDownloadStart("file:///sdcard/private", "", "", "", 1)
            assertEquals(1, manager.requestCount)
        }
    }
}
