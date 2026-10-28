package com.zailinn.c001apk.backup

import coil3.toUri
import android.content.Context
import android.graphics.Bitmap
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.zailinn.c001apk.AppContainer
import com.zailinn.c001apk.core.backup.BACKUP_MEDIA_SCHEME
import com.zailinn.c001apk.core.backup.FeedBackup
import com.zailinn.c001apk.data.LocalStore
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
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedBackupStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class Directory(val folder: File) : BackupDirectory {
        var reads = 0
        var zipReads = 0
        var failJson = false
        var afterZipWrite: (() -> Unit)? = null
        override fun list() = folder.listFiles().orEmpty().map { BackupDocument(it.name, it.length(), it.lastModified()) }
        override fun read(name: String) = File(folder, name).inputStream().also { if (name.endsWith(".json")) reads++; if (name.endsWith(".zip")) zipReads++ }
        override fun write(name: String, source: File) {
            if (failJson && name.endsWith(".json")) throw java.io.IOException("synthetic provider failure")
            check(!File(folder, name).exists())
            source.copyTo(File(folder, name))
            if (name.endsWith(".zip")) afterZipWrite?.invoke()
        }
        override fun delete(name: String) = File(folder, name).let { !it.exists() || it.delete() }
    }

    private inner class Harness : AutoCloseable {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val directory = Directory(temp.newFolder())
        val apiRequests = CopyOnWriteArrayList<okhttp3.Request>()
        val imageRequests = CopyOnWriteArrayList<okhttp3.Request>()
        val base = "https://image.coolapk.com/${UUID.randomUUID()}"
        val photo = java.io.ByteArrayOutputStream().use { out ->
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).also { it.compress(Bitmap.CompressFormat.PNG, 100, out); it.recycle() }
            out.toByteArray()
        }
        var failImage = false
        var bodyContent = "完整正文"
        val api = Interceptor { chain ->
            val request = chain.request(); apiRequests += request
            val body = when {
                request.url.encodedPath.endsWith("/feed/detail") -> """{"data":{"id":"42","entityType":"feed","uid":"7","username":"备份作者","message":"$bodyContent","replynum":2,"picArr":["$base/body.png"],"userAvatar":"$base/avatar.png"}}"""
                request.url.queryParameter("page") != "1" -> """{"data":[]}"""
                request.url.queryParameter("feedType") == "feed_reply" -> """{"data":[{"id":"12","entityType":"feed_reply","message":"楼中楼正文","picArr":["$base/nested.png"]}]}"""
                else -> """{"data":[{"id":"11","entityType":"feed_reply","message":"回复正文","replynum":1,"picArr":["$base/reply.png"]}]}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }
        val images = Interceptor { chain ->
            val request = chain.request(); imageRequests += request
            if (failImage) throw java.io.IOException("synthetic image failure")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(photo.toResponseBody()).build()
        }
        val app = AppContainer(context, "backup-test-${UUID.randomUUID()}", api, images)
        fun store() = FeedBackupStore(context,
            LocalStore(PreferenceDataStoreFactory.create(scope = scope) { temp.newFile("prefs-${UUID.randomUUID()}.preferences_pb") }, scope),
            scope, app.repository, app.galleryFiles, {}, archiveIndex = app.archiveIndex, directoryFactory = { directory })
        override fun close() { job.cancel(); app.scope.cancel() }
    }

    @Test fun constructorsDoNotWaitForDatabaseAccessAndImageFactoryIsLazy() = runBlocking {
        Harness().use { h ->
            val index = h.app.archiveIndex
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val store = synchronized(index) {
                    executor.submit<FeedBackupStore> { h.store() }.get(5, java.util.concurrent.TimeUnit.SECONDS)
                }
                store.awaitReady()
                val sync = synchronized(index) {
                    executor.submit<ArchiveSync> {
                        ArchiveSync(h.app.repository, store, index, h.scope, { "" }, { com.zailinn.c001apk.data.AppSettings() }, { true }, {})
                    }.get(5, java.util.concurrent.TimeUnit.SECONDS)
                }
                sync.awaitReady()
                var resolved = false
                val factory = BackupImageFetcher.Factory { resolved = true; store }
                val loader = coil3.ImageLoader.Builder(context).build()
                try {
                    assertNull(factory.create("https://image.coolapk.com/test.jpg".toUri(), coil3.request.Options(context), loader))
                    assertNotNull(factory.create("${BACKUP_MEDIA_SCHEME}${"a".repeat(64)}/image_1.jpg".toUri(), coil3.request.Options(context), loader))
                    assertFalse(resolved)
                } finally { loader.shutdown() }
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun legacyDataStoreIndexMigratesAndIsRemovedOnlyAfterDirectoryScan() = runBlocking {
        Harness().use { h ->
            val original = h.store()
            original.selectDirectory("test-directory")
            original.save("42", replace = false)
            val legacy = FeedBackup.json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(BackupRecord.serializer()), original.records.value)
            h.app.archiveIndex.rebuild()
            val preferences = PreferenceDataStoreFactory.create(scope = h.scope) {
                temp.newFile("legacy-${UUID.randomUUID()}.preferences_pb")
            }
            val key = androidx.datastore.preferences.core.stringPreferencesKey("feedBackupsV1")
            preferences.edit { it[key] = legacy }
            val local = LocalStore(preferences, h.scope)
            val restored = FeedBackupStore(context, local, h.scope, h.app.repository, h.app.galleryFiles, {},
                archiveIndex = h.app.archiveIndex, directoryFactory = { h.directory })
            restored.awaitReady()
            assertEquals("42", restored.records.value.single().feed.id)
            assertEquals("1", h.app.archiveIndex.meta("needs_rebuild"))
            assertNotNull(local.prefs.value[key])
            val apiCount = h.apiRequests.size
            restored.selectDirectory("test-directory")
            assertEquals("完整正文", h.app.archiveIndex.search("完整正文").single().record.feed.message)
            kotlinx.coroutines.withTimeout(5000) {
                local.prefs.first { it[key] == null }
            }
            assertEquals("0", h.app.archiveIndex.meta("needs_rebuild"))
            assertEquals(apiCount, h.apiRequests.size)
        }
    }

    @Test fun exportRebuildIndexAndReadImagesAndThreadsWithoutNetwork() = runBlocking {
        Harness().use { h ->
            val store = h.store()
            store.selectDirectory("test-directory")
            val snapshot = store.save("42", replace = false)
            assertTrue(snapshot.warnings.isEmpty())
            assertEquals(setOf("feed_42.json", "feed_42.zip"), h.directory.list().map { it.name }.toSet())
            ZipFile(File(h.directory.folder, "feed_42.zip")).use { zip ->
                assertEquals(4, zip.size())
                zip.entries().asSequence().forEach { assertEquals(ZipEntry.STORED, it.method) }
            }
            assertTrue(h.imageRequests.all { it.url.isHttps && it.header("Cookie") == null && it.header("X-App-Token") == null })
            val apiCount = h.apiRequests.size
            val imagesCount = h.imageRequests.size
            val restored = h.store() // Simulates new local storage on another installation.
            restored.selectDirectory("test-directory")
            assertEquals(1, restored.records.value.size)
            val offline = restored.open(restored.records.value.single())
            assertEquals("完整正文", offline.data.message)
            assertEquals("楼中楼正文", (offline.replies.single()["replyRows"] as JsonArray).single().jsonObject["message"]!!.jsonPrimitive.content)
            val picture = offline.data.picArr.single()
            assertTrue(picture.startsWith(BACKUP_MEDIA_SCHEME))
            assertArrayEquals(h.photo, restored.localMedia(picture)!!.readBytes())
            assertEquals(apiCount, h.apiRequests.size)
            assertEquals(imagesCount, h.imageRequests.size)
            assertNull(restored.localMedia("${BACKUP_MEDIA_SCHEME}../../outside"))
        }
    }

    @Test fun keepBothCreatesVersionsReplaceLeavesOneAndFailedReplacementKeepsTheOldPair() = runBlocking {
        Harness().use { h ->
            val store = h.store(); store.selectDirectory("test-directory")
            store.save("42", replace = false)
            store.save("42", replace = false)
            assertEquals(2, h.directory.list().count { it.name.endsWith(".json") })
            assertEquals(1, store.records.value.size)
            store.save("42", replace = true)
            assertEquals(1, h.directory.list().count { it.name.endsWith(".json") })
            val old = h.directory.list().map { it.name }.toSet()
            h.directory.failJson = true
            assertTrue(runCatching { store.save("42", replace = true) }.isFailure)
            assertEquals(old, h.directory.list().map { it.name }.toSet())
            assertEquals("完整正文", store.open(store.records.value.single()).data.message)
            h.directory.failJson = false
            store.delete("42")
            assertTrue(h.directory.list().isEmpty())
            assertTrue(store.records.value.isEmpty())
        }
    }

    @Test fun cancellationAfterZipUploadRemovesTheUnpublishedPairAndPreservesTheOldBackup() = runBlocking {
        Harness().use { h ->
            val store = h.store(); store.selectDirectory("test-directory")
            store.save("42", replace = false)
            val old = h.directory.list().map { it.name }.toSet()
            val task = async(start = CoroutineStart.LAZY) { store.save("42", replace = true) }
            h.directory.afterZipWrite = { task.cancel() }
            task.start()
            assertTrue(runCatching { task.await() }.isFailure)
            assertEquals(old, h.directory.list().map { it.name }.toSet())
            assertEquals("完整正文", store.open(store.records.value.single()).data.message)
        }
    }

    @Test fun incrementalScanReadsOnlyChangedDocumentsAndFullRebuildRestoresIndex() = runBlocking {
        Harness().use { h ->
            val store = h.store(); store.selectDirectory("test-directory")
            store.save("42", replace = false)
            val initial = h.directory.reads
            store.sync()
            assertEquals(initial, h.directory.reads)
            store.sync(full = true)
            assertEquals(initial + 1, h.directory.reads)
            assertEquals(1, store.records.value.size)
            assertTrue(store.archiveIndex.search("完整正文").isNotEmpty())
        }
    }

    @Test fun batchOptionsKeepOriginalIdentityAndCanOmitRepliesAndImages() = runBlocking {
        Harness().use { h ->
            val store = h.store(); store.selectDirectory("test-directory")
            val options = com.zailinn.c001apk.core.backup.BackupOptions(com.zailinn.c001apk.core.backup.BackupImageQuality.None, false)
            val text = store.save("42", replace = false, options = options)
            assertFalse(text.includesReplies)
            assertTrue(text.images.isEmpty())
            assertTrue(h.imageRequests.isEmpty())
            assertEquals(1, h.apiRequests.size)
            assertTrue(store.open(store.records.value.single()).warnings.isEmpty())
            h.apiRequests.clear()
            val preview = store.save("42", replace = true, options = options.copy(images = com.zailinn.c001apk.core.backup.BackupImageQuality.Preview))
            assertTrue(preview.images.all { it.url.endsWith(".png") && it.savedUrl.endsWith(".s.jpg") })
            assertTrue(store.open(store.records.value.single()).data.picArr.single().startsWith(BACKUP_MEDIA_SCHEME))
            store.delete("42")
            assertTrue(store.records.value.isEmpty())
        }
    }

    @Test fun archiveExportsUseOnlyLocalDataAndIncludeVerifiedImages() = runBlocking {
        Harness().use { h ->
            val store = h.store(); store.selectDirectory("test-directory")
            store.save("42", replace = false)
            val api = h.apiRequests.size; val images = h.imageRequests.size
            org.robolectric.shadows.ShadowStatFs.registerStats(context.cacheDir.path, 1_000_000, 500_000, 500_000)
            val exporter = ArchiveExporter(context, store, store.archiveIndex)
            for (format in ArchiveExportFormat.entries) {
                val zipReads = h.directory.zipReads
                val prepared = exporter.prepare(listOf("42"), format, replies = true, ascending = true)
                try {
                    if (format == ArchiveExportFormat.HtmlImages) ZipFile(prepared.file).use { zip ->
                        val html = zip.getInputStream(zip.getEntry("index.html")).reader().readText()
                        assertTrue(html.contains("完整正文")); assertFalse(html.contains("<img src=\"https:"))
                        assertEquals(4, zip.entries().asSequence().count { it.name.startsWith("images/") })
                        zip.entries().asSequence().filter { it.name.startsWith("images/") }.forEach { entry ->
                            assertEquals(ZipEntry.STORED, entry.method)
                            assertArrayEquals(h.photo, zip.getInputStream(entry).readBytes())
                        }
                    } else {
                        assertEquals(zipReads, h.directory.zipReads)
                        assertTrue(prepared.file.readText().contains("完整正文"))
                        assertTrue(prepared.file.readText().contains("楼中楼正文"))
                    }
                    assertEquals(api, h.apiRequests.size); assertEquals(images, h.imageRequests.size)
                } finally { exporter.discard(prepared) }
            }
        }
    }

    @Test fun oversizedHtmlIsSplitIntoPortableZipVolumes() = runBlocking {
        Harness().use { h ->
            h.bodyContent = "大段正文".repeat(2500)
            val store = h.store(); store.selectDirectory("test-directory"); store.save("42", replace = false)
            org.robolectric.shadows.ShadowStatFs.registerStats(context.cacheDir.path, 1_000_000, 500_000, 500_000)
            val exporter = ArchiveExporter(context, store, store.archiveIndex, volumeLimit = 16 * 1024)
            val prepared = exporter.prepare(listOf("42"), ArchiveExportFormat.Html, false, true)
            try {
                assertEquals(".zip", prepared.suffix)
                ZipFile(prepared.file).use { zip ->
                    assertNotNull(zip.getEntry("index.html"))
                    assertNotNull(zip.getEntry("index_2.html"))
                    zip.entries().asSequence().filter { it.name.endsWith(".html") }.forEach { assertTrue(it.size.toString(), it.size <= 16 * 1024) }
                }
            } finally { exporter.discard(prepared) }
        }
    }

    @Test fun imageFailuresKeepReadableTextAndMarkTheBackupPartial() = runBlocking {
        Harness().use { h ->
            h.failImage = true
            val store = h.store(); store.selectDirectory("test-directory")
            val snapshot = store.save("42", replace = false)
            assertEquals(4, snapshot.warnings.count { it.contains("未保存") })
            val offline = store.open(store.records.value.single())
            assertEquals("完整正文", offline.data.message)
            assertEquals(1, offline.replies.size)
            assertNull(store.localMedia(offline.data.picArr.single()))
            assertTrue(offline.warnings.isNotEmpty())
        }
    }

    @Test fun legacyPayloadsRecoverTheirImagesAndUnrelatedOrCorruptJsonIsIgnored() = runBlocking {
        Harness().use { h ->
            val store = h.store()
            File(h.directory.folder, "feed_42.json").writeText("""{"feed":{"data":{"fid":"42","entityType":"feed","message":"旧备份","picArr":["${h.base}/old.png"]}},"replies":[]}""")
            ZipOutputStream(File(h.directory.folder, "feed_42.zip").outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("image_1.png")); zip.write(h.photo); zip.closeEntry()
                zip.putNextEntry(ZipEntry("../../outside.txt")); zip.write(byteArrayOf(1)); zip.closeEntry()
            }
            File(h.directory.folder, "feed_420.json").writeText("invalid json")
            File(h.directory.folder, "other.json").writeText("""{"data":{"id":"43"}}""")
            store.selectDirectory("test-directory")
            assertEquals(listOf("42"), store.records.value.map { it.feed.id })
            val offline = store.open(store.records.value.single())
            assertEquals("旧备份", offline.data.message)
            assertArrayEquals(h.photo, store.localMedia(offline.data.picArr.single())!!.readBytes())
            assertFalse(File(context.cacheDir, "outside.txt").exists())
        }
    }
}
