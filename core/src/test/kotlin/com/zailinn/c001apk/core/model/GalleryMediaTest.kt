package com.zailinn.c001apk.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class GalleryMediaTest {
    @Test fun metadataKeepsOwnersAndMatchesImagesAcrossHttpAndHttps() {
        val data = Datum(Json.parseToJsonElement("""{"id":"42","entityType":"feed_reply",
            "picArr":["http://image.coolapk.com/a-livepic@100x200.jpg","https://image.coolapk.com/b-uhdr@200x100.jpg"],
            "imageUriList":[{"sourceUrl":"https://image.coolapk.com/a-livepic@100x200.jpg","compressedUrl":"https://image.coolapk.com/a.s.jpg","livePhotoEnable":1,"liveVideoUrl":"https://video.coolapk.com/a.mp4"}] }""") as JsonObject)
        assertEquals(2, data.mediaImages.size)
        val live = data.mediaImages.first()
        assertTrue(live.isLive)
        assertEquals("reply", live.contentType)
        assertEquals("42", live.contentId)
        assertEquals("https://video.coolapk.com/a.mp4", live.videoUrl)
        assertTrue(data.mediaImages.last().isHdr)
        val unrelated = GalleryMedia.align(listOf("https://image.coolapk.com/unrelated.jpg"), data.mediaImages).single()
        assertNull(unrelated.contentId)
        assertNull(unrelated.videoUrl)
    }

    @Test fun objectOnlyListsAndJsonStringsAreAcceptedWithoutTreatingPostVideosAsPhotos() {
        val data = Datum(Json.parseToJsonElement("""{"id":7,"image_uri_list":"[{\"sourceUrl\":\"https://image.coolapk.com/a-uhdr@40x50.jpg\",\"hdr\":1}]","media_url":"https://video.coolapk.com/post.mp4"}""") as JsonObject)
        assertEquals(1, data.picArr.size)
        assertTrue(data.mediaImages.single().isHdr)
        assertFalse(data.mediaImages.single().isLive)
        assertEquals("https://image.coolapk.com/a-uhdr@40x50.jpg.m.jpg", data.mediaImages.single().sdrHd)
    }

    @Test fun autoplayRequiresWifiRatherThanAnyUnmeteredNetwork() {
        assertFalse(MediaAutoPlay.Off.allows(true))
        assertFalse(MediaAutoPlay.Wifi.allows(false))
        assertTrue(MediaAutoPlay.Wifi.allows(true))
        assertTrue(MediaAutoPlay.Always.allows(false))
    }
}
