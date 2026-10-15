package com.zailinn.c001apk.ui.screens.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonOutline
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zailinn.c001apk.data.SavedAccount
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
                        if (account.uid == active?.uid) {
                            Icon(Icons.Outlined.CheckCircle, "当前账号", tint = MaterialTheme.colorScheme.primary)
                        } else {
                            IconButton(onClick = { removing = account }) { Icon(Icons.Outlined.Delete, "移除") }
                        }
                    },
                    modifier = Modifier.clickable {
                        when {
                            account.needsLogin -> navigator.navigate(LoginRoute)
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
                    modifier = Modifier.clickable { navigator.navigate(LoginRoute) },
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
    removing?.let { account ->
        AlertDialog(
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
