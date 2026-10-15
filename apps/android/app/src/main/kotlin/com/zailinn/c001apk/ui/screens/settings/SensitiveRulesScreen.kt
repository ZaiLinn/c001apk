package com.zailinn.c001apk.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zailinn.c001apk.core.model.SensitiveCategory
import com.zailinn.c001apk.data.SettingKeys
import com.zailinn.c001apk.ui.LocalAppSettings
import com.zailinn.c001apk.ui.common.SectionTitle
import com.zailinn.c001apk.ui.screens.appContainer
import com.zailinn.c001apk.ui.screens.list.SimpleTopBar
import kotlinx.coroutines.launch

/** Categories, built-in word lists and the user's own words for sensitive-topic folding. */
@Composable
fun SensitiveRulesScreen() {
    val app = appContainer()
    val settings = LocalAppSettings.current
    val words by app.content.sensitiveWords.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    val enabled = settings.sensitiveCategories ?: SensitiveCategory.builtIn.map { it.id }.toSet()
    fun toggle(id: String, on: Boolean) = scope.launch {
        val next = if (on) enabled + id else enabled - id
        app.settings.set(SettingKeys.sensitiveCategories, SensitiveCategory.builtIn.map { it.id }.filter { it in next }.joinToString(","))
    }
    Scaffold(
        topBar = {
            SimpleTopBar("折叠规则") {
                IconButton(onClick = { adding = true }) { Icon(Icons.Outlined.Add, "添加屏蔽词") }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item {
                ListItem(
                    headlineContent = { Text("折叠敏感话题") },
                    supportingContent = { Text("命中的动态和回复折叠成一行，点击展开。只在本机过滤，下拉刷新后生效") },
                    trailingContent = {
                        Switch(settings.sensitiveFold, { scope.launch { app.settings.set(SettingKeys.sensitiveFold, it) } })
                    },
                    modifier = Modifier.clickable { scope.launch { app.settings.set(SettingKeys.sensitiveFold, !settings.sensitiveFold) } },
                )
                HorizontalDivider()
                SectionTitle("内置分类")
            }
            items(SensitiveCategory.builtIn, key = { it.id }) { category ->
                CategoryItem(category, category.id in enabled) { toggle(category.id, it) }
            }
            item {
                HorizontalDivider()
                SectionTitle("自定义屏蔽词")
                ListItem(
                    headlineContent = {},
                    supportingContent = { Text("文字出现即折叠。写成 #话题# 则匹配该话题，例如 #相亲#") },
                )
                if (words.isEmpty()) {
                    ListItem(
                        headlineContent = { Text("添加屏蔽词") },
                        leadingContent = { Icon(Icons.Outlined.Add, null) },
                        modifier = Modifier.clickable { adding = true },
                    )
                }
            }
            items(words, key = { "word-$it" }) { word ->
                ListItem(
                    headlineContent = { Text(word) },
                    trailingContent = {
                        IconButton(onClick = { scope.launch { app.content.removeSensitiveWord(word) } }) {
                            Icon(Icons.Outlined.Delete, "移除")
                        }
                    },
                )
            }
        }
    }
    if (adding) {
        EditDialog("添加屏蔽词", "", onDismiss = { adding = false }) { value ->
            scope.launch { if (!app.content.addSensitiveWord(value)) app.toast("已存在或为空") }
        }
    }
}

@Composable
private fun CategoryItem(category: SensitiveCategory, checked: Boolean, onChange: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(category.title) },
        supportingContent = { Text(category.description) },
        leadingContent = { Checkbox(checked, onChange) },
        trailingContent = {
            IconButton(onClick = { open = !open }) {
                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (open) "收起词表" else "查看词表")
            }
        },
        modifier = Modifier.clickable { onChange(!checked) },
    )
    AnimatedVisibility(open) {
        val lines = buildList {
            if (category.tags.isNotEmpty()) add("话题：" + category.tags.joinToString("  ") { "#$it#" })
            if (category.keywords.isNotEmpty()) add("关键词：" + category.keywords.joinToString("、"))
            if (category.patterns.isNotEmpty()) add("句式（括号内任选一个）：\n" + category.patterns.joinToString("\n") { it.replace("?", "").replace("|", "/") })
        }
        Text(
            lines.joinToString("\n\n"),
            Modifier.padding(start = 72.dp, end = 16.dp, bottom = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
