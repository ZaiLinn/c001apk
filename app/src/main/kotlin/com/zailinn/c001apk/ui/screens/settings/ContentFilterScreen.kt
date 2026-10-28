package com.zailinn.c001apk.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zailinn.c001apk.core.model.SensitiveCategory
import com.zailinn.c001apk.data.SettingKeys
import com.zailinn.c001apk.ui.LocalAppSettings
import com.zailinn.c001apk.ui.common.AppAlertDialog
import com.zailinn.c001apk.ui.common.AppDropdownMenu
import com.zailinn.c001apk.ui.common.SectionTitle
import com.zailinn.c001apk.ui.common.hapticChange
import com.zailinn.c001apk.ui.common.hapticClick
import com.zailinn.c001apk.ui.nav.BlacklistRoute
import com.zailinn.c001apk.ui.nav.CategoryEditRoute
import com.zailinn.c001apk.ui.nav.HiddenLogRoute
import com.zailinn.c001apk.ui.nav.TopicBlockRoute
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.screens.appContainer
import com.zailinn.c001apk.ui.screens.list.SimpleTopBar
import kotlinx.coroutines.launch

/** User blacklist, the hidden-items log, filter categories with the fold switch, and semantic blocking. */
@Composable
fun ContentFilterScreen() {
    val app = appContainer()
    val navigator = LocalNavigator.current
    val settings = LocalAppSettings.current
    val categories by app.content.filterCategories.collectAsStateWithLifecycle()
    val users by app.content.blockedUsers.collectAsStateWithLifecycle()
    val topics by app.content.blockedTopics.collectAsStateWithLifecycle()
    val hidden by app.hiddenLog.records.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            SimpleTopBar("内容过滤") {
                IconButton(onClick = hapticClick { menu = true }) { Icon(Icons.Outlined.MoreVert, "更多") }
                AppDropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("恢复内置分类") }, onClick = { menu = false; restoring = true })
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item {
                SectionTitle("屏蔽名单")
                NavItem(Icons.Outlined.PersonOff, "用户黑名单", "${users.size} 位用户，其动态和回复始终隐藏") {
                    navigator.navigate(BlacklistRoute(users = true))
                }
                NavItem(Icons.Outlined.History, "最近隐藏", if (hidden.isEmpty()) "还没有被隐藏的内容" else "${hidden.size} 条，查看被隐藏的内容和原因") {
                    navigator.navigate(HiddenLogRoute)
                }
                HorizontalDivider()
                SectionTitle("过滤分类")
                SwitchRow(
                    Icons.Outlined.VisibilityOff, "折叠",
                    "命中分类的动态和回复折叠成一行，点击展开；关闭后直接隐藏。“我屏蔽的话题”始终隐藏", settings.filterFold,
                ) {
                    scope.launch { app.settings.set(SettingKeys.filterFold, it) }
                }
                SwitchRow(
                    Icons.Outlined.FilterAlt, "严格模式",
                    "分类里的“严格关键词”也参与匹配，例如 小姐姐、单身狗、表白。拦得更多，也更容易误伤普通帖子", settings.filterStrict,
                ) {
                    scope.launch { app.settings.set(SettingKeys.filterStrict, it) }
                }
                ListItem(
                    headlineContent = {},
                    supportingContent = { Text("只在本机过滤，下拉刷新列表后生效。点击分类可编辑其中的关键词、话题、句式和语义例句。") },
                )
                ListItem(
                    headlineContent = { Text("我屏蔽的话题") },
                    supportingContent = { Text(if (topics.isEmpty()) "在话题页点“屏蔽”添加 · 始终隐藏" else "${topics.size} 个话题 · 始终隐藏") },
                    leadingContent = { Icon(Icons.Outlined.Block, null, Modifier.padding(12.dp)) },
                    trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
                    modifier = Modifier.clickable { navigator.navigate(TopicBlockRoute) },
                )
            }
            items(categories, key = { it.id }) { category ->
                ListItem(
                    headlineContent = { Text(category.title.ifBlank { "未命名分类" }) },
                    supportingContent = { Text(summary(category)) },
                    leadingContent = {
                        Checkbox(category.enabled, { on -> scope.launch { app.content.updateCategory(category.id) { it.copy(enabled = on) } } })
                    },
                    trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
                    modifier = Modifier.clickable { navigator.navigate(CategoryEditRoute(category.id)) },
                )
            }
            item {
                NavItem(Icons.Outlined.Add, "新建分类", null) { adding = true }
                HorizontalDivider()
                SemanticSection()
            }
        }
    }
    if (adding) {
        EditDialog("新建分类", "", onDismiss = { adding = false }) { title ->
            if (title.isBlank()) return@EditDialog app.toast("请输入分类名称")
            scope.launch { navigator.navigate(CategoryEditRoute(app.content.addCategory(title))) }
        }
    }
    if (restoring) {
        AppAlertDialog(
            onDismissRequest = { restoring = false },
            title = { Text("恢复内置分类") },
            text = { Text("内置的四个分类会恢复为默认内容，对它们的修改会丢失。自己新建的分类保留。") },
            confirmButton = {
                TextButton(onClick = { restoring = false; scope.launch { app.content.restoreAllCategories() } }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { restoring = false }) { Text("取消") } },
        )
    }
}

