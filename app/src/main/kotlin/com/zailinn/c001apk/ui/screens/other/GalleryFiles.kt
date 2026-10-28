package com.zailinn.c001apk.ui.screens.other

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.zailinn.c001apk.core.media.MotionPhoto
import com.zailinn.c001apk.core.model.GalleryMedia
import com.zailinn.c001apk.core.net.CoolapkRepository
import com.zailinn.c001apk.core.net.HttpsUpgrade
import com.zailinn.c001apk.core.net.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class GalleryExport(val label: String) { Motion("实况照片"), Photo("照片"), Video("视频") }
data class GalleryFile(val file: File, val mime: String, val suffix: String)

/** Downloads use the credential-free image client, including resolved live videos. */
class GalleryFiles(context: Context, private val http: OkHttpClient, private val repository: CoolapkRepository, private val localMedia: (String) -> File? = { null }) {
    private val directory = File(context.cacheDir, "gallery").apply { mkdirs() }
    private val locks = ConcurrentHashMap<String, Mutex>()

    init {
        directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 7 * 24 * 60 * 60_000L }?.forEach { it.delete() }
    }

    suspend fun photo(media: GalleryMedia): File = image(media.sourceUrl)

    suspend fun image(url: String): File = download(url, MotionPhoto.MAX_PHOTO_BYTES)

    suspend fun embeddedVideo(media: GalleryMedia): Boolean = withContext(Dispatchers.IO) { MotionPhoto.inspect(photo(media)) != null }

    /**
     * A cached original is checked for an embedded video first. Otherwise the separate
     * video is tried before the original, which can be far larger than the clip.
     */
    suspend fun video(media: GalleryMedia): File = withContext(Dispatchers.IO) {
        cached(media.sourceUrl)?.let { embedded(media, it) }?.let { return@withContext it }
        try { remoteVideo(media) } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // No source information or no separate video: the original may carry it.
            val original = try { photo(media) } catch (c: CancellationException) { throw c } catch (_: Exception) { throw e }
            embedded(media, original) ?: throw e
        }
    }

    private fun cached(url: String): File? = HttpsUpgrade.url(url)?.let { File(directory, key(it)) }?.takeIf { it.isFile }

    private suspend fun embedded(media: GalleryMedia, original: File): File? {
        if (MotionPhoto.inspect(original) == null) return null
        val target = File(directory, key(media.sourceUrl) + "-embedded.mp4")
        locks.getOrPut(target.name, ::Mutex).withLock {
            if (!target.isFile) writeAtomic(target) { MotionPhoto.extractVideo(original, it) }
        }
        return target
    }

    private suspend fun remoteVideo(media: GalleryMedia): File {
        suspend fun fetch(item: GalleryMedia): File {
            val file = download(repository.livePhotoVideo(item), 128 * 1024 * 1024L)
            if (!MotionPhoto.isVideo(file)) {
                file.delete()
                error("实况视频格式无效")
            }
            return file
        }
        return try { fetch(media) } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // Metadata and history can contain expired CDN signatures.
            if (media.videoUrl != null && media.contentId != null) fetch(media.copy(videoUrl = null)) else throw e
        }
    }

    suspend fun export(media: GalleryMedia, kind: GalleryExport): GalleryFile = withContext(Dispatchers.IO) {
        val original = photo(media)
        when (kind) {
            GalleryExport.Photo -> {
                val image = if (MotionPhoto.inspect(original) == null) original else {
                    File(directory, key(media.sourceUrl) + "-still").also { target ->
                        locks.getOrPut(target.name, ::Mutex).withLock {
                            if (!target.isFile) writeAtomic(target) { MotionPhoto.extractPhoto(original, it) }
                        }
                    }
                }
                val (mime, suffix) = imageType(image)
                GalleryFile(image, mime, suffix)
            }
            GalleryExport.Video -> GalleryFile(video(media), "video/mp4", ".mp4")
            GalleryExport.Motion -> {
                if (MotionPhoto.inspect(original) != null) GalleryFile(original, "image/jpeg", ".MP.jpg")
                else {
                    val target = File(directory, key(media.sourceUrl) + "-motion.jpg")
                    locks.getOrPut(target.name, ::Mutex).withLock {
                        if (!target.isFile) {
                            val video = video(media)
                            val jpeg = if (imageType(original).first == "image/jpeg") original else jpeg(original)
                            writeAtomic(target) { MotionPhoto.write(jpeg, video, it) }
                        }
                    }
                    GalleryFile(target, "image/jpeg", ".MP.jpg")
                }
            }
        }
    }

    private fun jpeg(source: File): File {
        val target = File(directory, source.name + "-jpeg")
        if (target.isFile) return target
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取照片" }
        // A compressed PNG/WebP can expand far beyond the download limit.
        var sample = 1
        while (bounds.outWidth.toLong() * bounds.outHeight / sample / sample > 16_000_000L ||
            maxOf(bounds.outWidth, bounds.outHeight) / sample > 8192) sample *= 2
        val bitmap = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("无法读取照片")
        try {
            writeAtomic(target) { file -> file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) } }
        } finally { bitmap.recycle() }
        return target
    }

    private suspend fun download(value: String, limit: Long): File = withContext(Dispatchers.IO) {
        if (value.startsWith(com.zailinn.c001apk.core.backup.BACKUP_MEDIA_SCHEME)) {
            return@withContext localMedia(value) ?: error("备份图片缺失或缓存已清理，请重新打开备份")
        }
        val url = HttpsUpgrade.url(value) ?: error("媒体地址无效")
        val target = File(directory, key(url))
        locks.getOrPut(target.name, ::Mutex).withLock {
            if (!target.isFile) {
                val partial = File(directory, target.name + ".${UUID.randomUUID()}.part")
                try {
                    http.newCall(Request.Builder().url(url).build()).await().use { response ->
                        check(response.isSuccessful) { "媒体下载失败（HTTP ${response.code}）" }
                        val body = response.body ?: error("媒体内容为空")
                        check(body.contentLength() <= limit) { "媒体文件过大" }
                        partial.outputStream().use { out ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                var size = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    size += read
                                    check(size <= limit) { "媒体文件过大" }
                                    out.write(buffer, 0, read)
                                }
                                check(size > 0) { "媒体内容为空" }
                            }
                        }
                    }
                    check(partial.renameTo(target)) { "无法缓存媒体" }
                } finally { partial.delete() }
            }
        }
        target
    }

    private fun writeAtomic(target: File, write: (File) -> Unit) {
        val partial = File(directory, target.name + ".${UUID.randomUUID()}.part")
        try { write(partial); check(partial.renameTo(target)) } finally { partial.delete() }
    }

    companion object {
        private fun key(url: String) = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        private fun imageType(file: File): Pair<String, String> {
            val head = ByteArray(minOf(file.length(), 12).toInt()).also { bytes -> java.io.DataInputStream(file.inputStream()).use { it.readFully(bytes) } }
            return when {
                head.size >= 2 && head[0] == 0xff.toByte() && head[1] == 0xd8.toByte() -> "image/jpeg" to ".jpg"
                head.size >= 4 && head[0] == 0x89.toByte() && head[1] == 0x50.toByte() -> "image/png" to ".png"
                String(head.take(3).toByteArray()) == "GIF" -> "image/gif" to ".gif"
                head.size >= 12 && String(head, 8, 4) == "WEBP" -> "image/webp" to ".webp"
                head.size >= 12 && String(head, 4, 4) == "ftyp" && String(head, 8, 4) in setOf("avif", "avis") -> "image/avif" to ".avif"
                head.size >= 12 && String(head, 4, 4) == "ftyp" && String(head, 8, 4) in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1") -> "image/heif" to ".heic"
                else -> error("图片格式不受支持")
            }
        }
    }
}

/** A failed/canceled write is removed, so a half-written image never appears in the gallery. */
internal suspend fun saveGalleryFile(context: Context, media: GalleryFile): Unit = withContext(Dispatchers.IO) {
    val name = "c001apk_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}${media.suffix}"
    val video = media.mime.startsWith("video/")
    val folder = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, media.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/c001apk")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: error("无法创建相册文件")
        try {
            val stream = resolver.openOutputStream(uri) ?: error("无法写入相册")
            stream.use { out -> media.file.inputStream().use { it.copyTo(out) } }
            currentCoroutineContext().ensureActive()
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) > 0)
        } catch (e: Throwable) {
            withContext(NonCancellable) { resolver.delete(uri, null, null) }
            throw e
        }
    } else {
        @Suppress("DEPRECATION")
        val directory = File(Environment.getExternalStoragePublicDirectory(folder), "c001apk").apply { mkdirs() }
        val target = File(directory, name)
        try {
            media.file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            currentCoroutineContext().ensureActive()
            MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(media.mime), null)
        } catch (e: Throwable) { target.delete(); throw e }
    }
}
