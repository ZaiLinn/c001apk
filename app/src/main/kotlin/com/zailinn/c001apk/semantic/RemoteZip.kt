package com.zailinn.c001apk.semantic

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Reads single entries of a remote zip with HTTP range requests, so only the
 * native libraries for one ABI are downloaded instead of the whole AAR.
 */
class RemoteZip(private val http: OkHttpClient, private val url: String) {
    class Entry(val name: String, val method: Int, val compressedSize: Long, val size: Long, val localHeaderOffset: Long)

    /** Central directory; needs the server to honour `Range`. */
    fun entries(): Map<String, Entry> {
        val (tail, total) = range("bytes=-$TAIL")
        val buffer = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        val eocd = (tail.size - 22 downTo 0).firstOrNull { buffer.getInt(it) == 0x06054b50 }
            ?: throw IOException("压缩包结构异常")
        val count = buffer.getShort(eocd + 10).toInt() and 0xFFFF
        val cdSize = buffer.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
        val cdOffset = buffer.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
        val tailStart = total - tail.size
        val cd = if (cdOffset >= tailStart) {
            ByteBuffer.wrap(tail, (cdOffset - tailStart).toInt(), cdSize.toInt()).slice()
        } else {
            ByteBuffer.wrap(range("bytes=$cdOffset-${cdOffset + cdSize - 1}").first)
        }.order(ByteOrder.LITTLE_ENDIAN)
        val entries = HashMap<String, Entry>(count)
        var p = 0
        repeat(count) {
            if (cd.getInt(p) != 0x02014b50) throw IOException("压缩包目录异常")
            val nameLength = cd.getShort(p + 28).toInt() and 0xFFFF
            val extra = cd.getShort(p + 30).toInt() and 0xFFFF
            val comment = cd.getShort(p + 32).toInt() and 0xFFFF
            val name = ByteArray(nameLength).also { cd.position(p + 46); cd.get(it) }.toString(Charsets.UTF_8)
            entries[name] = Entry(
                name,
                method = cd.getShort(p + 10).toInt() and 0xFFFF,
                compressedSize = cd.getInt(p + 20).toLong() and 0xFFFFFFFFL,
                size = cd.getInt(p + 24).toLong() and 0xFFFFFFFFL,
                localHeaderOffset = cd.getInt(p + 42).toLong() and 0xFFFFFFFFL,
            )
            p += 46 + nameLength + extra + comment
        }
        return entries
    }

    /** Writes [entry] uncompressed to [target] and returns its SHA-256 in hex. */
    suspend fun extract(entry: Entry, target: File, onBytes: (Long) -> Unit): String {
        val header = ByteBuffer.wrap(range("bytes=${entry.localHeaderOffset}-${entry.localHeaderOffset + 29}").first)
            .order(ByteOrder.LITTLE_ENDIAN)
        if (header.getInt(0) != 0x04034b50) throw IOException("压缩包条目异常")
        val start = entry.localHeaderOffset + 30 + (header.getShort(26).toInt() and 0xFFFF) + (header.getShort(28).toInt() and 0xFFFF)
        val request = Request.Builder().url(url).header("Range", "bytes=$start-${start + entry.compressedSize - 1}").build()
        val digest = MessageDigest.getInstance("SHA-256")
        http.newCall(request).execute().use { response ->
            if (response.code != 206) throw IOException("服务器不支持分段下载（HTTP ${response.code}）")
            val raw = CountingStream(response.body!!.byteStream(), onBytes)
            val data: InputStream = when (entry.method) {
                0 -> raw
                8 -> InflaterInputStream(raw, Inflater(true), 64 * 1024)
                else -> throw IOException("不支持的压缩方式 ${entry.method}")
            }
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = data.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                }
            }
        }
        return digest.digest().toHex()
    }

    /** Body of a 206 response and the total length from `Content-Range`. */
    private fun range(value: String): Pair<ByteArray, Long> {
        http.newCall(Request.Builder().url(url).header("Range", value).build()).execute().use { response ->
            if (response.code != 206) throw IOException("服务器不支持分段下载（HTTP ${response.code}）")
            val total = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: throw IOException("服务器响应缺少 Content-Range")
            return response.body!!.bytes() to total
        }
    }

    private class CountingStream(input: InputStream, private val onBytes: (Long) -> Unit) : FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) onBytes(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) onBytes(it.toLong()) }
    }

    private companion object {
        /** End of central directory plus the longest possible comment. */
        const val TAIL = 22 + 65_535
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
