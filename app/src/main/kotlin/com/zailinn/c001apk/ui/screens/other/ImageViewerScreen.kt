package com.zailinn.c001apk.ui.screens.other

import android.Manifest
import android.content.Context
import android.graphics.BitmapFactory
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.zailinn.c001apk.core.media.MotionPhoto
import com.zailinn.c001apk.ui.common.readJpegFlags
import com.zailinn.c001apk.core.model.GalleryMedia
import com.zailinn.c001apk.ui.LocalAppSettings
import com.zailinn.c001apk.ui.common.ActionSheet
import com.zailinn.c001apk.ui.common.SheetAction
import com.zailinn.c001apk.ui.common.hapticClick
import com.zailinn.c001apk.ui.common.rememberWifi
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.screens.appContainer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.ZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableImageState
import java.io.File
import java.util.UUID

/** Full-screen gallery. The optional metadata preserves existing URL-only routes. */
@Composable
fun ImageViewerScreen(urls: List<String>, index: Int, mediaJson: String? = null) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val app = appContainer()
    val scope = rememberCoroutineScope()
    val items = remember(urls, mediaJson) {
        val metadata = runCatching { mediaJson?.let { Json.decodeFromString(ListSerializer(GalleryMedia.serializer()), it) } }.getOrNull().orEmpty()
        GalleryMedia.align(urls, metadata)
    }
    if (items.isEmpty()) return
    val pager = rememberPagerState(initialPage = index.coerceIn(items.indices)) { items.size }
    val hdr = remember { mutableStateMapOf<Int, Boolean>() }
    val live = remember { mutableStateMapOf<Int, Boolean>() }
    val displayed = remember { mutableStateMapOf<Int, Boolean>() }
    val automaticPlayed = remember { mutableListOf<String>() }
    var controls by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf<Boolean?>(null) } // false save, true share
    var busy by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Pair<GalleryMedia, GalleryExport>?>(null) }
    val current = items[pager.currentPage.coerceIn(items.indices)]
    fun touch() { interaction++; controls = true }
    fun export(media: GalleryMedia, kind: GalleryExport, share: Boolean) {
        if (busy != null) return
        scope.launch {
            busy = if (share) "正在准备${kind.label}…" else "正在保存${kind.label}…"
            try {
                val file = app.galleryFiles.export(media, kind)
                if (share) shareGalleryFile(context, file) else {
                    saveGalleryFile(context, file)
                    app.toast("已保存${kind.label}到相册")
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { app.toast(if (share) "分享失败，请检查网络后重试" else "保存失败，请检查网络和存储空间后重试") }
            finally { busy = null; touch() }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending?.let { (media, kind) -> if (granted) export(media, kind, false) else app.toast("需要存储权限才能保存") }
        pending = null
    }
    fun save(kind: GalleryExport, share: Boolean) {
        touch()
        if (!share && Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pending = current to kind
            permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else export(current, kind, share)
    }
    LaunchedEffect(pager.settledPage, interaction, menu, busy) {
        controls = true
        if (menu == null && busy == null) { delay(3500); controls = false }
    }
    HdrColorMode(hdr[pager.settledPage] == true, pager, displayed[pager.settledPage] == true)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // An image can appear twice in one gallery; pager keys must stay unique.
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { "$it:${items[it].sourceUrl}" }) { page ->
            ViewerPage(
                items[page], active = page == pager.settledPage && !pager.isScrollInProgress,
                automaticPlayed = automaticPlayed,
                onTap = { interaction++; controls = !controls }, onInteraction = ::touch,
                onHdr = { hdr[page] = it }, onLive = { live[page] = it }, onDisplayed = { displayed[page] = it },
            )
        }
        AnimatedVisibility(controls, modifier = Modifier.align(Alignment.TopStart)) {
            Row(Modifier.fillMaxWidth().statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White) }
                Text("${pager.currentPage + 1}/${items.size}", color = Color.White)
            }
        }
        AnimatedVisibility(controls, modifier = Modifier.align(Alignment.BottomEnd)) {
            Row(Modifier.navigationBarsPadding().padding(8.dp)) {
                IconButton(onClick = { touch(); navigator.copy(current.sourceUrl) }) { Icon(Icons.Outlined.ContentCopy, "复制链接", tint = Color.White) }
                IconButton(onClick = {
                    touch()
                    if (current.isLive || live[pager.currentPage] == true) menu = true else save(GalleryExport.Photo, true)
                }, enabled = busy == null) { Icon(Icons.Outlined.Share, "分享", tint = Color.White) }
                IconButton(onClick = {
                    touch()
                    if (current.isLive || live[pager.currentPage] == true) menu = false else save(GalleryExport.Photo, false)
                }, enabled = busy == null) { Icon(Icons.Outlined.Download, "保存", tint = Color.White) }
            }
        }
        busy?.let { text ->
            Column(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .8f), MaterialTheme.shapes.medium).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                LoadingIndicator()
                Text(text, color = Color.White)
            }
        }
    }
    menu?.let { share ->
        ActionSheet(
            actions = listOf(GalleryExport.Motion, GalleryExport.Photo, GalleryExport.Video).map { kind ->
                val label = when (kind) {
                    GalleryExport.Motion -> if (share) "分享实况照片" else "保存实况照片"
                    GalleryExport.Photo -> if (share) "只分享照片" else "只保存照片"
                    GalleryExport.Video -> if (share) "只分享视频" else "只保存视频"
                }
                SheetAction(if (kind == GalleryExport.Video) Icons.Outlined.Movie else Icons.Outlined.Photo, label) { save(kind, share) }
            },
            onDismiss = { menu = null },
        )
    }
}

