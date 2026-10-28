package com.zailinn.c001apk.backup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.toRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.zailinn.c001apk.AppContainer
import com.zailinn.c001apk.core.backup.FeedBackup
import com.zailinn.c001apk.core.backup.backupImageUrls
import com.zailinn.c001apk.ui.C001apkApp
import com.zailinn.c001apk.ui.nav.*
import com.zailinn.c001apk.ui.screens.LocalAppContainer
import com.zailinn.c001apk.ui.screens.other.ImageViewerScreen
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(coil3.annotation.DelicateCoilApi::class)
class BackupUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temp = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val requests = CopyOnWriteArrayList<String>()
    private var detailFailure = ""
    private val decoded = CopyOnWriteArrayList<Any>()
    private val tree = Uri.parse("content://backup.test/tree/root")
    private val feed = """{"id":"42","entityType":"feed","uid":"7","username":"备份作者","message":"完整正文","replynum":0,"picArr":["https://image.coolapk.com/synthetic-body.png"]}"""
    private fun provider(): Documents {
        val provider = Documents(temp.newFolder())
        provider.attachInfo(context, ProviderInfo().apply {
            authority = "backup.test"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission
        })
        ShadowContentResolver.registerProviderInternal("backup.test", provider)
        return provider
    }
    private fun app(): AppContainer {
        val png = java.io.ByteArrayOutputStream().use { stream ->
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).also { it.compress(Bitmap.CompressFormat.PNG, 100, stream); it.recycle() }
            stream.toByteArray()
        }
        val api = Interceptor { chain ->
            val request = chain.request(); requests += request.url.toString()
            val body = if (request.url.encodedPath.endsWith("/feed/detail")) {
                when (detailFailure) {
                    "gone" -> """{"status":-1,"message":"该动态已删除"}"""
                    "captcha" -> """{"status":"err_request_captcha","message":"需要验证码"}"""
                    "network" -> throw java.io.IOException("synthetic network failure")
                    else -> """{"data":$feed}"""
                }
            } else """{"data":[]}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }
        val images = Interceptor { chain ->
            val request = chain.request(); requests += request.url.toString()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(png.toResponseBody()).build()
        }
        return AppContainer(context, "backup-ui-${UUID.randomUUID()}", api, images).also { app ->
            SingletonImageLoader.setUnsafe(ImageLoader.Builder(context)
                .eventListener(object : coil3.EventListener() {
                    override fun onSuccess(request: coil3.request.ImageRequest, result: coil3.request.SuccessResult) { decoded += request.data }
                })
                .components {
                    add(BackupImageFetcher.Factory(app.backups))
                    add(coil3.decode.BitmapFactoryDecoder.Factory())
                    add(coil3.network.okhttp.OkHttpNetworkFetcherFactory(callFactory = { app.imageHttp }))
                }.build())
        }
    }
    @After fun releaseLoader() { SingletonImageLoader.get(context).shutdown(); SingletonImageLoader.reset() }

    @Test fun firstBackupPicksPersistentDirectoryAndDuplicateChoicesWork() {
        val docs = provider()
        val app = app()
        compose.setContent { C001apkApp(app, Uri.parse("https://www.coolapk.com/feed/42")) {} }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("完整正文").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("备份").performClick()
        compose.waitForIdle()
        val launched = shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, launched.intent.action)
        shadowOf(compose.activity).receiveResult(launched.intent, Activity.RESULT_OK,
            Intent().setData(tree).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        compose.waitUntil(15_000) { app.backups.records.value.size == 1 && app.backups.progress.value == null }
        assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission })
        assertEquals(setOf("feed_42.json", "feed_42.zip"), docs.folder.listFiles()!!.map { it.name }.toSet())
        fun openConflict() {
            compose.onNodeWithContentDescription("更多").performClick()
            compose.onNodeWithText("备份").performClick()
            compose.waitUntil { compose.onAllNodesWithText("这条动态已备份").fetchSemanticsNodes().isNotEmpty() }
        }
        openConflict()
        compose.onNodeWithText("均保留").performClick()
        compose.waitUntil(15_000) { docs.folder.listFiles()!!.size == 4 && app.backups.progress.value == null }
        openConflict()
        compose.onNodeWithText("替换").performClick()
        compose.waitUntil(15_000) { docs.folder.listFiles()!!.size == 2 && app.backups.progress.value == null }
        assertEquals(1, app.backups.records.value.size)
    }

    @Test fun restoredBackupShowsArticleRepliesAndLocalImageViewerWithoutNetwork() {
        val docs = provider()
        val app = app()
        val source = FeedBackup(Json.parseToJsonElement("""{"data":{"id":"42","entityType":"feed","uid":"7","username":"备份作者","message":"完整正文",
            "message_raw_output":"[{\"type\":\"text\",\"message\":\"离线文章段落\"},{\"type\":\"image\",\"url\":\"https://image.coolapk.com/offline-body.png\"}]"}}""").jsonObject,
            listOf(Json.parseToJsonElement("""{"id":"11","entityType":"feed_reply","uid":"8","username":"回复者","message":"回复正文","replynum":4,"replyRows":[{"id":"12","uid":"9","username":"楼中楼作者","message":"楼中楼正文"},{"id":"13","uid":"9","message":"第二条子回复"},{"id":"14","uid":"9","message":"第三条子回复"},{"id":"15","uid":"9","message":"第四条子回复"}]}""").jsonObject))
        File(docs.folder, "feed_42.json").writeText(FeedBackup.json.encodeToString(FeedBackup.serializer(), source))
        val png = java.io.ByteArrayOutputStream().use { out -> Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
        ZipOutputStream(File(docs.folder, "feed_42.zip").outputStream()).use { zip ->
            backupImageUrls(source).forEachIndexed { index, _ -> zip.putNextEntry(ZipEntry("image_${index + 1}.png")); zip.write(png); zip.closeEntry() }
        }
        runBlocking { app.backups.selectDirectory(tree.toString()) }
        requests.clear()
        compose.setContent {
            val nav = rememberNavController()
            CompositionLocalProvider(LocalAppContainer provides app, LocalNavigator provides AppNavigator(context, nav, app.settings)) {
                NavHost(nav, startDestination = BackupsRoute) {
                    composable<BackupsRoute> { BackupsScreen() }
                    composable<BackupDetailRoute> { BackupDetailScreen("42") }
                    composable<ImageViewerRoute> { entry ->
                        val args = entry.toRoute<ImageViewerRoute>()
                        ImageViewerScreen(args.urls, args.index, args.mediaJson)
                    }
                }
            }
        }
        compose.onNodeWithText("备份作者").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("离线文章段落").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("离线备份不加载网络回复").assertExists()
        compose.waitUntil(10_000) { decoded.any { it.toString().startsWith("c001apk-backup://") } }
        compose.onAllNodesWithContentDescription("查看图片").onFirst().performClick()
        // Coil reports through the paused main looper; drain it while waiting.
        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            decoded.any { it is File && it.path.contains("feed-backup-media") }
        }
        compose.onNodeWithContentDescription("返回").performClick()
        // Wait for navigation to expose the restored page in the merged tree.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("离线备份不加载网络回复").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().size == 1
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("回复正文"))
        compose.onNodeWithText("回复正文").assertExists()
        compose.onNodeWithText("楼中楼正文", substring = true).assertExists()
        compose.onNodeWithText("查看更多回复(4)").performClick()
        compose.onNodeWithText("备份中的楼中楼").assertExists()
        compose.onAllNodes(hasScrollAction()).onLast().performScrollToNode(hasText("第四条子回复"))
        compose.onNodeWithText("第四条子回复").assertExists()
        assertTrue("offline read must not call API or image CDN: $requests", requests.isEmpty())
    }

    @Test fun searchOpensOnlineThenFallsBackForDeletedFeedAndAllowsCopyingFullText() {
        provider()
        val app = app()
        runBlocking { app.backups.selectDirectory(tree.toString()); app.backups.save("42", replace = false) }
        detailFailure = "gone"
        compose.setContent {
            val nav = rememberNavController()
            CompositionLocalProvider(LocalAppContainer provides app, LocalNavigator provides AppNavigator(context, nav, app.settings)) {
                NavHost(nav, startDestination = ArchiveSearchRoute()) {
                    composable<ArchiveSearchRoute> { ArchiveSearchScreen() }
                    composable<FeedRoute> { entry -> val route = entry.toRoute<FeedRoute>(); com.zailinn.c001apk.ui.screens.feed.FeedScreen(route.id, route.toReplies, route.backupFallback) }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("备份作者").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("备份作者").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("在线动态无法打开", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("复制全文").performClick()
        assertTrue(context.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString().contains("完整正文"))
        compose.waitUntil { app.archiveIndex.state("42") == "gone" }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun localBatchUsesRequestedDefaultsAndCanPauseFromTheProgressScreen() {
        provider()
        val app = app()
        runBlocking {
            app.backups.selectDirectory(tree.toString())
            app.content.toggleFavorite(com.zailinn.c001apk.core.model.Datum(Json.parseToJsonElement(feed).jsonObject))
        }
        compose.setContent {
            val nav = rememberNavController()
            CompositionLocalProvider(LocalAppContainer provides app, LocalNavigator provides AppNavigator(context, nav, app.settings)) {
                ArchiveSyncScreen(local = true)
            }
        }
        compose.waitUntil { app.content.favorites.value.isNotEmpty() }
        compose.onNodeWithText("文字和原图").assertExists()
        assertTrue(app.settings.current.archiveIncludeReplies)
        assertFalse(app.settings.current.archiveImagesWifiOnly)
        compose.onNodeWithText("开始备份").performScrollTo().performClick()
        compose.waitUntil(10_000) { app.archiveSync.status.value.running }
        compose.onNodeWithText("暂停").performScrollTo().performClick()
        compose.waitUntil(10_000) { app.archiveSync.status.value.paused && !app.backups.batchRunning }
        compose.onNodeWithText("继续").assertExists()
        assertTrue(app.archiveIndex.queue().any { it.state == "pending" })
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun exporterUsesCreateDocumentAndWritesTheSelectedTextFileWithoutNetwork() {
        org.robolectric.shadows.ShadowStatFs.registerStats(context.cacheDir.path, 1_000_000, 500_000, 500_000)
        val docs = provider()
        val app = app()
        runBlocking { app.backups.selectDirectory(tree.toString()); app.backups.save("42", replace = false) }
        requests.clear()
        compose.setContent {
            val nav = rememberNavController()
            CompositionLocalProvider(LocalAppContainer provides app, LocalNavigator provides AppNavigator(context, nav, app.settings)) {
                ArchiveExportScreen()
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("1 条动态", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("选择保存位置并导出").performClick()
        var launched: org.robolectric.shadows.ShadowActivity.IntentForResult? = null
        compose.waitUntil(10_000) { launched = shadowOf(compose.activity).nextStartedActivityForResult; launched != null }
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, launched!!.intent.action)
        assertEquals("text/plain", launched!!.intent.type)
        val file = File(docs.folder, "export.txt").apply { writeText("") }
        shadowOf(compose.activity).receiveResult(launched!!.intent, Activity.RESULT_OK, Intent().setData(Uri.parse("content://backup.test/document/export.txt")))
        compose.waitUntil(10_000) { file.length() > 0 && app.archiveExporter.progress.value == null }
        assertTrue(file.readText().contains("完整正文"))
        assertTrue(requests.isEmpty())
    }

    private class Documents(val folder: File) : DocumentsProvider() {
        private val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS)
        override fun onCreate() = true
        override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_TITLE)).apply { addRow(arrayOf("root", "root", "备份目录")) }
        private fun cursor(projection: Array<out String>?, ids: List<String>): Cursor = MatrixCursor(projection ?: columns).apply {
            ids.forEach { id ->
                val file = if (id == "root") folder else File(folder, id)
                addRow(columnNames.map { key -> when (key) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> file.name
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> if (id == "root") DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                    DocumentsContract.Document.COLUMN_SIZE -> file.length()
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                    DocumentsContract.Document.COLUMN_FLAGS -> DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                    else -> null
                } }.toTypedArray<Any?>())
            }
        }
        override fun queryDocument(documentId: String, projection: Array<out String>?) = cursor(projection, listOf(documentId))
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?) = cursor(projection, folder.listFiles().orEmpty().map { it.name })
        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String = displayName.also { check(File(folder, it).createNewFile()) }
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?) = ParcelFileDescriptor.open(File(folder, documentId), ParcelFileDescriptor.parseMode(mode))
        override fun deleteDocument(documentId: String) { check(File(folder, documentId).delete()) }
        override fun isChildDocument(parentDocumentId: String, documentId: String) = parentDocumentId == "root"
    }
}
