package com.zailinn.c001apk

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.zailinn.c001apk.ui.common.emojiMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EmojiAssetsTest {
    private val assets = ApplicationProvider.getApplicationContext<android.content.Context>().assets

    @Test
    fun everyMappedEmoticonHasAnImage() {
        val missing = emojiMap.values.filter { it.endsWith(".png") }
            .filter { file -> runCatching { assets.open("emojis/$file").use(BitmapFactory::decodeStream) }.getOrNull() == null }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun niuniuSetIsComplete() {
        // Coolapk 16.x ships 32 牛牛 emoticons, e.g. [牛牛点赞].
        assertEquals(32, emojiMap.keys.count { it.startsWith("[牛牛") })
        assertTrue(emojiMap["[牛牛点赞]"] == "niuniu_emotion_thumbs_up.png")
    }
}