@Composable
internal fun ViewerPage(
    media: GalleryMedia,
    active: Boolean,
    automaticPlayed: MutableList<String>,
    onTap: () -> Unit,
    onInteraction: () -> Unit,
    onHdr: (Boolean) -> Unit,
    onLive: (Boolean) -> Unit,
    onDisplayed: (Boolean) -> Unit,
    state: ZoomableImageState = rememberZoomableImageState(),
) {
    val context = LocalContext.current
    val app = appContainer()
    val scope = rememberCoroutineScope()
    val mode = LocalAppSettings.current.mediaAutoPlay
    val wifi = rememberWifi()
    val resumed = rememberResumed()
    val display = LocalView.current.display
    val supported = Build.VERSION.SDK_INT >= 34 && display?.isHdr == true
    var highQuality by rememberSaveable(media.sourceUrl) { mutableStateOf(true) }
    var hdrEnabled by rememberSaveable(media.sourceUrl) { mutableStateOf(true) }
    var isHdr by remember(media.sourceUrl) { mutableStateOf(media.isHdr) }
    var isLive by remember(media.sourceUrl) { mutableStateOf(media.isLive) }
    val press = remember { MutableInteractionSource() }
    val comparing by press.collectIsPressedAsState()
    val useHdr = supported && isHdr && hdrEnabled && !comparing
    val source = when {
        !highQuality -> media.preview
        isHdr && !supported -> media.sdrHd
        else -> media.sourceUrl
    }
    var file by remember(media.sourceUrl) { mutableStateOf<File?>(null) }
    var failed by remember(media.sourceUrl) { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val player = remember(media.sourceUrl) { LivePhotoPlayer(context) }
    var video by remember(media.sourceUrl) { mutableStateOf<File?>(null) }
    var manualJob by remember { mutableStateOf<Job?>(null) }
    var automaticPlayback by remember { mutableStateOf(false) }
    suspend fun start(automatic: Boolean) {
        player.loading = true
        player.error = null
        try {
            video = app.galleryFiles.video(media)
            currentCoroutineContext().ensureActive()
            // The video is laid over the unzoomed photo; zooming waits until it stops.
            if (state.zoomableState.contentTransformation.scaleMetadata.userZoom > 1.01f) state.zoomableState.resetZoom()
            automaticPlayback = automatic
            player.play(automatic)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { player.error = "实况视频加载失败，点击播放重试" }
        finally { player.loading = false }
    }
    LaunchedEffect(source, active, attempt) {
        if (!active) return@LaunchedEffect
        failed = false
        try {
            val loaded = try { app.galleryFiles.image(source) } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (source != media.sourceUrl) app.galleryFiles.photo(media) else throw e }
            file = loaded
            isLive = media.isLive || withContext(Dispatchers.IO) { MotionPhoto.inspect(loaded) != null }
            if (!isHdr) isHdr = withContext(Dispatchers.IO) { hasGainmap(loaded) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    LaunchedEffect(active, resumed, file, isLive, mode, wifi) {
        if (!active || !resumed) {
            manualJob?.cancel(); player.stop()
        } else if (!mode.allows(wifi) && automaticPlayback) {
            player.stop()
        } else if (file != null && isLive && mode.allows(wifi) && media.sourceUrl !in automaticPlayed) {
            automaticPlayed += media.sourceUrl
            start(true)
        }
    }
    DisposableEffect(player) { onDispose { manualJob?.cancel(); player.stop() } }
    // Read readiness in composition so finishing image decoding invalidates the
    // page itself, rather than only the progress indicator's restart scope.
    val imageDisplayed = state.isImageDisplayed
    SideEffect { onHdr(active && useHdr && imageDisplayed); onLive(isLive); onDisplayed(imageDisplayed) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onTap() })
        if (file == null || !state.isImageDisplayed) {
            AsyncImage(media.preview, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
        }
        file?.let { image ->
            val request = remember(image, attempt) { ImageRequest.Builder(context).data(image).listener(onError = { _, _ -> failed = true }).build() }
            // Telephoto defers source replacement while zoomed. Recreate the
            // source composition immediately, retaining the hoisted zoom state.
            key(image.path, attempt) {
                ZoomableAsyncImage(request, null, Modifier.fillMaxSize(), state = state, gesturesEnabled = !player.visible, onClick = { onTap() })
            }
        }
        if ((!state.isImageDisplayed && !failed) || player.loading) LoadingIndicator(Modifier.align(Alignment.Center))
        if (failed) Column(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .75f)).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("图片加载失败", color = Color.White)
            TextButton(onClick = { scope.launch { file?.let { withContext(Dispatchers.IO) { it.delete() } }; attempt++; onInteraction() } }) { Text("点击重试", color = Color.White) }
        }
        if (player.visible && video != null) {
            val width = minOf(maxWidth, maxHeight * player.aspectRatio)
            LivePhotoSurface(video!!, player, Modifier.align(Alignment.Center).width(width).height(width / player.aspectRatio))
        }
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, bottom = 72.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            if (isHdr || file != null) {
                Text(
                    if (supported && isHdr) "HDR" else "HD",
                    Modifier.clip(MaterialTheme.shapes.small).background(Color.Black.copy(alpha = .65f))
                        .semantics {
                            contentDescription = if (supported && isHdr) "HDR，按住对比普通效果" else "HD 高清图片"
                            stateDescription = if (supported && isHdr) { if (hdrEnabled) "HDR 已开启" else "HDR 已关闭" }
                                else if (highQuality) "高清已开启" else "预览图片"
                        }
                        .combinedClickable(interactionSource = press, indication = null,
                            onClick = { if (supported && isHdr) hdrEnabled = !hdrEnabled else highQuality = !highQuality; onInteraction() },
                            onLongClick = {})
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    color = if (if (supported && isHdr) useHdr else highQuality) Color.White else Color.LightGray,
                )
            }
            if (isLive) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = hapticClick {
                    onInteraction()
                    automaticPlayback = false
                    if (player.visible) {
                        if (player.playing) player.pause() else player.resume()
                    } else {
                        if (media.sourceUrl !in automaticPlayed) automaticPlayed += media.sourceUrl
                        manualJob?.cancel(); manualJob = scope.launch { start(false) }
                    }
                }, enabled = active && resumed && !player.loading) {
                    Icon(if (player.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null, tint = Color.White)
                    Text(if (player.playing) "暂停实况" else "播放实况", color = Color.White)
                }
                IconButton(onClick = { player.toggleLoop(); onInteraction() }, enabled = player.visible) {
                    Icon(Icons.Outlined.Repeat, if (player.looping) "关闭循环" else "开启循环", tint = if (player.looping) MaterialTheme.colorScheme.primary else Color.White)
                }
                IconButton(onClick = { player.toggleSound(); onInteraction() }, enabled = player.visible) {
                    Icon(if (player.muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp, if (player.muted) "打开声音" else "静音", tint = Color.White)
                }
            }
        }
        player.error?.let { message -> Text(message, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp, start = 16.dp, end = 16.dp).background(Color.Black.copy(alpha = .75f)), color = Color.White) }
    }
}

