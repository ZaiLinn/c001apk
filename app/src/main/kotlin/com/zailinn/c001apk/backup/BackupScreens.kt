package com.zailinn.c001apk.backup

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zailinn.c001apk.AppContainer
import com.zailinn.c001apk.core.backup.FeedBackup
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.FeedArticle
import com.zailinn.c001apk.core.model.MediaAutoPlay
import com.zailinn.c001apk.ui.LocalAppSettings
import com.zailinn.c001apk.ui.cards.FeedCard
import com.zailinn.c001apk.ui.cards.ItemActions
import com.zailinn.c001apk.ui.cards.ReplyCard
import com.zailinn.c001apk.ui.common.*
import com.zailinn.c001apk.ui.nav.ArchiveSearchRoute
import com.zailinn.c001apk.ui.nav.BackupDetailRoute
import com.zailinn.c001apk.ui.nav.FeedRoute
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.screens.appContainer
import com.zailinn.c001apk.ui.screens.feed.ArticleBody
import com.zailinn.c001apk.ui.screens.list.SimpleTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
internal fun rememberBackupDirectoryPicker(app: AppContainer, onCancel: () -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentCancel by rememberUpdatedState(onCancel)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) currentCancel() else scope.launch {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                app.backups.selectDirectory(uri.toString())
                app.toast("已设置备份目录并同步列表")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { app.toast("无法访问这个目录，请选择可读写的文件夹"); currentCancel() }
        }
    }
    return { picker.launch(app.backups.directory.value.takeIf { it.isNotBlank() }?.let(Uri::parse)) }
}

