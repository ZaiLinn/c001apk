package com.zailinn.c001apk.backup

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    val navigator = LocalNavigator.current
    val request by app.backups.pending.collectAsStateWithLifecycle()
    val progress by app.backups.progress.collectAsStateWithLifecycle()
    val identity by app.backups.identityRequired.collectAsStateWithLifecycle()
    val records by app.backups.records.collectAsStateWithLifecycle()
    val directoryRevision by app.backups.directoryChanges.collectAsStateWithLifecycle()
    val picker = rememberBackupDirectoryPicker(app) { app.backups.pending.value = null }
    val needsDirectory by app.archiveSync.directoryNeeded.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // The user may keep reading while a backup runs; the dialog then shrinks to a bar.
    var background by remember { mutableStateOf(false) }
    LaunchedEffect(progress == null) { if (progress == null) background = false }
    LaunchedEffect(needsDirectory) {
        if (needsDirectory) {
            val result = snackbar.showSnackbar("开启自动备份需要先选择备份目录", actionLabel = "选择目录", duration = SnackbarDuration.Long)
            app.archiveSync.directoryNeeded.value = false
            if (result == SnackbarResult.ActionPerformed) picker()
        }
    }
    LaunchedEffect(Unit) {
        app.backups.saved.collect { done ->
            launch {
                val result = snackbar.showSnackbar(if (done.incomplete) "已备份，部分内容缺失" else "备份成功", actionLabel = "查看", duration = SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) navigator.navigate(BackupDetailRoute(done.id))
            }
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            progress?.takeIf { background }?.let { state ->
                Surface(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clickable { background = false },
                    shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 4.dp) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (state.total > 0) "${state.message} ${state.done}/${state.total}" else state.message, maxLines = 1,
                                style = MaterialTheme.typography.bodyMedium)
                            BackupProgressBar(state)
                        }
                        TextButton(onClick = app.backups::cancel) { Text("取消") }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.padding(12.dp))
        }
    }
    if (progress == null) identity?.let { BackupIdentityDialog(app, it) }
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
        text = {
            val previous = records.firstOrNull { it.feed.id == request?.id }
            Text((previous?.let { "已有备份保存于 ${DateFormat.getDateTimeInstance().format(Date(it.savedAt))}。\n" } ?: "") +
                "均保留会创建另一份文件；替换会在新备份保存成功后删除这条动态的旧备份。")
        },
        // Three choices do not fit side by side on a narrow screen.
        confirmButton = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                TextButton(onClick = { request?.let { app.backups.start(it, replace = false) } }) { Text("均保留") }
                TextButton(onClick = { request?.let { app.backups.start(it, replace = true) } },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("替换") }
                TextButton(onClick = { app.backups.pending.value = null }) { Text("取消") }
            }
        },
    )
    progress?.takeIf { !background }?.let { state ->
        AppAlertDialog(
            onDismissRequest = { background = true },
            title = { Text("正在备份") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(state.message)
                    BackupProgressBar(state)
                    if (state.total > 0) Text("${state.done}/${state.total}")
                }
            },
            confirmButton = { TextButton(onClick = { background = true }) { Text("后台继续") } },
            dismissButton = { TextButton(onClick = app.backups::cancel) { Text("取消备份") } },
        )
    }
}

