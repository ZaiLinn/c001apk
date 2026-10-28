package com.zailinn.c001apk.ui.common

import android.net.ConnectivityManager
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.zailinn.c001apk.core.net.await
import com.zailinn.c001apk.ui.screens.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** What a JPEG's header says about it: Ultra HDR gain map, embedded Motion Photo video. */
data class JpegFlags(val hdr: Boolean = false, val live: Boolean = false)

/**
 * Whether a list image is Ultra HDR or a Motion Photo. Thumbnails are resized
 * on the server and lose both, so the original's JPEG header is read instead,
 * a few small ranges at a time; results are kept for the process. The header
 * is only read on an unmetered network, so mobile data shows the badges just
 * for images already checked.
 */
@Composable
fun rememberJpegFlags(url: String): JpegFlags {
    if (LocalOfflineBackup.current || url.startsWith(com.zailinn.c001apk.core.backup.BACKUP_MEDIA_SCHEME)) return JpegFlags()
    if (!url.substringBefore('?').lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") }) return JpegFlags()
    val http = appContainer().imageHttp
    val connectivity = LocalContext.current.getSystemService(ConnectivityManager::class.java)
    var flags by remember(url) { mutableStateOf(JpegHeaderProbe.cached(url) ?: JpegFlags()) }
    LaunchedEffect(url) {
        if (JpegHeaderProbe.cached(url) == null && connectivity?.isActiveNetworkMetered == false) {
            JpegHeaderProbe.check(http, url)?.let { flags = it }
        }
    }
    return flags
}

private object JpegHeaderProbe {
    private val results = LruCache<String, JpegFlags>(2000)
    private val permits = Semaphore(4)

    fun cached(url: String): JpegFlags? = results.get(url)

    /** Null when the header could not be read; such images are tried again later. */
    suspend fun check(http: OkHttpClient, url: String): JpegFlags? = permits.withPermit {
        results.get(url) ?: runCatching { readJpegFlags { start, length -> readRange(http, url, start, length) } }
            .getOrNull()?.also { results.put(url, it) }
    }

    private suspend fun readRange(http: OkHttpClient, url: String, start: Long, length: Int): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Range", "bytes=$start-${start + length - 1}").build()
        http.newCall(request).await().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val source = response.body!!.source()
            // A server that ignores Range sends the whole file from byte 0.
            if (response.code == 200 && start > 0) source.skip(start)
            source.request(length.toLong())
            source.buffer.readByteArray(minOf(length.toLong(), source.buffer.size))
        }
    }
}

/** [readJpegFlags] on a downloaded file. */
fun readJpegFlags(file: java.io.File): JpegFlags = runCatching {
    java.io.RandomAccessFile(file, "r").use { input ->
        kotlinx.coroutines.runBlocking {
            readJpegFlags { start, length ->
                if (start >= input.length()) ByteArray(0)
                else ByteArray(minOf(length.toLong(), input.length() - start).toInt()).also { input.seek(start); input.readFully(it) }
            }
        }
    }
}.getOrDefault(JpegFlags())

private const val XMP_ID = "http://ns.adobe.com/xap/1.0/"
private const val ISO_GAIN_MAP_ID = "urn:iso:std:iso:ts:21496:-1"

/** Google's `Camera:MotionPhoto` and the older `GCamera:MicroVideo`, as attribute or element. */
private val MOTION_PHOTO = Regex("""(MotionPhoto|MicroVideo)\s*(=\s*["']|>)\s*1""")

internal suspend fun isUltraHdrJpeg(read: suspend (start: Long, length: Int) -> ByteArray): Boolean = readJpegFlags(read).hdr

/**
 * Walks the JPEG's APPn segments, which come before the image data: Ultra HDR
 * is `hdrgm` XMP or the ISO 21496-1 gain map tag, a Motion Photo is declared in
 * the XMP. [read] returns up to `length` bytes from `start`, fewer at the end
 * of the file; one read usually covers several segments.
 */
internal suspend fun readJpegFlags(read: suspend (start: Long, length: Int) -> ByteArray): JpegFlags {
    var block = read(0, 4096)
    var blockStart = 0L
    // A short read reached the end of the file: nothing more lies past it.
    var atEnd = block.size < 4096
    if (block.size < 4 || block.u8(0) != 0xFF || block.u8(1) != 0xD8) return JpegFlags()
    suspend fun bytes(start: Long, length: Int): ByteArray {
        val covered = start >= blockStart && (start + length <= blockStart + block.size || atEnd)
        if (!covered) {
            block = read(start, length)
            blockStart = start
            atEnd = block.size < length
        }
        val from = (start - blockStart).toInt().coerceAtMost(block.size)
        return block.copyOfRange(from, minOf(block.size, from + length))
    }
    var hdr = false
    var live = false
    var pos = 2L
    repeat(16) {
        // Marker, length and enough of the payload to identify it.
        val head = bytes(pos, 4 + 2048)
        if (head.size < 4 || head.u8(0) != 0xFF) return JpegFlags(hdr, live)
        val marker = head.u8(1)
        if (marker !in 0xE0..0xEF && marker != 0xFE) return JpegFlags(hdr, live)
        val length = (head.u8(2) shl 8) or head.u8(3)
        val id = String(head, 4, minOf(head.size - 4, 64).coerceAtLeast(0), Charsets.ISO_8859_1)
        if (marker == 0xE1 && id.startsWith(XMP_ID)) {
            // The Motion Photo attributes can sit anywhere in the XMP, so read all of it.
            val xmp = String(bytes(pos + 4, length - 2), Charsets.ISO_8859_1)
            if ("hdrgm" in xmp) hdr = true
            if (MOTION_PHOTO.containsMatchIn(xmp)) live = true
        }
        if (marker == 0xE2 && id.startsWith(ISO_GAIN_MAP_ID)) hdr = true
        if (hdr && live) return JpegFlags(true, true)
        pos += 2 + length
    }
    return JpegFlags(hdr, live)
}
private fun ByteArray.u8(index: Int) = this[index].toInt() and 0xFF
