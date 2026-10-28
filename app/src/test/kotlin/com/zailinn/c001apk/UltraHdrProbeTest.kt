package com.zailinn.c001apk

import com.zailinn.c001apk.ui.common.JpegFlags
import com.zailinn.c001apk.ui.common.isUltraHdrJpeg
import com.zailinn.c001apk.ui.common.readJpegFlags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Ultra HDR and Motion Photos are told apart from the JPEG header alone, reading past a large EXIF block in ranges. */
class UltraHdrProbeTest {
    private fun segment(marker: Int, payload: ByteArray) = byteArrayOf(0xFF.toByte(), marker.toByte(), ((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()) + payload

    private fun jpeg(vararg segments: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        segments.forEach { write(it) }
        write(segment(0xDA, ByteArray(100)))
    }.toByteArray()

    // An EXIF block with a thumbnail pushes the XMP far past the first read.
    private val exif = segment(0xE1, "Exif\u0000\u0000".toByteArray() + ByteArray(30_000))
    private val ultraHdrXmp = segment(0xE1, ("http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta><rdf:Description " +
        "xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\" hdrgm:Version=\"1.0\"/>").toByteArray(Charsets.ISO_8859_1))
    private val plainXmp = segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta/>".toByteArray(Charsets.ISO_8859_1))
    private val isoGainMap = segment(0xE2, "urn:iso:std:iso:ts:21496:-1\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(4))

    private fun probe(file: ByteArray): Pair<Boolean, Int> {
        var reads = 0
        val hdr = runBlocking {
            isUltraHdrJpeg { start, length ->
                reads++
                file.copyOfRange(start.toInt().coerceAtMost(file.size), (start + length).toInt().coerceAtMost(file.size))
            }
        }
        return hdr to reads
    }

    @Test
    fun findsTheGainMapBehindExif() {
        assertEquals(true to 2, probe(jpeg(exif, ultraHdrXmp)))
        assertEquals(true, probe(jpeg(exif, plainXmp, isoGainMap)).first)
    }

    @Test
    fun plainImagesAreNotHdr() {
        assertEquals(false, probe(jpeg(exif, plainXmp)).first)
        assertEquals(false, probe("GIF89a".toByteArray()).first)
    }

    private fun flags(file: ByteArray) = runBlocking {
        readJpegFlags { start, length -> file.copyOfRange(start.toInt().coerceAtMost(file.size), (start + length).toInt().coerceAtMost(file.size)) }
    }

    @Test
    fun findsAMotionPhotoDeclaredDeepInTheXmp() {
        // Long XMP before the flag: the attribute lies past the first 2 KB of the segment.
        val motion = segment(0xE1, ("http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta><rdf:Description " + " ".repeat(5000) +
            "Camera:MotionPhoto=\"1\" hdrgm:Version=\"1.0\"/>").toByteArray(Charsets.ISO_8859_1))
        assertEquals(JpegFlags(hdr = true, live = true), flags(jpeg(exif, motion)))
        val micro = segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<GCamera:MicroVideo>1</GCamera:MicroVideo>".toByteArray(Charsets.ISO_8859_1))
        assertEquals(JpegFlags(live = true), flags(jpeg(micro)))
        val off = segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<x Camera:MotionPhoto=\"0\"/>".toByteArray(Charsets.ISO_8859_1))
        assertEquals(JpegFlags(), flags(jpeg(exif, off)))
    }
}
