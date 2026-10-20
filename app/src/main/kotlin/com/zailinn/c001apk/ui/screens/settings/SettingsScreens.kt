package com.zailinn.c001apk.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Feed
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SwitchAccount
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material3.Checkbox
import androidx.compose.ui.Alignment
import com.zailinn.c001apk.ui.screens.main.HomeTab
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zailinn.c001apk.BuildConfig
import com.zailinn.c001apk.core.protocol.Constants
import com.zailinn.c001apk.core.update.UpdateResult
import com.zailinn.c001apk.core.update.UpdateStatus
import com.zailinn.c001apk.data.SettingKeys
import com.zailinn.c001apk.ui.LocalAppSettings
import com.zailinn.c001apk.ui.LocalSession
import com.zailinn.c001apk.ui.common.SectionTitle
import com.zailinn.c001apk.ui.nav.AboutRoute
import com.zailinn.c001apk.ui.nav.AccountsRoute
import com.zailinn.c001apk.ui.nav.ContentFilterRoute
import com.zailinn.c001apk.ui.nav.LocalFollowRoute
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.nav.ParamsRoute
import com.zailinn.c001apk.ui.nav.SettingsSectionRoute
import com.zailinn.c001apk.ui.screens.appContainer
import com.zailinn.c001apk.ui.screens.list.SimpleTopBar
import com.zailinn.c001apk.ui.theme.seedColorNames
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.BoxWithConstraints

enum class SettingsSection(val title: String, val description: String, val icon: ImageVector) {
    Appearance("外观与字号", "动态取色、主题、深色模式、字体大小与粗细", Icons.Outlined.Palette),
    Content("关注与内容", "首页标签、本地关注、悄悄关注、返回刷新与表情", Icons.Outlined.Feed),
    Filter("内容过滤", "用户与话题黑名单、敏感话题折叠或隐藏、语义屏蔽", Icons.Outlined.FilterAlt),
    Privacy("浏览与隐私", "浏览历史、外部链接", Icons.Outlined.PrivacyTip),
    Storage("存储与更新", "图片缓存、版本与更新检查", Icons.Outlined.Storage),
    Protocol("协议与设备", "设备标识与客户端请求参数", Icons.Outlined.DeveloperMode),
}

/** Categories; on wide windows the selected category is shown beside the list. */
@Composable
fun SettingsScreen() {
    val navigator = LocalNavigator.current
    val app = appContainer()
    val vault by app.accounts.vault.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(SettingsSection.Appearance) }
    Scaffold(topBar = { SimpleTopBar("设置") }) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            val categories = @Composable {
                LazyColumn {
                    items(SettingsSection.entries) { section ->
                        ListItem(
                            headlineContent = { Text(section.title) },
                            supportingContent = { Text(section.description) },
                            leadingContent = { Icon(section.icon, null) },
                            modifier = Modifier.clickable {
                                when {
                                    // A page of its own, also on wide windows.
                                    section == SettingsSection.Filter -> navigator.navigate(ContentFilterRoute)
                                    wide -> selected = section
                                    else -> navigator.navigate(SettingsSectionRoute(section.name))
                                }
                            },
                        )
                    }
                    item { HorizontalDivider() }
                    item {
                        ListItem(
                            headlineContent = { Text("账号管理") },
                            supportingContent = { Text("已保存 ${vault.accounts.size} 个账号 · 切换、添加或移除") },
                            leadingContent = { Icon(Icons.Outlined.SwitchAccount, null) },
                            modifier = Modifier.clickable { navigator.navigate(AccountsRoute) },
                        )
                    }
                    item {
                        ListItem(
                            headlineContent = { Text("关于") },
                            leadingContent = { Icon(Icons.Outlined.Info, null) },
                            modifier = Modifier.clickable { navigator.navigate(AboutRoute) },
                        )
                    }
                }
            }
            if (wide) {
                Row {
                    Column(Modifier.width(320.dp)) { categories() }
                    VerticalDivider()
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        SectionTitle(selected.title)
                        SectionItems(selected)
                    }
                }
            } else {
                categories()
            }
        }
    }
}

@Composable
fun SettingsSectionScreen(name: String) {
    val section = SettingsSection.valueOf(name)
    Scaffold(topBar = { SimpleTopBar(section.title) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) { SectionItems(section) }
    }
}