private fun summary(category: SensitiveCategory): String = listOf(
    category.keywords.size to "个关键词",
    category.strictKeywords.size to "个严格关键词",
    category.authors.size to "位用户",
    category.tags.size to "个话题",
    category.patterns.size to "条句式",
    category.examples.size to "条语义例句",
).filter { it.first > 0 }.joinToString(" · ") { "${it.first} ${it.second}" }.ifEmpty { "空分类，点击添加内容" }

@Composable
private fun NavItem(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(icon, null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val change = hapticChange(onChange)
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Switch(checked, change) },
        modifier = Modifier.clickable { change(!checked) },
    )
}

/** One category: switch, name, and its editable lists. */
@Composable
fun CategoryEditScreen(id: String) {
    val app = appContainer()
    val navigator = LocalNavigator.current
    val categories by app.content.filterCategories.collectAsStateWithLifecycle()
    val category = categories.firstOrNull { it.id == id }
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    // Leaves the page once the category is deleted.
    LaunchedEffect(category == null) { if (category == null) navigator.back() }
    if (category == null) return
    fun update(transform: (SensitiveCategory) -> SensitiveCategory) = scope.launch { app.content.updateCategory(id, transform) }
    Scaffold(
        topBar = {
            SimpleTopBar(category.title.ifBlank { "未命名分类" }) {
                IconButton(onClick = { renaming = true }) { Icon(Icons.Outlined.Edit, "重命名") }
                IconButton(onClick = { deleting = true }) { Icon(Icons.Outlined.Delete, "删除分类") }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text("启用此分类") },
                supportingContent = { Text("关闭后不参与折叠和隐藏") },
                trailingContent = { Switch(category.enabled, hapticChange { on -> update { it.copy(enabled = on) } }) },
                modifier = Modifier.clickable(onClick = hapticClick { update { it.copy(enabled = !category.enabled) } }),
            )
            HorizontalDivider()
            WordList(
                "关键词", "文字中出现就命中，不区分大小写；英文词只按整词匹配。可一次添加多个，用空格、逗号或顿号分隔。",
                category.keywords, multiple = true,
            ) { list -> update { it.copy(keywords = list) } }
            WordList(
                "严格关键词", "只在内容过滤页开启“严格模式”后生效。放常被用在普通语境里的词，例如 小姐姐、表白。",
                category.strictKeywords, multiple = true,
            ) { list -> update { it.copy(strictKeywords = list) } }
            WordList(
                "例外词", "一句话里出现这些词时，这句不按本分类匹配，例如 举报、反对。只影响这一句，不影响整条帖子。",
                category.exceptions, multiple = true,
            ) { list -> update { it.copy(exceptions = list) } }
            WordList(
                "话题", "动态所属的话题，或正文里的 #话题#，例如 相亲、酷安夜话。可一次添加多个。",
                category.tags, multiple = true, display = { "#$it#" }, clean = { it.trim('#', ' ') },
            ) { list -> update { it.copy(tags = list) } }
            WordList(
                "用户", "这些用户的动态和回复都归入本分类，按分类折叠或隐藏。填用户 ID（个人主页链接 /u/ 后的数字）。",
                category.authors, multiple = true, validate = { it.all(Char::isDigit) },
            ) { list -> update { it.copy(authors = list) } }
            WordList(
                "句式（高级）", "正则表达式，用来匹配更灵活的说法，例如 (想|好想)找个?女朋友。",
                category.patterns, multiple = false,
                validate = { runCatching { Regex(it) }.isSuccess },
            ) { list -> update { it.copy(patterns = list) } }
            WordList(
                "语义例句", "开启语义屏蔽后，意思和这些句子相近的帖子会被命中。一句话描述一种想过滤的内容。",
                category.examples, multiple = false,
            ) { list -> update { it.copy(examples = list) } }
            if (SensitiveCategory.builtIn(id) != null) {
                TextButton(
                    onClick = { scope.launch { app.content.restoreCategory(id) } },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
                ) { Text("恢复此分类的默认内容") }
            }
        }
    }
    if (renaming) {
        EditDialog("分类名称", category.title, onDismiss = { renaming = false }) { title ->
            if (title.isBlank()) return@EditDialog app.toast("请输入分类名称")
            update { it.copy(title = title.trim()) }
        }
    }
    if (deleting) {
        AppAlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("删除分类") },
            text = {
                Text(if (SensitiveCategory.builtIn(id) != null) "删除后可在内容过滤页的“恢复内置分类”中找回。" else "删除后不可恢复。")
            },
            confirmButton = {
                TextButton(onClick = { deleting = false; scope.launch { app.content.deleteCategory(id) } }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } },
        )
    }
}

