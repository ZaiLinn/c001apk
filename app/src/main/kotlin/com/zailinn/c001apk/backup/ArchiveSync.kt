package com.zailinn.c001apk.backup

import com.zailinn.c001apk.core.backup.*
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.net.*
import com.zailinn.c001apk.data.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*

interface ArchiveClock {
    fun now(): Long
    suspend fun wait(milliseconds: Long)
}
private object RealArchiveClock : ArchiveClock {
    override fun now() = System.currentTimeMillis()
    override suspend fun wait(milliseconds: Long) { delay(milliseconds) }
}
@Serializable data class ArchiveCollection(val id: String, val title: String, val count: Int = 0, val readable: Boolean = true)
data class ArchiveSyncStatus(val phase: String = "待开始", val running: Boolean = false, val paused: Boolean = false,
    val captcha: Boolean = false, val completed: Int = 0, val total: Int = 0, val added: Int = 0, val updated: Int = 0,
    val skipped: Int = 0, val failed: Int = 0, val removed: Int = 0, val etaSeconds: Long = 0, val imagesDeferred: Boolean = false)

/** One foreground-process worker. Only public metadata and task state are persisted. */
class ArchiveSync(
    private val repository: CoolapkRepository, private val store: FeedBackupStore, private val index: ArchiveIndex,
    private val scope: CoroutineScope, private val account: () -> String, private val settings: () -> AppSettings,
    private val wifi: () -> Boolean, private val toast: (String) -> Unit,
    private val write: suspend (ArchiveQueueItem, BackupOptions, suspend () -> Unit, (BackupProgress) -> Unit) -> Unit = { item, options, before, report ->
        store.save(item.id, replace = true, options = options, fingerprint = item.fingerprint, verify = false, report = report, beforeRequest = before)
        Unit
    },
    private val clock: ArchiveClock = RealArchiveClock, private val spacing: () -> Long = { kotlin.random.Random.nextLong(3000, 5001) },
) {
    val status = MutableStateFlow(ArchiveSyncStatus())
    val collections = MutableStateFlow<List<ArchiveCollection>>(emptyList())
    val directoryNeeded = MutableStateFlow(false)
    @Volatile private var task: Job? = null
    private val durations = ArrayDeque<Long>()
    private val initialization = scope.async(Dispatchers.IO) {
        store.awaitReady()
        if (store.directory.value.isNotEmpty()) index.directory(store.directory.value)
        status.value = status.value.copy(added = index.meta("sync_added")?.toIntOrNull() ?: 0,
            updated = index.meta("sync_updated")?.toIntOrNull() ?: 0, skipped = index.meta("sync_skipped")?.toIntOrNull() ?: 0,
            removed = index.meta("sync_removed")?.toIntOrNull() ?: 0)
        index.recoverQueue(); refresh()
        if (index.meta("queue_captcha") == "1") status.value = status.value.copy(captcha = true, paused = true, phase = "需要完成验证后继续")
    }
    suspend fun awaitReady() { initialization.await() }
    fun refresh() {
        val rows = index.queue()
        status.value = status.value.copy(completed = rows.count { it.state in listOf("done", "skipped", "failed") }, total = rows.size,
            failed = rows.count { it.state == "failed" }, paused = (rows.any { it.state == "pending" } || index.meta("cloud_selection").orEmpty().isNotEmpty()) && !status.value.running)
    }
    private fun rememberCaptcha(error: Exception) {
        if (error is ArchiveCaptchaException) index.meta("captcha_request", error.requestUrl)
        index.meta("queue_captcha", "1")
    }
    private fun saveSummary() {
        index.meta("sync_added", "${status.value.added}"); index.meta("sync_updated", "${status.value.updated}")
        index.meta("sync_skipped", "${status.value.skipped}"); index.meta("sync_removed", "${status.value.removed}")
    }
    private fun resetSummary() { status.value = ArchiveSyncStatus(); saveSummary() }
    private fun owner(): String = index.meta("queue_account").orEmpty()
    private fun checkAccount() { if (owner().isNotEmpty() && owner() != account()) throw CancellationException("账号已切换，同步已暂停") }
    private suspend fun pace() {
        checkAccount()
        val last = index.meta("last_archive_request")?.toLongOrNull()
        if (last != null) clock.wait((spacing() - (clock.now() - last)).coerceAtLeast(0))
        checkAccount()
        index.meta("last_archive_request", clock.now().toString())
    }
    suspend fun loadCollections() = withContext(Dispatchers.IO) {
        require(account().isNotEmpty()) { "请先登录" }
        awaitReady()
        val found = linkedMapOf<String, ArchiveCollection>()
        var error: Exception? = null
        for (show in listOf(false, true)) {
            var last: String? = null
            var previous = ""
            try {
                for (page in 1..500) {
                    val rows = repository.archiveCollections(show, Page(page, lastItem = last))
                    val signature = rows.joinToString { it.id.orEmpty() }
                    if (rows.isEmpty() || signature == previous || rows.any { it.entityType == "noMoreDataCard" }) {
                        rows.filter { it.entityType != "noMoreDataCard" }.forEach { row -> row.id?.let { found[it] = ArchiveCollection(it, row.title.orEmpty(), row.int("item_num") ?: 0) } }
                        break
                    }
                    rows.forEach { row -> row.id?.let { found[it] = ArchiveCollection(it, row.title.orEmpty(), row.int("item_num") ?: 0) } }
                    previous = signature; last = rows.lastOrNull()?.id
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e; if (ApiFailure.from(e).kind == ApiFailureKind.Captcha) { rememberCaptcha(e); break } }
        }
        collections.value = found.values.toList()
        if (error != null && (found.isEmpty() || ApiFailure.from(error).kind == ApiFailureKind.Captcha)) throw error
    }
    suspend fun startCloud(selected: List<ArchiveCollection>, options: BackupOptions, force: Boolean = false) = withContext(Dispatchers.IO) {
        awaitReady()
        if (task?.isActive == true || store.busy) return@withContext toast("请先完成当前备份")
        if (index.meta("queue_captcha") == "1") return@withContext toast("请先完成验证")
        if (account().isEmpty()) return@withContext toast("请先登录")
        if (store.directory.value.isEmpty()) return@withContext toast("请先选择备份目录")
        if (index.queue().any { it.state == "pending" }) return@withContext toast("上次同步未完成，请先继续或取消")
        index.directory(store.directory.value)
        index.meta("queue_paused", "0")
        index.clearFinishedQueue(); index.meta("queue_account", account())
        index.meta("cloud_selection", FeedBackup.json.encodeToString(ListSerializer(ArchiveCollection.serializer()), selected))
        index.meta("cloud_options", FeedBackup.json.encodeToString(BackupOptions.serializer(), options))
        index.meta("cloud_force", force.toString()); index.meta("cloud_cursor", "0")
        resetSummary()
        launchWorker()
    }
    suspend fun startLocal(ids: List<String>, options: BackupOptions) = withContext(Dispatchers.IO) {
        awaitReady()
        if (task?.isActive == true || store.busy) return@withContext toast("请先完成当前备份")
        if (index.meta("queue_captcha") == "1") return@withContext toast("请先完成验证")
        if (store.directory.value.isEmpty()) return@withContext toast("请先选择备份目录")
        if (index.queue().any { it.state == "pending" }) return@withContext toast("上次同步未完成，请先继续或取消")
        index.directory(store.directory.value)
        index.meta("queue_paused", "0")
        index.clearFinishedQueue(); resetSummary(); index.meta("queue_account", account()); index.meta("cloud_selection", "")
        ids.distinct().forEach { index.enqueue(it, options, ""); index.source(it, "local") }
        launchWorker()
    }
    private fun needs(row: Map<String, String>?, fingerprint: String, options: BackupOptions, force: Boolean): Boolean {
        if (row == null || force) return true
        if (fingerprint.isNotEmpty() && row["list_fingerprint"].orEmpty().isNotEmpty() && row["list_fingerprint"] != fingerprint) return true
        val old = runCatching { BackupImageQuality.valueOf(row["image_quality"].orEmpty()) }.getOrDefault(BackupImageQuality.None)
        return old.ordinal < options.images.ordinal || (options.replies && row["includes_replies"] != "1")
    }
    private suspend fun discover() {
        val raw = index.meta("cloud_selection").orEmpty(); if (raw.isEmpty()) return
        val selected = FeedBackup.json.decodeFromString(ListSerializer(ArchiveCollection.serializer()), raw)
        val options = FeedBackup.json.decodeFromString(BackupOptions.serializer(), index.meta("cloud_options")!!)
        val force = index.meta("cloud_force") == "true"
        var cursor = index.meta("cloud_cursor")?.toIntOrNull() ?: 0
        while (cursor < selected.size) {
            checkAccount()
            val collection = selected[cursor]
            status.value = status.value.copy(phase = "读取收藏列表：${collection.title}")
            val checkpoint = "cloud_scan_${owner()}_${collection.id}"
            var page = index.meta("${checkpoint}_page")?.toIntOrNull() ?: 1
            var last = index.meta("${checkpoint}_last")?.takeIf { it.isNotEmpty() }
            val seen = index.meta("${checkpoint}_seen")?.takeIf { it.isNotEmpty() }?.let { FeedBackup.json.decodeFromString(ListSerializer(String.serializer()), it).toMutableSet() } ?: mutableSetOf()
            var previous = index.meta("${checkpoint}_previous").orEmpty()
            var complete = false
            try {
                while (page <= 500) {
                    pace()
                    val rows = repository.collectionItems(collection.id, Page(page, lastItem = last))
                    val feeds = rows.filter { it.entityType == "feed" && it.id?.matches(Regex("[0-9]{1,30}")) == true }
                    val signature = java.security.MessageDigest.getInstance("SHA-256").digest(rows.joinToString { it.json.toString() }.toByteArray()).joinToString("") { "%02x".format(it) }
                    if (rows.isNotEmpty() && signature == previous) { complete = true; break }
                    feeds.forEach { feed ->
                        val id = feed.id!!; seen += id
                        index.source(id, "cloud", owner(), collection.id, collection.title)
                        val fingerprint = BackupText.fingerprint(feed)
                        if (needs(index.row(id), fingerprint, options, force)) index.enqueue(id, options, fingerprint)
                        else {
                            if (index.row(id)?.get("list_fingerprint").isNullOrEmpty()) index.fingerprint(id, fingerprint)
                            status.value = status.value.copy(skipped = status.value.skipped + 1)
                        }
                    }
                    status.value = status.value.copy(total = index.queue().size, skipped = status.value.skipped + rows.count { it.entityType != "noMoreDataCard" && (it.entityType != "feed" || it.id?.matches(Regex("[0-9]{1,30}")) != true) })
                    if (rows.isEmpty() || rows.any { it.entityType == "noMoreDataCard" } || signature == previous) { complete = true; break }
                    previous = signature; last = feeds.lastOrNull()?.id ?: rows.lastOrNull()?.id; page++
                    index.meta("${checkpoint}_page", "$page"); index.meta("${checkpoint}_last", last.orEmpty())
                    index.meta("${checkpoint}_seen", FeedBackup.json.encodeToString(ListSerializer(String.serializer()), seen.toList()))
                    index.meta("${checkpoint}_previous", previous)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (ApiFailure.from(e).kind == ApiFailureKind.Captcha) throw e
                collections.value = collections.value.map { if (it.id == collection.id) it.copy(readable = false) else it }
            }
            if (complete) status.value = status.value.copy(removed = status.value.removed + index.markRemoved(owner(), collection.id, seen))
            listOf("page", "last", "seen", "previous").forEach { index.meta("${checkpoint}_$it", "") }
            saveSummary()
            cursor++; index.meta("cloud_cursor", "$cursor")
        }
        index.meta("cloud_selection", "")
    }
    private fun effectiveOptions(row: Map<String, String>?, requested: BackupOptions): BackupOptions {
        val previous = runCatching { BackupImageQuality.valueOf(row?.get("image_quality").orEmpty()) }.getOrDefault(BackupImageQuality.None)
        return BackupOptions(if (previous.ordinal > requested.images.ordinal) previous else requested.images,
            requested.replies || row?.get("includes_replies") == "1")
    }
    @Synchronized private fun launchWorker() {
        if (index.meta("queue_captcha") == "1" || status.value.captcha) return toast("请先完成验证")
        if (index.meta("queue_paused") == "1") return

        if (task?.isActive == true || store.busy) return toast("请先完成当前备份")
        if (owner().isNotEmpty() && owner() != account()) return toast("此队列属于其他账号，请切回原账号继续或取消旧队列")
        store.batchRunning = true
        index.meta("queue_cancelled", "0")
        status.value = status.value.copy(running = true, paused = false, captcha = false)
        val job = scope.launch(Dispatchers.IO) {
            var current: ArchiveQueueItem? = null
            try {
                checkAccount(); store.sync(); discover(); refresh()
                status.value = status.value.copy(running = true, paused = false)
                var processed = 0
                val deferredIds = mutableSetOf<String>()
                while (processed < 500) {
                    checkAccount()
                    current = index.queue().firstOrNull { it.state == "pending" && it.id !in deferredIds } ?: break
                    val item = current
                    val original = index.row(item.id)
                    var options = effectiveOptions(original, item.options)
                    val deferred = settings().archiveImagesWifiOnly && !wifi() && options.images != BackupImageQuality.None
                    if (deferred && original?.get("image_quality")?.let { it != "None" } == true) {
                        deferredIds += item.id
                        status.value = status.value.copy(imagesDeferred = true)
                        current = null
                        continue
                    }
                    if (deferred) { options = options.copy(images = BackupImageQuality.None); status.value = status.value.copy(imagesDeferred = true) }
                    if (index.meta("cloud_force") != "true" && item.fingerprint.isNotEmpty() && !needs(original, item.fingerprint, options, false)) {
                        index.fingerprint(item.id, item.fingerprint)
                        index.queueState(item.id, "skipped"); processed++; refresh(); continue
                    }
                    index.queueState(item.id, "running")
                    val began = clock.now()
                    var attempts = item.attempts
                    while (true) {
                        try {
                            val retryAt = index.meta("retry_after_${item.id}")?.toLongOrNull() ?: 0
                            if (retryAt > clock.now()) clock.wait(retryAt - clock.now())
                            checkAccount()
                            write(item, options, ::pace) { status.value = status.value.copy(phase = "备份动态：${it.message}") }
                            index.queueState(item.id, "done", attempts)
                            index.state(item.id, "ok")
                            status.value = if (original == null) status.value.copy(added = status.value.added + 1) else status.value.copy(updated = status.value.updated + 1)
                            break
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            val failure = ApiFailure.from(e)
                            if (failure.kind == ApiFailureKind.Captcha) throw e
                            if (failure.kind == ApiFailureKind.Transport && attempts < 3) {
                                attempts++; index.queueState(item.id, "running", attempts, failure.message)
                                val backoff = listOf(5000L, 20000L, 60000L)[attempts - 1]
                                index.meta("retry_after_${item.id}", (clock.now() + backoff).toString())
                                clock.wait(backoff); continue
                            }
                            index.queueState(item.id, "failed", attempts, failure.message)
                            if (ArchiveFailure.isGone(failure.kind, failure.message)) index.state(item.id, "gone")
                            break
                        }
                    }
                    durations.addLast((clock.now() - began).coerceAtLeast(0)); if (durations.size > 10) durations.removeFirst()
                    processed++; current = null; saveSummary(); refresh()
                    val remaining = index.queue().count { it.state == "pending" }
                    status.value = status.value.copy(etaSeconds = if (durations.isEmpty()) 0 else durations.sum() / durations.size * remaining / 1000)
                    if (processed % 50 == 0 && remaining > 0) { status.value = status.value.copy(phase = "已处理 50 条，休息 30 秒"); clock.wait(30000); index.meta("last_archive_request", clock.now().toString()) }
                }
                val pending = index.queue().any { it.state == "pending" }
                status.value = status.value.copy(phase = if (deferredIds.isNotEmpty()) "等待 WiFi 后继续，保留已有图片和回复" else if (pending) "已达到单次 500 条上限，下次继续" else "同步完成", running = false, paused = pending)
            } catch (e: CancellationException) {
                val cancelled = index.meta("queue_cancelled") == "1"
                if (!cancelled) index.meta("queue_paused", "1")
                if (!cancelled) current?.let { index.queueState(it.id, "pending") }
                status.value = status.value.copy(phase = if (cancelled) "已取消" else "已暂停，可继续", running = false, paused = !cancelled)
            } catch (e: Exception) {
                current?.let { index.queueState(it.id, "pending") }
                val captcha = ApiFailure.from(e).kind == ApiFailureKind.Captcha
                if (captcha) rememberCaptcha(e)
                status.value = status.value.copy(phase = if (captcha) "需要完成验证后继续" else ApiFailure.from(e).message, running = false, paused = true, captcha = captcha)
                index.meta("queue_captcha", if (captcha) "1" else "0")
            } finally { refresh() }
        }
        // Cleared only once the job is no longer active, so a start that follows sees the worker as finished.
        task = job
        job.invokeOnCompletion { store.batchRunning = false }
    }
    fun pause() { status.value = status.value.copy(paused = true); task?.cancel() }
    suspend fun resume() = withContext(Dispatchers.IO) {
        awaitReady()
        if (status.value.captcha || index.meta("queue_captcha") == "1") return@withContext toast("请先完成验证")
        index.meta("queue_paused", "0")
        launchWorker()
    }
    suspend fun cancel() = withContext(Dispatchers.IO) {
        awaitReady()
        index.meta("queue_cancelled", "1"); task?.cancelAndJoin()
        index.cancelQueue(); index.meta("cloud_selection", ""); index.meta("queue_captcha", "0"); index.meta("queue_paused", "0")
        status.value = ArchiveSyncStatus(phase = "已取消"); refresh()
    }
    suspend fun retryFailures() = withContext(Dispatchers.IO) {
        awaitReady()
        index.queue().filter { it.state == "failed" }.forEach { index.queueState(it.id, "pending", 0) }
        resume()
    }
    fun verify() {
        if (task?.isActive == true) return
        task = scope.launch(Dispatchers.IO) {
            try {
                awaitReady()
                checkAccount()
                val item = index.queue().firstOrNull { it.state == "pending" }
                val originalRequest = index.meta("captcha_request").orEmpty()
                if (originalRequest.isNotEmpty()) repository.verifyArchiveRequest(originalRequest)
                else if (item != null) repository.backupFeed(item.id, verify = true)
                else repository.verifyArchiveCollections()
                index.meta("queue_captcha", "0"); index.meta("captcha_request", ""); status.value = status.value.copy(captcha = false, phase = "验证完成，点击继续")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { toast("验证尚未通过，请稍后重试") }
        }
    }
    fun favoriteChanged(feedId: String, collection: String = "", title: String = "", collected: Boolean, local: Boolean = false) {
        val uid = account()
        scope.launch(Dispatchers.IO) {
            awaitReady()
            index.source(feedId, if (local) "local" else "cloud", if (local) "" else uid, collection, title, removed = !collected)
            val value = settings().archiveAutoBackup
            if (!collected || value == "Off") return@launch
            if (store.directory.value.isEmpty()) {
                if (index.meta("auto_directory_notice") != "1") { index.meta("auto_directory_notice", "1"); directoryNeeded.value = true }
                return@launch
            }
            if (index.queue().any { it.state == "pending" } && owner() != uid) return@launch
            val shouldStart = task?.isActive != true && !store.busy && !status.value.paused && !status.value.captcha &&
                index.meta("queue_paused") != "1" && index.meta("queue_captcha") != "1" &&
                index.queue().none { it.state in listOf("pending", "running") } && index.meta("cloud_selection").orEmpty().isEmpty()
            index.meta("queue_account", uid)
            val quality = runCatching { BackupImageQuality.valueOf(value) }.getOrDefault(BackupImageQuality.Original)
            index.enqueue(feedId, BackupOptions(quality, settings().archiveIncludeReplies), "")
            if (shouldStart) launchWorker()
        }
    }
}