@Composable
private fun <T> rememberSetter(): (Preferences.Key<T>, T) -> Unit {
    val app = appContainer()
    val scope = rememberCoroutineScope()
    return { key, value -> scope.launch { app.settings.set(key, value) } }
}

@Composable
private fun SwitchItem(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Switch(checked, onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun ChoiceItem(icon: ImageVector, title: String, subtitle: String, options: List<String>, selected: Int, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text("$subtitle · ${options.getOrNull(selected).orEmpty()}") },
        leadingContent = { Icon(icon, null) },
        trailingContent = {
            DropdownMenu(open, { open = false }) {
                options.forEachIndexed { index, label ->
                    DropdownMenuItem({ Text(label) }, { open = false; onSelect(index) })
                }
            }
        },
        modifier = Modifier.clickable(enabled = enabled) { open = true },
    )
}

@Composable
private fun SectionItems(section: SettingsSection) {
    val navigator = LocalNavigator.current
    val app = appContainer()
    val settings = LocalAppSettings.current
    val session = LocalSession.current
    val scope = rememberCoroutineScope()
    val setBool = rememberSetter<Boolean>()
    val setInt = rememberSetter<Int>()
    when (section) {
        SettingsSection.Appearance -> {
            SwitchItem(Icons.Outlined.Palette, "动态取色", "根据系统配色调整应用主题", settings.dynamicColor) { setBool(SettingKeys.dynamicColor, it) }
            ChoiceItem(
                Icons.Outlined.FormatColorFill, "主题颜色", "关闭动态取色后可选择固定配色", seedColorNames, settings.staticColor,
                enabled = !settings.dynamicColor,
            ) { setInt(SettingKeys.staticColor, it) }
            val modes = listOf("跟随系统", "始终关闭", "始终开启")
            ChoiceItem(Icons.Outlined.DarkMode, "深色模式", "选择浅色、深色或跟随系统", modes, settings.themeMode) { setInt(SettingKeys.themeMode, it) }
            var fontDialog by remember { mutableStateOf(false) }
            ListItem(
                headlineContent = { Text("字体大小") },
                supportingContent = { Text("调整界面文字大小 · ${"%.2f".format(settings.fontScale)}x") },
                leadingContent = { Icon(Icons.Outlined.TextFields, null) },
                modifier = Modifier.clickable { fontDialog = true },
            )
            ChoiceItem(
                Icons.Outlined.FormatBold, "字体粗细", "使用系统字体，可整体加粗", listOf("标准", "中等", "加粗"), settings.fontWeightLevel,
            ) { setInt(SettingKeys.fontWeightLevel, it) }
            if (fontDialog) {
                var value by remember { mutableFloatStateOf(settings.fontScale) }
                AlertDialog(
                    onDismissRequest = { fontDialog = false },
                    title = { Text("字体大小") },
                    text = {
                        Column {
                            Text("${"%.2f".format(value)}x", style = MaterialTheme.typography.titleMedium)
                            Slider(value, { value = (it * 20).toInt() / 20f }, valueRange = 0.8f..1.5f, steps = 13)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            fontDialog = false
                            scope.launch { app.settings.set(SettingKeys.fontScale, value) }
                        }) { Text("确定") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            fontDialog = false
                            scope.launch { app.settings.set(SettingKeys.fontScale, 1f) }
                        }) { Text("重置") }
                    },
                )
            }
        }
        SettingsSection.Content -> {
            SectionTitle("首页")
            var tabsDialog by remember { mutableStateOf(false) }
            ListItem(
                headlineContent = { Text("首页标签") },
                supportingContent = { Text("显示、隐藏或调整顺序，也可长按首页任意标签打开 · ${HomeTab.visible(settings.homeTabs).joinToString("、") { it.label }}") },
                leadingContent = { Icon(Icons.Outlined.Tab, null) },
                modifier = Modifier.clickable { tabsDialog = true },
            )
            if (tabsDialog) {
                HomeTabsDialog(HomeTab.visible(settings.homeTabs), onDismiss = { tabsDialog = false }) { tabs ->
                    scope.launch { app.settings.set(SettingKeys.homeTabs, tabs.joinToString(",") { it.name }) }
                }
            }
            SectionTitle("关注")
            val label = if (session.isLogin) "悄悄关注" else "本地关注"
            if (session.isLogin) {
                SwitchItem(
                    Icons.Outlined.VisibilityOff, "悄悄关注", "开启后，长按用户主页的关注按钮添加或取消。仅保存在本机，不会同步到账号",
                    settings.secretFollow,
                ) { setBool(SettingKeys.secretFollow, it) }
            } else {
                ListItem(
                    headlineContent = { Text("本地关注") },
                    supportingContent = { Text("游客可在用户主页直接关注，并在底栏“关注”查看内容。名单仅保存在本机") },
                    leadingContent = { Icon(Icons.Outlined.PersonAddAlt, null) },
                )
            }
            ListItem(
                headlineContent = { Text("管理$label") },
                supportingContent = { Text("查看本机关注名单、进入用户主页或取消关注") },
                leadingContent = { Icon(Icons.Outlined.ManageAccounts, null) },
                modifier = Modifier.clickable { navigator.navigate(LocalFollowRoute) },
            )
            SwitchItem(
                Icons.Outlined.Refresh, "返回首页时自动刷新", "从其他页面或底栏回到主页时，当前列表回到顶部并刷新",
                settings.refreshOnReturn,
            ) { setBool(SettingKeys.refreshOnReturn, it) }
            SwitchItem(Icons.Outlined.EmojiEmotions, "显示表情", "将内容中的酷安表情标记显示为图片", settings.showEmoji) { setBool(SettingKeys.showEmoji, it) }
        }
        SettingsSection.Privacy -> {
            SwitchItem(Icons.Outlined.History, "记录浏览历史", "将浏览过的动态保存在本机，便于再次查看", settings.recordHistory) {
                setBool(SettingKeys.recordHistory, it)
            }
            SwitchItem(Icons.Outlined.TravelExplore, "在浏览器中打开", "使用系统浏览器打开外部网页链接", settings.openInBrowser) {
                setBool(SettingKeys.openInBrowser, it)
            }
        }
        SettingsSection.Filter -> Unit
        SettingsSection.Storage -> StorageItems()
        SettingsSection.Protocol -> {
            var editing by remember { mutableStateOf(false) }
            ListItem(
                headlineContent = { Text("设备标识（SZLM ID）") },
                supportingContent = { Text("用于客户端请求，修改后可能影响接口访问\n${settings.protocol.szlmId.ifEmpty { "未设置" }}") },
                leadingContent = { Icon(Icons.Outlined.DeveloperMode, null) },
                modifier = Modifier.clickable { editing = true },
            )
            ListItem(
                headlineContent = { Text("协议参数") },
                supportingContent = { Text("配置客户端版本、设备与请求参数") },
                leadingContent = { Icon(Icons.Outlined.DeveloperMode, null) },
                modifier = Modifier.clickable { navigator.navigate(ParamsRoute) },
            )
            if (editing) {
                EditDialog("设备标识（SZLM ID）", settings.protocol.szlmId, onDismiss = { editing = false }) { value ->
                    scope.launch { app.settings.saveProtocol(settings.protocol.copy(szlmId = value).withDevice()) }
                }
            }
        }
    }
}