@Composable
private fun BackupProgressBar(state: BackupProgress) {
    if (state.total > 0) LinearProgressIndicator(progress = { state.done.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
    else LinearProgressIndicator(Modifier.fillMaxWidth())
}

internal fun imageQualityLabel(name: String): String = when (name) {
    "None" -> "仅文字"; "Preview" -> "缩略图"; "Original" -> "原图"; else -> ""
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun BackupsScreen() {
    val app = appContainer()
    val navigator = LocalNavigator.current
    val records by app.backups.records.collectAsStateWithLifecycle()
    val directory by app.backups.directory.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val picker = rememberBackupDirectoryPicker(app)
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var deleting by remember(directory) { mutableStateOf<List<BackupRecord>>(emptyList()) }
    var autoSettings by remember { mutableStateOf(false) }
    var commentSettings by remember { mutableStateOf(false) }
    var showSettings by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var incompleteOnly by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var selection by remember(directory) { mutableStateOf<Set<String>>(emptySet()) }
    val incomplete = records.count { it.warnings.isNotEmpty() }
    val shown = if (incompleteOnly) records.filter { it.warnings.isNotEmpty() } else records
    val selected = shown.filter { it.feed.id in selection }
    LaunchedEffect(shown) { selection = selection.intersect(shown.map { it.feed.id }.toSet()) }
    suspend fun sync(showToast: Boolean = false, rebuild: Boolean = false) {
        loading = true; error = ""
        try { val count = app.backups.sync(full = rebuild); if (showToast) app.toast("已同步 $count 条备份") }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "无法读取备份目录，请重新选择目录" }
        finally { loading = false }
    }
    LaunchedEffect(directory) { if (directory.isNotEmpty()) sync() }
    androidx.activity.compose.BackHandler(selected.isNotEmpty()) { selection = emptySet() }
    Scaffold(topBar = {
        if (selected.isNotEmpty()) TopAppBar(
            title = { Text("已选 ${selected.size} 条") },
            navigationIcon = { IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Outlined.Close, "取消选择") } },
            actions = {
                IconButton(onClick = { selection = shown.map { it.feed.id }.toSet() }) { Icon(Icons.Outlined.SelectAll, "全选") }
                IconButton(onClick = { deleting = selected }) { Icon(Icons.Outlined.Delete, "删除所选备份") }
            },
        ) else SimpleTopBar("我的备份") {
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
                ListItem(
                    headlineContent = { Text("备份设置") },
                    supportingContent = { Text("自动备份：${autoBackupSummary(settings)} · 回复：" +
                        (if (settings.archiveIncludeReplies) "最多 ${settings.archiveCommentLimit} 条" else "关闭") +
                        if (settings.archiveImagesWifiOnly) " · 仅 WiFi 下载图片" else "") },
                    leadingContent = { Icon(Icons.Outlined.Tune, null) },
                    trailingContent = { Icon(if (showSettings) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (showSettings) "收起" else "展开") },
                    modifier = Modifier.clickable { showSettings = !showSettings },
                )
                if (showSettings) {
                    ListItem(
                        headlineContent = { Text("收藏时自动备份") }, supportingContent = { Text(autoBackupSummary(settings)) },
                        leadingContent = { Icon(Icons.Outlined.BookmarkAdd, null) }, modifier = Modifier.clickable { autoSettings = true },
                    )
                    ListItem(
                        headlineContent = { Text("备份时包含回复") }, supportingContent = { Text(replyRuleSummary(settings)) },
                        leadingContent = { Icon(Icons.Outlined.Forum, null) }, modifier = Modifier.clickable { commentSettings = true },
                    )
                    fun wifiOnly(value: Boolean) { scope.launch { app.settings.set(com.zailinn.c001apk.data.SettingKeys.archiveImagesWifiOnly, value) } }
                    ListItem(
                        headlineContent = { Text("仅在 WiFi 下下载图片") }, supportingContent = { Text("关闭时可使用移动网络下载备份图片") },
                        leadingContent = { Icon(Icons.Outlined.Wifi, null) },
                        trailingContent = { Switch(settings.archiveImagesWifiOnly, ::wifiOnly) },
                        modifier = Modifier.clickable { wifiOnly(!settings.archiveImagesWifiOnly) },
                    )
                }
                HorizontalDivider()
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error.isNotEmpty()) Text(error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                if (incompleteOnly || incomplete > 0) FilterChip(incompleteOnly, { incompleteOnly = !incompleteOnly; selection = emptySet() },
                    label = { Text("只看有缺失（$incomplete）") }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                if (incompleteOnly && incomplete == 0 && records.isNotEmpty() && !loading) {
                    Text("没有缺失的备份", Modifier.padding(16.dp))
                }
                if (records.isEmpty() && !loading) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Inventory2, null, tint = MaterialTheme.colorScheme.outline)
                    if (directory.isEmpty()) {
                        Text("先选择一个保存备份的文件夹。换设备后选回原文件夹，列表会自动恢复。", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Button(onClick = picker) { Text("选择目录") }
                    } else Text("还没有备份，可在动态详情或列表的更多菜单中选择“备份”。", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            items(shown, key = { it.feed.id }) { record ->
                var menu by remember { mutableStateOf(false) }
                val id = record.feed.id
                fun toggle() { selection = if (id in selection) selection - id else selection + id }
                ListItem(
                    headlineContent = { Text(record.feed.username.ifBlank { "动态 $id" }) },
                    supportingContent = {
                        Column {
                            Text(htmlToPlain(record.feed.message), maxLines = 3)
                            Text(listOf(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(record.savedAt)),
                                imageQualityLabel(record.imageQuality), if (record.includesReplies) "含回复" else "")
                                .filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                            if (record.warnings.isNotEmpty()) Text("备份有缺失，打开查看详情", color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    leadingContent = {
                        if (selected.isNotEmpty()) Checkbox(id in selection, { toggle() })
                        else Icon(Icons.Outlined.Inventory2, null)
                    },
                    trailingContent = {
                        if (selected.isEmpty()) Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "备份操作") }
                            AppDropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("重新备份") }, { menu = false; app.backups.request(id) })
                                DropdownMenuItem({ Text("打开在线动态") }, { menu = false; navigator.navigate(FeedRoute(id)) })
                                DropdownMenuItem({ Text("多选") }, { menu = false; toggle() })
                                DropdownMenuItem({ Text("删除备份") }, { menu = false; deleting = listOf(record) })
                            }
                        }
                    },
                    modifier = Modifier.combinedClickable(onLongClick = ::toggle) {
                        if (selected.isNotEmpty()) toggle() else navigator.navigate(BackupDetailRoute(id))
                    },
                )
                HorizontalDivider()
            }
        }
    }
    if (autoSettings) BackupAutoSettingsDialog(app) { autoSettings = false }
    if (commentSettings) BackupCommentSettingsDialog(app) { commentSettings = false }
    if (deleting.isNotEmpty()) DeleteBackupsDialog(app, deleting.map { it.feed.id }.toSet(), directory, { deleting = emptyList() })
}

