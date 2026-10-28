package com.zailinn.c001apk.backup

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zailinn.c001apk.core.backup.*
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.net.CoolapkRepository
import com.zailinn.c001apk.data.FeedRecord
import com.zailinn.c001apk.data.LocalStore
import com.zailinn.c001apk.ui.screens.other.GalleryFiles
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@Serializable
data class BackupRecord(val feed: FeedRecord, val base: String, val savedAt: Long, val warnings: List<String> = emptyList())
data class BackupProgress(val message: String, val done: Int = 0, val total: Int = 0)
data class BackupRequest(val id: String, val nonce: String = UUID.randomUUID().toString())

/** The selected SAF directory is authoritative. The local index can always be rebuilt from it. */
class FeedBackupStore internal constructor(
    context: Context,
    private val store: LocalStore,
    private val scope: CoroutineScope,
    private val repository: CoolapkRepository,
    private val gallery: GalleryFiles,
    private val toast: (String) -> Unit,
    val archiveIndex: ArchiveIndex = ArchiveIndex(context),
    private val directoryFactory: (String) -> BackupDirectory = { SafBackupDirectory(context, Uri.parse(it)) },
) {
    private val work = File(context.cacheDir, "feed-backup-work").apply { mkdirs() }
    private val media = File(context.cacheDir, "feed-backup-media").apply { mkdirs() }
    private val indexSerializer = ListSerializer(BackupRecord.serializer())
    private val selectedDirectory = MutableStateFlow(store.prefs.value[TREE].orEmpty())
    val directory: StateFlow<String> = selectedDirectory
    private val directoryRevision = MutableStateFlow(0L)
    val directoryChanges: StateFlow<Long> = directoryRevision
    private val index = MutableStateFlow<List<BackupRecord>>(emptyList())
    val records: StateFlow<List<BackupRecord>> = index
    val pending = MutableStateFlow<BackupRequest?>(null)
    val progress = MutableStateFlow<BackupProgress?>(null)
    private val mutex = Mutex()
    private var task: Job? = null
    @Volatile var batchRunning: Boolean = false
    val busy: Boolean get() = batchRunning || task?.isActive == true || pending.value != null
    private val initialization = scope.async(Dispatchers.IO) {
        val legacy = store.readJson(store.prefs.value, INDEX, indexSerializer, emptyList())
        if (legacy.isNotEmpty() && archiveIndex.records().isEmpty()) {
            legacy.forEach { record ->
                val data = kotlinx.serialization.json.buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive(record.feed.id))
                    put("uid", kotlinx.serialization.json.JsonPrimitive(record.feed.uid))
                    put("username", kotlinx.serialization.json.JsonPrimitive(record.feed.username))
                    put("message", kotlinx.serialization.json.JsonPrimitive(record.feed.message))
                    put("dateline", kotlinx.serialization.json.JsonPrimitive(record.feed.dateline))
                }
                archiveIndex.put(FeedBackup(data, savedAt = record.savedAt, warnings = listOf("索引待重建")), record.base, -1, -1)
            }
            archiveIndex.meta("needs_rebuild", "1")
        }
        index.value = archiveIndex.records()
    }
    suspend fun awaitReady() { initialization.await() }

    fun request(feed: Datum) {
        val id = backupFeedId(feed) ?: return toast("无法确定这条动态的编号")
        request(id)
    }
    fun request(id: String) {
        if (!id.matches(Regex("[0-9]{1,30}"))) return toast("动态编号无效")
        if (batchRunning || task?.isActive == true || progress.value != null || pending.value != null) return toast("请先完成当前备份")
        pending.value = BackupRequest(id)
    }
    suspend fun selectDirectory(value: String) = withContext(Dispatchers.IO) {
        awaitReady()
        check(!batchRunning && task?.isActive != true) { "请先完成当前备份" }
        val root = directoryFactory(value)
        root.list() // Verify access before replacing the working selection.
        mutex.withLock {
            store.edit { it[TREE] = value }
            selectedDirectory.value = value
            archiveIndex.directory(value)
            scan(root)
            directoryRevision.value++
        }
    }
    suspend fun hasBackup(id: String): Boolean = withContext(Dispatchers.IO) {
        awaitReady()
        directoryFactory(selected()).list().any { baseId(it.name) == id }
    }
    private fun selected() = directory.value.takeIf { it.isNotBlank() } ?: error("请先选择备份目录")
    suspend fun sync(full: Boolean = false): Int = withContext(Dispatchers.IO) {
        awaitReady()
        mutex.withLock {
            archiveIndex.directory(selected())
            if (full) archiveIndex.rebuild()
            scan(directoryFactory(selected()))
        }
    }
    private suspend fun scan(root: BackupDirectory): Int {
        val files = root.list().filter { baseId(it.name) != null }
        val names = files.map { it.name }.toSet()
        archiveIndex.retainDocuments(names)
        archiveIndex.records().filter { "${it.base}.json" !in names }.forEach { archiveIndex.remove(it.feed.id) }
        suspend fun read(document: BackupDocument) {
            currentCoroutineContext().ensureActive()
            val id = baseId(document.name) ?: return
            try {
                val backup = root.read(document.name).use { FeedBackup.parse(it.readBounded(MAX_JSON).decodeToString()) }
                require(backup.id == id)
                val saved = backup.savedAt.takeIf { it > 0 } ?: document.modified
                archiveIndex.document(document.name, id, document.modified, document.size, saved, true)
                val previous = archiveIndex.row(id)
                if (previous == null || saved >= previous.getValue("saved_at").toLong()) {
                    archiveIndex.put(backup, document.name.removeSuffix(".json"), document.modified, document.size)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                archiveIndex.document(document.name, id, document.modified, document.size, 0, false)
                if (archiveIndex.row(id)?.get("base") == document.name.removeSuffix(".json")) archiveIndex.remove(id)
            }
        }
        files.forEach { document ->
            val old = archiveIndex.document(document.name)
            if (old == null || old["modified"] != document.modified.toString() || old["size"] != document.size.toString()) read(document)
        }
        val indexedIds = archiveIndex.records().map { it.feed.id }.toSet()
        archiveIndex.documents().filter { it["valid"] == "1" && it["feed_id"] !in indexedIds }.groupBy { it.getValue("feed_id") }.values.forEach { versions ->
            val latest = versions.first().getValue("name")
            files.firstOrNull { it.name == latest }?.let { read(it) }
        }
        index.value = archiveIndex.records()
        // Old DataStore summaries are superseded only after the authoritative directory was scanned.
        store.edit { it.remove(androidx.datastore.preferences.core.stringPreferencesKey(INDEX)) }
        archiveIndex.meta("needs_rebuild", "0")
        return index.value.size
    }

    fun start(request: BackupRequest, replace: Boolean) {
        if (task?.isActive == true) return
        pending.value = null
        val tree = directory.value
        task = scope.launch {
            progress.value = BackupProgress("正在准备备份")
            try {
                val result = save(request.id, replace, tree, report = { progress.value = it })
                toast(if (result.warnings.isEmpty()) "备份成功" else "文字和已读取的内容已保存，备份有缺失，详情见“我的备份”")
            } catch (e: CancellationException) { toast("已取消备份"); throw e }
            catch (e: Exception) { toast("备份失败：${com.zailinn.c001apk.core.net.ApiFailure.from(e).message}，请检查备份目录权限") }
            finally { progress.value = null }
        }
    }
    fun cancel() { task?.cancel() }

    internal suspend fun save(id: String, replace: Boolean, tree: String = selected(), options: BackupOptions = BackupOptions(), fingerprint: String = "", verify: Boolean = true, report: (BackupProgress) -> Unit = {}, beforeRequest: suspend () -> Unit = {}): FeedBackup = withContext(Dispatchers.IO) {
        awaitReady()
        mutex.withLock {
            val root = directoryFactory(tree)
            val existing = root.list()
            val previous = existing.filter { baseId(it.name) == id }.map { it.name.removeSuffix(".json") }
            val canonical = "feed_$id"
            val base = if (existing.none { it.name == "$canonical.json" || it.name == "$canonical.zip" }) canonical
                else "${canonical}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
            val stage = File(work, UUID.randomUUID().toString()).apply { mkdirs() }
            var zipPublished = false
            var jsonPublished = false
            try {
                val source = FeedBackupCollector(repository).collect(id, options, verify, beforeRequest) { report(BackupProgress(it)) }
                // Stage the text first. Image failures still produce a readable, explicitly partial archive.
                val json = File(stage, "$base.json")
                json.writeText(FeedBackup.json.encodeToString(FeedBackup.serializer(), source))
                val warnings = source.warnings.toMutableList()
                val allUrls = if (options.images == BackupImageQuality.None) emptyList() else backupImageUrls(source)
                val urls = allUrls.take(20_000)
                if (allUrls.size > urls.size) warnings += "图片数量超过上限，只保存前 20000 张"
                val images = mutableListOf<BackupImage>()
                val avatars = backupAvatarUrls(source)
                var total = 0L
                val zip = File(stage, "$base.zip")
                ZipOutputStream(zip.outputStream().buffered()).use { output ->
                    urls.forEachIndexed { index, url ->
                        currentCoroutineContext().ensureActive()
                        report(BackupProgress("正在保存图片", index + 1, urls.size))
                        val fallback = BackupImage(url, "image_${index + 1}.jpg")
                        var writingEntry = false
                        try {
                            val avatar = url in avatars
                            val savedUrl = if (options.images == BackupImageQuality.Preview || avatar) com.zailinn.c001apk.core.model.GalleryMedia.fromUrl(url).preview else url
                            val file = gallery.image(savedUrl)
                            if (options.images == BackupImageQuality.Preview && !url.startsWith("https://image.coolapk.com/")) require(file.length() <= 2 * 1024 * 1024)
                            require(total + file.length() <= MAX_TOTAL) { "图片总大小超过备份上限" }
                            val name = "image_${index + 1}${imageSuffix(file)}"
                            val crc = CRC32()
                            val sha = MessageDigest.getInstance("SHA-256")
                            file.inputStream().use { input -> input.copyChecked { bytes, count -> crc.update(bytes, 0, count); sha.update(bytes, 0, count) } }
                            val image = BackupImage(url, name, file.length(), sha.digest().hex(), savedUrl)
                            writingEntry = true
                            output.putNextEntry(ZipEntry(name).apply { method = ZipEntry.STORED; size = image.size; compressedSize = size; this.crc = crc.value })
                            file.inputStream().use { input -> input.copyCancellable(output) }
                            output.closeEntry()
                            images += image
                            total += file.length()
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            if (writingEntry) throw e
                            images += fallback; warnings += "第 ${index + 1} 张图片未保存"
                        }
                    }
                }
                val complete = source.copy(images = images, warnings = warnings.distinct(), listFingerprint = fingerprint)
                json.writeText(FeedBackup.json.encodeToString(FeedBackup.serializer(), complete))
                require(json.length() <= MAX_JSON) { "动态数据过大" }
                // Publish JSON last: scanning cannot discover an unfinished pair.
                root.write("$base.zip", zip); zipPublished = true
                currentCoroutineContext().ensureActive()
                root.write("$base.json", json); jsonPublished = true
                currentCoroutineContext().ensureActive()
                if (replace) previous.filter { it != base }.forEach { old -> root.delete("$old.json"); root.delete("$old.zip") }
                scan(root)
                complete
            } finally {
                if (!jsonPublished && zipPublished) withContext(NonCancellable) { runCatching { root.delete("$base.zip") } }
                stage.deleteRecursively()
            }
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        awaitReady()
        mutex.withLock {
            val root = directoryFactory(selected())
            root.list().filter { baseId(it.name) == id }.forEach { file ->
                check(root.delete(file.name)) { "无法删除备份，请检查目录权限" }
                check(root.delete(file.name.removeSuffix(".json") + ".zip")) { "无法删除图片备份" }
            }
            scan(root)
        }
    }

    suspend fun open(record: BackupRecord, readImages: Boolean = true): FeedBackup = withContext(Dispatchers.IO) {
        awaitReady()
        mutex.withLock {
            val root = directoryFactory(selected())
            val backup = root.read("${record.base}.json").use { FeedBackup.parse(it.readBounded(MAX_JSON).decodeToString()) }
            require(backup.id == record.feed.id) { "备份编号不匹配，请同步备份列表" }
            if (!readImages) return@withLock backup
            val key = MessageDigest.getInstance("SHA-256").digest((selected() + record.base + backup.savedAt).toByteArray()).hex()
            val folder = File(media, key).apply { mkdirs() }
            val warnings = backup.warnings.toMutableList()
            val urls = backupImageUrls(backup)
            val zipFile = File(work, UUID.randomUUID().toString() + ".zip")
            val mapped = mutableMapOf<String, String>()
            try {
                val listed = root.list()
                if (backup.formatVersion == 0 && urls.isNotEmpty()) warnings += "旧备份未记录图片映射，按迁移说明的收集顺序恢复图片"
                if (listed.any { it.name == "${record.base}.zip" }) {
                    root.read("${record.base}.zip").use { input -> zipFile.outputStream().use { input.copyCancellable(it, MAX_TOTAL + 32 * 1024 * 1024) } }
                    ZipFile(zipFile).use { archive ->
                        var extracted = 0L
                        val images = if (backup.imageQuality == BackupImageQuality.None) emptyList() else if (backup.images.isNotEmpty()) backup.images else legacyBackupImageUrls(backup).mapIndexed { index, url ->
                            val name = archive.entries().asSequence().firstOrNull { it.name.matches(Regex("(?:images/)?image_${index + 1}\\.[a-zA-Z0-9]+")) }?.name ?: "image_${index + 1}.jpg"
                            BackupImage(url, name)
                        }
                        for (image in images) {
                            currentCoroutineContext().ensureActive()
                            val url = backupImageUrl(image.url) ?: continue
                            // ZIP paths never become filesystem paths. Only an app-generated basename is used.
                            val localName = "image_${mapped.size + 1}." + image.name.substringAfterLast('.', "jpg").takeIf { it.matches(Regex("[a-zA-Z0-9]{1,8}")) }.orEmpty().ifEmpty { "jpg" }
                            val target = File(folder, localName)
                            mapped[url] = "$BACKUP_MEDIA_SCHEME$key/$localName"
                            try {
                                val entry = archive.getEntry(image.name) ?: error("图片缺失")
                                require(!entry.isDirectory && entry.size in 1..MAX_IMAGE && extracted + entry.size <= MAX_TOTAL)
                                val partial = File(folder, "$localName.part")
                                try {
                                    val crc = CRC32(); val sha = MessageDigest.getInstance("SHA-256")
                                    archive.getInputStream(entry).use { input -> partial.outputStream().use { output ->
                                        input.copyChecked(MAX_IMAGE) { bytes, count -> output.write(bytes, 0, count); crc.update(bytes, 0, count); sha.update(bytes, 0, count) }
                                    } }
                                    check(crc.value == entry.crc)
                                    if (image.sha256.isNotEmpty()) check(sha.digest().hex() == image.sha256 && partial.length() == image.size)
                                    check(partial.renameTo(target))
                                    extracted += target.length()
                                } finally { partial.delete() }
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { target.delete(); warnings += "部分备份图片缺失或损坏" }
                        }
                    }
                } else if (backup.imageQuality != BackupImageQuality.None) warnings += "图片 ZIP 不存在，仅能查看文字与回复"
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { warnings += "图片 ZIP 无法读取，仅能查看文字与回复" }
            finally { zipFile.delete() }
            urls.forEachIndexed { index, url -> mapped.putIfAbsent(url, "$BACKUP_MEDIA_SCHEME$key/missing_$index.jpg") }
            offlineBackup(backup, mapped).copy(warnings = warnings.distinct())
        }
    }

    suspend fun estimateExport(ids: Set<String>, images: Boolean): Long = withContext(Dispatchers.IO) {
        awaitReady()
        mutex.withLock {
            val bases = archiveIndex.records().filter { it.feed.id in ids }.map { it.base }.toSet()
            directoryFactory(selected()).list().filter { file ->
                file.name.removeSuffix(".json").removeSuffix(".zip") in bases && (images || file.name.endsWith(".json"))
            }.sumOf { it.size.coerceAtLeast(0) }.let { size -> if (images) size * 3 else size * 4 }
        }
    }
    /** An internal URL can resolve only inside this app's extracted backup cache. */
    fun localMedia(value: String): File? {
        if (!value.startsWith(BACKUP_MEDIA_SCHEME)) return null
        val path = value.removePrefix(BACKUP_MEDIA_SCHEME)
        if (!path.matches(Regex("[a-f0-9]{64}/(?:image_[0-9]+|missing_[0-9]+)\\.[a-zA-Z0-9]{1,8}"))) return null
        return File(media, path).takeIf { it.isFile }
    }

    companion object {
        private val TREE = stringPreferencesKey("backupTreeUri")
        private const val INDEX = "feedBackupsV1"
        private const val MAX_JSON = 32 * 1024 * 1024L
        private const val MAX_IMAGE = 64 * 1024 * 1024L
        private const val MAX_TOTAL = 512 * 1024 * 1024L
        private fun baseId(name: String): String? = Regex("^feed_([0-9]{1,30})(?:_[0-9]+(?:_[a-f0-9]+)?)?\\.json$").matchEntire(name)?.groupValues?.get(1)
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        private suspend fun InputStream.copyChecked(limit: Long = Long.MAX_VALUE, block: (ByteArray, Int) -> Unit) {
            val buffer = ByteArray(64 * 1024); var size = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = read(buffer); if (count < 0) break
                size += count; require(size <= limit) { "备份文件过大" }
                block(buffer, count)
            }
        }
        private suspend fun InputStream.copyCancellable(output: OutputStream, limit: Long = Long.MAX_VALUE) = copyChecked(limit) { buffer, count -> output.write(buffer, 0, count) }
        private suspend fun InputStream.readBounded(limit: Long): ByteArray = java.io.ByteArrayOutputStream().use { output -> copyCancellable(output, limit); output.toByteArray() }
        private fun imageSuffix(file: File): String {
            val header = ByteArray(minOf(12, file.length().toInt()))
            java.io.DataInputStream(file.inputStream()).use { it.readFully(header) }
            return when {
                header.size >= 2 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() -> ".jpg"
                header.size >= 4 && header[0] == 0x89.toByte() && header[1] == 0x50.toByte() -> ".png"
                String(header.take(3).toByteArray()) == "GIF" -> ".gif"
                header.size >= 12 && String(header, 8, 4) == "WEBP" -> ".webp"
                header.size >= 2 && String(header, 0, 2) == "BM" -> ".bmp"
                header.size >= 12 && String(header, 4, 4) == "ftyp" && String(header, 8, 4) in setOf("avif", "avis") -> ".avif"
                header.size >= 12 && String(header, 4, 4) == "ftyp" && String(header, 8, 4) in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1") -> ".heic"
                else -> error("图片格式无效")
            }
        }
    }
}
