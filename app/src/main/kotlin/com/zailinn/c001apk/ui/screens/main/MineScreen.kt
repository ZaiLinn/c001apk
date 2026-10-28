package com.zailinn.c001apk.ui.screens.main

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.SwitchAccount
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Badge
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zailinn.c001apk.AppContainer
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.net.ApiFailure
import com.zailinn.c001apk.core.net.readApiObject
import com.zailinn.c001apk.ui.LocalSession
import com.zailinn.c001apk.ui.cards.CardShape
import com.zailinn.c001apk.ui.cards.ItemCard
import com.zailinn.c001apk.ui.cards.cardColor
import com.zailinn.c001apk.ui.common.Avatar
import com.zailinn.c001apk.ui.common.MessageBox
import com.zailinn.c001apk.ui.common.PagedList
import com.zailinn.c001apk.ui.common.PagedListView
import com.zailinn.c001apk.ui.nav.AccountsRoute
import com.zailinn.c001apk.ui.nav.HistoryRoute
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.nav.LoginRoute
import com.zailinn.c001apk.ui.nav.NotificationRoute
import com.zailinn.c001apk.ui.nav.SettingsRoute
import com.zailinn.c001apk.ui.nav.UserListRoute
import com.zailinn.c001apk.ui.nav.UserRoute
import com.zailinn.c001apk.ui.screens.ContentViewModel
import com.zailinn.c001apk.ui.screens.appViewModel
import com.zailinn.c001apk.ui.theme.TextSizes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

class MineViewModel(app: AppContainer) : ContentViewModel(app) {
    val profile = MutableStateFlow<Datum?>(null)
    val counts = MutableStateFlow<List<Int?>>(List(5) { null })

    override val list = PagedList(viewModelScope) { page -> app.repository.notifications("/v6/notification/list", page) }

    fun refreshAll() {
        if (!app.session.isLogin) return
        viewModelScope.launch {
            loadProfile()
            checkCount()
        }
    }

    private suspend fun loadProfile() {
        val generation = app.session.generation
        val uid = app.session.account?.uid ?: return
        try {
            val body = readApiObject(app.repository.profile(uid))
            val data = (body["data"] as? JsonObject)?.let(::Datum) ?: return
            if (generation != app.session.generation || data.uid != uid) return
            app.accounts.refreshCurrent(
                generation,
                avatar = data.userAvatar,
                username = data.username?.takeIf { it.isNotEmpty() }?.let { Uri.encode(it) },
                level = data.int("level"),
                exp = data.int("experience"),
                nextExp = data.int("next_level_experience"),
            )
            profile.value = data
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Profile header falls back to the saved account.
        }
    }

