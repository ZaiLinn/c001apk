package com.zailinn.c001apk.core.media

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer

class MotionPhotoTest {
    @get:Rule val folder = TemporaryFolder()
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xda.toByte(), 0, 2, 10, 20, 0xff.toByte(), 0xd9.toByte())
    private val mp4 = ByteBuffer.allocate(24).putInt(24).put("ftypisom".toByteArray()).putInt(0).put("isommp42".toByteArray()).array()

    @Test fun composedFileRoundTripsAndPhotoAndVideoStayLossless() {
        val photo = folder.newFile("photo.jpg").apply { writeBytes(jpeg) }
        val video = folder.newFile("video.mp4").apply { writeBytes(mp4) }
        val result = folder.newFile("result.MP.jpg")
        MotionPhoto.write(photo, video, result, 1234)
        val info = MotionPhoto.inspect(result)!!
        assertEquals(mp4.size.toLong(), info.length)
        assertEquals(1234, info.presentationUs)
        assertTrue(result.readText(Charsets.ISO_8859_1).contains("MotionPhoto"))
        val extracted = folder.newFile("extracted.mp4")
        MotionPhoto.extractVideo(result, extracted)
        assertArrayEquals(mp4, extracted.readBytes())
        val still = folder.newFile("still.jpg")
        MotionPhoto.extractPhoto(result, still)
        assertNull(MotionPhoto.inspect(still))
        assertFalse("photo-only XMP must not advertise a video", still.readText(Charsets.ISO_8859_1).contains("Camera:MotionPhoto"))
        assertTrue(still.readBytes().takeLast(8).toByteArray().contentEquals(jpeg.takeLast(8).toByteArray()))
        val second = folder.newFile("second.MP.jpg")
        MotionPhoto.write(result, video, second)
        assertEquals(mp4.size.toLong(), MotionPhoto.inspect(second)?.length)
    }

    @Test fun gainmapJpegSurvivesComposition() {
        val gainmap = jpeg.copyOf().apply { this[6] = 99 }
        val photo = folder.newFile("hdr.jpg").apply { writeBytes(jpeg + gainmap) }
        val video = folder.newFile("video.mp4").apply { writeBytes(mp4) }
        val result = folder.newFile("hdr.MP.jpg")
        MotionPhoto.write(photo, video, result)
        val bytes = result.readBytes()
        val videoOffset = MotionPhoto.inspect(result)!!.offset.toInt()
        assertArrayEquals(gainmap, bytes.copyOfRange(videoOffset - gainmap.size, videoOffset))
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("GainMap"))
    }

    @Test fun ordinaryOrTruncatedPhotosAreNotReportedAsLive() {
        val photo = folder.newFile("photo.jpg").apply { writeBytes(jpeg) }
        assertNull(MotionPhoto.inspect(photo))
        val video = folder.newFile("invalid.mp4").apply { writeText("not a video") }
        assertThrows(IllegalArgumentException::class.java) { MotionPhoto.write(photo, video, folder.newFile("bad.MP.jpg")) }
    }
}