private val ENTRY_SEPARATORS = Regex("[\\s,，、]+")

/** Words and topics may be added several at a time; sentences and patterns one per entry. */
internal fun splitEntries(text: String, multiple: Boolean): List<String> =
    if (multiple) text.split(ENTRY_SEPARATORS).filter { it.isNotBlank() } else listOf(text)

/** Editable chips: tap one to edit it, × removes it, "添加" appends. */
@Composable
private fun WordList(
    title: String,
    hint: String,
    items: List<String>,
    multiple: Boolean,
    display: (String) -> String = { it },
    clean: (String) -> String = { it.trim() },
    validate: (String) -> Boolean = { true },
    onChange: (List<String>) -> Unit,
) {
    val app = appContainer()
    var editing by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }
    fun accept(values: List<String>): List<String>? {
        val cleaned = values.map(clean).filter { it.isNotEmpty() }
        if (cleaned.any { !validate(it) }) {
            app.toast("格式无效，请检查后重试")
            return null
        }
        return cleaned
    }
    SectionTitle(title)
    Text(
        hint,
        Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEachIndexed { index, item ->
            InputChip(
                selected = false,
                onClick = { editing = index },
                label = { Text(display(item)) },
                trailingIcon = {
                    Icon(
                        Icons.Outlined.Close, "删除 $item",
                        Modifier.size(InputChipDefaults.IconSize).clickable { onChange(items.filterIndexed { i, _ -> i != index }) },
                    )
                },
            )
        }
        AssistChip(
            onClick = { adding = true },
            label = { Text("添加") },
            leadingIcon = { Icon(Icons.Outlined.Add, null, Modifier.size(AssistChipDefaults.IconSize)) },
        )
    }
    if (adding) {
        EditDialog("添加$title", "", onDismiss = { adding = false }) { text ->
            accept(splitEntries(text, multiple))?.let { added -> onChange((items + added).distinct()) }
        }
    }
    editing?.let { index ->
        EditDialog("编辑$title", items.getOrElse(index) { "" }, onDismiss = { editing = null }) { text ->
            val value = accept(listOf(text)) ?: return@EditDialog
            // Saving an empty value removes the item.
            onChange(
                if (value.isEmpty()) items.filterIndexed { i, _ -> i != index }
                else items.mapIndexed { i, old -> if (i == index) value.single() else old }.distinct(),
            )
        }
    }
}