/** The viewer has the last word after Telephoto's image/neighbor effects. */
@Composable
private fun HdrColorMode(hdr: Boolean, pager: PagerState, displayed: Boolean) {
    if (Build.VERSION.SDK_INT < 26) return
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window) { onDispose { window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT } }
    LaunchedEffect(hdr, displayed, pager.isScrollInProgress, pager.settledPage) {
        if (pager.isScrollInProgress) { window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT; return@LaunchedEffect }
        snapshotFlow { pager.layoutInfo.visiblePagesInfo.size }.first { it <= 1 }
        withFrameNanos {}
        window.colorMode = if (hdr) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
    }
}

/**
 * The JPEG header first; then a small decode by the platform, which keeps the
 * gain map (an image loader may redraw the bitmap and drop it).
 */
private fun hasGainmap(file: File): Boolean {
    if (readJpegFlags(file).hdr) return true
    if (Build.VERSION.SDK_INT < 34) return false
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0) return false
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= GAINMAP_PROBE_SIZE) sample *= 2
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
    return try { bitmap.hasGainmap() } finally { bitmap.recycle() }
}

private const val GAINMAP_PROBE_SIZE = 256

@Composable
private fun rememberResumed(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return resumed
}


private suspend fun shareGalleryFile(context: Context, media: GalleryFile) {
    val file = withContext(Dispatchers.IO) {
        File(context.cacheDir, "shared").apply { mkdirs() }.resolve("c001apk_${UUID.randomUUID()}${media.suffix}").also { target ->
            media.file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND).setType(media.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
