package com.zailinn.c001apk.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zailinn.c001apk.core.update.UpdateResult
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.screens.appContainer
import kotlinx.coroutines.launch

/** New release: later (24 h), skip this version, or download in the browser. */
@Composable
fun UpdateDialog(result: UpdateResult, onDismiss: () -> Unit) {
    val release = result.release ?: return
    val navigator = LocalNavigator.current
    val app = appContainer()
    val scope = rememberCoroutineScope()
    val later = {
        scope.launch { app.updates.preferences.snooze(System.currentTimeMillis()) }
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = later,
        title = { Text("发现新版本 ${release.version}") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "当前版本 ${result.currentVersion}" + (release.publishedAt?.take(10)?.let { " · 发布于 $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(release.notes, Modifier.heightIn(min = 0.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                navigator.launchBrowser(release.androidDownload(Build.SUPPORTED_ABIS.toList()).toString())
            }) { Text("更新") }
        },
        dismissButton = {
            TextButton(onClick = {
                scope.launch { app.updates.preferences.skip(release.version.toString()) }
                onDismiss()
            }) { Text("跳过") }
            TextButton(onClick = later) { Text("稍后") }
        },
    )
}
