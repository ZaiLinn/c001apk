package com.zailinn.c001apk.backup

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zailinn.c001apk.AppContainer
import com.zailinn.c001apk.core.protocol.OfficialDeviceIdentity
import com.zailinn.c001apk.ui.common.AppAlertDialog
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.screens.settings.DEVICE_ID_GUIDE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun BackupIdentityDialog(app: AppContainer, request: BackupIdentityRequest) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var input by remember(request) { mutableStateOf("") }
    var error by remember(request) { mutableStateOf("") }
    var saving by remember(request) { mutableStateOf(false) }
    var visible by remember(request) { mutableStateOf(false) }
    if (request.needsLogin) {
        AppAlertDialog(
            onDismissRequest = { app.backups.identityRequired.value = null },
            title = { Text("需要登录") },
            text = { Text("游客只能读取部分帖子的完整正文，填写数盟 ID 也无法解决。登录后重新备份即可保存全文；批量任务会保留队列，登录后点击“继续”。") },
            confirmButton = { TextButton(onClick = {
                app.backups.identityRequired.value = null
                navigator.navigate(com.zailinn.c001apk.ui.nav.LoginRoute())
            }) { Text("去登录") } },
            dismissButton = { TextButton(onClick = { app.backups.identityRequired.value = null }) { Text("取消") } },
        )
        return
    }
    AppAlertDialog(
        onDismissRequest = { if (!saving) app.backups.identityRequired.value = null },
        title = { Text("帖子正文受限") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("完整正文暂时无法读取。可填写本机的数盟 ID（SZLM ID），保存后重试当前备份。")
                OutlinedTextField(input, { input = it; error = "" }, Modifier.fillMaxWidth(),
                    label = { Text("数盟 ID 或官方复制的完整内容") }, enabled = !saving,
                    visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { visible = !visible }) {
                        Icon(if (visible) androidx.compose.material.icons.Icons.Outlined.VisibilityOff else androidx.compose.material.icons.Icons.Outlined.Visibility,
                            if (visible) "隐藏内容" else "显示内容")
                    } }, maxLines = 4, isError = error.isNotEmpty())
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                androidx.compose.foundation.layout.Row {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        input = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        error = if (input.isEmpty()) "剪贴板为空" else ""
                    }, enabled = !saving) { Text("粘贴") }
                    TextButton(onClick = { navigator.launchBrowser(DEVICE_ID_GUIDE) }) { Text("获取方法") }
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            val id = try { OfficialDeviceIdentity.parseInput(input) }
                catch (e: IllegalArgumentException) { error = e.message.orEmpty(); return@TextButton }
            saving = true
            scope.launch {
                try {
                    app.settings.setSzlmId(id)
                    app.settings.settings.first { it.protocol.szlmId == id }
                    app.backups.retryAfterIdentity(request)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = "保存失败，请重试" }
                finally { saving = false }
            }
        }, enabled = input.isNotBlank() && !saving) { Text(if (saving) "正在保存…" else "保存并重试") } },
        dismissButton = { TextButton(onClick = { app.backups.identityRequired.value = null }, enabled = !saving) { Text("暂不填写") } },
    )
}
