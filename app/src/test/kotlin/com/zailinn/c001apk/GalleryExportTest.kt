package com.zailinn.c001apk

import android.content.*
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.zailinn.c001apk.core.media.MotionPhoto
import com.zailinn.c001apk.ui.screens.other.GalleryFile
import com.zailinn.c001apk.ui.screens.other.saveGalleryFile
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GalleryExportTest {
    @get:Rule val folder = TemporaryFolder()
    private val mp4 = ByteBuffer.allocate(24).putInt(24).put("ftypisom".toByteArray()).putInt(0).put("isommp42".toByteArray()).array()

    @Test fun hdrMotionPhotoKeepsJpegGainmapAndCorrectMpfOffsets() {
        fun jpeg(size: Int, color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            return java.io.ByteArrayOutputStream().use { stream ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream))
                bitmap.recycle()
                stream.toByteArray()
            }
        }
        val primary = jpeg(32, android.graphics.Color.GRAY)
        val gainmap = jpeg(8, android.graphics.Color.WHITE)
        val xml = ("http://ns.adobe.com/xap/1.0/\u0000" + """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0"/></rdf:RDF></x:xmpmeta>""").toByteArray()
        val xmp = byteArrayOf(0xff.toByte(), 0xe1.toByte(), ((xml.size + 2) shr 8).toByte(), (xml.size + 2).toByte()) + xml
        val firstSize = primary.size + xmp.size + 90
        val tiff = ByteBuffer.allocate(82).order(ByteOrder.LITTLE_ENDIAN).apply {
            put('I'.code.toByte()); put('I'.code.toByte()); putShort(42); putInt(8); putShort(3)
            putShort(0xb000.toShort()); putShort(7); putInt(4); put("0100".toByteArray())
            putShort(0xb001.toShort()); putShort(4); putInt(1); putInt(2)
            putShort(0xb002.toShort()); putShort(7); putInt(32); putInt(50)
            putInt(0)
            putInt(0x20030000); putInt(firstSize); putInt(0); putInt(0)
            putInt(0); putInt(gainmap.size); putInt(firstSize - 10); putInt(0)
        }.array()
        val mpf = byteArrayOf(0xff.toByte(), 0xe2.toByte(), 0, 88) + byteArrayOf(77, 80, 70, 0) + tiff
        val photo = folder.newFile("hdr.jpg").apply { writeBytes(primary.take(2).toByteArray() + mpf + xmp + primary.drop(2).toByteArray() + gainmap) }
        val video = folder.newFile("video.mp4").apply { writeBytes(mp4) }
        val motion = folder.newFile("hdr.MP.jpg")
        MotionPhoto.write(photo, video, motion)
        val output = motion.readBytes()
        val mpfStart = (0 until output.size - 4).first { output.copyOfRange(it, it + 4).contentEquals(byteArrayOf(77, 80, 70, 0)) }
        val newTiff = mpfStart + 4
        val table = newTiff + ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).getInt(newTiff + 8 + 2 + 2 * 12 + 8)
        val data = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
        val offset = newTiff + data.getInt(table + 16 + 8)
        assertArrayEquals(gainmap, output.copyOfRange(offset, offset + data.getInt(table + 16 + 4)))
        assertEquals(offset, data.getInt(table + 4))
        // Robolectric does not implement gain-map JPEG decoding; independently
        // verify both JPEG streams and the MPF addresses used by device decoders.
        assertEquals(32, BitmapFactory.decodeFile(motion.path).width)
        assertEquals(8, BitmapFactory.decodeByteArray(output, offset, gainmap.size).width)
        assertEquals(mp4.size.toLong(), MotionPhoto.inspect(motion)?.length)
    }

    @Test fun separateAndCombinedExportsUseTheOriginalMediaWithoutCredentials() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val photo = java.io.ByteArrayOutputStream().use { stream ->
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).also {
                assertTrue(it.compress(Bitmap.CompressFormat.JPEG, 95, stream)); it.recycle()
            }
            stream.toByteArray()
        }
        val requests = java.util.concurrent.CopyOnWriteArrayList<okhttp3.Request>()
        var invalidVideo = false
        val transport = okhttp3.Interceptor { chain ->
            val request = chain.request()
            requests += request
            val bytes = if (request.url.encodedPath.endsWith(".mp4")) {
                if (invalidVideo) "not an MP4".toByteArray() else mp4
            } else photo
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(bytes.toResponseBody()).build()
        }
        val app = AppContainer(context, "exports-${java.util.UUID.randomUUID()}", imageTransport = transport)
        val media = com.zailinn.c001apk.core.model.GalleryMedia(
            "http://image.coolapk.com/${java.util.UUID.randomUUID()}.jpg", isLive = true,
            videoUrl = "http://cdn.example.com/${java.util.UUID.randomUUID()}.mp4",
        )
        val files = app.galleryFiles
        val motion = files.export(media, com.zailinn.c001apk.ui.screens.other.GalleryExport.Motion)
        assertEquals(".MP.jpg", motion.suffix)
        assertEquals(mp4.size.toLong(), MotionPhoto.inspect(motion.file)?.length)
        val still = files.export(media, com.zailinn.c001apk.ui.screens.other.GalleryExport.Photo)
        assertArrayEquals(photo, still.file.readBytes())
        val clip = files.export(media, com.zailinn.c001apk.ui.screens.other.GalleryExport.Video)
        assertArrayEquals(mp4, clip.file.readBytes())
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.url.isHttps && it.header("Cookie") == null && it.header("X-App-Token") == null })
        invalidVideo = true
        val bad = media.copy(videoUrl = "https://cdn.example.com/${java.util.UUID.randomUUID()}.mp4")
        assertTrue(runCatching { files.export(bad, com.zailinn.c001apk.ui.screens.other.GalleryExport.Video) }.isFailure)
    }

    @Test fun playingASeparateVideoSkipsTheOriginalButAnEmbeddedOneStillPlays() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val photo = java.io.ByteArrayOutputStream().use { stream ->
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).also {
                assertTrue(it.compress(Bitmap.CompressFormat.JPEG, 95, stream)); it.recycle()
            }
            stream.toByteArray()
        }
        val motion = File(folder.root, "motion.jpg").also { target ->
            MotionPhoto.write(File(folder.root, "still.jpg").apply { writeBytes(photo) }, File(folder.root, "clip.mp4").apply { writeBytes(mp4) }, target)
        }.readBytes()
        val requests = java.util.concurrent.CopyOnWriteArrayList<okhttp3.Request>()
        val transport = okhttp3.Interceptor { chain ->
            val request = chain.request()
            requests += request
            val bytes = if (request.url.encodedPath.endsWith(".mp4")) mp4 else motion
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(bytes.toResponseBody()).build()
        }
        val files = AppContainer(context, "videos-${java.util.UUID.randomUUID()}", imageTransport = transport).galleryFiles
        val separate = com.zailinn.c001apk.core.model.GalleryMedia(
            "https://image.coolapk.com/${java.util.UUID.randomUUID()}.jpg", isLive = true,
            videoUrl = "https://cdn.example.com/${java.util.UUID.randomUUID()}.mp4",
        )
        assertArrayEquals(mp4, files.video(separate).readBytes())
        assertEquals(listOf(separate.videoUrl), requests.map { it.url.toString() })
        requests.clear()
        // Without source information the original is the only place the video can be.
        val embedded = com.zailinn.c001apk.core.model.GalleryMedia("https://image.coolapk.com/${java.util.UUID.randomUUID()}.jpg", isLive = true)
        assertArrayEquals(mp4, files.video(embedded).readBytes())
        assertEquals(listOf(embedded.sourceUrl), requests.map { it.url.toString() })
    }

    @Test fun aMotionExportCreatesOneImageAndCommitsAfterTheWrite() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = GalleryProvider(folder.newFile("saved"))
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = MediaStore.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)
        val image = folder.newFile("source.MP.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        saveGalleryFile(context, GalleryFile(image, "image/jpeg", ".MP.jpg"))
        assertEquals(1, provider.inserts.size)
        assertEquals(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, provider.inserts.single().first)
        assertTrue(provider.inserts.single().second.getAsString(MediaStore.MediaColumns.DISPLAY_NAME).endsWith(".MP.jpg"))
        assertEquals(1, provider.inserts.single().second.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals(0, provider.committed?.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertArrayEquals(image.readBytes(), provider.target.readBytes())
    }

    @Test fun failedGalleryWritesRemoveThePendingEntry() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = GalleryProvider(folder.newFile("saved"), failWrite = true)
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = MediaStore.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)
        val image = folder.newFile("source.jpg").apply { writeBytes(byteArrayOf(1)) }
        assertTrue(runCatching { saveGalleryFile(context, GalleryFile(image, "image/jpeg", ".jpg")) }.isFailure)
        assertEquals(1, provider.deleted)
        assertNull(provider.committed)
    }

    private class GalleryProvider(val target: File, private val failWrite: Boolean = false) : ContentProvider() {
        val inserts = mutableListOf<Pair<Uri, ContentValues>>()
        var committed: ContentValues? = null
        var deleted = 0
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserts += uri to ContentValues(values)
            return ContentUris.withAppendedId(uri, 1)
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (failWrite) throw java.io.IOException("synthetic failure")
            return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int { committed = ContentValues(values); return 1 }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { deleted++; return 1 }
        override fun getType(uri: Uri) = "image/jpeg"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    }
}
