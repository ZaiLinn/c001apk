package com.zailinn.c001apk.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zailinn.c001apk.core.semantic.SemanticScore
import com.zailinn.c001apk.data.SettingKeys
import com.zailinn.c001apk.semantic.SemanticStatus
import com.zailinn.c001apk.ui.LocalAppSettings
import com.zailinn.c001apk.ui.common.SectionTitle
import com.zailinn.c001apk.ui.screens.appContainer
import kotlinx.coroutines.launch

private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1_048_576.0)

/** Optional on-device model: download, switch, threshold, a tester and the user's own sentences. */
@Composable
fun SemanticSection() {
    val app = appContainer()
    val settings = LocalAppSettings.current
    val status by app.semantic.status.collectAsStateWithLifecycle()
    val loadError by app.semantic.loadError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    val installed = status is SemanticStatus.Installed

    Column {
        SectionTitle("语义屏蔽（实验）")
        ListItem(
            headlineContent = {},
            supportingContent = {
                Text("用本机运行的中文语义模型，把帖子和各分类的“语义例句”比较，能抓到换了说法、不含关键词的内容。只看文字不看图片，内容不会上传。")
            },
        )
        when (val s = status) {
            SemanticStatus.Unsupported -> ListItem(
                headlineContent = { Text("当前设备不支持") },
                supportingContent = { Text("没有适用于此 CPU 架构的运行库") },
                leadingContent = { Icon(Icons.Outlined.Psychology, null) },
            )
            is SemanticStatus.Absent -> ListItem(
                headlineContent = { Text("下载语义模型") },
                supportingContent = {
                    Text(buildString {
                        append("需下载约 ${mb(s.downloadBytes)}，安装后不影响应用本身大小")
                        s.error?.let { append("\n上次失败：").append(it) }
                    })
                },
                leadingContent = { Icon(Icons.Outlined.Psychology, null) },
                trailingContent = { FilledTonalButton(onClick = app.semantic::download) { Text(if (s.error != null) "重试" else "下载") } },
            )
            is SemanticStatus.Downloading -> ListItem(
                headlineContent = { Text("正在下载 ${mb(s.done)} / ${mb(s.total)}") },
                supportingContent = {
                    LinearProgressIndicator(
                        progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                },
                leadingContent = { Icon(Icons.Outlined.Psychology, null) },
                trailingContent = { TextButton(onClick = app.semantic::cancelDownload) { Text("取消") } },
            )
            is SemanticStatus.Installed -> ListItem(
                headlineContent = { Text("模型已下载") },
                supportingContent = { Text(loadError?.let { "$it\n点击重试" } ?: "bge-small-zh · 占用 ${mb(s.bytes)}") },
                leadingContent = { Icon(Icons.Outlined.Psychology, null) },
                trailingContent = { TextButton(onClick = { confirmDelete = true }) { Text("删除") } },
                modifier = Modifier.clickable(enabled = loadError != null) { app.semantic.retry() },
            )
        }
        ListItem(
            headlineContent = { Text("启用语义屏蔽") },
            supportingContent = {
                Text(if (installed) "关键词没命中时再按意思判断，按上面的开关折叠或隐藏" else "下载模型后可开启")
            },
            trailingContent = {
                Switch(settings.semanticFold, { scope.launch { app.settings.set(SettingKeys.semanticFold, it) } }, enabled = installed)
            },
            modifier = Modifier.clickable(enabled = installed) {
                scope.launch { app.settings.set(SettingKeys.semanticFold, !settings.semanticFold) }
            },
        )
        var threshold by remember(settings.semanticThreshold) { mutableFloatStateOf(settings.semanticThreshold) }
        ListItem(
            headlineContent = { Text("相似度阈值 ${"%.2f".format(threshold)}") },
            supportingContent = {
                Column {
                    Text("越低折叠越多，也越容易误伤。默认 0.50")
                    Slider(
                        value = threshold,
                        onValueChange = { threshold = (it * 100).toInt() / 100f },
                        onValueChangeFinished = { scope.launch { app.settings.set(SettingKeys.semanticThreshold, threshold) } },
                        valueRange = 0.4f..0.7f,
                        enabled = installed,
                    )
                }
            },
        )
        ListItem(
            headlineContent = { Text("试一试") },
            supportingContent = { Text("输入一句话，看它会不会被折叠") },
            leadingContent = { Icon(Icons.Outlined.Science, null) },
            modifier = Modifier.clickable(enabled = installed) { testing = true },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除语义模型") },
            text = { Text("删除后语义屏蔽停止工作，需要时可重新下载。关键词折叠不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        app.settings.set(SettingKeys.semanticFold, false)
                        app.semantic.delete()
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
    if (testing) TestDialog { testing = false }
}

@Composable
private fun TestDialog(onDismiss: () -> Unit) {
    val app = appContainer()
    val settings = LocalAppSettings.current
    var text by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<SemanticScore?>?>(null) }
    var request by remember { mutableStateOf(0) }
    LaunchedEffect(request) {
        if (request == 0) return@LaunchedEffect
        running = true
        result = runCatching { app.semantic.test(text, app.semanticConfig(settings)) }
        running = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("试一试") },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), placeholder = { Text("例如：周末又被家里安排去相亲") })
                val message = when {
                    running -> "分析中…（首次需加载模型）"
                    result == null -> null
                    else -> result!!.fold(
                        onSuccess = { score ->
                            when {
                                score == null -> app.semantic.loadError.value ?: "模型未就绪"
                                score.folds -> "会折叠：${score.title}\n最像「${score.anchor}」，相似度 %.2f".format(score.score)
                                else -> "不折叠\n最像「${score.anchor}」（${score.title}）%.2f，日常内容 %.2f".format(score.score, score.everyday)
                            }
                        },
                        onFailure = { it.message ?: "分析失败" },
                    )
                }
                message?.let {
                    Text(it, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = { request++ }, enabled = text.isNotBlank() && !running) { Text("分析") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