@Composable
private fun DeleteBackupsDialog(app: AppContainer, ids: Set<String>, directory: String, dismiss: () -> Unit, onDeleted: () -> Unit = {}) {
    AppAlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (ids.size == 1) "删除这条动态的全部备份？" else "删除 ${ids.size} 条动态的全部备份？") },
        text = { Text("会从所选目录删除对应的 JSON 和图片 ZIP，无法撤销。本地收藏不受影响。") },
        confirmButton = { TextButton(onClick = {
            dismiss()
            // Outlives this dialog: the files are deleted even though it closes at once.
            app.scope.launch {
                try { app.backups.delete(ids, directory); app.toast("已删除备份"); kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onDeleted() } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { app.toast("删除失败，请检查目录权限后同步列表") }
            }
        }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } },
    )
}

@Composable
fun BackupDetailScreen(id: String, onlineReason: String? = null, retryOnline: (() -> Unit)? = null) {
    val app = appContainer()
    val navigator = LocalNavigator.current
    val entry = LocalLifecycleOwner.current
    val directory by app.backups.directory.collectAsStateWithLifecycle()
    var backup by remember(id, directory) { mutableStateOf<FeedBackup?>(null) }
    var error by remember(id, directory) { mutableStateOf("") }
    var attempt by remember(id, directory) { mutableIntStateOf(0) }
    var thread by remember(id, directory) { mutableStateOf<Datum?>(null) }
    var deleting by remember(id, directory) { mutableStateOf(false) }
    var expandWarnings by remember(id, directory) { mutableStateOf(false) }
    LaunchedEffect(id, directory, attempt) {
        try {
            app.backups.sync()
            val record = app.backups.records.value.firstOrNull { it.feed.id == id } ?: error("备份已不存在，请返回同步列表")
            backup = app.backups.open(record)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "无法读取备份" }
    }
    fun copyAll(snapshot: FeedBackup) { navigator.copy(com.zailinn.c001apk.core.backup.BackupText.body(snapshot, snapshot.includesReplies)) }
    CompositionLocalProvider(LocalOfflineBackup provides true,
        LocalAppSettings provides LocalAppSettings.current.copy(mediaAutoPlay = MediaAutoPlay.Off)) {
        Scaffold(topBar = { SimpleTopBar("离线备份") {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "备份操作") }
                AppDropdownMenu(menu, { menu = false }) {
                    // As a fallback this page already replaces the online post.
                    if (onlineReason == null) DropdownMenuItem({ Text("打开在线动态") }, { menu = false; navigator.navigate(FeedRoute(id)) },
                        leadingIcon = { Icon(Icons.Outlined.Public, null) })
                    DropdownMenuItem({ Text("重新备份") }, { menu = false; app.backups.request(id) },
                        leadingIcon = { Icon(Icons.Outlined.Sync, null) })
                    DropdownMenuItem({ Text("复制全文") }, { menu = false; backup?.let(::copyAll) },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, enabled = backup != null)
                    DropdownMenuItem({ Text("删除备份") }, { menu = false; deleting = true },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null) }, enabled = backup != null)
                }
            }
        } }) { padding ->
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
                fun showReplies(item: Datum) {
                    val origin = completeReplies[item.id] ?: item
                    if (origin.replyRows.isNotEmpty()) thread = origin
                }
                val actions = ItemActions(onShowReplies = ::showReplies, onReply = { target, parent ->
                    val origin = completeReplies[(parent ?: target).id] ?: (parent ?: target)
                    if (origin.replyRows.isNotEmpty()) thread = origin
                })
                LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (onlineReason != null) Text("在线动态无法打开：$onlineReason。正在显示本地备份。")
                                Text(listOf("保存于 ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(snapshot.savedAt))}",
                                    if (snapshot.imageQuality == com.zailinn.c001apk.core.backup.BackupImageQuality.None) "仅文字，未缓存图片"
                                    else imageQualityLabel(snapshot.imageQuality.name)).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                Text("离线备份不加载网络回复", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (snapshot.warnings.isNotEmpty()) {
                                    Row(Modifier.fillMaxWidth().clickable { expandWarnings = !expandWarnings }, verticalAlignment = Alignment.CenterVertically) {
                                        Text("备份有 ${snapshot.warnings.size} 处缺失", Modifier.weight(1f), color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall)
                                        Icon(if (expandWarnings) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                            if (expandWarnings) "收起缺失详情" else "展开缺失详情", tint = MaterialTheme.colorScheme.error)
                                    }
                                    if (expandWarnings) Text(snapshot.warnings.joinToString("\n"), color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                if (onlineReason != null) Row {
                                    TextButton(onClick = { copyAll(snapshot) }) { Text("复制全文") }
                                    retryOnline?.let { retry -> TextButton(onClick = retry) { Text("重试在线") } }
                                }
                            }
                        }
                        if (articles.isEmpty()) FeedCard(data, ItemActions(), detail = true) else ArticleBody(data, articles, ItemActions())
                        HorizontalDivider()
                        Text(replySummary(snapshot), Modifier.padding(16.dp))
                    }
                    items(snapshot.replies, key = { (it["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: it.hashCode() }) { json ->
                        val original = Datum(json)
                        // Keep long threads out of the eager preview Column; the
                        // sheet below renders their complete contents lazily.
                        // A main-reply-only backup keeps the server's replynum without the
                        // rows: show what was saved, so no entry leads to an empty thread.
                        val preview = original.with("fetchType", "feed_reply")
                            .withList("replyRows", original.replyRows.take(3))
                            .with("replyRowsMore", (original.replyRows.size - 3).coerceAtLeast(0))
                            .let { if (original.replyRows.isEmpty()) it.with("replynum", 0) else it }
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
        if (deleting) DeleteBackupsDialog(app, setOf(id), directory, { deleting = false }) { navigator.backFrom(entry) }
    }
}

internal fun replySummary(backup: com.zailinn.c001apk.core.backup.FeedBackup): String {
    if (!backup.includesReplies) return "此备份未包含回复"
    val nested = backup.replies.sumOf { Datum(it).replyRows.size }
    val saved = "已备份 ${backup.replies.size} 条主回复" + if (nested > 0) "、$nested 条楼中楼" else ""
    val rule = when {
        backup.commentLimit <= 0 -> ""
        backup.includesSubReplies -> "（设置上限 ${backup.commentLimit} 条，含楼中楼）"
        else -> "（设置上限 ${backup.commentLimit} 条，仅主回复）"
    }
    return saved + rule
}
