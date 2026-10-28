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
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.MotionPhotosOn
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
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
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material3.Checkbox
import androidx.compose.ui.Alignment
import com.zailinn.c001apk.ui.common.AppAlertDialog
import com.zailinn.c001apk.ui.common.AppDropdownMenu
import com.zailinn.c001apk.ui.screens.main.HomeTab
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.zailinn.c001apk.core.protocol.ProtocolParams
import com.zailinn.c001apk.data.PROTOCOL_GENERATED
import com.zailinn.c001apk.data.PROTOCOL_LOCAL
import com.zailinn.c001apk.data.SettingKeys
import com.zailinn.c001apk.data.androidDevice
import com.zailinn.c001apk.ui.LocalAppSettings
import com.zailinn.c001apk.ui.LocalSession
import com.zailinn.c001apk.ui.common.SectionTitle
import com.zailinn.c001apk.ui.common.hapticChange
import com.zailinn.c001apk.ui.common.hapticClick
import com.zailinn.c001apk.ui.nav.AboutRoute
import com.zailinn.c001apk.ui.screens.account.CookieExportDialog
import com.zailinn.c001apk.ui.nav.AccountsRoute
import com.zailinn.c001apk.ui.nav.CaptchaLogRoute
import com.zailinn.c001apk.ui.nav.ContentFilterRoute
import com.zailinn.c001apk.ui.nav.LocalFollowRoute
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.nav.ParamsRoute
import com.zailinn.c001apk.ui.nav.DeviceIdentityRoute
import com.zailinn.c001apk.ui.nav.SettingsSectionRoute
import com.zailinn.c001apk.ui.screens.appContainer
import com.zailinn.c001apk.ui.screens.list.SimpleTopBar
import com.zailinn.c001apk.ui.theme.seedColorNames
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.BoxWithConstraints

/** The groups the settings list is split into, in order. */
enum class SettingsGroup(val title: String) { Personal("个性化"), Data("数据"), Advanced("高级") }

enum class SettingsSection(val title: String, val description: String, val icon: ImageVector, val group: SettingsGroup) {
    Appearance("外观与字号", "主题、深色模式、字号与表情", Icons.Outlined.Palette, SettingsGroup.Personal),
    Interaction("交互", "震动、滑动隐藏栏、返回刷新、实况播放", Icons.Outlined.Vibration, SettingsGroup.Personal),
    Content("首页与关注", "首页标签、本地关注与悄悄关注", Icons.Outlined.Home, SettingsGroup.Personal),
    Filter("内容过滤", "黑名单、敏感话题、语义屏蔽", Icons.Outlined.FilterAlt, SettingsGroup.Personal),
    Archive("备份与收藏", "我的备份、收藏自动备份", Icons.Outlined.FolderOpen, SettingsGroup.Data),
    Privacy("隐私", "浏览历史、外部链接", Icons.Outlined.PrivacyTip, SettingsGroup.Data),
    Storage("存储与更新", "缓存、版本更新", Icons.Outlined.Storage, SettingsGroup.Data),
    Protocol("协议与设备", "设备标识、请求参数、验证记录", Icons.Outlined.DeveloperMode, SettingsGroup.Advanced),
}

