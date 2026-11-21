package com.zailinn.c001apk.backup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zailinn.c001apk.AppContainer
import com.zailinn.c001apk.data.AppSettings
import com.zailinn.c001apk.data.SettingKeys
import com.zailinn.c001apk.ui.common.AppAlertDialog
import kotlinx.coroutines.launch

private val autoBackupChoices = listOf("Off" to "关闭", "None" to "仅文字", "Preview" to "文字和缩略图", "Original" to "文字和原图")
private val commentPresets = listOf(10, 50, 200, 1000)

/** Current values, shown where a setting is opened from. */
internal fun autoBackupSummary(settings: AppSettings): String =
    autoBackupChoices.firstOrNull { it.first == settings.archiveAutoBackup }?.second ?: "关闭"
internal fun replyRuleSummary(settings: AppSettings): String =
    if (!settings.archiveIncludeReplies) "关闭，仅保存帖子正文和图片"
    else "最多 ${settings.archiveCommentLimit} 条 · " + if (settings.archiveIncludeSubReplies) "主回复和楼中楼" else "仅主回复"

@Composable
internal fun BackupAutoSettingsDialog(app: AppContainer, dismiss: () -> Unit) {
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    AppAlertDialog(onDismissRequest = dismiss, title = { Text("收藏时自动备份") }, text = {
        Column {
            Text("加入收藏后排队保存到所选备份目录。")
            autoBackupChoices.forEach { (value, title) ->
                fun select() { scope.launch { app.settings.set(SettingKeys.archiveAutoBackup, value) } }
                Row(Modifier.fillMaxWidth().clickable { select() }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(settings.archiveAutoBackup == value, ::select)
                    Text(title)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("完成") } })
}

@Composable
internal fun BackupCommentSettingsDialog(app: AppContainer, dismiss: () -> Unit) {
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var count by remember { mutableStateOf(settings.archiveCommentLimit.toString()) }
    AppAlertDialog(onDismissRequest = dismiss, title = { Text("备份时包含回复") }, text = {
        Column {
            Text("默认关闭，适用于单条备份、批量备份、云端同步和收藏时自动备份。")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("包含回复", Modifier.weight(1f))
                Switch(settings.archiveIncludeReplies, { enabled -> scope.launch { app.settings.set(SettingKeys.archiveSaveComments, enabled) } },
                    modifier = Modifier.semantics { contentDescription = "备份时包含回复开关" })
            }
            if (settings.archiveIncludeReplies) Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                commentPresets.forEach { preset ->
                    FilterChip(count.toIntOrNull() == preset, {
                        count = preset.toString()
                        scope.launch { app.settings.set(SettingKeys.archiveCommentLimit, preset) }
                    }, label = { Text("$preset", maxLines = 1) })
                }
            }
            if (settings.archiveIncludeReplies) OutlinedTextField(count, { value ->
                if (value.length <= 4 && value.all(Char::isDigit)) {
                    count = value
                    value.toIntOrNull()?.takeIf { it in 1..1000 }?.let { valid -> scope.launch { app.settings.set(SettingKeys.archiveCommentLimit, valid) } }
                }
            }, label = { Text("每帖最多保存评论数") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                isError = count.toIntOrNull()?.let { it in 1..1000 } != true,
                supportingText = { Text(if (count.toIntOrNull()?.let { it in 1..1000 } == true) "1–1000 条，置顶优先；楼中楼计入总数。"
                    else "请输入 1–1000，当前仍按 ${settings.archiveCommentLimit} 条保存") })
            if (settings.archiveIncludeReplies) {
                listOf(false to "仅保存主回复", true to "主回复和楼中楼都保存").forEach { (nested, label) ->
                    fun select() { scope.launch { app.settings.set(SettingKeys.archiveIncludeSubReplies, nested) } }
                    Row(Modifier.fillMaxWidth().clickable { select() }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(settings.archiveIncludeSubReplies == nested, ::select)
                        Text(label)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("完成") } })
}
