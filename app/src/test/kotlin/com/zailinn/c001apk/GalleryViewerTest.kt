package com.zailinn.c001apk

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.snap
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.zailinn.c001apk.core.model.GalleryMedia
import com.zailinn.c001apk.core.model.MediaAutoPlay
import com.zailinn.c001apk.data.AppSettings
import com.zailinn.c001apk.ui.LocalAppSettings
import com.zailinn.c001apk.ui.nav.AppNavigator
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.screens.LocalAppContainer
import com.zailinn.c001apk.ui.screens.other.ImageViewerScreen
import com.zailinn.c001apk.ui.screens.other.ViewerPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.saket.telephoto.zoomable.ZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableImageState
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(coil3.annotation.DelicateCoilApi::class)
class GalleryViewerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @After fun releaseImageLoader() {
        SingletonImageLoader.get(context).shutdown()
        SingletonImageLoader.reset()
    }
    private val requests = CopyOnWriteArrayList<okhttp3.Request>()
    private val decodedFiles = CopyOnWriteArrayList<String>()
    private fun image(size: Int) = ByteArrayOutputStream().use { out ->
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(android.graphics.Color.GRAY)
            assertTrue(it.compress(Bitmap.CompressFormat.JPEG, 95, out))
            it.recycle()
        }
        out.toByteArray()
    }
    private fun app(): AppContainer {
        val original = image(1024)
        val preview = image(512)
        val mp4 = ByteBuffer.allocate(24).putInt(24).put("ftypisom".toByteArray()).putInt(0).put("isommp42".toByteArray()).array()
        val transport = Interceptor { chain ->
            val request = chain.request()
            requests += request
            val body = when {
                request.url.encodedPath.endsWith(".mp4") -> mp4
                request.url.encodedPath.endsWith(".s.jpg") -> preview
                else -> original
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }
        return AppContainer(context, "gallery-${UUID.randomUUID()}", imageTransport = transport).also { app ->
            SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).memoryCache(null).diskCache(null)
                .eventListener(object : coil3.EventListener() {
                    override fun onSuccess(request: coil3.request.ImageRequest, result: coil3.request.SuccessResult) {
                        (request.data as? java.io.File)?.let { decodedFiles += it.path }
                    }
                })
                .components {
                    // Robolectric cannot decode files using ImageDecoder's native file source.
                    add(coil3.decode.BitmapFactoryDecoder.Factory())
                    add(OkHttpNetworkFetcherFactory(callFactory = { app.imageHttp }))
                }.build())
        }
    }
    /** waitUntil only advances frames. Coil delivers results through the paused main looper, so drain it too. */
    private fun waitDraining(timeoutMillis: Long = 1_000, condition: () -> Boolean) =
        compose.waitUntil(timeoutMillis) { shadowOf(android.os.Looper.getMainLooper()).idle(); condition() }
    private fun media(suffix: String) = GalleryMedia.fromUrl("https://image.coolapk.com/test/${UUID.randomUUID()}-$suffix@64x64.jpg", "123")

    @Test fun unsupportedHdrLoadsSdrHdAndQualitySwitchKeepsZoom() {
        val app = app()
        val media = media("uhdr")
        lateinit var state: ZoomableImageState
        lateinit var scope: CoroutineScope
        var touches = 0
        compose.setContent {
            state = rememberZoomableImageState()
            scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalAppContainer provides app, LocalAppSettings provides AppSettings(mediaAutoPlay = MediaAutoPlay.Off)) {
                ViewerPage(media, true, remember { mutableListOf() }, {}, { touches++ }, {}, {}, {}, state)
            }
        }
        waitDraining(10_000) { state.isImageDisplayed }
        assertTrue(requests.any { it.url.toString() == media.sdrHd })
        assertFalse(requests.any { it.url.toString() == media.sourceUrl })
        compose.runOnIdle { scope.launch { state.zoomableState.zoomBy(2f, animationSpec = snap()) } }
        waitDraining { state.zoomableState.contentTransformation.scaleMetadata.userZoom > 1.5f }
        val zoom = state.zoomableState.contentTransformation.scaleMetadata.userZoom
        compose.onNodeWithContentDescription("HD 高清图片").performClick()
        assertEquals("HD tap reaches the control", 1, touches)
        compose.onNodeWithContentDescription("HD 高清图片").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "预览图片"))
        try {
            waitDraining(10_000) { state.isImageDisplayed && decodedFiles.distinct().size >= 2 }
        } catch (e: Throwable) {
            println("decoded files=$decodedFiles")
            println("requests=$requests")
            println(compose.onRoot().printToString())
            throw e
        }
        assertEquals(zoom, state.zoomableState.contentTransformation.scaleMetadata.userZoom, .01f)
        assertTrue(requests.all { it.url.isHttps && it.header("Cookie") == null && it.header("X-App-Token") == null })
    }

    @Test fun playingALivePhotoUndoesTheZoomFirst() {
        val app = app()
        val media = media("livepic").copy(videoUrl = "https://cdn.example.com/${UUID.randomUUID()}.mp4")
        lateinit var state: ZoomableImageState
        lateinit var scope: CoroutineScope
        compose.setContent {
            state = rememberZoomableImageState()
            scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalAppContainer provides app, LocalAppSettings provides AppSettings(mediaAutoPlay = MediaAutoPlay.Off)) {
                ViewerPage(media, true, remember { mutableListOf() }, {}, {}, {}, {}, {}, state)
            }
        }
        waitDraining(10_000) { state.isImageDisplayed }
        compose.runOnIdle { scope.launch { state.zoomableState.zoomBy(2f, animationSpec = snap()) } }
        waitDraining { state.zoomableState.contentTransformation.scaleMetadata.userZoom > 1.5f }
        compose.onNodeWithText("播放实况").performClick()
        waitDraining(10_000) { state.zoomableState.contentTransformation.scaleMetadata.userZoom < 1.01f }
    }

    @Test fun hdrCanBeComparedWhileHeldAndWindowReturnsToSdrOnExit() {
        val display = context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        shadowOf(display).setDisplayHdrCapabilities(display.displayId, 1000f, 500f, .1f, Display.HdrCapabilities.HDR_TYPE_HDR10)
        assertTrue(display.isHdr)
        val app = app()
        val media = media("uhdr")
        var open by mutableStateOf(true)
        compose.setContent {
            val view = androidx.compose.ui.platform.LocalView.current
            // Runs during composition, before the viewer reads display.isHdr.
            remember(view) {
                view.display?.also { shadowOf(it).setDisplayHdrCapabilities(it.displayId, 1000f, 500f, .1f, Display.HdrCapabilities.HDR_TYPE_HDR10) }
            }
            val nav = rememberNavController()
            CompositionLocalProvider(LocalAppContainer provides app, LocalNavigator provides AppNavigator(context, nav, app.settings)) {
                if (open) ImageViewerScreen(listOf(media.sourceUrl), 0)
            }
        }
        compose.onNodeWithContentDescription("HDR，按住对比普通效果").assertExists()
        waitDraining(10_000) { compose.activity.window.colorMode == ActivityInfo.COLOR_MODE_HDR }
        val hdr = compose.onNodeWithContentDescription("HDR，按住对比普通效果")
        hdr.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(600)
        waitDraining { compose.activity.window.colorMode == ActivityInfo.COLOR_MODE_DEFAULT }
        hdr.performTouchInput { up() }
        waitDraining { compose.activity.window.colorMode == ActivityInfo.COLOR_MODE_HDR }
        compose.runOnIdle { open = false }
        compose.waitForIdle()
        assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, compose.activity.window.colorMode)
    }

    @Test fun liveAutoPlayOffWaitsForManualPlayAndOffersThreeExportChoices() {
        val app = app()
        val media = media("livepic").copy(videoUrl = "https://cdn.example.com/${UUID.randomUUID()}.mp4")
        compose.setContent {
            val nav = rememberNavController()
            CompositionLocalProvider(LocalAppContainer provides app, LocalNavigator provides AppNavigator(context, nav, app.settings),
                LocalAppSettings provides AppSettings(mediaAutoPlay = MediaAutoPlay.Off)) {
                ImageViewerScreen(listOf(media.sourceUrl), 0, Json.encodeToString(ListSerializer(GalleryMedia.serializer()), listOf(media)))
            }
        }
        waitDraining(10_000) { requests.any { it.url.toString() == media.sourceUrl } }
        compose.waitForIdle()
        assertFalse(requests.any { it.url.encodedPath.endsWith(".mp4") })
        compose.onNodeWithText("播放实况").performClick()
        waitDraining(10_000) { requests.any { it.url.toString() == media.videoUrl } }
        compose.onNodeWithContentDescription("保存").performClick()
        compose.onNodeWithText("保存实况照片").assertExists()
        compose.onNodeWithText("只保存照片").assertExists()
        compose.onNodeWithText("只保存视频").assertExists()
    }
}
