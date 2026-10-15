package com.zailinn.c001apk.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zailinn.c001apk.ui.LocalSession
import com.zailinn.c001apk.ui.common.Avatar
import com.zailinn.c001apk.ui.common.MessageBox
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.nav.UserRoute
import com.zailinn.c001apk.ui.screens.appContainer
import com.zailinn.c001apk.ui.screens.list.SimpleTopBar
import kotlinx.coroutines.launch

@Composable
fun BlacklistScreen(users: Boolean) {
    val navigator = LocalNavigator.current
    val app = appContainer()
    val list by (if (users) app.content.blockedUsers else app.content.blockedTopics).collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            SimpleTopBar(if (users) "用户黑名单" else "话题黑名单") {
                IconButton(onClick = { adding = true }) { Icon(Icons.Outlined.Add, "添加") }
            }
        },
    ) { padding ->
        if (list.isEmpty()) {
            MessageBox("暂无内容", Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(list, key = { it }) { value ->
                    ListItem(
                        headlineContent = { Text(if (users) "uid: $value" else value) },
                        trailingContent = {
                            IconButton(onClick = { scope.launch { app.content.unblock(value, users) } }) { Icon(Icons.Outlined.Delete, "移除") }
                        },
                        modifier = Modifier.clickable(enabled = users) { navigator.navigate(UserRoute(value)) },
                    )
                }
            }
        }
    }
    if (adding) {
        EditDialog(if (users) "添加用户 uid" else "添加话题关键词", "", onDismiss = { adding = false }) { value ->
            if (users && !value.all(Char::isDigit)) return@EditDialog app.toast("请输入数字 uid")
            scope.launch { if (!app.content.block(value, users)) app.toast("已存在") }
        }
    }
}

@Composable
fun LocalFollowManageScreen() {
    val navigator = LocalNavigator.current
    val app = appContainer()
    val session = LocalSession.current
    val users by app.content.localFollows.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val label = if (session.isLogin) "悄悄关注" else "本地关注"
    Scaffold(topBar = { SimpleTopBar("管理$label") }) { padding ->
        Column(Modifier.padding(padding)) {
            ListItem(headlineContent = {}, supportingContent = { Text("名单仅保存在本机，登录、退出或关闭悄悄关注开关后仍会保留。不会自动同步到账号。") })
            if (users.isEmpty()) {
                MessageBox("还没有关注的用户")
            } else {
                LazyColumn {
                    items(users, key = { it.uid }) { user ->
                        ListItem(
                            headlineContent = { Text(user.name.ifEmpty { "用户 ${user.uid}" }) },
                            supportingContent = { Text("uid: ${user.uid}") },
                            leadingContent = { Avatar(user.avatar, androidx.compose.ui.unit.Dp(40f)) },
                            trailingContent = {
                                IconButton(onClick = {
                                    scope.launch {
                                        app.content.localUnfollow(user.uid)
                                        app.toast("已取消$label，仅修改本机名单")
                                    }
                                }) { Icon(Icons.Outlined.Delete, "取消关注") }
                            },
                            modifier = Modifier.clickable { navigator.navigate(UserRoute(user.uid)) },
                        )
                    }
                }
            }
        }
    }
}
