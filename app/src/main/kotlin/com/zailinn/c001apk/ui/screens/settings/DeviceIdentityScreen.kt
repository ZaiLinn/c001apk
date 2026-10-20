package com.zailinn.c001apk.ui.screens.settings

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.zailinn.c001apk.core.protocol.OfficialDeviceIdentity
import com.zailinn.c001apk.ui.LocalAppSettings
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.screens.appContainer
import com.zailinn.c001apk.ui.screens.list.SimpleTopBar
import kotlinx.coroutines.launch

/** Illustrated guide by TG-Twilight, whose import method this page follows. */
const val DEVICE_ID_GUIDE = "https://github.com/TG-Twilight/c001apk/blob/main/docs/DEVICE_ID_IMPORT.md"

@Composable
private fun Heading(text: String) =
    Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable
private fun VisibilityToggle(visible: Boolean, onChange: (Boolean) -> Unit) =
    IconButton(onClick = { onChange(!visible) }) {
        Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "隐藏数盟 ID" else "显示数盟 ID")
    }

private enum class OpenResult { Opened, NotInstalled, Failed }

/** Opens the official app's 检查日志和测试 page, never this app's own coolmarket handler. */
private fun openOfficialTestPage(context: Context): OpenResult {
    try {
        context.packageManager.getPackageInfo(OfficialDeviceIdentity.OFFICIAL_PACKAGE, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        return OpenResult.NotInstalled
    }
    return try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(OfficialDeviceIdentity.TEST_PAGE))
                .setPackage(OfficialDeviceIdentity.OFFICIAL_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        OpenResult.Opened
    } catch (_: Exception) {
        OpenResult.Failed
    }
}

/**
 * Imports the SZLM ID from the text the official app copies. The clipboard is
 * read only when 粘贴并识别 is tapped; the copied text is hidden once recognised
 * and only the ID is saved.
 */
@Composable
fun DeviceIdentityScreen() {
    val app = appContainer()
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val saved = LocalAppSettings.current.protocol.szlmId
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var openFailure by remember { mutableStateOf<OpenResult?>(null) }
    var manual by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }

    fun recognise(input: String) {
        text = input
        pending = null
        error = null
        if (input.isBlank()) return
        try {
            pending = OfficialDeviceIdentity.parse(input)
            text = ""
        } catch (e: IllegalArgumentException) {
            error = e.message
        }
    }

    fun save(id: String) = scope.launch {
        app.settings.setSzlmId(id)
        pending = null
        app.toast(if (id.isEmpty()) "已清除设备标识" else "已保存，返回内容页刷新即可")
    }

    Scaffold(topBar = { SimpleTopBar("导入官方设备标识") }) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("减少重复人机验证", style = MaterialTheme.typography.titleMedium)
            Text(
                "从官方酷安导入数盟 ID（SZLM ID），用于本应用的请求，可减少部分内容的人机验证。" +
                    "这是临时方案，有效期由服务端决定，仍可能要求验证。" +
                    "导入方法来自 TG-Twilight/c001apk，感谢其整理与验证。",
            )
            Text(
                "1. 点击下方“酷安 OAID 界面”，打开官方酷安的“检查日志和测试”页。\n" +
                    "2. 向下找到“复制 OAID”，在同一页面慢慢累计点击 5 次，第 5 次才会复制完整内容。\n" +
                    "3. 回到这里点击“粘贴并识别”，识别成功后点击“保存并应用”。",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    openOfficialTestPage(context).takeIf { it != OpenResult.Opened }?.let { openFailure = it }
                }) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.padding(end = 8.dp))
                    Text("酷安 OAID 界面")
                }
                OutlinedButton(onClick = { navigator.launchBrowser(DEVICE_ID_GUIDE) }) { Text("图文说明") }
            }
            Text(
                "官方按钮虽然叫“复制 OAID”，这里只使用其中的 ShuzlmID（数盟 ID），不会保存 OAID、AndroidID、UUID 等其他字段。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Always available for pasting; recognised text is cleared from it at once.
            OutlinedTextField(
                text, ::recognise, Modifier.fillMaxWidth(),
                label = { Text("粘贴官方复制的完整文本") },
                placeholder = { Text("需包含 ShuzlmID 一行，识别后自动清空") },
                maxLines = 3,
                isError = error != null,
                supportingText = error?.let { message -> { Text(message) } },
            )
            pending?.let {
                Text("已识别数盟 ID，复制内容已清空。点击“保存并应用”后生效。", color = MaterialTheme.colorScheme.primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = runCatching { clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
                    if (clip.isNullOrBlank()) error = "剪贴板没有文本，请先在官方酷安完成复制，或长按输入框手动粘贴" else recognise(clip)
                }) {
                    Icon(Icons.Outlined.ContentPaste, null, Modifier.padding(end = 8.dp))
                    Text("粘贴并识别")
                }
                Button(onClick = { pending?.let(::save) }, enabled = pending != null) { Text("保存并应用") }
            }

            Heading("当前状态")
            Text(
                if (saved.isEmpty()) "尚未导入数盟 ID，正在使用本应用生成的设备标识。"
                else "已导入数盟 ID，机型等其他设备参数保持不变。",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { manual = true }) { Text("手动输入") }
                if (saved.isNotEmpty()) TextButton(onClick = { clearing = true }) { Text("清除") }
            }
            Text(
                "只在点击“粘贴并识别”时读取剪贴板。数盟 ID 只保存在本机、用于酷安请求；请勿公开复制内容或真实标识，也不要使用别人的标识。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    openFailure?.let { result ->
        AlertDialog(
            onDismissRequest = { openFailure = null },
            title = { Text(if (result == OpenResult.NotInstalled) "尚未安装官方酷安" else "无法打开酷安 OAID 界面") },
            text = {
                Text(
                    if (result == OpenResult.NotInstalled) "请先安装并打开一次官方酷安，再点击“酷安 OAID 界面”。"
                    else "请确认官方酷安已启用并更新到最新版本后重试，也可以查看图文说明。",
                )
            },
            confirmButton = { TextButton(onClick = { openFailure = null }) { Text("知道了") } },
        )
    }
    if (manual) {
        // Starts empty so the saved ID is never shown in the clear.
        var value by remember { mutableStateOf("") }
        var visible by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { manual = false },
            title = { Text("手动输入数盟 ID") },
            text = {
                OutlinedTextField(
                    value, { value = it.trim() }, singleLine = true,
                    placeholder = { Text("只填 ShuzlmID 后面的值") },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { VisibilityToggle(visible) { visible = it } },
                )
            },
            confirmButton = {
                TextButton(onClick = { manual = false; save(value) }, enabled = value.isNotEmpty()) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { manual = false }) { Text("取消") } },
        )
    }
    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text("清除数盟 ID？") },
            text = { Text("清除后改用新生成的随机设备标识，可能需要重新完成人机验证。") },
            confirmButton = { TextButton(onClick = { clearing = false; save("") }) { Text("清除") } },
            dismissButton = { TextButton(onClick = { clearing = false }) { Text("取消") } },
        )
    }
}
