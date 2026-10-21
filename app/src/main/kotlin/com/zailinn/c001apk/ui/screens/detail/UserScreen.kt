package com.zailinn.c001apk.ui.screens.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zailinn.c001apk.AppContainer
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.LoadResult
import com.zailinn.c001apk.ui.LocalAppSettings
import com.zailinn.c001apk.ui.LocalSession
import com.zailinn.c001apk.ui.cards.ItemCard
import com.zailinn.c001apk.ui.common.Avatar
import com.zailinn.c001apk.ui.common.HtmlText
import com.zailinn.c001apk.ui.common.NetworkImage
import com.zailinn.c001apk.ui.common.PagedList
import com.zailinn.c001apk.ui.common.PagedListView
import com.zailinn.c001apk.ui.common.StatusBox
import com.zailinn.c001apk.ui.common.fromToday
import com.zailinn.c001apk.ui.nav.ChatRoute
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.nav.SearchRoute
import com.zailinn.c001apk.ui.nav.UserListRoute
import com.zailinn.c001apk.ui.screens.ContentViewModel
import com.zailinn.c001apk.ui.screens.appViewModel
import com.zailinn.c001apk.ui.screens.list.SimpleTopBar
import com.zailinn.c001apk.ui.screens.runAction
import com.zailinn.c001apk.ui.theme.TextSizes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class UserViewModel(app: AppContainer, private var uid: String) : ContentViewModel(app) {
    val user = MutableStateFlow<LoadResult<Datum>>(LoadResult.Loading)
    val blocked = MutableStateFlow(false)
    private var busy = false

    override val list = PagedList(viewModelScope) { page -> app.repository.userFeed(uid, page) }

    init {
        load()
        // A numeric uid needs no lookup, so posts load together with the profile.
        if (uid.all(Char::isDigit) && !app.content.isUserBlocked(uid)) list.start()
    }

    fun load() {
        user.value = LoadResult.Loading
        viewModelScope.launch {
            val result = app.repository.userSpace(uid)
            if (result is LoadResult.Success) {
                uid = result.value.uid ?: uid
                blocked.value = app.content.isUserBlocked(uid)
                if (!blocked.value) list.start()
            }
            user.value = result
        }
    }

    fun toggleBlock() {
        viewModelScope.launch {
            if (blocked.value) app.content.unblock(uid) else app.content.block(uid)
            blocked.value = !blocked.value
            if (!blocked.value) list.start()
        }
    }

    /** Device-only follow: guests always, accounts when secret follow is enabled. */
    fun toggleLocalFollow() {
        val data = (user.value as? LoadResult.Success)?.value ?: return
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val label = if (app.session.isLogin) "悄悄关注" else "本地关注"
                if (app.content.isLocalFollow(uid)) {
                    app.content.localUnfollow(uid)
                    app.toast("已取消$label，仅修改本机名单")
                } else {
                    app.content.localFollow(uid, data.username.orEmpty(), data.userAvatar.orEmpty())
                    app.toast("已$label，仅保存在本机，不会同步到账号")
                }
            } catch (_: Exception) {
                app.toast("本地关注保存失败，请重试")
            } finally {
                busy = false
            }
        }
    }

    fun toggleFollow() {
        if (!app.session.isLogin) return toggleLocalFollow()
        val data = (user.value as? LoadResult.Success)?.value ?: return
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val following = data.int("isFollow") == 1
                val result = app.runAction {
                    app.repository.postAction(if (following) "/v6/user/unfollow" else "/v6/user/follow", uid = uid)
                } ?: return@launch
                user.value = LoadResult.Success(data.with("isFollow", result.dataText?.toIntOrNull() ?: if (following) 0 else 1))
                app.toast(if (following) "取消关注成功" else "关注成功")
            } finally {
                busy = false
            }
        }
    }

    override fun block(uid: String, parent: Datum?) {
        if (uid == this.uid) toggleBlock() else super.block(uid, parent)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserScreen(uid: String) {
    val navigator = LocalNavigator.current
    val session = LocalSession.current
    val vm = appViewModel(key = "user-$uid") { UserViewModel(it, uid) }
    val state by vm.user.collectAsStateWithLifecycle()
    val blocked by vm.blocked.collectAsStateWithLifecycle()
    val follows by vm.app.content.localFollows.collectAsStateWithLifecycle()
    val secretFollow = LocalAppSettings.current.secretFollow
    var menu by remember { mutableStateOf(false) }
    val data = (state as? LoadResult.Success)?.value
    Scaffold(
        topBar = {
            SimpleTopBar(data?.username ?: "用户") {
                if (data != null) {
                    IconButton(onClick = {
                        navigator.navigate(SearchRoute(title = data.username, pageType = "user", pageParam = data.uid))
                    }) { Icon(Icons.Outlined.Search, "搜索") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "更多") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("复制链接") }, { menu = false; navigator.copy("https://www.coolapk.com/u/${data.uid}") },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) })
                        DropdownMenuItem({ Text("分享") }, { menu = false; navigator.share("https://www.coolapk1s.com/u/${data.uid}") },
                            leadingIcon = { Icon(Icons.Outlined.Share, null) })
                        DropdownMenuItem({ Text(if (blocked) "取消屏蔽" else "屏蔽") }, { menu = false; vm.toggleBlock() },
                            leadingIcon = { Icon(Icons.Outlined.Block, null) })
                        DropdownMenuItem({ Text("举报") }, { menu = false; navigator.report(data.uid.orEmpty(), "user") },
                            leadingIcon = { Icon(Icons.Outlined.ErrorOutline, null) })
                    }
                }
            }
        },
    ) { padding ->
        if (data == null) {
            StatusBox(state, vm::load, Modifier.padding(padding))
            return@Scaffold
        }
        val localFollow = follows.any { it.uid == data.uid }
        PagedListView(
            vm.list,
            Modifier.padding(padding),
            header = {
                item(key = "header") {
                    Column {
                        Box {
                            NetworkImage(
                                data.string("cover"),
                                Modifier.fillMaxWidth().aspectRatio(2.6f).clip(RoundedCornerShape(12.dp))
                                    .clickable { data.string("cover")?.let { navigator.openImages(listOf(it)) } },
                            )
                            Box(Modifier.align(Alignment.BottomStart).offset(x = 12.dp, y = 28.dp)) {
                                Avatar(data.userAvatar, 64.dp) { data.userAvatar?.let { navigator.openImages(listOf(it)) } }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.weight(1f))
                            if (session.isLogin) {
                                FilledTonalIconButton(onClick = {
                                    navigator.navigate(ChatRoute("", data.uid.orEmpty(), data.username.orEmpty()))
                                }) { Icon(Icons.Outlined.MailOutline, "私信") }
                            }
                            val following = if (session.isLogin) data.int("isFollow") == 1 else localFollow
                            // Long press adds a secret (device-only) follow when enabled.
                            androidx.compose.material3.Surface(
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = if (following) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primary,
                                contentColor = if (following) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(start = 8.dp).clip(androidx.compose.foundation.shape.CircleShape).combinedClickable(
                                    onClick = vm::toggleFollow,
                                    onLongClick = if (session.isLogin && secretFollow) vm::toggleLocalFollow else null,
                                ),
                            ) {
                                Text(
                                    when {
                                        !session.isLogin -> if (localFollow) "取消本地关注" else "本地关注"
                                        following -> "取消关注"
                                        else -> "关注"
                                    } + if (session.isLogin && localFollow) " · 悄悄关注" else "",
                                    Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                        Text(
                            data.username.orEmpty(),
                            Modifier.padding(top = 8.dp).clickable { navigator.copy(data.username.orEmpty()) },
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("uid: ${data.uid}", Modifier.clickable { navigator.copy(data.uid.orEmpty()) }, color = MaterialTheme.colorScheme.outline, fontSize = TextSizes.Meta)
                            Spacer(Modifier.width(8.dp))
                            Text("Lv.${data.text("level")}", color = MaterialTheme.colorScheme.primary, fontSize = TextSizes.Meta)
                        }
                        data.string("bio")?.takeIf { it.isNotEmpty() }?.let { HtmlText(it, Modifier.padding(top = 6.dp), fontSize = TextSizes.Nested) }
                        Row(Modifier.padding(top = 6.dp)) {
                            Text("${data.text("feed")}动态  ${data.text("be_like_num")}赞  ")
                            Text("${data.text("follow")}关注  ", Modifier.clickable {
                                navigator.navigate(UserListRoute("USER_FOLLOW", uid = data.uid))
                            }, color = MaterialTheme.colorScheme.primary)
                            Text("${data.text("fans")}粉丝", Modifier.clickable {
                                navigator.navigate(UserListRoute("FAN", uid = data.uid))
                            }, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(
                            "${fromToday(data.long("logintime"))}活跃" + (data.string("ip_location")?.let { " · IP 属地：$it" } ?: ""),
                            Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outline, fontSize = TextSizes.Meta,
                        )
                        if (blocked) Text("已屏蔽用户 ${data.username.orEmpty()}", Modifier.padding(vertical = 24.dp))
                    }
                }
            },
        ) { _, item -> if (!blocked) ItemCard(item, vm.actions) }
    }
}