@Composable
private fun StorageItems() {
    val app = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(app.updates.preferences.enabled) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateResult?>(null) }
    var cache by remember { mutableStateOf(cacheSize(context)) }
    SwitchItem(Icons.Outlined.Update, "自动检查 c001apk 更新", "启动后检查最新正式版本，每 24 小时最多一次", enabled) {
        enabled = it
        scope.launch { app.updates.preferences.setEnabled(it) }
    }
    ListItem(
        headlineContent = { Text("检查 c001apk 更新") },
        supportingContent = { Text(if (checking) "正在检查…" else "当前版本 ${BuildConfig.VERSION_NAME}") },
        leadingContent = { Icon(Icons.Outlined.Update, null) },
        modifier = Modifier.clickable(enabled = !checking) {
            checking = true
            scope.launch {
                val r = app.updates.check()
                checking = false
                when (r.status) {
                    UpdateStatus.Available -> result = r
                    UpdateStatus.UpToDate -> app.toast("已是最新版本")
                    UpdateStatus.Failed -> app.toast(r.message)
                    else -> Unit
                }
            }
        },
    )
    ListItem(
        headlineContent = { Text("清理缓存") },
        supportingContent = { Text(cache) },
        leadingContent = { Icon(Icons.Outlined.CleaningServices, null) },
        modifier = Modifier.clickable {
            scope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                    coil3.SingletonImageLoader.get(context).let { loader ->
                        loader.memoryCache?.clear()
                        loader.diskCache?.clear()
                    }
                }
                cache = cacheSize(context)
                app.toast("已清理")
            }
        },
    )
    result?.let { UpdateDialog(it) { result = null } }
}

