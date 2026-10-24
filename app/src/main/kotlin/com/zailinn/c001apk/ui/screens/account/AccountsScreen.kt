package com.zailinn.c001apk.ui.screens.account

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonOutline
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zailinn.c001apk.core.net.AccountCredentials
import com.zailinn.c001apk.data.SavedAccount
import com.zailinn.c001apk.ui.common.AppAlertDialog
import com.zailinn.c001apk.ui.common.Avatar
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.nav.LoginRoute
import com.zailinn.c001apk.ui.screens.appContainer
import com.zailinn.c001apk.ui.screens.list.SimpleTopBar
import kotlinx.coroutines.launch

/** Saved accounts on this device: switch, add, remove or browse as a guest. */
@Composable
fun AccountsScreen() {
    val navigator = LocalNavigator.current
    val app = appContainer()
    val vault by app.accounts.vault.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var removing by remember { mutableStateOf<SavedAccount?>(null) }
    var exporting by remember { mutableStateOf<SavedAccount?>(null) }
    val active = vault.active
    Scaffold(topBar = { SimpleTopBar("账号管理") }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(vault.accounts, key = { it.uid }) { account ->
                ListItem(
                    headlineContent = { Text(account.displayName.ifEmpty { "用户 ${account.uid}" }) },
                    supportingContent = {
                        Text(if (account.needsLogin) "登录已失效，请重新登录" else "uid ${account.uid} · Lv.${account.level}")
                    },
                    leadingContent = { Avatar(account.avatar) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!account.needsLogin) {
                                IconButton(onClick = { exporting = account }) { Icon(Icons.Outlined.Cookie, "复制 Cookie") }
                            }
                            if (account.uid == active?.uid) {
                                Icon(Icons.Outlined.CheckCircle, "当前账号", tint = MaterialTheme.colorScheme.primary)
                            } else {
                                IconButton(onClick = { removing = account }) { Icon(Icons.Outlined.Delete, "移除") }
                            }
                        }
                    },
                    modifier = Modifier.clickable {
                        when {
                            account.needsLogin -> navigator.navigate(LoginRoute())
                            account.uid != active?.uid -> scope.launch {
                                app.accounts.switchTo(account.uid)
                                app.toast("已切换到 ${account.displayName}")
                            }
                        }
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("添加账号") },
                    leadingContent = { Icon(Icons.Outlined.PersonAdd, null) },
                    modifier = Modifier.clickable { navigator.navigate(LoginRoute()) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("使用 Cookie 添加账号") },
                    supportingContent = { Text("粘贴从其他设备导出的 Cookie，验证通过后保存") },
                    leadingContent = { Icon(Icons.Outlined.Cookie, null) },
                    modifier = Modifier.clickable { navigator.navigate(LoginRoute(cookie = true)) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("使用游客模式") },
                    supportingContent = { Text("保留已保存的账号，方便之后切换回来") },
                    leadingContent = { Icon(Icons.Outlined.PersonOutline, null) },
                    trailingContent = { if (active == null) Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { if (active != null) scope.launch { app.accounts.useGuest() } },
                )
            }
            active?.let { current ->
                item {
                    ListItem(
                        headlineContent = { Text("移除当前账号", color = MaterialTheme.colorScheme.error) },
                        supportingContent = { Text("清除本机保存的登录，并进入游客模式") },
                        modifier = Modifier.clickable { removing = current },
                    )
                }
            }
        }
    }
    exporting?.let { account -> CookieExportDialog(account, onDismiss = { exporting = null }) }
    removing?.let { account ->
        AppAlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("移除账号") },
            text = { Text("将清除 ${account.displayName} 在本机保存的登录。历史、收藏和屏蔽设置会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    scope.launch { app.accounts.remove(account.uid) }
                }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } },
        )
    }
}

/** Warns that the Cookie works like a password, then copies [account]'s Cookie. */
@Composable
internal fun CookieExportDialog(account: SavedAccount, onDismiss: () -> Unit) {
    val app = appContainer()
    val context = LocalContext.current
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出 ${account.displayName} 的 Cookie") },
        text = {
            Text(
                "Cookie 包含登录凭据，等同于账号密码，拿到它的人可以直接登录你的账号。只粘贴到自己的设备，不要发送或分享给他人。" +
                    "在不同设备上同时使用同一个 Cookie，可能触发服务端风控。",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                copySensitive(context, AccountCredentials(account.uid, account.username, account.token).toCookie())
                // Android 13 and later confirm a copy themselves.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) app.toast("已复制 Cookie")
            }) { Text("复制") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** Marks the clip sensitive so clipboard previews hide it; the flag only works in a PersistableBundle. */
private fun copySensitive(context: Context, text: String) {
    val clip = ClipData.newPlainText("c001apk cookie", text)
    clip.description.extras = PersistableBundle().apply {
        putBoolean("android.content.extra.IS_SENSITIVE", true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
    }
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
}
