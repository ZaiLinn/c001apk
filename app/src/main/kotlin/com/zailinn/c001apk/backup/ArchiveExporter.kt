package com.zailinn.c001apk.backup

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import com.zailinn.c001apk.core.backup.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ArchiveExportFormat { Text, Html, HtmlImages }
data class PreparedArchive(val file: File, val mime: String, val suffix: String, val volumes: Int, val missing: Int)

/** Exports read exclusively from verified local backup files. No network dependency exists here. */
class ArchiveExporter(private val context: Context, private val store: FeedBackupStore, private val index: ArchiveIndex, private val volumeLimit: Long = 64 * 1024 * 1024L) {
    val progress = MutableStateFlow<BackupProgress?>(null)
    suspend fun prepare(ids: List<String>, format: ArchiveExportFormat, replies: Boolean, ascending: Boolean): PreparedArchive = withContext(Dispatchers.IO) {
        require(ids.size <= 20000) { "单次最多导出 20000 条动态" }
        store.awaitReady()
        val records = index.records().filter { it.feed.id in ids.toSet() }.sortedBy { if (ascending) it.feed.dateline else -it.feed.dateline }
        require(records.isNotEmpty()) { "没有可导出的备份" }
        val estimated = store.estimateExport(records.map { it.feed.id }.toSet(), format == ArchiveExportFormat.HtmlImages)
        require(estimated < StatFs(context.cacheDir.path).availableBytes) { "存储空间不足，无法准备导出" }
        val folder = File(context.cacheDir, "archive-export/${UUID.randomUUID()}").apply { mkdirs() }
        progress.value = BackupProgress("正在准备导出", 0, records.size)
        try {
            val text = File(folder, "archive.txt")
            val parts = mutableListOf<File>()
            val toc = mutableListOf<Pair<String, String>>()
            var part: File? = null
            var partSize = 0L
            var missing = 0
            val missingPosts = mutableListOf<Pair<String, String>>()
            fun newPart(): File {
                part?.appendText(ArchiveExport.footer)
                val name = if (parts.isEmpty()) "index.html" else "index_${parts.size + 1}.html"
                val result = File(folder, name)
                result.writeText(ArchiveExport.header(emptyList()))
                partSize = result.length(); parts += result; part = result
                return result
            }
            for ((position, record) in records.withIndex()) {
                currentCoroutineContext().ensureActive()
                progress.value = BackupProgress("正在导出动态", position + 1, records.size)
                val snapshot = store.open(record, readImages = format == ArchiveExportFormat.HtmlImages)
                val sources = index.sources(record.feed.id).map { if (it["kind"] == "local") "本地收藏" else "收藏夹「${it["collection_title"].orEmpty()}」" }.distinct()
                if (format == ArchiveExportFormat.Text) {
                    text.appendText(ArchiveExport.txt(snapshot, sources, replies)); continue
                }
                val images = linkedMapOf<String, String>()
                if (format == ArchiveExportFormat.HtmlImages && snapshot.imageQuality != BackupImageQuality.None) {
                    val localUrls = Regex("c001apk-backup://[a-f0-9]{64}/(?:image|missing)_[0-9]+\\.[a-zA-Z0-9]{1,8}").findAll(snapshot.feed.toString() + snapshot.replies.toString()).map { it.value }.distinct().toList()
                    var absent = false
                    localUrls.forEachIndexed { number, url ->
                        currentCoroutineContext().ensureActive()
                        val source = store.localMedia(url)
                        if (source == null) absent = true else {
                            val relative = "images/${snapshot.id}_${number + 1}.${source.extension}"
                            val target = File(folder, relative).apply { parentFile!!.mkdirs() }
                            source.inputStream().use { input -> target.outputStream().use { output -> copy(input, output) } }
                            images[url] = relative
                        }
                    }
                    if (absent) { missing++; missingPosts += snapshot.id to BackupText.title(snapshot) }
                } else if (format == ArchiveExportFormat.HtmlImages && snapshot.imageQuality == BackupImageQuality.None && snapshot.feed.toString().contains(BACKUP_MEDIA_SCHEME)) { missing++; missingPosts += snapshot.id to BackupText.title(snapshot) }
                var html = ArchiveExport.article(snapshot, sources, replies, images)
                if (html.toByteArray().size > volumeLimit / 2) {
                    // Exceptional oversized posts use bounded plain-text continuations.
                    val chunks = BackupText.body(snapshot, replies).chunked((volumeLimit / 24).coerceAtLeast(64).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    chunks.forEachIndexed { chunk, body ->
                        val continuation = "<article id=\"feed_${snapshot.id}_$chunk\"><h2>${ArchiveExport.escape(BackupText.title(snapshot))}（${chunk + 1}/${chunks.size}）</h2><pre>${ArchiveExport.escape(body)}</pre></article>"
                        if (part == null || partSize + continuation.toByteArray().size + 1024 > volumeLimit) newPart()
                        part!!.appendText(continuation); partSize += continuation.toByteArray().size
                        toc += "${part!!.name}#feed_${snapshot.id}_$chunk" to BackupText.title(snapshot)
                    }
                    images.values.distinct().chunked((volumeLimit / 512).coerceIn(1, 100).toInt()).forEachIndexed { gallery, paths ->
                        val fragment = paths.joinToString("", "<article id=\"feed_${snapshot.id}_images_$gallery\"><h2>图片</h2>", "</article>") { path -> "<img src=\"${ArchiveExport.escape(path)}\">" }
                        if (part == null || partSize + fragment.toByteArray().size + 1024 > volumeLimit) newPart()
                        part!!.appendText(fragment); partSize += fragment.toByteArray().size
                        toc += "${part!!.name}#feed_${snapshot.id}_images_$gallery" to "${BackupText.title(snapshot)}（图片）"
                    }
                } else {
                    if (part == null || partSize + html.toByteArray().size + 1024 > volumeLimit) newPart()
                    part!!.appendText(html); partSize += html.toByteArray().size
                    toc += "${part!!.name}#feed_${snapshot.id}" to BackupText.title(snapshot)
                }
            }
            if (format == ArchiveExportFormat.Text) return@withContext PreparedArchive(text, "text/plain", ".txt", 1, 0)
            if (missing > 0) {
                val summary = missingPosts.joinToString("", "<h2>缺图动态</h2><ul>", "</ul>") { (id, title) -> "<li>${ArchiveExport.escape(title)}（$id）</li>" }
                if (part == null || partSize + summary.toByteArray().size + 1024 > volumeLimit) newPart()
                part?.appendText(summary)
            }
            part?.appendText(ArchiveExport.footer)
            // A single HTML file contains its own TOC. Split output needs a ZIP
            // because CreateDocument grants access to one document, not siblings.
            val singleHeader = ArchiveExport.header(toc.map { (path, title) -> "#${path.substringAfter('#')}" to title })
            val emptyHeaderSize = ArchiveExport.header(emptyList()).toByteArray().size
            if (parts.size == 1 && parts.single().length() - emptyHeaderSize + singleHeader.toByteArray().size <= volumeLimit) {
                val file = parts.single()
                val temporary = File(folder, "html.tmp")
                temporary.outputStream().use { output ->
                    output.write(singleHeader.toByteArray())
                    file.inputStream().use { input -> check(input.skip(emptyHeaderSize.toLong()) == emptyHeaderSize.toLong()); copy(input, output) }
                }
                check(temporary.renameTo(file))
            } else {
                val first = parts.first()
                val renamed = File(folder, "index_1.html"); check(first.renameTo(renamed)); parts[0] = renamed
                File(folder, "index.html").writeText(ArchiveExport.header(toc.map { (target, title) -> target.replace("index.html#", "index_1.html#") to title }) + ArchiveExport.footer)
                parts += File(folder, "index.html")
            }
            if (format == ArchiveExportFormat.Html && parts.size == 1) return@withContext PreparedArchive(parts.single(), "text/html", ".html", 1, missing)
            val zip = File(folder, "archive.zip")
            ZipOutputStream(zip.outputStream().buffered()).use { output ->
                (parts + File(folder, "images").walkTopDown().filter { it.isFile }.toList()).distinct().forEach { file ->
                    currentCoroutineContext().ensureActive()
                    val crc = CRC32()
                    file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break; crc.update(buffer, 0, count) } }
                    output.putNextEntry(ZipEntry(file.relativeTo(folder).invariantSeparatorsPath).apply { method = ZipEntry.STORED; size = file.length(); compressedSize = size; this.crc = crc.value })
                    file.inputStream().use { copy(it, output) }; output.closeEntry()
                }
            }
            PreparedArchive(zip, "application/zip", ".zip", parts.size, missing)
        } catch (e: Throwable) { folder.deleteRecursively(); throw e }
        finally { progress.value = null }
    }
    suspend fun write(prepared: PreparedArchive, uri: Uri) = withContext(Dispatchers.IO) {
        progress.value = BackupProgress("正在写入导出文件")
        try {
            val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入所选文件")
            output.use { out -> prepared.file.inputStream().use { copy(it, out) } }
            currentCoroutineContext().ensureActive()
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                val removed = runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
                if (!removed) runCatching { context.contentResolver.delete(uri, null, null) }
            }
            throw e
        } finally { progress.value = null; discard(prepared) }
    }
    fun discard(prepared: PreparedArchive) { prepared.file.parentFile?.deleteRecursively() }
    private suspend fun copy(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(65536)
        while (true) { currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) return; output.write(buffer, 0, count) }
    }
}
