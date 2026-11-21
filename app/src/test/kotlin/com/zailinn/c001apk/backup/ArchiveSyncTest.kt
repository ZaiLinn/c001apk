package com.zailinn.c001apk.backup

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.zailinn.c001apk.AppContainer
import com.zailinn.c001apk.core.backup.*
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.net.*
import com.zailinn.c001apk.data.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArchiveSyncTest {
    @get:Rule val folder = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val text = BackupOptions(BackupImageQuality.None, false)
    private class Clock : ArchiveClock {
        var time = 100_000L
        val waits = CopyOnWriteArrayList<Long>()
        override fun now() = time
        override suspend fun wait(milliseconds: Long) { waits += milliseconds; time += milliseconds; yield() }
    }
    private class Directory(val folder: File) : BackupDirectory {
        @Volatile var listDenied = false
        override fun list(): List<BackupDocument> {
            if (listDenied) throw SecurityException("synthetic revoked permission")
            return folder.listFiles().orEmpty().map { BackupDocument(it.name, it.length(), it.lastModified()) }
        }
        override fun read(name: String) = File(folder, name).inputStream()
        @Volatile var readOnly = false
        override fun write(name: String, source: File) {
            if (readOnly) throw java.io.IOException("synthetic revoked permission")
            source.copyTo(File(folder, name))
        }
        override fun delete(name: String) = File(folder, name).let { !it.exists() || it.delete() }
    }
    private inner class Harness : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val clock = Clock()
        val detailCalls = CopyOnWriteArrayList<Pair<String, Long>>()
        var feedIds = listOf("42", "43")
        var fingerprintText = "列表正文"
        var captcha = false
        var publicFeed = false
        var network = false
        var pageFailure = false
        var collectionFailures = 0
        val transport = Interceptor { chain ->
            val request = chain.request(); val path = request.url.encodedPath
            val body = when {
                request.url.host == "www.coolapk.com" && publicFeed -> """{"data":{"id":"42","entityType":"feed","message":"公开全文","replynum":0}}"""
                path.endsWith("/collection/list") -> {
                    if (collectionFailures > 0) { collectionFailures--; throw java.io.IOException("synthetic connection reset") }
                    if (request.url.queryParameter("page") != "1") """{"data":[]}""" else """{"data":[{"entityType":"collection","id":"101","title":"合成收藏夹","item_num":2}]}"""
                }
                path.endsWith("/collection/itemList") -> {
                    if (request.url.queryParameter("page") != "1") {
                        if (pageFailure) throw java.io.IOException("synthetic discovery failure")
                        """{"data":[]}"""
                    } else buildJsonObject { put("data", JsonArray(feedIds.map { id -> buildJsonObject { put("entityType", "feed"); put("id", id); put("message", fingerprintText); put("picArr", JsonArray(emptyList())) } })) }.toString()
                }
                path.endsWith("/feed/detail") -> {
                    val id = request.url.queryParameter("id")!!; detailCalls += id to clock.now()
                    if (network) throw java.io.IOException("synthetic offline")
                    if (captcha) """{"status":"err_request_captcha","message":"需要验证码"}"""
                    else """{"data":{"entityType":"feed","id":"$id","username":"合成作者","message":"全文$id","replynum":0}}"""
                }
                else -> """{"data":[]}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }
        val app = AppContainer(context, "archive-sync-${UUID.randomUUID()}", transport)
        val root = Directory(folder.newFolder())
        val store = FeedBackupStore(context, LocalStore(PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("${UUID.randomUUID()}.preferences_pb") }, scope),
            scope, app.repository, app.galleryFiles, {}, archiveIndex = app.archiveIndex, directoryFactory = { root })
        var config = AppSettings()
        fun sync() = ArchiveSync(app.repository, store, app.archiveIndex, scope, { "7" }, { config }, { true }, {}, clock = clock) { 3000 }
        suspend fun ready() { store.selectDirectory("synthetic-directory-${UUID.randomUUID()}") }
        suspend fun done(sync: ArchiveSync) {
            withTimeout(30_000) { while (sync.status.value.running || store.batchRunning) delay(2) }
        }
        override fun close() { scope.cancel(); app.scope.cancel() }
    }
    @Test fun manualFullBackupIsAdoptedWithoutNewFilesOrDetailRequestsAndNeverDowngraded() = runBlocking {
        Harness().use { h ->
            h.ready(); h.feedIds = listOf("42")
            h.store.save("42", replace = false)
            val originalNames = h.root.list().map { it.name }.toSet()
            val originalBase = h.store.records.value.single().base
            val calls = h.detailCalls.size
            val sync = h.sync(); sync.loadCollections()
            sync.startCloud(sync.collections.value, text); h.done(sync)
            assertEquals(calls, h.detailCalls.size)
            assertEquals(originalNames, h.root.list().map { it.name }.toSet())
            assertEquals(originalBase, h.store.records.value.single().base)
            assertTrue(h.app.archiveIndex.row("42")!!["list_fingerprint"].orEmpty().isNotEmpty())
            var snapshot = h.store.open(h.store.records.value.single())
            assertEquals(BackupImageQuality.Original, snapshot.imageQuality)
            assertFalse(snapshot.includesReplies)
            repeat(2) {
                sync.startCloud(sync.collections.value, text, force = true); h.done(sync)
                assertEquals(2, h.root.list().size)
                snapshot = h.store.open(h.store.records.value.single())
                assertEquals(BackupImageQuality.Original, snapshot.imageQuality)
                assertFalse(snapshot.includesReplies)
            }
            h.fingerprintText = "已编辑"
            sync.startCloud(sync.collections.value, text); h.done(sync)
            assertEquals(2, h.root.list().size)
            snapshot = h.store.open(h.store.records.value.single())
            assertEquals(BackupImageQuality.Original, snapshot.imageQuality)
            assertFalse(snapshot.includesReplies)
        }
    }

    @Test fun wifiOnlyWaitsRatherThanDowngradingAnExistingImageBackup() = runBlocking {
        Harness().use { h ->
            h.ready(); h.feedIds = listOf("42")
            h.store.save("42", replace = false)
            var wifi = false
            h.config = h.config.copy(archiveImagesWifiOnly = true)
            val sync = ArchiveSync(h.app.repository, h.store, h.app.archiveIndex, h.scope, { "7" }, { h.config }, { wifi }, {}, clock = h.clock, spacing = { 3000 })
            sync.loadCollections()
            val calls = h.detailCalls.size
            sync.startCloud(sync.collections.value, text, force = true); h.done(sync)
            assertEquals(calls, h.detailCalls.size)
            assertEquals(2, h.root.list().size)
            assertTrue(sync.status.value.paused)
            assertEquals("pending", h.app.archiveIndex.queue().single().state)
            wifi = true
            sync.resume(); h.done(sync)
            assertEquals(calls + 1, h.detailCalls.size)
            assertEquals(2, h.root.list().size)
            val snapshot = h.store.open(h.store.records.value.single())
            assertEquals(BackupImageQuality.Original, snapshot.imageQuality)
            assertFalse(snapshot.includesReplies)
        }
    }

    @Test fun automaticFavoritesDoNotResumeManualOrCaptchaPausedQueues() = runBlocking {
        Harness().use { h ->
            h.ready()
            h.config = h.config.copy(archiveAutoBackup = "None", archiveIncludeReplies = false)
            val entered = CompletableDeferred<Unit>()
            val sync = ArchiveSync(h.app.repository, h.store, h.app.archiveIndex, h.scope, { "7" }, { h.config }, { true }, {},
                write = { _, _, _, _ -> entered.complete(Unit); awaitCancellation() }, clock = h.clock)
            sync.startLocal(listOf("42", "43"), text)
            withTimeout(5000) { entered.await() }
            sync.pause(); h.done(sync)
            sync.favoriteChanged("44", collected = true, local = true)
            withTimeout(5000) { while (h.app.archiveIndex.queue().none { it.id == "44" }) delay(2) }
            assertTrue(sync.status.value.paused)
            assertFalse(sync.status.value.running)
            assertTrue(h.detailCalls.isEmpty())
            sync.cancel()

            h.captcha = true
            val captchaSync = h.sync()
            captchaSync.startLocal(listOf("42"), text); h.done(captchaSync)
            assertTrue(captchaSync.status.value.needsIdentity)
            val before = h.detailCalls.size
            captchaSync.favoriteChanged("45", collected = true, local = true)
            withTimeout(5000) { while (h.app.archiveIndex.queue().none { it.id == "45" }) delay(2) }
            assertEquals(before, h.detailCalls.size)
            assertTrue(captchaSync.status.value.needsIdentity)
            assertFalse(captchaSync.status.value.running)
            assertEquals("1", h.app.archiveIndex.meta("queue_identity"))
            captchaSync.resume()
            assertEquals(before, h.detailCalls.size)
        }
    }

    @Test fun discoversDeduplicatesSkipsUnchangedAndUpdatesFingerprints() = runBlocking {
        Harness().use { h ->
            h.ready(); val sync = h.sync(); sync.loadCollections()
            assertEquals(1, sync.collections.value.size)
            sync.startCloud(sync.collections.value, text); h.done(sync)
            assertEquals(2, h.detailCalls.size)
            assertEquals(2, h.store.records.value.size)
            assertTrue(h.detailCalls[1].second - h.detailCalls[0].second >= 3000)
            sync.startCloud(sync.collections.value, text); h.done(sync)
            assertEquals(2, h.detailCalls.size)
            sync.startCloud(sync.collections.value, text, force = true); h.done(sync)
            assertEquals(4, h.detailCalls.size)
            h.fingerprintText = "编辑后的正文"
            sync.startCloud(sync.collections.value, text); h.done(sync)
            assertEquals(6, h.detailCalls.size)
            h.feedIds = listOf("42")
            sync.startCloud(sync.collections.value, text); h.done(sync)
            assertTrue(h.app.archiveIndex.sources("43").any { it["removed_at"].orEmpty().isNotEmpty() })
            assertEquals(2, h.store.records.value.size)
        }
    }
    @Test fun incompleteCollectionDoesNotMarkMissingItemsRemoved() = runBlocking {
        Harness().use { h ->
            h.ready(); val sync = h.sync(); sync.loadCollections()
            sync.startCloud(sync.collections.value, text); h.done(sync)
            h.feedIds = listOf("42"); h.pageFailure = true
            sync.startCloud(sync.collections.value, text); h.done(sync)
            assertTrue(h.app.archiveIndex.sources("43").all { it["removed_at"].orEmpty().isEmpty() })
            assertTrue(sync.status.value.phase.contains("部分收藏夹读取失败"))
            assertTrue(sync.collections.value.single().readable)
            h.pageFailure = false
            sync.startCloud(sync.collections.value, text); h.done(sync)
            assertTrue(h.app.archiveIndex.sources("43").all { it["removed_at"].orEmpty().isNotEmpty() })
            assertEquals("同步完成", sync.status.value.phase)
        }
    }
    @Test fun captchaPausesAndProcessRecreationKeepsPendingTasks() = runBlocking {
        Harness().use { h ->
            h.ready(); val sync = h.sync(); h.captcha = true
            sync.startLocal(listOf("42", "43"), text); h.done(sync)
            assertTrue(sync.status.value.needsIdentity)
            assertEquals(1, h.detailCalls.size)
            assertEquals(2, h.app.archiveIndex.queue().count { it.state == "pending" })
            val restored = h.sync()
            restored.awaitReady()
            assertTrue(restored.status.value.needsIdentity)
            h.captcha = false
            h.app.archiveIndex.meta("queue_identity", "0"); restored.status.value = restored.status.value.copy(needsIdentity = false); h.store.identityRequired.value = null
            restored.resume(); h.done(restored)
            assertEquals(2, h.app.archiveIndex.queue().count { it.state == "done" })
        }
    }
    @Test fun networkRetriesThreeTimesThenContinuesWithOtherTasks() = runBlocking {
        Harness().use { h ->
            h.ready(); val sync = h.sync(); h.network = true
            sync.startLocal(listOf("42", "43"), text); h.done(sync)
            assertEquals(2, h.app.archiveIndex.queue().count { it.state == "failed" && it.attempts == 3 })
            assertEquals(8, h.detailCalls.size)
            assertTrue(h.clock.waits.containsAll(listOf(5000L, 20000L, 60000L)))
        }
    }
    @Test fun batchStopsAtFiveHundredAndRestsBetweenGroupsWithoutRealSleeping() = runBlocking {
        Harness().use { h ->
            h.ready()
            val requested = CopyOnWriteArrayList<Long>()
            val sync = ArchiveSync(h.app.repository, h.store, h.app.archiveIndex, h.scope, { "7" }, { AppSettings() }, { true }, {},
                write = { _, _, before, _ -> before(); requested += h.clock.now() }, clock = h.clock, spacing = { 3000 })
            sync.startLocal((1..501).map(Int::toString), text)
            h.done(sync)
            assertEquals(500, requested.size)
            assertTrue(sync.status.value.paused)
            assertEquals(1, h.app.archiveIndex.queue().count { it.state == "pending" })
            requested.zipWithNext().forEachIndexed { index, pair ->
                assertTrue(pair.toString(), pair.second - pair.first >= if ((index + 1) % 50 == 0) 33000 else 3000)
            }
            sync.resume(); h.done(sync)
            assertEquals(501, requested.size)
        }
    }

    @Test fun manualBackupIsRejectedWhileQueueIsWritingAndPauseCanResume() = runBlocking {
        Harness().use { h ->
            h.ready()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val sync = ArchiveSync(h.app.repository, h.store, h.app.archiveIndex, h.scope, { "7" }, { AppSettings() }, { true }, {},
                write = { _, _, _, _ -> if (!entered.isCompleted) entered.complete(Unit); release.await() }, clock = h.clock, spacing = { 3000 })
            sync.startLocal(listOf("42"), text)
            withTimeout(5000) { entered.await() }
            h.store.request("43")
            assertNull(h.store.pending.value)
            sync.pause(); h.done(sync)
            assertEquals("pending", h.app.archiveIndex.queue().single().state)
            release.complete(Unit)
            sync.resume(); h.done(sync)
            assertEquals("done", h.app.archiveIndex.queue().single().state)
        }
    }

    @Test fun qualityAndReplyUpgradesEnqueueEvenWhenListFingerprintIsUnchanged() = runBlocking {
        Harness().use { h ->
            h.ready(); val sync = h.sync(); sync.loadCollections()
            sync.startCloud(sync.collections.value, text); h.done(sync)
            sync.startCloud(sync.collections.value, text.copy(images = BackupImageQuality.Preview)); h.done(sync)
            assertEquals(4, h.detailCalls.size)
            assertEquals("Preview", h.app.archiveIndex.row("42")!!["image_quality"])
            sync.startCloud(sync.collections.value, BackupOptions(BackupImageQuality.Original, true)); h.done(sync)
            assertEquals(6, h.detailCalls.size)
            assertEquals("0", h.app.archiveIndex.row("42")!!["includes_replies"])
        }
    }

    @Test fun favoriteHookIsOptInAndNeverDeletesAnArchiveOnUnfavorite() = runBlocking {
        Harness().use { h ->
            h.ready(); val sync = h.sync()
            sync.favoriteChanged("42", "101", "合成收藏夹", true)
            withTimeout(5000) { while (h.app.archiveIndex.sources("42").isEmpty()) delay(2) }
            assertTrue(h.app.archiveIndex.queue().isEmpty())
            h.config = h.config.copy(archiveAutoBackup = "None", archiveIncludeReplies = false)
            sync.favoriteChanged("42", "101", "合成收藏夹", true)
            withTimeout(10_000) { while (h.app.archiveIndex.queue().none { it.id == "42" && it.state == "done" } || h.store.batchRunning) delay(2) }
            assertNotNull(h.app.archiveIndex.row("42"))
            sync.favoriteChanged("42", "101", "合成收藏夹", false)
            withTimeout(5000) { while (h.app.archiveIndex.sources("42").none { it["removed_at"].orEmpty().isNotEmpty() }) delay(2) }
            assertNotNull(h.app.archiveIndex.row("42"))
        }
    }

    @Test fun cloudDiscoveryRetriesTransientDisconnectsWithBackoff() = runBlocking {
        Harness().use { h ->
            h.ready(); h.collectionFailures = 2
            val sync = h.sync()
            sync.loadCollections()
            assertEquals(listOf("101"), sync.collections.value.map { it.id })
            assertTrue(h.clock.waits.containsAll(listOf(5000L, 20000L)))
            assertEquals(0, h.collectionFailures)
        }
    }

    @Test fun bothKindsOfFavoriteStartAfterBusyStoreBecomesAvailable() = runBlocking {
        Harness().use { h ->
            h.ready()
            h.config = h.config.copy(archiveAutoBackup = "None", archiveIncludeReplies = false)
            val sync = h.sync(); sync.awaitReady()
            h.store.batchRunning = true
            sync.favoriteChanged("42", "101", "合成收藏夹", collected = true)
            sync.favoriteChanged("43", collected = true, local = true)
            withTimeout(5000) { while (h.app.archiveIndex.queue().size != 2) delay(2) }
            assertTrue(h.detailCalls.isEmpty())
            h.store.batchRunning = false
            withTimeout(10_000) { while (h.app.archiveIndex.queue().count { it.state == "done" } != 2 || h.store.batchRunning) delay(2) }
            assertEquals(setOf("42", "43"), h.store.records.value.map { it.feed.id }.toSet())
        }
    }

    @Test fun choosingDirectoryContinuesTheFavoriteThatRequestedIt() = runBlocking {
        Harness().use { h ->
            h.config = h.config.copy(archiveAutoBackup = "None", archiveIncludeReplies = false)
            val sync = h.sync(); sync.awaitReady()
            // An old dismissed notice must not suppress future directory requests.
            h.app.archiveIndex.meta("auto_directory_notice", "1")
            sync.favoriteChanged("42", "101", "合成收藏夹", collected = true)
            withTimeout(5000) { while (!sync.directoryNeeded.value) delay(2) }
            assertTrue(h.detailCalls.isEmpty())
            h.ready()
            withTimeout(10_000) { while (h.app.archiveIndex.queue().none { it.id == "42" && it.state == "done" } || h.store.batchRunning) delay(2) }
            assertNotNull(h.app.archiveIndex.row("42"))
            assertTrue(h.app.archiveIndex.sources("42").any { it["collection_id"] == "101" })
        }
    }

    @Test fun removingOneFavoriteBeforeChoosingDirectoryKeepsTheOtherSource() = runBlocking {
        Harness().use { h ->
            h.config = h.config.copy(archiveAutoBackup = "None")
            val sync = h.sync()
            sync.favoriteChanged("42", "101", collected = true)
            withTimeout(5000) { while (!sync.directoryNeeded.value) delay(2) }
            sync.favoriteChanged("42", "102", collected = true)
            withTimeout(5000) {
                while (h.app.archiveIndex.sources("42").none { it["collection_id"] == "102" }) delay(2)
            }
            sync.favoriteChanged("42", "102", collected = false)
            withTimeout(5000) {
                while (h.app.archiveIndex.sources("42").none { it["collection_id"] == "102" && it["removed_at"].orEmpty().isNotEmpty() }) delay(2)
            }
            h.ready()
            withTimeout(10_000) {
                while (h.app.archiveIndex.queue().none { it.id == "42" && it.state == "done" } || h.store.batchRunning) delay(2)
            }
            assertEquals(listOf("101"), h.app.archiveIndex.sources("42").map { it["collection_id"] })
            assertEquals(1, h.detailCalls.size)
        }
    }

    @Test fun restrictedPostPausesThenResumesAfterIdentityRetry() = runBlocking {
        Harness().use { h ->
            h.ready(); h.captcha = true
            val sync = h.sync()
            sync.startLocal(listOf("42"), text); h.done(sync)
            assertTrue(sync.status.value.needsIdentity)
            assertFalse(sync.status.value.captcha)
            assertEquals("pending", h.app.archiveIndex.queue().single().state)
            val request = h.store.identityRequired.value!!
            h.captcha = false
            h.store.retryAfterIdentity(request)
            withTimeout(5000) { while (h.app.archiveIndex.queue().single().state != "done" || h.store.batchRunning) delay(2) }
            assertFalse(sync.status.value.needsIdentity)
            assertNull(h.store.identityRequired.value)
            assertEquals("0", h.app.archiveIndex.meta("queue_identity"))
        }
    }

    @Test fun completePublicPostDoesNotPauseTheQueueForIdentity() = runBlocking {
        Harness().use { h ->
            h.ready(); h.captcha = true; h.publicFeed = true
            val sync = h.sync()
            sync.startLocal(listOf("42"), text); h.done(sync)
            assertEquals("done", h.app.archiveIndex.queue().single().state)
            assertFalse(sync.status.value.needsIdentity)
            assertNull(h.store.identityRequired.value)
            assertEquals("公开全文", h.store.open(h.store.records.value.single()).data.message)
        }
    }

    @Test fun defaultsMatchRequestedOriginalImagesRepliesAndAnyNetwork() {
        val config = AppSettings()
        assertEquals("Original", config.archiveImageQuality)
        assertFalse(config.archiveIncludeReplies)
        assertFalse(config.archiveImagesWifiOnly)
        assertEquals("Off", config.archiveAutoBackup)
    }

    @Test fun changingTheCommentLimitRefreshesAnOtherwiseUnchangedBackup() = runBlocking {
        Harness().use { h ->
            h.ready(); val sync = h.sync(); sync.loadCollections()
            val options = BackupOptions(BackupImageQuality.None, replies = true, commentLimit = 3)
            sync.startCloud(sync.collections.value, options); h.done(sync)
            assertEquals("3", h.app.archiveIndex.row("42")!!["comment_limit"])
            assertEquals(2, h.detailCalls.size)
            sync.startCloud(sync.collections.value, options); h.done(sync)
            assertEquals(2, h.detailCalls.size)
            sync.startCloud(sync.collections.value, options.copy(commentLimit = 10)); h.done(sync)
            assertEquals(4, h.detailCalls.size)
            assertEquals("10", h.app.archiveIndex.row("42")!!["comment_limit"])
            sync.startCloud(sync.collections.value, options.copy(commentLimit = 10, includeSubReplies = true)); h.done(sync)
            assertEquals(6, h.detailCalls.size)
            assertEquals("1", h.app.archiveIndex.row("42")!!["comment_nested"])
        }
    }

    @Test fun localBatchKeepsExistingBackupsUnlessForced() = runBlocking {
        Harness().use { h ->
            h.ready(); val sync = h.sync()
            sync.startLocal(listOf("42"), text); h.done(sync)
            assertEquals(listOf("42"), h.detailCalls.map { it.first })
            sync.startLocal(listOf("42", "43"), text); h.done(sync)
            assertEquals(listOf("42", "43"), h.detailCalls.map { it.first })
            assertEquals(1, sync.status.value.skipped)
            assertEquals(1, sync.status.value.added)
            // A higher image tier is still an upgrade of the saved post.
            sync.startLocal(listOf("42"), text.copy(images = BackupImageQuality.Preview)); h.done(sync)
            assertEquals(3, h.detailCalls.size)
            sync.startLocal(listOf("42", "43"), text, force = true); h.done(sync)
            assertEquals(5, h.detailCalls.size)
            assertEquals(4, h.root.list().size)
        }
    }

    @Test fun favoriteOfAnAlreadySavedPostIsNotDownloadedAgain() = runBlocking {
        Harness().use { h ->
            h.ready()
            h.config = h.config.copy(archiveAutoBackup = "None")
            val sync = h.sync()
            sync.favoriteChanged("42", "101", "合成收藏夹", collected = true)
            withTimeout(10_000) { while (h.app.archiveIndex.queue().none { it.id == "42" && it.state == "done" } || h.store.batchRunning) delay(2) }
            sync.favoriteChanged("42", "102", "另一个收藏夹", collected = true)
            withTimeout(5000) { while (h.app.archiveIndex.sources("42").none { it["collection_id"] == "102" }) delay(2) }
            delay(200)
            assertEquals(1, h.detailCalls.size)
            assertEquals("done", h.app.archiveIndex.queue().single().state)
            h.config = h.config.copy(archiveAutoBackup = "Preview")
            sync.favoriteChanged("42", "102", "另一个收藏夹", collected = true)
            withTimeout(10_000) { while (h.detailCalls.size != 2 || h.store.batchRunning) delay(2) }
        }
    }

    @Test fun unwritableDirectoryPausesTheQueueInsteadOfFailingEveryItem() = runBlocking {
        Harness().use { h ->
            h.ready(); h.root.readOnly = true
            val sync = h.sync()
            sync.startLocal(listOf("42", "43"), text); h.done(sync)
            assertEquals(1, h.detailCalls.size)
            assertTrue(sync.status.value.paused)
            assertTrue(sync.status.value.phase.contains("备份目录"))
            assertEquals(listOf("pending", "pending"), h.app.archiveIndex.queue().map { it.state })
            h.root.readOnly = false
            sync.resume(); h.done(sync)
            assertEquals(listOf("done", "done"), h.app.archiveIndex.queue().map { it.state })
        }
    }

    @Test fun unreadableDirectoryPausesBeforeRequestingPosts() = runBlocking {
        Harness().use { h ->
            h.ready()
            h.root.listDenied = true
            val sync = h.sync()
            sync.startLocal(listOf("42", "43"), text); h.done(sync)
            assertTrue(h.detailCalls.isEmpty())
            assertEquals("1", h.app.archiveIndex.meta("queue_paused"))
            assertTrue(sync.status.value.phase.contains("备份目录"))
            assertEquals(listOf("pending", "pending"), h.app.archiveIndex.queue().map { it.state })
            h.root.listDenied = false
            sync.resume(); h.done(sync)
            assertEquals(listOf("done", "done"), h.app.archiveIndex.queue().map { it.state })
        }
    }

    @Test fun severalBackupsAreDeletedTogetherAndRecordsCarryTheirTier() = runBlocking {
        Harness().use { h ->
            h.ready()
            listOf("42", "43", "44").forEach { h.store.save(it, replace = false, options = text) }
            assertEquals(setOf("None"), h.store.records.value.map { it.imageQuality }.toSet())
            assertFalse(h.store.records.value.any { it.includesReplies })
            h.store.delete(setOf("42", "44"))
            assertEquals(listOf("43"), h.store.records.value.map { it.feed.id })
            assertEquals(setOf("feed_43.json", "feed_43.zip"), h.root.list().map { it.name }.toSet())
        }
    }
}
