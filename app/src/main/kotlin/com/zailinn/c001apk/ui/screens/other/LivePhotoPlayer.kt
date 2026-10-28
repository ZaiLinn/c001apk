package com.zailinn.c001apk.ui.screens.other

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/** One page owns one player. Never survives a page change, backgrounding or viewer exit. */
internal class LivePhotoPlayer(context: Context) {
    var playing by mutableStateOf(false); private set
    var muted by mutableStateOf(true); private set
    var looping by mutableStateOf(false); private set
    var visible by mutableStateOf(false); private set
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var aspectRatio by mutableFloatStateOf(1f); private set
    private var requested = false
    private var prepared = false
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change -> if (change < 0) pause() }
    private var focus: AudioFocusRequest? = null
    private var hasFocus = false

    fun play(automatic: Boolean = false) {
        error = null
        if (automatic) { muted = true; looping = false } else looping = true
        requested = true
        visible = true
        if (prepared) start()
    }

    fun pause() {
        requested = false
        if (prepared) runCatching { player?.pause() }
        playing = false
        abandonFocus()
    }

    fun resume() {
        requested = true
        if (prepared) start()
    }

    fun toggleLoop() {
        looping = !looping
        if (prepared) player?.isLooping = looping
    }

    fun toggleSound() {
        muted = !muted
        if (!muted && playing && !acquireFocus()) muted = true
        if (muted) abandonFocus()
        player?.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
    }

    fun stop() {
        pause()
        visible = false
        muted = true
        loading = false
        detach()
    }

    fun attach(file: File, texture: SurfaceTexture) {
        detach()
        loading = true
        val output = Surface(texture).also { surface = it }
        try {
            val created = MediaPlayer().also { player = it }
            created.apply {
                setAudioAttributes(attributes)
                setSurface(output)
                setVolume(0f, 0f)
                setOnVideoSizeChangedListener { _, width, height -> if (height > 0 && width > 0) aspectRatio = width.toFloat() / height }
                setOnPreparedListener {
                    prepared = true
                    loading = false
                    if (requested) this@LivePhotoPlayer.start()
                }
                setOnCompletionListener {
                    playing = false; requested = false; visible = false
                    abandonFocus()
                }
                setOnErrorListener { _, _, _ ->
                    error = "当前设备无法播放此实况视频，可尝试保存视频后打开"
                    requested = false; playing = false; visible = false; loading = false
                    abandonFocus()
                    true
                }
                setDataSource(file.absolutePath)
                prepareAsync()
            }
        } catch (_: Exception) {
            error = "实况视频加载失败，点击播放重试"
            visible = false; loading = false; requested = false
            detach()
        }
    }

    private fun start() {
        if (!muted && !acquireFocus()) muted = true
        runCatching {
            player?.isLooping = looping
            player?.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
            player?.start()
            playing = true
        }.onFailure {
            error = "实况视频播放失败"; playing = false; requested = false; visible = false
            abandonFocus()
        }
    }

    fun detach() {
        prepared = false
        playing = false
        player?.setOnVideoSizeChangedListener(null)
        player?.setOnPreparedListener(null)
        player?.setOnCompletionListener(null)
        player?.setOnErrorListener(null)
        player?.release()
        player = null
        surface?.release(); surface = null
        abandonFocus()
    }

    private fun acquireFocus(): Boolean {
        if (hasFocus) return true
        val result = if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(focusListener).build().also { focus = it }
            audio.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    private fun abandonFocus() {
        if (hasFocus) {
            if (Build.VERSION.SDK_INT >= 26) focus?.let(audio::abandonAudioFocusRequest)
            else { @Suppress("DEPRECATION") audio.abandonAudioFocus(focusListener) }
        }
        focus = null; hasFocus = false
    }
}

@Composable
internal fun LivePhotoSurface(file: File, player: LivePhotoPlayer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    DisposableEffect(player, file) { onDispose { player.detach() } }
    AndroidView(
        factory = {
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) { player.attach(file, texture) }
                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { player.detach(); return true }
                }
            }
        },
        modifier = modifier,
        onRelease = { it.surfaceTextureListener = null; player.detach() },
    )
}
