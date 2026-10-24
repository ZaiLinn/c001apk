package com.zailinn.c001apk.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zailinn.c001apk.data.CaptchaLog
import com.zailinn.c001apk.ui.common.SectionTitle
import com.zailinn.c001apk.ui.screens.appContainer
import com.zailinn.c001apk.ui.screens.list.SimpleTopBar
import kotlinx.coroutines.launch

/** The last captcha challenges with the endpoint and request rate, to find out what triggers them. */
@Composable
fun CaptchaLogScreen() {
    val app = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val records by app.captchaLog.records.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            SimpleTopBar("人机验证记录") {
                IconButton(onClick = { copy(context, CaptchaLog.report(records)); app.toast("已复制") }, enabled = records.isNotEmpty()) {
                    Icon(Icons.Outlined.ContentCopy, "复制")
                }
                IconButton(onClick = { confirmClear = true }, enabled = records.isNotEmpty()) {
                    Icon(Icons.Outlined.DeleteSweep, "清空")
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item {
                Text(
                    "记录最近 ${CaptchaLog.LIMIT} 次被要求人机验证的接口、当时的请求频率和处理结果，只存在本机，" +
                        "不含账号、设备标识和内容。反馈问题时可以复制附上。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (records.isEmpty()) {
                item { ListItem(headlineContent = { Text("还没有记录") }) }
            } else {
                item { SectionTitle("统计") }
                items(CaptchaLog.summary(records)) { ListItem(headlineContent = { Text(it, style = MaterialTheme.typography.bodyMedium) }) }
                item { SectionTitle("明细（最新在前）") }
                items(records.asReversed(), key = { "${it.time}-${it.path}-${it.outcome}" }) {
                    ListItem(headlineContent = { Text(CaptchaLog.describe(it), style = MaterialTheme.typography.bodySmall) })
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空记录？") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; scope.launch { app.captchaLog.clear() } }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

private fun copy(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText("c001apk captcha log", text))
}