private fun cacheSize(context: android.content.Context): String {
    val bytes = context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    return when {
        bytes >= 1 shl 30 -> "%.2f GB".format(bytes / (1 shl 30).toDouble())
        bytes >= 1 shl 20 -> "%.2f MB".format(bytes / (1 shl 20).toDouble())
        else -> "%.2f KB".format(bytes / 1024.0)
    }
}

/**
 * Visible tabs first in their order, hidden ones after; at least one tab stays
 * visible. Rows are reordered by dragging the handle, or the row after a long press.
 */
@Composable
fun HomeTabsDialog(visible: List<HomeTab>, onDismiss: () -> Unit, onSave: (List<HomeTab>) -> Unit) {
    var order by remember { mutableStateOf(visible + HomeTab.entries.filter { it !in visible }) }
    var shown by remember { mutableStateOf(visible.toSet()) }
    var dragging by remember { mutableStateOf<HomeTab?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(0) }
    // Finger positions are in root coordinates: the lifted row moves under the
    // finger, so its own coordinates would hide part of the movement.
    var startY by remember { mutableFloatStateOf(0f) }
    var shift by remember { mutableFloatStateOf(0f) }
    fun start(tab: HomeTab, y: Float) {
        dragging = tab
        startY = y
        shift = 0f
        offset = 0f
    }
    // Swap with a neighbour once the row is dragged past half of it; the swap
    // moves the row by one row height, which the offset gives back.
    fun move(tab: HomeTab, y: Float) {
        offset = y - startY - shift
        val index = order.indexOf(tab)
        val half = rowHeight / 2f
        if (half <= 0f) return
        if (offset > half && index < order.lastIndex) {
            order = order.toMutableList().apply { add(index + 1, removeAt(index)) }
            shift += rowHeight
            offset -= rowHeight
        } else if (offset < -half && index > 0) {
            order = order.toMutableList().apply { add(index - 1, removeAt(index)) }
            shift -= rowHeight
            offset += rowHeight
        }
    }
    fun stop() {
        dragging = null
        offset = 0f
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("首页标签") },
        text = {
            Column {
                Text("拖动排序，勾选的标签显示在首页", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                order.forEach { tab ->
                    key(tab) {
                        val checked = tab in shown
                        val lifted = dragging == tab
                        Row(
                            Modifier
                                .onSizeChanged { rowHeight = it.height }
                                .zIndex(if (lifted) 1f else 0f)
                                .graphicsLayer { translationY = if (lifted) offset else 0f }
                                .background(
                                    if (lifted) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
                                    MaterialTheme.shapes.medium,
                                )
                                .reorderDrag(tab, afterLongPress = true, { start(tab, it) }, { move(tab, it) }, ::stop),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { shown = if (it) shown + tab else shown - tab },
                                enabled = !checked || shown.size > 1,
                            )
                            Text(tab.label, Modifier.weight(1f))
                            Icon(
                                Icons.Outlined.DragHandle, "拖动${tab.label}",
                                Modifier
                                    .reorderDrag(tab, afterLongPress = false, { start(tab, it) }, { move(tab, it) }, ::stop)
                                    .padding(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onSave(order.filter { it in shown }) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { onDismiss(); onSave(HomeTab.entries) }) { Text("恢复默认") } },
    )
}

/** Drag reporting the finger's y in root coordinates: on the handle at once, elsewhere after a long press. */
@Composable
private fun Modifier.reorderDrag(
    key: Any,
    afterLongPress: Boolean,
    onStart: (Float) -> Unit,
    onMove: (Float) -> Unit,
    onStop: () -> Unit,
): Modifier {
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return onGloballyPositioned { coordinates[0] = it }.pointerInput(key, afterLongPress) {
        fun rootY(position: Offset) = coordinates[0]?.takeIf { it.isAttached }?.localToRoot(position)?.y ?: position.y
        if (afterLongPress) {
            detectDragGesturesAfterLongPress({ onStart(rootY(it)) }, onStop, onStop) { change, _ ->
                change.consume()
                onMove(rootY(change.position))
            }
        } else {
            detectVerticalDragGestures({ onStart(rootY(it)) }, onStop, onStop) { change, _ ->
                change.consume()
                onMove(rootY(change.position))
            }
        }
    }
}

@Composable
fun EditDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onDismiss(); onSave(value.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** Protocol parameters; device fields regenerate the device code, version fields only the user agent. */
@Composable
fun ParamsScreen() {
    val app = appContainer()
    val params = LocalAppSettings.current.protocol
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Triple<String, String, (String) -> com.zailinn.c001apk.core.protocol.ProtocolParams>?>(null) }
    fun item(title: String, value: String, device: Boolean, update: (String) -> com.zailinn.c001apk.core.protocol.ProtocolParams) =
        Triple(title, value) { v: String -> update(v).withUserAgent().let { if (device) it.withDevice() else it } }
    val fields = listOf(
        item("客户端版本名称", params.versionName, false) { params.copy(versionName = it) },
        item("API 版本", params.apiVersion, false) { params.copy(apiVersion = it) },
        item("客户端版本号", params.versionCode, false) { params.copy(versionCode = it) },
        item("制造商", params.manufacturer, true) { params.copy(manufacturer = it) },
        item("品牌", params.brand, true) { params.copy(brand = it) },
        item("型号", params.model, true) { params.copy(model = it) },
        item("系统构建号", params.buildNumber, true) { params.copy(buildNumber = it) },
        item("SDK 版本", params.sdkInt, false) { params.copy(sdkInt = it) },
        item("Android 版本", params.androidVersion, false) { params.copy(androidVersion = it) },
    )
    Scaffold(topBar = { SimpleTopBar("协议参数") }) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            items(fields) { (title, value, update) ->
                ListItem(
                    headlineContent = { Text(title) }, supportingContent = { Text(value) },
                    modifier = Modifier.clickable { editing = Triple(title, value, update) },
                )
            }
            item { ListItem(headlineContent = { Text("用户代理（User-Agent）") }, supportingContent = { Text(params.userAgent) }) }
            item { ListItem(headlineContent = { Text("设备编码（X-App-Device）") }, supportingContent = { Text(params.xAppDevice) }) }
            item {
                ListItem(
                    headlineContent = { Text("重新生成参数", color = MaterialTheme.colorScheme.primary) },
                    supportingContent = { Text("随机生成新的设备与客户端参数") },
                    modifier = Modifier.clickable { scope.launch { app.settings.regenerateProtocol(); app.toast("已重新生成") } },
                )
            }
        }
    }
    editing?.let { (title, value, update) ->
        EditDialog(title, value, onDismiss = { editing = null }) { input ->
            if (input.isNotEmpty()) scope.launch { app.settings.saveProtocol(update(input)) }
        }
    }
}

@Composable
fun AboutScreen() {
    val navigator = LocalNavigator.current
    Scaffold(topBar = { SimpleTopBar("关于") }) { padding ->
        Column(Modifier.padding(padding)) {
            ListItem(headlineContent = { Text(Constants.APP_NAME) }, supportingContent = { Text("一个轻量的非官方酷安客户端") })
            ListItem(headlineContent = { Text("版本") }, supportingContent = { Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") })
            ListItem(
                headlineContent = { Text("项目地址") }, supportingContent = { Text(Constants.URL_SOURCE_CODE) },
                modifier = Modifier.clickable { navigator.launchBrowser(Constants.URL_SOURCE_CODE) },
            )
            ListItem(
                headlineContent = { Text("开源许可证") }, supportingContent = { Text("GNU AGPL v3") },
                modifier = Modifier.clickable { navigator.launchBrowser("${Constants.URL_SOURCE_CODE}/blob/main/LICENSE") },
            )
            ListItem(
                headlineContent = { Text("致谢") }, supportingContent = { Text("c001apk-flutter") },
                modifier = Modifier.clickable { navigator.launchBrowser(Constants.URL_UPSTREAM) },
            )
        }
    }
}
