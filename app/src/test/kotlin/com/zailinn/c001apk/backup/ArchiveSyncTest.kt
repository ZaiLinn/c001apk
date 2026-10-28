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
        override fun list() = folder.listFiles().orEmpty().map { BackupDocument(it.name, it.length(), it.lastModified()) }
        override fun read(name: String) = File(folder, name).inputStream()
        override fun write(name: String, source: File) { source.copyTo(File(folder, name)) }
        override fun delete(name: String) = File(folder, name).let { !it.exists() || it.delete() }
    }
    private inner class Harness : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val clock = Clock()
        val detailCalls = CopyOnWriteArrayList<Pair<String, Long>>()
        var feedIds = listOf("42", "43")
        var fingerprintText = "列表正文"
        var captcha = false
        var network = false
        var pageFailure = false
        val transport = Interceptor { chain ->
            val request = chain.request(); val path = request.url.encodedPath
            val body = when {
                path.endsWith("/collection/list") -> if (request.url.queryParameter("page") != "1") """{"data":[]}""" else """{"data":[{"entityType":"collection","id":"101","title":"合成收藏夹","item_num":2}]}"""
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
            assertTrue(snapshot.includesReplies)
            repeat(2) {
                sync.startCloud(sync.collections.value, text, force = true); h.done(sync)
                assertEquals(2, h.root.list().size)
                snapshot = h.store.open(h.store.records.value.single())
                assertEquals(BackupImageQuality.Original, snapshot.imageQuality)
                assertTrue(snapshot.includesReplies)
            }
            h.fingerprintText = "已编辑"
            sync.startCloud(sync.collections.value, text); h.done(sync)
            assertEquals(2, h.root.list().size)
            snapshot = h.store.open(h.store.records.value.single())
            assertEquals(BackupImageQuality.Original, snapshot.imageQuality)
            assertTrue(snapshot.includesReplies)
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
            assertTrue(snapshot.includesReplies)
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
            assertTrue(captchaSync.status.value.captcha)
            val before = h.detailCalls.size
            captchaSync.favoriteChanged("45", collected = true, local = true)
            withTimeout(5000) { while (h.app.archiveIndex.queue().none { it.id == "45" }) delay(2) }
            assertEquals(before, h.detailCalls.size)
            assertTrue(captchaSync.status.value.captcha)
            assertFalse(captchaSync.status.value.running)
            assertEquals("1", h.app.archiveIndex.meta("queue_captcha"))
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
        }
    }
    @Test fun captchaPausesAndProcessRecreationKeepsPendingTasks() = runBlocking {
        Harness().use { h ->
            h.ready(); val sync = h.sync(); h.captcha = true
            sync.startLocal(listOf("42", "43"), text); h.done(sync)
            assertTrue(sync.status.value.captcha)
            assertEquals(1, h.detailCalls.size)
            assertEquals(2, h.app.archiveIndex.queue().count { it.state == "pending" })
            val restored = h.sync()
            restored.awaitReady()
            assertTrue(restored.status.value.captcha)
            h.captcha = false
            h.app.archiveIndex.meta("queue_captcha", "0"); restored.status.value = restored.status.value.copy(captcha = false)
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
            assertEquals("1", h.app.archiveIndex.row("42")!!["includes_replies"])
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

    @Test fun defaultsMatchRequestedOriginalImagesRepliesAndAnyNetwork() {
        val config = AppSettings()
        assertEquals("Original", config.archiveImageQuality)
        assertTrue(config.archiveIncludeReplies)
        assertFalse(config.archiveImagesWifiOnly)
        assertEquals("Off", config.archiveAutoBackup)
    }
}