/** Categories; on wide windows the selected category is shown beside the list. */
@Composable
fun SettingsScreen() {
    val navigator = LocalNavigator.current
    val app = appContainer()
    val vault by app.accounts.vault.collectAsStateWithLifecycle()
    // Saveable: on wide windows a page opened from a section returns to that section.
    var selected by rememberSaveable { mutableStateOf(SettingsSection.Appearance) }
    Scaffold(topBar = { SimpleTopBar("设置") }) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            val categories = @Composable {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    item { SectionTitle("账号") }
                    item {
                        ListItem(
                            headlineContent = { Text("账号管理") },
                            supportingContent = { Text("已保存 ${vault.accounts.size} 个账号 · 切换、添加或移除") },
                            leadingContent = { Icon(Icons.Outlined.SwitchAccount, null) },
                            modifier = Modifier.clickable { navigator.navigate(AccountsRoute) },
                        )
                    }
                    item { CookieExportItem() }
                    SettingsGroup.entries.forEach { group ->
                        item { SectionTitle(group.title, Modifier.padding(top = 8.dp)) }
                        items(SettingsSection.entries.filter { it.group == group }) { section ->
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
                    }
                    item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                    item {
                        ListItem(
                            headlineContent = { Text("关于") },
                            supportingContent = { Text("版本 ${BuildConfig.VERSION_NAME}") },
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

/** Copies the active account as Cookie text, after a warning; disabled for guests. */
@Composable
private fun CookieExportItem() {
    val app = appContainer()
    val vault by app.accounts.vault.collectAsStateWithLifecycle()
    val account = vault.active
    var confirm by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text("导出 Cookie") },
        supportingContent = {
            Text(if (account == null) "登录后可复制当前账号的 Cookie" else "复制当前账号的 Cookie，用于在其他设备登录")
        },
        leadingContent = { Icon(Icons.Outlined.Cookie, null) },
        modifier = Modifier.clickable(enabled = account != null) { confirm = true },
    )
    if (confirm && account != null) CookieExportDialog(account, onDismiss = { confirm = false })
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
    val change = hapticChange(onChange)
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Switch(checked, change) },
        modifier = Modifier.clickable { change(!checked) },
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
            AppDropdownMenu(open, { open = false }) {
                options.forEachIndexed { index, label ->
                    DropdownMenuItem({ Text(label) }, hapticClick { open = false; onSelect(index) })
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
    val setString = rememberSetter<String>()
    when (section) {
        SettingsSection.Archive -> {
            ListItem(
                headlineContent = { Text("我的备份") },
                supportingContent = { Text("备份目录、同步收藏、导出与离线查看") },
                leadingContent = { Icon(Icons.Outlined.Archive, null) },
                modifier = Modifier.clickable { navigator.navigate(com.zailinn.c001apk.ui.nav.BackupsRoute) },
            )
            val values = listOf("Off", "None", "Preview", "Original")
            ChoiceItem(Icons.Outlined.BookmarkAdd, "收藏时自动备份", "加入收藏后排队保存到所选目录", listOf("关闭", "仅文字", "文字和缩略图", "文字和原图"), values.indexOf(settings.archiveAutoBackup).coerceAtLeast(0)) { setString(SettingKeys.archiveAutoBackup, values[it]) }
            SwitchItem(Icons.Outlined.Forum, "批量备份包含回复", "保存主回复与楼中楼", settings.archiveIncludeReplies) { setBool(SettingKeys.archiveIncludeReplies, it) }
            SwitchItem(Icons.Outlined.Wifi, "仅在 WiFi 下下载图片", "关闭时可使用移动网络下载备份图片", settings.archiveImagesWifiOnly) { setBool(SettingKeys.archiveImagesWifiOnly, it) }
        }
        SettingsSection.Interaction -> {
            SwitchItem(
                Icons.Outlined.Vibration, "震动反馈", "切换标签、点赞、回复、更多菜单和调整设置时提供触感反馈", settings.hapticFeedback,
            ) { setBool(SettingKeys.hapticFeedback, it) }
            val modes = com.zailinn.c001apk.core.model.MediaAutoPlay.entries
            ChoiceItem(
                Icons.Outlined.MotionPhotosOn, "实况图片自动播放", "打开大图时静音播放一次", modes.map { it.label }, modes.indexOf(settings.mediaAutoPlay),
            ) { setString(SettingKeys.mediaAutoPlay, modes[it].name) }
            SwitchItem(
                Icons.Outlined.UnfoldLess, "滑动时隐藏顶栏和底栏", "主页和关注页向下浏览时收起，向上滑动时恢复",
                settings.hideBarsOnScroll,
            ) { setBool(SettingKeys.hideBarsOnScroll, it) }
            SwitchItem(
                Icons.Outlined.Refresh, "返回首页时自动刷新", "回到主页时列表回到顶部并刷新",
                settings.refreshOnReturn,
            ) { setBool(SettingKeys.refreshOnReturn, it) }
        }
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
            SwitchItem(Icons.Outlined.EmojiEmotions, "显示表情", "将内容中的酷安表情标记显示为图片", settings.showEmoji) { setBool(SettingKeys.showEmoji, it) }
            if (fontDialog) {
                var value by remember { mutableFloatStateOf(settings.fontScale) }
                AppAlertDialog(
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
            val shownTabs = HomeTab.visible(settings.homeTabs)
            ChoiceItem(
                Icons.Outlined.Home, "默认打开", "打开应用时首页停留的标签", shownTabs.map { it.label },
                shownTabs.indexOf(HomeTab.start(shownTabs, settings.homeStartTab)),
            ) { setString(SettingKeys.homeStartTab, shownTabs[it].name) }
            if (tabsDialog) {
                HomeTabsDialog(HomeTab.visible(settings.homeTabs), onDismiss = { tabsDialog = false }) { tabs ->
                    scope.launch { app.settings.set(SettingKeys.homeTabs, tabs.joinToString(",") { it.name }) }
                }
            }
            SectionTitle("关注")
            val label = if (session.isLogin) "悄悄关注" else "本地关注"
            if (session.isLogin) {
                SwitchItem(
                    Icons.Outlined.VisibilityOff, "悄悄关注", "开启后，长按用户主页的关注按钮，或在话题页菜单中添加或取消。仅保存在本机，不会同步到账号",
                    settings.secretFollow,
                ) { setBool(SettingKeys.secretFollow, it) }
            } else {
                ListItem(
                    headlineContent = { Text("本地关注") },
                    supportingContent = { Text("游客可在用户主页或话题页菜单中关注，并在底栏“关注”查看内容。名单仅保存在本机") },
                    leadingContent = { Icon(Icons.Outlined.PersonAddAlt, null) },
                )
            }
            ListItem(
                headlineContent = { Text("管理$label") },
                supportingContent = { Text("查看本机关注的用户和话题，进入主页或取消关注") },
                leadingContent = { Icon(Icons.Outlined.ManageAccounts, null) },
                modifier = Modifier.clickable { navigator.navigate(LocalFollowRoute) },
            )
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
            ListItem(
                headlineContent = { Text("导入官方设备标识") },
                supportingContent = {
                    val id = settings.protocol.szlmId
                    Text("从官方酷安复制数盟 ID（SZLM ID），可减少人机验证 · ${if (id.isEmpty()) "未导入" else "已导入"}")
                },
                leadingContent = { Icon(Icons.Outlined.Fingerprint, null) },
                modifier = Modifier.clickable { navigator.navigate(DeviceIdentityRoute) },
            )
            ListItem(
                headlineContent = { Text("协议参数") },
                supportingContent = { Text("配置客户端版本、设备与请求参数") },
                leadingContent = { Icon(Icons.Outlined.Tune, null) },
                modifier = Modifier.clickable { navigator.navigate(ParamsRoute) },
            )
            ListItem(
                headlineContent = { Text("人机验证记录") },
                supportingContent = { Text("最近被要求验证的接口与请求频率，用于反馈问题") },
                leadingContent = { Icon(Icons.Outlined.VerifiedUser, null) },
                modifier = Modifier.clickable { navigator.navigate(CaptchaLogRoute) },
            )
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
    AppAlertDialog(
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
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onDismiss(); onSave(value.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * Protocol parameters. The device model and system come from this phone or
 * from a generated real model; editing them by hand counts as generated.
 */
@Composable
fun ParamsScreen() {
    val app = appContainer()
    val settings = LocalAppSettings.current
    val params = settings.protocol
    val local = settings.protocolSource == PROTOCOL_LOCAL
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Triple<String, String, (String) -> Unit>?>(null) }
    fun version(title: String, value: String, update: (String) -> ProtocolParams) = Triple<String, String, (String) -> Unit>(title, value) { v ->
        scope.launch { app.settings.saveProtocol(update(v).withUserAgent()) }
    }
    val hasSzlmId = params.szlmId.isNotEmpty()
    fun device(title: String, value: String, update: (String) -> ProtocolParams) = Triple<String, String, (String) -> Unit>(title, value) { v ->
        scope.launch {
            app.settings.saveProtocol(update(v).withUserAgent().withDevice(), PROTOCOL_GENERATED)
            if (hasSzlmId) app.toast("已导入的数盟 ID 来自本机，改成别的机型可能更容易触发人机验证")
        }
    }
    val versions = listOf(
        version("客户端版本名称", params.versionName) { params.copy(versionName = it) },
        version("API 版本", params.apiVersion) { params.copy(apiVersion = it) },
        version("客户端版本号", params.versionCode) { params.copy(versionCode = it) },
    )
    val devices = listOf(
        device("制造商", params.manufacturer) { params.copy(manufacturer = it) },
        device("品牌", params.brand) { params.copy(brand = it) },
        device("型号", params.model) { params.copy(model = it) },
        device("系统构建号", params.buildNumber) { params.copy(buildNumber = it) },
        device("SDK 版本", params.sdkInt) { params.copy(sdkInt = it) },
        device("Android 版本", params.androidVersion) { params.copy(androidVersion = it) },
    )
    @Composable
    fun Field(field: Triple<String, String, (String) -> Unit>) = ListItem(
        headlineContent = { Text(field.first) }, supportingContent = { Text(field.second) },
        modifier = Modifier.clickable { editing = field },
    )
    Scaffold(topBar = { SimpleTopBar("协议参数") }) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item { SectionTitle("设备参数") }
            item {
                ListItem(
                    headlineContent = { Text("使用本机") },
                    supportingContent = { Text("使用这台手机的型号与系统版本，系统升级后自动更新 · ${androidDevice().brand} ${androidDevice().model}") },
                    leadingContent = { RadioButton(local, null) },
                    modifier = Modifier.clickable {
                        scope.launch { app.settings.useLocalDevice(); app.toast("已使用本机参数") }
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("重新生成参数") },
                    supportingContent = {
                        Text(
                            when {
                                hasSzlmId -> "已导入数盟 ID，它来自这台手机，需要和本机型号一起使用。清除数盟 ID 后可用"
                                local -> "随机换成另一款真实机型，并生成新的设备标识"
                                else -> "已使用生成的参数，再次点按换一款机型"
                            },
                        )
                    },
                    leadingContent = { RadioButton(!local, null, enabled = !hasSzlmId) },
                    modifier = Modifier.clickable(enabled = !hasSzlmId) {
                        scope.launch { if (app.settings.regenerateProtocol()) app.toast("已重新生成") }
                    },
                )
            }
            items(devices) { Field(it) }
            item { SectionTitle("客户端") }
            items(versions) { Field(it) }
            item { ListItem(headlineContent = { Text("用户代理（User-Agent）") }, supportingContent = { Text(params.userAgent) }) }
            item {
                // It carries the SZLM ID, so it stays hidden until tapped.
                var shown by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text("设备编码（X-App-Device）") },
                    supportingContent = { Text(if (shown) params.xAppDevice else "已隐藏，点按显示") },
                    modifier = Modifier.clickable { shown = !shown },
                )
            }
        }
    }
    editing?.let { (title, value, update) ->
        EditDialog(title, value, onDismiss = { editing = null }) { input -> if (input.isNotEmpty()) update(input) }
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
