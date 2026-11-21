package com.zailinn.c001apk.backup

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zailinn.c001apk.core.backup.BackupText
import com.zailinn.c001apk.data.backupOptions
import com.zailinn.c001apk.ui.LocalSession
import com.zailinn.c001apk.ui.common.*
import com.zailinn.c001apk.ui.nav.*
import com.zailinn.c001apk.ui.screens.appContainer
import com.zailinn.c001apk.ui.screens.list.SimpleTopBar
import kotlinx.coroutines.*

@Composable
fun ArchiveSearchScreen(initialScope: String = "all") {
    val app = appContainer()
    val navigator = LocalNavigator.current
    val account = LocalSession.current.uid
    val revision by app.archiveIndex.revision.collectAsStateWithLifecycle()
    val favorites by app.content.favorites.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var range by remember { mutableStateOf(initialScope) }
    var collection by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("relevance") }
    var limit by remember { mutableIntStateOf(100) }
    var loading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<ArchiveHit>>(emptyList()) }
    var selected by remember { mutableStateOf<ArchiveHit?>(null) }
    var deleting by remember { mutableStateOf<ArchiveHit?>(null) }
    val coroutine = rememberCoroutineScope()
    var collections by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(revision, account) {
        collections = withContext(Dispatchers.IO) { app.backups.awaitReady(); app.archiveIndex.cloudCollections(account) }
    }
    LaunchedEffect(query, range, collection, sort, limit, revision, favorites, account) {
        loading = true
        delay(300)
        searchError = ""
        try {
        hits = withContext(Dispatchers.IO) {
            app.backups.awaitReady()
            val backed = app.archiveIndex.search(query, range, account, collection, sort, limit)
            if (range !in listOf("all", "local")) backed else {
                val terms = BackupText.terms(query)
                val extra = favorites.filter { app.archiveIndex.row(it.id) == null && terms.all { word -> word in BackupText.normalize(it.username + " " + BackupText.plain(it.message)) } }.map {
                    ArchiveHit(BackupRecord(it, "", it.time), BackupText.plain(it.message), listOf(mapOf("kind" to "local")), "None", false, "unknown",
                        terms.sumOf { term -> BackupText.normalize(BackupText.plain(it.message)).windowed(term.length, 1).count { part -> part == term } })
                }
                val combined = backed + extra
                (when (sort) {
                    "published" -> combined.sortedByDescending { it.record.feed.dateline }
                    "saved" -> combined.sortedByDescending { it.record.savedAt }
                    else -> combined.sortedWith(compareByDescending<ArchiveHit> { it.relevance }.thenByDescending { it.record.savedAt })
                }).take(limit)
            }
        }
        }
        catch (e: CancellationException) { throw e }
        catch (e: IllegalArgumentException) { searchError = e.message ?: "无法执行搜索"; hits = emptyList() }
        catch (_: Exception) { searchError = "无法读取索引，请在我的备份中重建索引"; hits = emptyList() }
        finally { loading = false }
    }
    fun copy(hit: ArchiveHit) { coroutine.launch {
        try {
            val text = if (hit.record.base.isEmpty()) BackupText.plain(hit.record.feed.message) else BackupText.body(app.backups.open(hit.record), true)
            navigator.copy(text)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { app.toast("无法读取备份，请同步目录后重试") }
    } }
    Scaffold(topBar = {
        SimpleTopBar("搜索收藏与备份") {
            IconButton(onClick = { navigator.navigate(ArchiveExportRoute(range, query, collection)) }) { Icon(Icons.Outlined.FileDownload, "导出当前结果") }
        }
    }) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(query, { query = it; limit = 100 }, Modifier.fillMaxWidth().padding(12.dp), label = { Text("搜索全文、作者、话题或回复") }, singleLine = true)
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                var openRange by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { openRange = true }) { Text(when (range) { "local" -> "本地收藏"; "cloud" -> if (collection.isEmpty()) "云端收藏" else collections.firstOrNull { it.first == collection }?.second ?: "指定收藏夹"; else -> "全部备份" }) }
                    AppDropdownMenu(openRange, { openRange = false }) {
                        listOf("all" to "全部", "local" to "本地收藏", "cloud" to "云端收藏").forEach { (value, label) -> DropdownMenuItem({ Text(label) }, { range = value; collection = ""; limit = 100; openRange = false }) }
                        collections.forEach { (id, name) -> DropdownMenuItem({ Text(name) }, { range = "cloud"; collection = id; limit = 100; openRange = false }) }
                    }
                }
                var openSort by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { openSort = true }) { Text(when (sort) { "published" -> "发布时间"; "saved" -> "备份时间"; else -> "相关" }) }
                    AppDropdownMenu(openSort, { openSort = false }) {
                        listOf("relevance" to "相关", "published" to "发布时间", "saved" to "备份时间").forEach { (value, name) -> DropdownMenuItem({ Text(name) }, { sort = value; openSort = false }) }
                    }
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (searchError.isNotEmpty()) Text(searchError, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            LazyColumn {
                if (hits.isEmpty() && !loading) item { Text("暂无匹配内容，请同步云端收藏或选择备份目录。", Modifier.padding(16.dp)) }
                items(hits, key = { it.record.feed.id }) { hit ->
                    val labels = buildList {
                        if (hit.record.base.isEmpty()) add("未备份全文")
                        if (hit.quality == "None") add("仅文字")
                        if (hit.incomplete) add("有缺失")
                        if (hit.onlineState == "gone") add("已失效")
                        hit.sources.forEach { source ->
                            add(if (source["kind"] == "local") "本地收藏" else source["collection_title"].orEmpty().ifBlank { "云端收藏" })
                            if (source["removed_at"].orEmpty().isNotEmpty()) add("已取消收藏")
                        }
                    }.distinct().joinToString(" · ")
                    ListItem(
                        headlineContent = { Text(hit.record.feed.username.ifBlank { "动态 ${hit.record.feed.id}" }) },
                        supportingContent = { Column {
                            Text(highlightSnippet(hit.snippet, BackupText.terms(query), MaterialTheme.colorScheme.primary))
                            Text(labels, style = MaterialTheme.typography.labelSmall)
                            Text(com.zailinn.c001apk.ui.common.fromToday(hit.record.feed.dateline), style = MaterialTheme.typography.labelSmall)
                        } },
                        modifier = Modifier.combinedClickable(onClick = { navigator.navigate(FeedRoute(hit.record.feed.id, backupFallback = true)) }, onLongClick = { selected = hit }),
                    )
                    HorizontalDivider()
                }
                if (hits.size >= limit) item { TextButton(onClick = { limit += 100 }) { Text("加载更多") } }
                if (favorites.any { favorite -> hits.any { it.record.feed.id == favorite.id && it.record.base.isEmpty() } }) item {
                    TextButton(onClick = { navigator.navigate(ArchiveSyncRoute(local = true)) }) { Text("备份全部本地收藏") }
                }
            }
        }
    }
    selected?.let { hit -> ActionSheet(buildList {
        if (hit.record.base.isNotEmpty()) add(SheetAction(Icons.Outlined.Inventory2, "打开本地备份") { navigator.navigate(BackupDetailRoute(hit.record.feed.id)) })
        add(SheetAction(Icons.Outlined.OpenInNew, "打开在线动态") { navigator.navigate(FeedRoute(hit.record.feed.id)) })
        add(SheetAction(Icons.Outlined.ContentCopy, if (hit.record.base.isEmpty()) "复制预览" else "复制全文") { copy(hit) })
        if (hit.record.base.isNotEmpty()) add(SheetAction(Icons.Outlined.Delete, "删除备份", danger = true) { deleting = hit })
    }, onDismiss = { selected = null }) }
    deleting?.let { hit -> AppAlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这条动态的全部备份？") },
        confirmButton = { TextButton(onClick = { deleting = null; coroutine.launch { try { app.backups.delete(hit.record.feed.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { app.toast("删除失败，请检查目录权限") } } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

internal fun highlightSnippet(text: String, terms: List<String>, color: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    append(text)
    terms.forEach { term ->
        var from = 0
        while (from < text.length) {
            val start = text.indexOf(term, from, ignoreCase = true); if (start < 0) break
            addStyle(SpanStyle(color = color, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), start, start + term.length)
            from = start + term.length
        }
    }
}

@Composable
fun ArchiveExportScreen(initialScope: String = "all", initialQuery: String = "", initialCollection: String = "") {
    val app = appContainer()
    val navigator = LocalNavigator.current
    val account = LocalSession.current.uid
    val exporter = app.archiveExporter
    val context = androidx.compose.ui.platform.LocalContext.current
    val progress by exporter.progress.collectAsStateWithLifecycle()
    val coroutine = rememberCoroutineScope()
    var range by remember { mutableStateOf(initialScope) }
    var query by remember { mutableStateOf(initialQuery) }
    var collection by remember { mutableStateOf(initialCollection) }
    var format by remember { mutableStateOf(ArchiveExportFormat.Text) }
    var replies by remember { mutableStateOf(false) }
    var ascending by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var estimated by remember { mutableLongStateOf(0) }
    var error by remember { mutableStateOf("") }
    var task by remember { mutableStateOf<Job?>(null) }
    var prepared by remember { mutableStateOf<PreparedArchive?>(null) }
    var saveMime by remember { mutableStateOf("application/octet-stream") }
    var collections by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val contract = remember { object : androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream") {
        override fun createIntent(context: android.content.Context, input: String): android.content.Intent = super.createIntent(context, input).setType(saveMime)
    } }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(contract) { uri ->
        val file = prepared
        if (file != null) {
            if (uri == null) { exporter.discard(file); prepared = null } else task = coroutine.launch {
                try { exporter.write(file, uri); app.toast("导出完成") }
                catch (e: CancellationException) { app.toast("已取消导出"); throw e }
                catch (_: Exception) { app.toast("导出失败，请检查存储空间和文件权限") }
                finally { prepared = null }
            }
        }
    }
    LaunchedEffect(range, collection, query, format, account) {
        delay(300); error = ""
        try {
            withContext(Dispatchers.IO) {
                app.backups.awaitReady()
                collections = app.archiveIndex.cloudCollections(account)
                selectedIds = app.archiveIndex.search(query, range, account, collection, limit = 20000).map { it.record.feed.id }
                estimated = if (app.backups.directory.value.isNotEmpty()) app.backups.estimateExport(selectedIds.toSet(), format == ArchiveExportFormat.HtmlImages) else 0
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "无法读取备份目录，请先选择目录并同步索引" }
    }
    DisposableEffect(Unit) { onDispose { task?.cancel(); prepared?.let(exporter::discard) } }
    Scaffold(topBar = { SimpleTopBar("导出收藏与备份") }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("只读取本地备份，不请求网络。图片使用备份中已保存的清晰度。")
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { open = true }) { Text(when (range) { "local" -> "本地收藏"; "cloud" -> if (collection.isEmpty()) "云端收藏" else collections.firstOrNull { it.first == collection }?.second ?: "指定收藏夹"; else -> "全部备份" }) }
                AppDropdownMenu(open, { open = false }) {
                    listOf("all" to "全部备份", "local" to "本地收藏", "cloud" to "云端收藏").forEach { (value, label) -> DropdownMenuItem({ Text(label) }, { range = value; collection = ""; open = false }) }
                    collections.forEach { (id, title) -> DropdownMenuItem({ Text(title) }, { range = "cloud"; collection = id; open = false }) }
                }
            }
            if (initialQuery.isNotEmpty()) Text("当前结果：$query")
            ArchiveExportFormat.entries.forEach { choice ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(format == choice, { format = choice })
                    Text(when (choice) { ArchiveExportFormat.Text -> "TXT"; ArchiveExportFormat.Html -> "HTML（仅文字）"; ArchiveExportFormat.HtmlImages -> "HTML 含图片（ZIP）" })
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text("包含已备份的回复", Modifier.weight(1f)); Switch(replies, { replies = it }) }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text(if (ascending) "发布时间：从早到晚" else "发布时间：从晚到早", Modifier.weight(1f)); Switch(ascending, { ascending = it }) }
            Text("${selectedIds.size} 条动态 · 准备空间估算 ${estimated / (1024 * 1024)} MiB")
            Text("文字 HTML 超过 64 MiB 时会分卷并打包为 ZIP。")
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            Button(onClick = { task = coroutine.launch {
                error = ""
                try {
                    val result = exporter.prepare(selectedIds, format, replies, ascending)
                    prepared = result; saveMime = result.mime
                    val date = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(java.util.Date())
                    picker.launch("c001apk_收藏_$date${result.suffix}")
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "无法准备导出" }
            } }, enabled = selectedIds.isNotEmpty() && progress == null && prepared == null) { Text("选择保存位置并导出") }
            TextButton(onClick = { navigator.navigate(BackupsRoute) }) { Text("选择备份目录 / 重建索引") }
            progress?.let { state ->
                Text(state.message)
                if (state.total > 0) { LinearProgressIndicator(progress = { state.done.toFloat() / state.total }); Text("${state.done}/${state.total}") }
                else LinearProgressIndicator()
                TextButton(onClick = { task?.cancel() }) { Text("取消导出") }
            }
        }
    }
}

@Composable
fun ArchiveSyncScreen(local: Boolean = false) {
    val app = appContainer()
    val navigator = LocalNavigator.current
    val session = LocalSession.current
    val sync = app.archiveSync
    val config by app.settings.settings.collectAsStateWithLifecycle()
    val state by sync.status.collectAsStateWithLifecycle()
    val collections by sync.collections.collectAsStateWithLifecycle()
    val directory by app.backups.directory.collectAsStateWithLifecycle()
    val revision by app.archiveIndex.revision.collectAsStateWithLifecycle()
    val coroutine = rememberCoroutineScope()
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var force by remember { mutableStateOf(false) }
    var failures by remember { mutableStateOf<List<ArchiveQueueItem>>(emptyList()) }
    var expandFailures by remember { mutableStateOf(false) }
    var continueAfterPicker by remember { mutableStateOf(false) }
    var commentSettings by remember { mutableStateOf(false) }
    val picker = rememberBackupDirectoryPicker(app, onCancel = { continueAfterPicker = false })
    val options = config.backupOptions(
        runCatching { com.zailinn.c001apk.core.backup.BackupImageQuality.valueOf(config.archiveImageQuality) }
            .getOrDefault(com.zailinn.c001apk.core.backup.BackupImageQuality.Original))
    suspend fun load() {
        loading = true; error = ""
        try { sync.loadCollections(); selected = sync.collections.value.filter { it.readable }.map { it.id }.toSet() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val failure = com.zailinn.c001apk.core.net.ApiFailure.from(e)
            error = failure.message
            if (failure.kind == com.zailinn.c001apk.core.net.ApiFailureKind.Captcha) sync.status.value = sync.status.value.copy(captcha = true, paused = true, phase = "读取收藏夹列表：接口要求人机验证，完成后可继续")
        } finally { loading = false }
    }
    LaunchedEffect(local, session.uid) { if (!local && session.isLogin) load() }
    LaunchedEffect(revision) { failures = withContext(Dispatchers.IO) { app.archiveIndex.queue().filter { it.state == "failed" } } }
    suspend fun start() {
        if (directory.isEmpty()) { continueAfterPicker = true; picker(); return }
        if (local) sync.startLocal(app.content.favorites.value.map { it.id }, options, force)
        else sync.startCloud(collections.filter { it.id in selected }, options, force)
    }
    LaunchedEffect(directory) { if (continueAfterPicker && directory.isNotEmpty()) { continueAfterPicker = false; start() } }
    if (commentSettings) BackupCommentSettingsDialog(app) { commentSettings = false }
    Scaffold(topBar = { SimpleTopBar(if (local) "备份全部本地收藏" else "同步云端收藏") }) { padding ->
        val idle = !state.running
        fun set(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, value: Boolean) { coroutine.launch { app.settings.set(key, value) } }
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                ListItem(
                    headlineContent = { Text("备份目录") },
                    supportingContent = { Text(if (directory.isEmpty()) "尚未选择" else android.net.Uri.parse(directory).lastPathSegment.orEmpty(), maxLines = 2) },
                    leadingContent = { Icon(Icons.Outlined.FolderOpen, null) },
                    modifier = Modifier.clickable(enabled = idle, onClick = picker),
                )
                SectionTitle("备份档位")
                com.zailinn.c001apk.core.backup.BackupImageQuality.entries.forEach { quality ->
                    fun select() { coroutine.launch { app.settings.set(com.zailinn.c001apk.data.SettingKeys.archiveImageQuality, quality.name) } }
                    Row(Modifier.fillMaxWidth().clickable(enabled = idle) { select() }.padding(horizontal = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(options.images == quality, ::select, enabled = idle)
                        Text(when (quality) { com.zailinn.c001apk.core.backup.BackupImageQuality.None -> "仅文字"; com.zailinn.c001apk.core.backup.BackupImageQuality.Preview -> "文字和缩略图"; com.zailinn.c001apk.core.backup.BackupImageQuality.Original -> "文字和原图" })
                    }
                }
                ListItem(
                    headlineContent = { Text("备份时包含回复") },
                    supportingContent = { Text(replyRuleSummary(config)) },
                    modifier = Modifier.clickable(enabled = idle) { commentSettings = true },
                    trailingContent = { Switch(config.archiveIncludeReplies, { set(com.zailinn.c001apk.data.SettingKeys.archiveSaveComments, it) }, enabled = idle) },
                )
                ListItem(
                    headlineContent = { Text("仅在 WiFi 下下载图片") },
                    supportingContent = { Text("移动网络下先保存文字，回到 WiFi 再同步补全图片") },
                    modifier = Modifier.clickable(enabled = idle) { set(com.zailinn.c001apk.data.SettingKeys.archiveImagesWifiOnly, !config.archiveImagesWifiOnly) },
                    trailingContent = { Switch(config.archiveImagesWifiOnly, { set(com.zailinn.c001apk.data.SettingKeys.archiveImagesWifiOnly, it) }, enabled = idle) },
                )
                ListItem(
                    headlineContent = { Text("全部重新备份") },
                    supportingContent = { Text("关闭时跳过未变化且档位足够的备份") },
                    modifier = Modifier.clickable(enabled = idle) { force = !force },
                    trailingContent = { Switch(force, { force = it }, enabled = idle) },
                )
                if (state.running) Text("同步进行中，暂停后可修改以上选项。", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!local) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("收藏夹", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { coroutine.launch { load() } }, enabled = !loading && idle && session.isLogin) { Text("重新读取") }
                    }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (error.isNotEmpty()) Text(error, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                    if (!session.isLogin) Text("登录后才能同步云端收藏", Modifier.padding(horizontal = 16.dp))
                }
            }
            if (!local) items(collections, key = { it.id }) { collection ->
                val enabled = idle && collection.readable
                fun toggle() { selected = if (collection.id in selected) selected - collection.id else selected + collection.id }
                Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { toggle() }.padding(horizontal = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(collection.id in selected, { toggle() }, enabled = enabled)
                    Text("${collection.title}（${collection.count}）" + if (!collection.readable) " · 无法读取" else "")
                }
            }
            item {
                // One card: what is happening, how far it is, and the single next step.
                Surface(Modifier.fillMaxWidth().padding(16.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(state.phase, style = MaterialTheme.typography.titleMedium)
                        if (state.total > 0) {
                            LinearProgressIndicator(progress = { (state.completed.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text("已完成 ${state.completed}/${state.total}" + (if (state.failed > 0) " · 失败 ${state.failed}" else "") +
                                (if (state.running && state.etaSeconds > 0) " · 预计剩余 ${etaText(state.etaSeconds)}" else ""),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.fillMaxWidth()) {
                            SyncStat("新增", state.added); SyncStat("更新", state.updated); SyncStat("跳过", state.skipped)
                            if (!local) SyncStat("已取消收藏", state.removed)
                        }
                        if (state.imagesDeferred) Text("非 WiFi 下已按仅文字备份，可在 WiFi 下再次同步补全图片。", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when {
                                state.running -> OutlinedButton(onClick = sync::pause) { Text("暂停") }
                                state.captcha -> Button(onClick = sync::verify, enabled = !state.verifying) { Text(if (state.verifying) "正在验证…" else "完成验证") }
                                state.needsIdentity -> Button(onClick = sync::requestIdentityRetry) { Text("填写数盟 ID 并重试") }
                                state.paused -> Button(onClick = { coroutine.launch { sync.resume() } }) { Text("继续") }
                                else -> Button(onClick = { coroutine.launch { start() } }, enabled = local || (session.isLogin && selected.isNotEmpty() && !loading)) { Text("开始备份") }
                            }
                            if (!session.isLogin && (!local || state.paused)) TextButton(onClick = { navigator.navigate(LoginRoute()) }) { Text("去登录") }
                            if (state.running || state.paused) TextButton(onClick = { coroutine.launch { sync.cancel() } }) { Text("取消同步") }
                        }
                        Text("每次最多备份 500 条，可暂停并在下次继续。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (failures.isNotEmpty()) ListItem(
                    headlineContent = { Text("失败 ${failures.size} 条") },
                    supportingContent = { Text(if (expandFailures) "点击收起" else "点击查看原因") },
                    leadingContent = { Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
                    trailingContent = { TextButton(onClick = { coroutine.launch { sync.retryFailures() } }, enabled = idle) { Text("重试失败项") } },
                    modifier = Modifier.clickable { expandFailures = !expandFailures },
                )
            }
            if (expandFailures) items(failures, key = { it.id }) {
                ListItem(headlineContent = { Text("动态 ${it.id}") }, supportingContent = { Text(it.error.ifEmpty { "未知原因" }) },
                    modifier = Modifier.clickable { navigator.navigate(FeedRoute(it.id)) })
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun RowScope.SyncStat(label: String, value: Int) {
    Column(Modifier.weight(1f), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text("$value", style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

internal fun etaText(seconds: Long): String = when {
    seconds < 60 -> "$seconds 秒"
    seconds < 3600 -> "${seconds / 60} 分 ${seconds % 60} 秒"
    else -> "${seconds / 3600} 小时 ${seconds % 3600 / 60} 分"
}
