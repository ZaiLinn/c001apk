package com.zailinn.c001apk.ui.screens.settings

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zailinn.c001apk.data.HiddenLog
import com.zailinn.c001apk.ui.common.AppAlertDialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zailinn.c001apk.ui.LocalSession
import com.zailinn.c001apk.ui.common.Avatar
import com.zailinn.c001apk.ui.common.MessageBox
import com.zailinn.c001apk.ui.common.RoundedImage
import com.zailinn.c001apk.ui.common.SectionTitle
import com.zailinn.c001apk.ui.nav.FeedRoute
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.nav.TopicRoute
import com.zailinn.c001apk.ui.nav.UserRoute
import com.zailinn.c001apk.ui.screens.appContainer
import com.zailinn.c001apk.ui.screens.list.SimpleTopBar
import kotlinx.coroutines.launch

@Composable
fun BlacklistScreen(users: Boolean) {
    // Topics moved to the 我屏蔽的话题 category; an old route still lands there.
    if (!users) return TopicBlockScreen()
    val navigator = LocalNavigator.current
    val app = appContainer()
    val list by app.content.blockedUsers.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            SimpleTopBar("用户黑名单") {
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
                        headlineContent = { Text("uid: $value") },
                        trailingContent = {
                            IconButton(onClick = { scope.launch { app.content.unblock(value) } }) { Icon(Icons.Outlined.Delete, "移除") }
                        },
                        modifier = Modifier.clickable { navigator.navigate(UserRoute(value)) },
                    )
                }
            }
        }
    }
    if (adding) {
        EditDialog("添加用户 uid", "", onDismiss = { adding = false }) { value ->
            if (!value.all(Char::isDigit)) return@EditDialog app.toast("请输入数字 uid")
            scope.launch { if (!app.content.block(value)) app.toast("已存在") }
        }
    }
}

/**
 * The fixed category 我屏蔽的话题. A rule hides one topic by its exact name, or
 * with 包含 every topic whose name contains the word.
 */
@Composable
fun TopicBlockScreen() {
    val app = appContainer()
    val rules by app.content.blockedTopics.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            SimpleTopBar("我屏蔽的话题") {
                IconButton(onClick = { adding = true }) { Icon(Icons.Outlined.Add, "添加") }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item {
                ListItem(
                    headlineContent = {},
                    supportingContent = {
                        Text("属于这些话题的动态，以及正文里带 #话题# 的动态始终隐藏。打开“包含”后，名称里含有这个词的话题也一并隐藏。")
                    },
                )
            }
            if (rules.isEmpty()) item { MessageBox("还没有屏蔽的话题，可以在话题页点“屏蔽”添加") }
            items(rules, key = { it.name }) { rule ->
                ListItem(
                    headlineContent = { Text(rule.label) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                rule.contains,
                                onClick = { scope.launch { app.content.blockTopic(rule.name, !rule.contains) } },
                                label = { Text("包含") },
                            )
                            IconButton(onClick = { scope.launch { app.content.unblockTopic(rule.name) } }) { Icon(Icons.Outlined.Delete, "移除") }
                        }
                    },
                )
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var contains by remember { mutableStateOf(false) }
        AppAlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("屏蔽话题") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("话题名称") })
                    Row(
                        Modifier.fillMaxWidth().clickable { contains = !contains }.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(contains, { contains = it })
                        Text("名称包含此词的话题也屏蔽")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isBlank()) return@TextButton app.toast("请输入话题名称")
                    adding = false
                    scope.launch { if (!app.content.blockTopic(name, contains)) app.toast("已存在") }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("取消") } },
        )
    }
}

/** What the blacklists and hiding categories left out lately, newest first; tap to open the feed. */
@Composable
fun HiddenLogScreen() {
    val navigator = LocalNavigator.current
    val app = appContainer()
    val records by app.hiddenLog.records.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            SimpleTopBar("最近隐藏") {
                IconButton(onClick = { scope.launch { app.hiddenLog.clear() } }, enabled = records.isNotEmpty()) {
                    Icon(Icons.Outlined.DeleteSweep, "清空")
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item {
                ListItem(
                    headlineContent = {},
                    supportingContent = { Text("最近 ${HiddenLog.LIMIT} 条因黑名单或过滤分类而没有显示的内容，只存在本机。误伤时可以据此修改对应规则。") },
                )
            }
            if (records.isEmpty()) item { MessageBox("还没有被隐藏的内容") }
            items(records, key = { it.key }) { record ->
                ListItem(
                    overlineContent = { Text(listOf(record.title, record.hit).filter { it.isNotEmpty() }.joinToString(" · ")) },
                    headlineContent = { Text(record.text.ifEmpty { "（无文字）" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(listOf(record.username, DateFormat.format("MM-dd HH:mm", record.time).toString()).filter { it.isNotEmpty() }.joinToString(" · "))
                    },
                    modifier = Modifier.clickable(enabled = record.feedId.isNotEmpty()) { navigator.navigate(FeedRoute(record.feedId)) },
                )
            }
        }
    }
}

@Composable
fun LocalFollowManageScreen() {
    val navigator = LocalNavigator.current
    val app = appContainer()
    val session = LocalSession.current
    val users by app.content.localFollows.collectAsStateWithLifecycle()
    val topics by app.content.localFollowTopics.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val label = if (session.isLogin) "悄悄关注" else "本地关注"
    Scaffold(topBar = { SimpleTopBar("管理$label") }) { padding ->
        Column(Modifier.padding(padding)) {
            ListItem(headlineContent = {}, supportingContent = { Text("名单仅保存在本机，登录、退出或关闭悄悄关注开关后仍会保留。不会自动同步到账号。") })
            if (users.isEmpty() && topics.isEmpty()) {
                MessageBox("还没有关注的用户或话题")
            } else {
                LazyColumn {
                    if (users.isNotEmpty()) item(key = "users") { SectionTitle("用户") }
                    items(users, key = { "user-${it.uid}" }) { user ->
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
                    if (topics.isNotEmpty()) item(key = "topics") { SectionTitle("话题") }
                    items(topics, key = { "topic-${it.id}" }) { topic ->
                        ListItem(
                            headlineContent = { Text(topic.title) },
                            leadingContent = { RoundedImage(topic.logo, androidx.compose.ui.unit.Dp(40f)) },
                            trailingContent = {
                                IconButton(onClick = {
                                    scope.launch {
                                        app.content.localUnfollowTopic(topic.id)
                                        app.toast("已取消$label，仅修改本机名单")
                                    }
                                }) { Icon(Icons.Outlined.Delete, "取消关注") }
                            },
                            modifier = Modifier.clickable { navigator.navigate(TopicRoute(tag = topic.title)) },
                        )
                    }
                }
            }
        }
    }
}
