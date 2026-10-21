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

/**
 * Whether a list image is Ultra HDR. Thumbnails are resized on the server and
 * lose the gain map, so the original's JPEG header is read instead, a few small
 * ranges at a time; results are kept for the process. The header is only read
 * on an unmetered network, so mobile data shows the badge just for images
 * already checked.
 */
@Composable
fun rememberUltraHdr(url: String): Boolean {
    if (!url.substringBefore('?').lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") }) return false
    val http = appContainer().imageHttp
    val connectivity = LocalContext.current.getSystemService(ConnectivityManager::class.java)
    var hdr by remember(url) { mutableStateOf(UltraHdrProbe.cached(url) ?: false) }
    LaunchedEffect(url) {
        if (UltraHdrProbe.cached(url) == null && connectivity?.isActiveNetworkMetered == false) {
            UltraHdrProbe.check(http, url)?.let { hdr = it }
        }
    }
    return hdr
}

private object UltraHdrProbe {
    private val results = LruCache<String, Boolean>(2000)
    private val permits = Semaphore(4)

    fun cached(url: String): Boolean? = results.get(url)

    /** Null when the header could not be read; such images are tried again later. */
    suspend fun check(http: OkHttpClient, url: String): Boolean? = permits.withPermit {
        results.get(url) ?: runCatching { isUltraHdrJpeg { start, length -> readRange(http, url, start, length) } }
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

private const val XMP_ID = "http://ns.adobe.com/xap/1.0/"
private const val ISO_GAIN_MAP_ID = "urn:iso:std:iso:ts:21496:-1"

/**
 * Walks the JPEG's APPn segments, which come before the image data, looking
 * for Ultra HDR's `hdrgm` XMP or the ISO 21496-1 gain map tag. [read] returns
 * up to `length` bytes from `start`, fewer at the end of the file.
 */
internal suspend fun isUltraHdrJpeg(read: suspend (start: Long, length: Int) -> ByteArray): Boolean {
    val head = read(0, 4096)
    if (head.size < 4 || head.u8(0) != 0xFF || head.u8(1) != 0xD8) return false
    var pos = 2L
    repeat(16) {
        // Marker, length and enough of the payload to identify it and reach the hdrgm namespace.
        val chunk = if (pos + 4 + 2048 <= head.size) head.copyOfRange(pos.toInt(), (pos + 4 + 2048).toInt()) else read(pos, 4 + 2048)
        if (chunk.size < 4 || chunk.u8(0) != 0xFF) return false
        val marker = chunk.u8(1)
        val isApp = marker in 0xE0..0xEF || marker == 0xFE
        if (!isApp) return false
        val length = (chunk.u8(2) shl 8) or chunk.u8(3)
        val payload = String(chunk, 4, minOf(chunk.size - 4, length - 2).coerceAtLeast(0), Charsets.ISO_8859_1)
        if (marker == 0xE1 && payload.startsWith(XMP_ID) && payload.contains("hdrgm")) return true
        if (marker == 0xE2 && payload.startsWith(ISO_GAIN_MAP_ID)) return true
        pos += 2 + length
    }
    return false
}

private fun ByteArray.u8(index: Int) = this[index].toInt() and 0xFF
