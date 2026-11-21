package com.zailinn.c001apk.backup

import com.zailinn.c001apk.core.backup.*
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.net.*
import com.zailinn.c001apk.data.AppSettings
import com.zailinn.c001apk.data.backupOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
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
    val skipped: Int = 0, val failed: Int = 0, val removed: Int = 0, val etaSeconds: Long = 0, val imagesDeferred: Boolean = false,
    val verifying: Boolean = false, val needsIdentity: Boolean = false)

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
    private var autoStart: Job? = null
    private data class FavoriteSource(val id: String, val collection: String, val local: Boolean, val account: String)
    private val awaitingDirectory = mutableMapOf<FavoriteSource, String>()
    private val durations = ArrayDeque<Long>()
    private val initialization = scope.async(Dispatchers.IO) {
        store.awaitReady()
        if (store.directory.value.isNotEmpty()) index.directory(store.directory.value)
        status.value = status.value.copy(added = index.meta("sync_added")?.toIntOrNull() ?: 0,
            updated = index.meta("sync_updated")?.toIntOrNull() ?: 0, skipped = index.meta("sync_skipped")?.toIntOrNull() ?: 0,
            removed = index.meta("sync_removed")?.toIntOrNull() ?: 0)
        index.recoverQueue(); refresh()
        if (index.meta("queue_captcha") == "1" && index.meta("captcha_request").orEmpty().contains("/v6/feed/")) {
            // Old queues may be paused on a reply request. New backups never fetch replies.
            index.meta("queue_captcha", "0"); index.meta("captcha_request", "")
            status.value = status.value.copy(paused = true, phase = "已改为仅备份帖子，可继续")
        } else if (index.meta("queue_captcha") == "1") status.value = status.value.copy(captcha = true, paused = true, phase = captchaMessage())
        if (index.meta("queue_identity") == "1") status.value = status.value.copy(needsIdentity = true, paused = true, phase = "帖子正文受限，请填写数盟 ID 后重试")
    }
    suspend fun awaitReady() { initialization.await() }
    init {
        scope.launch(Dispatchers.IO) {
            store.directoryChanges.collect {
                synchronized(this@ArchiveSync) {
                    if (store.directory.value.isEmpty()) return@synchronized
                    val deferred = awaitingDirectory.toMap()
                    awaitingDirectory.clear()
                    deferred.forEach { (favorite, title) ->
                        if (favorite.account == account()) updateFavorite(favorite, title, collected = true)
                    }
                }
            }
        }
    }
    fun refresh() {
        val rows = index.queue()
        status.value = status.value.copy(completed = rows.count { it.state in listOf("done", "skipped", "failed") }, total = rows.size,
            failed = rows.count { it.state == "failed" }, paused = (rows.any { it.state == "pending" } || index.meta("cloud_selection").orEmpty().isNotEmpty()) && !status.value.running)
    }
    private fun captchaMessage(): String = index.meta("captcha_phase")?.takeIf { it.isNotEmpty() }
        ?.let { "$it：接口要求人机验证，完成后可继续" } ?: "接口要求人机验证，完成后可继续"
    private fun rememberCaptcha(error: Exception, phase: String = status.value.phase) {
        if (error is ArchiveCaptchaException) index.meta("captcha_request", error.requestUrl)
        index.meta("captcha_phase", phase)
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
    private suspend fun <T> readCloud(load: suspend () -> T): T {
        for (attempt in 0..2) {
            pace()
            try { return load() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (ApiFailure.from(e).kind != ApiFailureKind.Transport || attempt == 2) throw e
                clock.wait(if (attempt == 0) 5000 else 20000)
            }
        }
        error("unreachable")
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
                    val rows = readCloud { repository.archiveCollections(show, Page(page, lastItem = last)) }
                    val signature = rows.joinToString { it.id.orEmpty() }
                    if (rows.isEmpty() || signature == previous || rows.any { it.entityType == "noMoreDataCard" }) {
                        rows.filter { it.entityType != "noMoreDataCard" }.forEach { row -> row.id?.let { found[it] = ArchiveCollection(it, row.title.orEmpty(), row.int("item_num") ?: 0) } }
                        break
                    }
                    rows.forEach { row -> row.id?.let { found[it] = ArchiveCollection(it, row.title.orEmpty(), row.int("item_num") ?: 0) } }
                    previous = signature; last = rows.lastOrNull()?.id
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e; if (ApiFailure.from(e).kind == ApiFailureKind.Captcha) { rememberCaptcha(e, "读取收藏夹列表"); break } }
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
        index.meta("cloud_read_failed", "0")
        resetSummary()
        launchWorker()
    }
    /** Local favorites have no list fingerprint: an existing backup that meets [options] is kept unless [force]. */
    suspend fun startLocal(ids: List<String>, options: BackupOptions, force: Boolean = false) = withContext(Dispatchers.IO) {
        awaitReady()
        if (task?.isActive == true || store.busy) return@withContext toast("请先完成当前备份")
        if (index.meta("queue_captcha") == "1") return@withContext toast("请先完成验证")
        if (store.directory.value.isEmpty()) return@withContext toast("请先选择备份目录")
        if (index.queue().any { it.state == "pending" }) return@withContext toast("上次同步未完成，请先继续或取消")
        index.directory(store.directory.value)
        index.meta("queue_paused", "0")
        index.clearFinishedQueue(); resetSummary(); index.meta("queue_account", account()); index.meta("cloud_selection", "")
        index.meta("cloud_read_failed", "0")
        // The folder decides what is already saved, not a possibly stale index.
        runCatching { store.sync() }.onFailure { if (it is CancellationException) throw it }
        ids.distinct().forEach { id ->
            index.source(id, "local")
            if (force || needs(index.row(id), "", options, false)) index.enqueue(id, options, "")
            else status.value = status.value.copy(skipped = status.value.skipped + 1)
        }
        saveSummary()
        launchWorker()
    }
    private fun needs(row: Map<String, String>?, fingerprint: String, options: BackupOptions, force: Boolean): Boolean {
        if (row == null || force) return true
        if (fingerprint.isNotEmpty() && row["list_fingerprint"].orEmpty().isNotEmpty() && row["list_fingerprint"] != fingerprint) return true
        val old = runCatching { BackupImageQuality.valueOf(row["image_quality"].orEmpty()) }.getOrDefault(BackupImageQuality.None)
        return old.ordinal < options.images.ordinal || (options.replies && options.commentLimit > 0 &&
            (row["includes_replies"] != "1" || row["comment_limit"]?.toIntOrNull() != options.commentLimit ||
                (row["comment_nested"] == "1") != options.includeSubReplies))
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
                    val rows = readCloud { repository.collectionItems(collection.id, Page(page, lastItem = last)) }
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
                index.meta("cloud_read_failed", "1")
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
            requested.replies && requested.commentLimit > 0, if (requested.replies) requested.commentLimit else 0, requested.includeSubReplies)
    }
    @Synchronized private fun launchWorker() {
        if (index.meta("queue_identity") == "1" || status.value.needsIdentity) return
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
                            // Every following item would fail the same way: pause instead.
                            if (e is BackupRestrictedException || e is BackupDirectoryException) throw e
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
                val cloudReadFailed = index.meta("cloud_read_failed") == "1"
                if (pending) index.meta("queue_paused", "1")
                else index.meta("cloud_read_failed", "0")
                val phase = when {
                    deferredIds.isNotEmpty() -> "等待 WiFi 后继续，保留已有图片和回复"
                    pending -> "已达到单次 500 条上限，下次继续"
                    cloudReadFailed -> "同步结束，部分收藏夹读取失败，请重试同步"
                    else -> "同步完成"
                }
                status.value = status.value.copy(phase = phase, running = false, paused = pending)
            } catch (e: CancellationException) {
                val cancelled = index.meta("queue_cancelled") == "1"
                if (!cancelled) index.meta("queue_paused", "1")
                if (!cancelled) current?.let { index.queueState(it.id, "pending") }
                status.value = status.value.copy(phase = if (cancelled) "已取消" else "已暂停，可继续", running = false, paused = !cancelled)
            } catch (e: BackupRestrictedException) {
                current?.let { index.queueState(it.id, "pending") }
                if (e.needsLogin) {
                    // A 数盟 ID cannot help a guest. Keep the queue; it continues after login.
                    index.meta("queue_paused", "1")
                    status.value = status.value.copy(phase = e.message.orEmpty(), running = false, paused = true)
                    store.requireIdentity(needsLogin = true) {}
                    return@launch
                }
                index.meta("queue_identity", "1")
                status.value = status.value.copy(phase = e.message.orEmpty(), running = false, paused = true, needsIdentity = true)
                requestIdentityRetry()
            } catch (e: Exception) {
                current?.let { index.queueState(it.id, "pending") }
                val captcha = ApiFailure.from(e).kind == ApiFailureKind.Captcha
                if (captcha) rememberCaptcha(e)
                if (e is BackupDirectoryException) index.meta("queue_paused", "1")
                status.value = status.value.copy(phase = if (captcha) captchaMessage() else backupFailureMessage(e), running = false, paused = true, captcha = captcha)
                index.meta("queue_captcha", if (captcha) "1" else "0")
            } finally { refresh() }
        }
        // Cleared only once the job is no longer active, so a start that follows sees the worker as finished.
        task = job
        job.invokeOnCompletion { store.batchRunning = false }
    }
    fun pause() { autoStart?.cancel(); index.meta("queue_paused", "1"); status.value = status.value.copy(paused = true); task?.cancel() }
    suspend fun resume() = withContext(Dispatchers.IO) {
        awaitReady()
        if (status.value.needsIdentity || index.meta("queue_identity") == "1") return@withContext requestIdentityRetry()
        if (status.value.captcha || index.meta("queue_captcha") == "1") return@withContext toast("请先完成验证")
        index.meta("queue_paused", "0")
        launchWorker()
    }
    suspend fun cancel() = withContext(Dispatchers.IO) {
        awaitReady()
        autoStart?.cancel()
        index.meta("queue_cancelled", "1"); task?.cancelAndJoin()
        if (status.value.needsIdentity) store.identityRequired.value = null
        index.meta("queue_identity", "0")
        index.cancelQueue(); index.meta("cloud_selection", ""); index.meta("queue_captcha", "0"); index.meta("queue_paused", "0")
        index.meta("cloud_read_failed", "0")
        status.value = ArchiveSyncStatus(phase = "已取消"); refresh()
    }
    suspend fun retryFailures() = withContext(Dispatchers.IO) {
        awaitReady()
        index.queue().filter { it.state == "failed" }.forEach { index.queueState(it.id, "pending", 0) }
        resume()
    }
    fun requestIdentityRetry() {
        store.requireIdentity {
            task?.join()
            index.meta("queue_identity", "0")
            status.value = status.value.copy(needsIdentity = false, phase = "已更新数盟 ID，正在重试")
            resume()
        }
    }
    fun verify() {
        if (task?.isActive == true) return
        status.value = status.value.copy(verifying = true)
        task = scope.launch(Dispatchers.IO) {
            try {
                awaitReady()
                checkAccount()
                val item = index.queue().firstOrNull { it.state == "pending" }
                val originalRequest = index.meta("captcha_request").orEmpty()
                if (originalRequest.isNotEmpty()) repository.verifyArchiveRequest(originalRequest)
                else if (item != null) repository.verifyArchiveRequest("https://api.coolapk.com/v6/feed/detail?id=${item.id}")
                else repository.verifyArchiveCollections()
                index.meta("queue_captcha", "0"); index.meta("captcha_request", ""); status.value = status.value.copy(captcha = false, phase = "验证完成，点击继续")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = "验证未完成：${ApiFailure.from(e).message}"
                status.value = status.value.copy(phase = message)
                toast(message)
            } finally { status.value = status.value.copy(verifying = false) }
        }
    }
    fun favoriteChanged(feedId: String, collection: String = "", title: String = "", collected: Boolean, local: Boolean = false) {
        val favorite = FavoriteSource(feedId, collection, local, account())
        scope.launch(Dispatchers.IO) {
            awaitReady()
            updateFavorite(favorite, title, collected)
        }
    }

    @Synchronized private fun updateFavorite(favorite: FavoriteSource, title: String, collected: Boolean) {
        val (feedId, collection, local, uid) = favorite
        val needsDirectory = store.directory.value.isEmpty()
        index.source(feedId, if (local) "local" else "cloud", if (local) "" else uid, collection, title, removed = !collected)
        val value = settings().archiveAutoBackup
        if (!collected || value == "Off") { awaitingDirectory.remove(favorite); return }
        if (needsDirectory) {
            awaitingDirectory[favorite] = title
            directoryNeeded.value = true
            return
        }
        if (index.queue().any { it.state in listOf("pending", "running") } && owner() != uid) return
        val quality = runCatching { BackupImageQuality.valueOf(value) }.getOrDefault(BackupImageQuality.Original)
        val options = settings().backupOptions(quality)
        // Adding a saved post to another collection must not download it again.
        if (!needs(index.row(feedId), "", options, false)) return
        val shouldStart = !status.value.paused && !status.value.captcha && !status.value.needsIdentity &&
            index.meta("queue_paused") != "1" && index.meta("queue_captcha") != "1" &&
            (autoStart?.isActive == true || task?.isActive == true || index.queue().none { it.state in listOf("pending", "running") })
        index.meta("queue_account", uid)
        index.enqueue(feedId, options, "")
        if (shouldStart) scheduleAutoStart()
        else toast("已加入备份队列，请在收藏同步中继续")
    }

    @Synchronized private fun scheduleAutoStart() {
        if (autoStart?.isActive == true) return
        autoStart = scope.launch(Dispatchers.IO) {
            // A manual backup or a worker finishing its last item can still own
            // the store. Keep the new favorite queued and start once it releases it.
            while (task?.isActive == true || store.busy) delay(100)
            if (index.meta("queue_paused") == "1" || index.meta("queue_captcha") == "1" || status.value.captcha ||
                index.meta("queue_identity") == "1" || status.value.needsIdentity) return@launch
            if (index.queue().any { it.state == "pending" }) launchWorker()
        }
    }
}
