package com.zailinn.c001apk.ui.screens.other

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.zailinn.c001apk.AppContainer
import com.zailinn.c001apk.core.net.await
import com.zailinn.c001apk.ui.common.thumbnail
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.screens.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import okhttp3.Request
import java.io.File

/** Full-screen pager with pinch zoom, save to gallery and share. */
@Composable
fun ImageViewerScreen(urls: List<String>, index: Int) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val app = appContainer()
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = index) { urls.size }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) save(scope, app, context, urls[pager.currentPage]) else app.toast("需要存储权限才能保存图片")
    }
    HdrColorMode(urls, pager)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { it }) { page ->
            ViewerPage(urls[page], onTap = navigator::back)
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { navigator.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White) }
            if (urls.size > 1) Text("${pager.currentPage + 1}/${urls.size}", color = Color.White)
        }
        Row(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(8.dp)) {
            IconButton(onClick = { navigator.copy(urls[pager.currentPage]) }) { Icon(Icons.Outlined.ContentCopy, "复制链接", tint = Color.White) }
            IconButton(onClick = { share(scope, app, context, urls[pager.currentPage]) }) { Icon(Icons.Outlined.Share, "分享", tint = Color.White) }
            IconButton(onClick = {
                val needsPermission = Build.VERSION.SDK_INT < 29 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                if (needsPermission) permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                else save(scope, app, context, urls[pager.currentPage])
            }) { Icon(Icons.Outlined.Download, "保存", tint = Color.White) }
        }
    }
}


/**
 * One image: the list's thumbnail, usually cached already, with a spinner until
 * the original shows; a failure says so and retries on tap. A single tap closes
 * the viewer, a double tap still zooms.
 */
@Composable
private fun ViewerPage(url: String, onTap: () -> Unit) {
    val context = LocalContext.current
    var attempt by remember(url) { mutableIntStateOf(0) }
    var failed by remember(url) { mutableStateOf(false) }
    val state = rememberZoomableImageState()
    val request = remember(url, attempt) {
        ImageRequest.Builder(context).data(url)
            .listener(onStart = { failed = false }, onError = { _, _ -> failed = true })
            .build()
    }
    Box(Modifier.fillMaxSize()) {
        if (!state.isImageDisplayed && url.contains("image.coolapk.com")) {
            AsyncImage(thumbnail(url), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        key(attempt) {
            ZoomableAsyncImage(model = request, contentDescription = null, modifier = Modifier.fillMaxSize(), state = state, onClick = { onTap() })
        }
        if (!state.isImageDisplayed) {
            if (failed) {
                Column(
                    Modifier.align(Alignment.Center).clickable { attempt++ }.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("图片加载失败", color = Color.White)
                    Text("点击重试", color = Color.White.copy(alpha = 0.7f))
                }
            } else {
                LoadingIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

/**
 * Telephoto switches the window to HDR for every Ultra HDR image and, when that
 * image leaves, restores the mode it found. Neighbouring pages overlap, so one
 * can find HDR, restore it, and leave the whole app in HDR after the viewer
 * closes (#21). The viewer owns the mode instead: HDR only while the settled
 * page has a gain map, and the standard mode once the viewer is gone.
 */
@Composable
private fun HdrColorMode(urls: List<String>, pager: PagerState) {
    val window = LocalActivity.current?.window ?: return
    val context = LocalContext.current
    // Remembered before the pages, so it is forgotten after them and has the last word.
    DisposableEffect(window) { onDispose { window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT } }
    LaunchedEffect(pager, window) {
        snapshotFlow { pager.settledPage }.collectLatest { page ->
            val hdr = hasGainmap(context, urls[page])
            // Let pages that scrolled away leave first; leaving runs their restore.
            snapshotFlow { pager.layoutInfo.visiblePagesInfo.size }.first { it <= 1 }
            withFrameNanos {}
            window.colorMode = if (hdr) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
        }
    }
}

/** Whether the image carries an Ultra HDR gain map; decoded small from the disk cache. */
private suspend fun hasGainmap(context: Context, url: String): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
    val request = ImageRequest.Builder(context).data(url).size(256).allowHardware(false)
        .memoryCachePolicy(CachePolicy.DISABLED).build()
    val image = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image as? BitmapImage
    return image?.bitmap?.hasGainmap() == true
}

private suspend fun fetch(app: AppContainer, url: String): ByteArray = withContext(Dispatchers.IO) {
    app.imageHttp.newCall(Request.Builder().url(url).build()).await().use { response ->
        check(response.isSuccessful) { "HTTP ${response.code}" }
        response.body!!.bytes()
    }
}

private fun mimeOf(name: String) = when (name.substringAfterLast('.').lowercase()) {
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    else -> "image/jpeg"
}

private fun save(scope: CoroutineScope, app: AppContainer, context: Context, url: String) = scope.launch {
    try {
        val bytes = fetch(app, url)
        val name = url.substringAfterLast('/').substringBefore('?').ifEmpty { "${System.currentTimeMillis()}.jpg" }
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeOf(name))
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/c001apk")
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("insert")
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "c001apk").apply { mkdirs() }
                File(dir, name).writeBytes(bytes)
            }
        }
        app.toast("已保存到相册")
    } catch (_: Exception) {
        app.toast("图片保存失败，请稍后重试")
    }
}

private fun share(scope: CoroutineScope, app: AppContainer, context: Context, url: String) = scope.launch {
    try {
        val bytes = fetch(app, url)
        val name = url.substringAfterLast('/').substringBefore('?').ifEmpty { "image.jpg" }
        val file = withContext(Dispatchers.IO) {
            File(context.cacheDir, "shared").apply { mkdirs() }.resolve(name).apply { writeBytes(bytes) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).setType(mimeOf(name)).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        app.toast("图片分享失败，请稍后重试")
    }
}