/** One host for requests from feed menus anywhere in the app, including after a directory picker. */
@Composable
fun BackupHost(app: AppContainer) {
    val request by app.backups.pending.collectAsStateWithLifecycle()
    val progress by app.backups.progress.collectAsStateWithLifecycle()
    val directoryRevision by app.backups.directoryChanges.collectAsStateWithLifecycle()
    val picker = rememberBackupDirectoryPicker(app) { app.backups.pending.value = null }
    val needsDirectory by app.archiveSync.directoryNeeded.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(needsDirectory) {
        if (needsDirectory) {
            val result = snackbar.showSnackbar("开启自动备份需要先选择备份目录", actionLabel = "选择目录", duration = SnackbarDuration.Long)
            app.archiveSync.directoryNeeded.value = false
            if (result == SnackbarResult.ActionPerformed) picker()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        SnackbarHost(snackbar, Modifier.navigationBarsPadding().padding(12.dp))
    }
    var conflict by remember(request) { mutableStateOf(false) }
    LaunchedEffect(request, directoryRevision) {
        val pending = request ?: return@LaunchedEffect
        if (app.backups.directory.value.isBlank()) picker() else {
            try {
                if (app.backups.hasBackup(pending.id)) conflict = true else app.backups.start(pending, replace = false)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { app.toast("备份目录不可用，请重新选择"); picker() }
        }
    }
    if (conflict && request != null) AppAlertDialog(
        onDismissRequest = { app.backups.pending.value = null },
        title = { Text("这条动态已备份") },
        text = { Text("均保留会创建另一份文件；替换会在新备份保存成功后删除这条动态的旧备份。") },
        confirmButton = {
            Row {
                TextButton(onClick = { request?.let { app.backups.start(it, replace = false) } }) { Text("均保留") }
                TextButton(onClick = { request?.let { app.backups.start(it, replace = true) } }) { Text("替换") }
            }
        },
        dismissButton = { TextButton(onClick = { app.backups.pending.value = null }) { Text("取消") } },
    )
    progress?.let { state ->
        AppAlertDialog(
            onDismissRequest = {},
            title = { Text("正在备份") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(state.message)
                    if (state.total > 0) {
                        LinearProgressIndicator(progress = { state.done.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
                        Text("${state.done}/${state.total}")
                    } else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = app.backups::cancel) { Text("取消备份") } },
        )
    }
}

@Composable
fun BackupsScreen() {
    val app = appContainer()
    val navigator = LocalNavigator.current
    val records by app.backups.records.collectAsStateWithLifecycle()
    val directory by app.backups.directory.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val picker = rememberBackupDirectoryPicker(app)
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<BackupRecord?>(null) }
    suspend fun sync(showToast: Boolean = false, rebuild: Boolean = false) {
        loading = true; error = ""
        try { val count = app.backups.sync(full = rebuild); if (showToast) app.toast("已同步 $count 条备份") }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "无法读取备份目录，请重新选择目录" }
        finally { loading = false }
    }
    LaunchedEffect(directory) { if (directory.isNotEmpty()) sync() }
    Scaffold(topBar = {
        SimpleTopBar("我的备份") {
            // Search stays in the bar; the rest go in the menu, each named next to its icon.
            IconButton(onClick = { navigator.navigate(ArchiveSearchRoute("all")) }) { Icon(Icons.Outlined.Search, "搜索备份") }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "更多") }
                AppDropdownMenu(menu, { menu = false }) {
                    val ready = directory.isNotEmpty() && !loading
                    if (com.zailinn.c001apk.ui.LocalSession.current.isLogin) DropdownMenuItem(
                        { Text("同步收藏到本地备份") }, { menu = false; navigator.navigate(com.zailinn.c001apk.ui.nav.ArchiveSyncRoute()) },
                        leadingIcon = { Icon(Icons.Outlined.CloudDownload, null) },
                    )
                    DropdownMenuItem(
                        { Text("导出备份") }, { menu = false; navigator.navigate(com.zailinn.c001apk.ui.nav.ArchiveExportRoute()) },
                        leadingIcon = { Icon(Icons.Outlined.FileDownload, null) },
                    )
                    DropdownMenuItem(
                        { Text("刷新备份列表") }, { menu = false; scope.launch { sync(showToast = true) } },
                        leadingIcon = { Icon(Icons.Outlined.Sync, null) }, enabled = ready,
                    )
                    DropdownMenuItem(
                        { Text("重建全文索引") }, { menu = false; scope.launch { sync(showToast = true, rebuild = true) } },
                        leadingIcon = { Icon(Icons.Outlined.Restore, null) }, enabled = ready,
                    )
                    DropdownMenuItem(
                        { Text("选择备份目录") }, { menu = false; picker() },
                        leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) },
                    )
                }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                ListItem(
                    headlineContent = { Text("备份目录") },
                    supportingContent = { Text(if (directory.isEmpty()) "选择文件夹，已有备份也会自动恢复到列表" else Uri.parse(directory).lastPathSegment.orEmpty()) },
                    leadingContent = { Icon(Icons.Outlined.FolderOpen, null) },
                    modifier = Modifier.clickable(onClick = picker),
                )
                Text("打开条目始终读取本地备份。更换设备后重新选择原目录即可恢复列表；本地收藏独立保留。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error.isNotEmpty()) Text(error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                if (records.isEmpty() && !loading) Text("还没有备份，可在动态详情或列表的更多菜单中选择“备份”。", Modifier.padding(16.dp))
            }
            items(records, key = { it.feed.id }) { record ->
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text(record.feed.username.ifBlank { "动态 ${record.feed.id}" }) },
                    supportingContent = {
                        Column {
                            Text(htmlToPlain(record.feed.message), maxLines = 3)
                            Text(DateFormat.getDateTimeInstance().format(Date(record.savedAt)), style = MaterialTheme.typography.labelSmall)
                            if (record.warnings.isNotEmpty()) Text("备份有缺失，打开查看详情", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    leadingContent = { Icon(Icons.Outlined.Inventory2, null) },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "备份操作") }
                            AppDropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("重新备份") }, { menu = false; app.backups.request(record.feed.id) })
                                DropdownMenuItem({ Text("打开在线动态") }, { menu = false; navigator.navigate(FeedRoute(record.feed.id)) })
                                DropdownMenuItem({ Text("删除备份") }, { menu = false; deleting = record })
                            }
                        }
                    },
                    modifier = Modifier.clickable { navigator.navigate(BackupDetailRoute(record.feed.id)) },
                )
                HorizontalDivider()
            }
        }
    }
    deleting?.let { record -> AppAlertDialog(
        onDismissRequest = { deleting = null },
        title = { Text("删除这条动态的全部备份？") },
        text = { Text("会从所选目录删除这条动态的 JSON 和图片 ZIP，本地收藏不受影响。") },
        confirmButton = { TextButton(onClick = {
            deleting = null
            scope.launch {
                try { app.backups.delete(record.feed.id); app.toast("已删除备份") }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { app.toast("删除失败，请检查目录权限后同步列表") }
            }
        }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
    ) }
}

@Composable
fun BackupDetailScreen(id: String, onlineReason: String? = null, retryOnline: (() -> Unit)? = null) {
    val app = appContainer()
    val navigator = LocalNavigator.current
    var backup by remember(id) { mutableStateOf<FeedBackup?>(null) }
    var error by remember(id) { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    var thread by remember { mutableStateOf<Datum?>(null) }
    LaunchedEffect(id, attempt) {
        try {
            app.backups.sync()
            val record = app.backups.records.value.firstOrNull { it.feed.id == id } ?: error("备份已不存在，请返回同步列表")
            backup = app.backups.open(record)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "无法读取备份" }
    }
    CompositionLocalProvider(LocalOfflineBackup provides true,
        LocalAppSettings provides LocalAppSettings.current.copy(mediaAutoPlay = MediaAutoPlay.Off)) {
        Scaffold(topBar = { SimpleTopBar("离线备份") }) { padding ->
            val snapshot = backup
            if (snapshot == null) {
                Column(Modifier.padding(padding).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    if (error.isEmpty()) LoadingIndicator() else {
                        Text(error)
                        TextButton(onClick = { error = ""; attempt++ }) { Text("重试") }
                    }
                }
            } else {
                val data = snapshot.data.with("id", id)
                val articles = remember(snapshot) { runCatching { FeedArticle.parse(data) }.getOrDefault(emptyList()) }
                val completeReplies = remember(snapshot) { snapshot.replies.map(::Datum).associateBy { it.id } }
                fun showReplies(item: Datum) { thread = completeReplies[item.id] ?: item }
                val actions = ItemActions(onShowReplies = ::showReplies, onReply = { target, parent ->
                    val origin = completeReplies[(parent ?: target).id] ?: (parent ?: target)
                    if (origin.replyRows.isNotEmpty()) thread = origin
                })
                LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        if (onlineReason != null) {
                            Text("在线动态无法打开：$onlineReason。正在显示 ${DateFormat.getDateTimeInstance().format(Date(snapshot.savedAt))} 的本地备份", Modifier.padding(16.dp))
                            Row {
                                TextButton(onClick = { navigator.copy(com.zailinn.c001apk.core.backup.BackupText.body(snapshot, snapshot.includesReplies)) }) { Text("复制全文") }
                                retryOnline?.let { retry -> TextButton(onClick = retry) { Text("重试在线") } }
                            }
                        }
                        Text("离线备份不加载网络回复", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                        if (snapshot.imageQuality == com.zailinn.c001apk.core.backup.BackupImageQuality.None) Text("此备份仅包含文字，未缓存图片", Modifier.padding(horizontal = 16.dp))
                        if (snapshot.warnings.isNotEmpty()) Text(snapshot.warnings.joinToString("\n"), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error)
                        if (articles.isEmpty()) FeedCard(data, ItemActions(), detail = true) else ArticleBody(data, articles, ItemActions())
                        HorizontalDivider()
                        Text(if (snapshot.includesReplies) "已备份 ${snapshot.replies.size} 条主回复" else "此备份未包含回复", Modifier.padding(16.dp))
                    }
                    items(snapshot.replies, key = { (it["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: it.hashCode() }) { json ->
                        val original = Datum(json)
                        // Keep long threads out of the eager preview Column; the
                        // sheet below renders their complete contents lazily.
                        val preview = original.with("fetchType", "feed_reply")
                            .withList("replyRows", original.replyRows.take(3))
                            .with("replyRowsMore", (original.replyRows.size - 3).coerceAtLeast(0))
                        ReplyCard(preview, actions)
                        HorizontalDivider()
                    }
                }
            }
        }
        thread?.let { origin ->
            AppBottomSheet(onDismissRequest = { thread = null }) {
                LazyColumn(Modifier.navigationBarsPadding()) {
                    item { Text("备份中的楼中楼", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
                    item { ReplyCard(origin, ItemActions(), nested = true) }
                    items(origin.replyRows, key = { it.id ?: it.hashCode() }) { ReplyCard(it, ItemActions(), nested = true); HorizontalDivider() }
                }
            }
        }
    }
}