    private suspend fun checkCount() {
        val generation = app.session.generation
        try {
            val data = (readApiObject(app.repository.checkCount())["data"] as? JsonObject)?.let(::Datum) ?: return
            if (generation != app.session.generation) return
            counts.value = listOf("atme", "atcommentme", "feedlike", "contacts_follow", "message").map { data.int(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ApiFailure.from(e)
        }
    }

    fun clearCount(index: Int) {
        counts.value = counts.value.toMutableList().also { it[index] = null }
    }
}

private data class Shortcut(val label: String, val icon: ImageVector, val needsLogin: Boolean, val route: Any)

private val notificationEntries = listOf(
    Triple("@我的动态", Icons.Outlined.AlternateEmail, Color(0xFF2196F3)),
    Triple("@我的评论", Icons.AutoMirrored.Outlined.Message, Color(0xFF00BCD4)),
    Triple("我收到的赞", Icons.Outlined.ThumbUp, Color(0xFF4CAF50)),
    Triple("好友关注", Icons.Outlined.PersonAddAlt, Color(0xFFF44336)),
    Triple("私信", Icons.Outlined.MailOutline, Color(0xFFFF9800)),
)
val notificationTypes = listOf("AT", "COMMENT", "LIKE", "FOLLOW", "MESSAGE")

@Composable
fun MineScreen(generation: Int) {
    val navigator = LocalNavigator.current
    val session = LocalSession.current
    val vm = appViewModel(key = "mine-$generation") { MineViewModel(it) }
    val profile by vm.profile.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val account = vm.app.accounts.active
    // Each visit to this tab, and each pull-to-refresh, updates the profile and the unread counts.
    LaunchedEffect(vm) { vm.refreshAll() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的") },
                actions = {
                    IconButton(onClick = { navigator.navigate(AccountsRoute) }) { Icon(Icons.Outlined.SwitchAccount, "账号管理") }
                    IconButton(onClick = { navigator.navigate(SettingsRoute) }) { Icon(Icons.Outlined.Settings, "设置") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val header: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
                item {
                    if (!session.isLogin) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            FilledTonalButton(onClick = { navigator.navigate(LoginRoute()) }) { Text("点击登录") }
                        }
                    } else {
                        ProfileHeader(
                            avatar = profile?.userAvatar ?: account?.avatar,
                            name = profile?.username ?: account?.displayName.orEmpty(),
                            level = profile?.int("level") ?: account?.level ?: 0,
                            exp = profile?.int("experience") ?: account?.exp ?: 0,
                            nextExp = profile?.int("next_level_experience") ?: account?.nextExp ?: 1,
                            onClick = { navigator.navigate(UserRoute(session.uid)) },
                        )
                    }
                }
                if (session.isLogin) {
                    item {
                        CardRow(
                            listOf("动态", "关注", "粉丝").zip(listOf("feed", "follow", "fans")).map { (label, key) ->
                                Triple(label, profile?.string(key) ?: "-", null as ImageVector?)
                            },
                            onClick = { index -> navigator.navigate(UserListRoute(listOf("FEED", "FOLLOW", "FAN")[index])) },
                        )
                    }
                }
                item {
                    val shortcuts = listOf(
                        Shortcut("我的备份", Icons.Outlined.Archive, false, com.zailinn.c001apk.ui.nav.BackupsRoute),
                        Shortcut("本地收藏", Icons.Outlined.Archive, false, HistoryRoute(favorites = true)),
                        Shortcut("浏览历史", Icons.Outlined.History, false, HistoryRoute(favorites = false)),
                        Shortcut("我的常去", Icons.Outlined.MyLocation, true, UserListRoute("RECENT")),
                        Shortcut("我的收藏", Icons.Outlined.StarBorder, true, UserListRoute("COLLECTION")),
                        Shortcut("我的赞", Icons.Outlined.FavoriteBorder, true, UserListRoute("LIKE")),
                        Shortcut("我的回复", Icons.AutoMirrored.Outlined.Message, true, UserListRoute("REPLY")),
                    )
                    Surface(color = cardColor(), shape = CardShape) {
                        Column {
                            shortcuts.chunked(3).forEach { row ->
                                CardRow(row.map { Triple(it.label, null, it.icon) }, surface = false) { index ->
                                    val item = row[index]
                                    if (!item.needsLogin || session.isLogin) navigator.navigate(item.route) else navigator.toast("请先登录")
                                }
                            }
                        }
                    }
                }
                if (session.isLogin) {
                    notificationEntries.forEachIndexed { index, (label, icon, color) ->
                        item {
                            Surface(
                                color = cardColor(), shape = CardShape,
                                onClick = {
                                    navigator.navigate(NotificationRoute(notificationTypes[index]))
                                    vm.clearCount(index)
                                },
                            ) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(36.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
                                        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Text(label, Modifier.weight(1f))
                                    counts.getOrNull(index)?.takeIf { it > 0 }?.let { Badge { Text("$it") } }
                                }
                            }
                        }
                    }
                }
            }
            if (session.isLogin) {
                PagedListView(vm.list, onRefresh = vm::refreshAll, header = header) { _, item -> ItemCard(item, vm.actions) }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    header()
                    item { MessageBox("登录后可查看通知、私信与账号内容；本地收藏和浏览历史无需登录") }
                }
            }
        }
    }
}

@Composable
private fun ProfileHeader(avatar: String?, name: String, level: Int, exp: Int, nextExp: Int, onClick: () -> Unit) {
    Surface(color = cardColor(), shape = CardShape, onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(avatar, 56.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Lv.$level", color = MaterialTheme.colorScheme.primary, fontSize = TextSizes.Meta)
                    Spacer(Modifier.width(8.dp))
                    Text("$exp/$nextExp", color = MaterialTheme.colorScheme.outline, fontSize = TextSizes.Meta)
                }
                Spacer(Modifier.height(6.dp))
                LinearWavyProgressIndicator(progress = { (exp.toFloat() / nextExp.coerceAtLeast(1)).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CardRow(items: List<Triple<String, String?, ImageVector?>>, surface: Boolean = true, onClick: (Int) -> Unit) {
    val content = @Composable {
        Row(Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, (label, value, icon) ->
                Surface(onClick = { onClick(index) }, color = Color.Transparent, modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                        if (value != null) Text(value, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(label, fontSize = TextSizes.Meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (surface) Surface(color = cardColor(), shape = CardShape) { content() } else content()
}
