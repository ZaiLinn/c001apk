package com.zailinn.c001apk

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.zailinn.c001apk.ui.screens.other.LivePhotoPlayer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LivePhotoPlayerTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun automaticPlaybackIsSilentAndOnceWhileManualPlaybackCanLoopAndPause() {
        val media = folder.newFile("clip.mp4")
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(10_000, 0) }
        lateinit var native: MediaPlayer
        ShadowMediaPlayer.setCreateListener { player, _ -> native = player }
        val controller = LivePhotoPlayer(ApplicationProvider.getApplicationContext<Context>())
        val texture = SurfaceTexture(0)
        try {
            controller.play(automatic = true)
            controller.attach(media, texture)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("playing=${controller.playing}, error=${controller.error}, native=${shadowOf(native).state}", controller.playing)
            assertTrue(controller.muted)
            assertFalse(native.isLooping)
            assertEquals(0f, shadowOf(native).leftVolume)
            shadowOf(native).invokeCompletionListener()
            assertFalse(controller.visible)
            assertFalse(controller.playing)
            controller.play()
            assertTrue(native.isLooping)
            controller.pause()
            assertFalse(controller.playing)
            controller.resume()
            assertTrue(controller.playing)
            controller.toggleLoop()
            assertFalse(native.isLooping)
            controller.toggleSound()
            assertFalse(controller.muted)
            assertEquals(1f, shadowOf(native).leftVolume)
            controller.stop()
            assertFalse(controller.visible)
            assertTrue(controller.muted)
            assertEquals(ShadowMediaPlayer.State.END, shadowOf(native).state)
        } finally { controller.stop(); texture.release() }
    }
}
