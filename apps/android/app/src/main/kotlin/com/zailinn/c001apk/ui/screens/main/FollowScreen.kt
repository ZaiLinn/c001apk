package com.zailinn.c001apk.ui.screens.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zailinn.c001apk.AppContainer
import com.zailinn.c001apk.core.follow.LocalFollowPager
import com.zailinn.c001apk.core.model.LoadResult
import com.zailinn.c001apk.ui.LocalAppSettings
import com.zailinn.c001apk.ui.LocalSession
import com.zailinn.c001apk.ui.cards.ItemCard
import com.zailinn.c001apk.ui.common.MessageBox
import com.zailinn.c001apk.ui.common.PagedList
import com.zailinn.c001apk.ui.common.PagedListView
import com.zailinn.c001apk.ui.nav.LocalFollowRoute
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.nav.SearchRoute
import com.zailinn.c001apk.ui.nav.SettingsRoute
import com.zailinn.c001apk.ui.screens.ContentViewModel
import com.zailinn.c001apk.ui.screens.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

fun followTitle(type: Int) = when (type) {
    1 -> "好友关注"
    2 -> "话题关注"
    3 -> "数码关注"
    4 -> "应用关注"
    else -> "全部关注"
}

fun followUrl(type: Int): String {
    val url = "/page?url=V9_HOME_TAB_FOLLOW"
    return when (type) {
        1 -> "$url&type=circle"
        2 -> "$url&type=topic"
        3 -> "$url&type=product"
        4 -> "$url&type=apk"
        else -> url
    }
}

class AccountFollowViewModel(app: AppContainer, type: Int) : ContentViewModel(app) {
    override val list = PagedList(viewModelScope) { page -> app.repository.dataList(followUrl(type), followTitle(type), page) }
}

/** Timelines of users followed only on this device, merged by time. */
class LocalFollowFeedViewModel(app: AppContainer) : ContentViewModel(app) {
    val warning = MutableStateFlow<String?>(null)
    private var pager: LocalFollowPager? = null

    override val list = PagedList(viewModelScope) { page ->
        if (page.page == 1 || pager == null) {
            pager = LocalFollowPager(
                app.content.localFollows.value.map { it.uid }.filter { !app.content.isUserBlocked(it) },
            ) { uid, cursor -> app.repository.userFeed(uid, cursor, includeTop = false) }
        }
        val current = pager!!
        val items = current.next()
        warning.value = current.warning
        when {
            items.isNotEmpty() -> LoadResult.Success(items)
            current.warning != null -> LoadResult.Error(current.warning!!)
            else -> LoadResult.Empty
        }
    }

    init {
        viewModelScope.launch {
            combine(app.content.localFollows, app.content.blockedUsers) { a, b -> a to b }.drop(1).collect { list.refresh() }
        }
    }
}

@Composable
fun FollowScreen(generation: Int, reselect: Int) {
    val navigator = LocalNavigator.current
    val session = LocalSession.current
    var local by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("动态") },
                actions = {
                    IconButton(onClick = { navigator.navigate(SettingsRoute) }) { Icon(Icons.Outlined.Settings, "设置") }
                    IconButton(onClick = { navigator.navigate(SearchRoute()) }) { Icon(Icons.Outlined.Search, "搜索") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (session.isLogin) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    listOf("账号关注", "悄悄关注").forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = local == (index == 1),
                            onClick = { local = index == 1 },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) { Text(label) }
                    }
                }
            }
            if (!session.isLogin || local) {
                LocalFollowFeed(generation, reselect)
            } else {
                val type = LocalAppSettings.current.followType
                val vm = appViewModel(key = "follow-account-$type-$generation") { AccountFollowViewModel(it, type) }
                val listState = rememberLazyListState()
                LaunchedEffect(reselect) { if (reselect > 0) listState.animateScrollToItem(0) }
                PagedListView(vm.list, listState = listState) { _, item -> ItemCard(item, vm.actions) }
            }
        }
    }
}

@Composable
private fun LocalFollowFeed(generation: Int, reselect: Int) {
    val navigator = LocalNavigator.current
    val session = LocalSession.current
    val label = if (session.isLogin) "悄悄关注" else "本地关注"
    val vm = appViewModel(key = "follow-local-$generation") { LocalFollowFeedViewModel(it) }
    val users by vm.app.content.localFollows.collectAsStateWithLifecycle()
    val warning by vm.warning.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(reselect) { if (reselect > 0) listState.animateScrollToItem(0) }
    Column {
        ListItem(
            headlineContent = { Text("${label}动态") },
            supportingContent = { Text("读取本机名单中的用户动态，不会同步账号关注") },
            trailingContent = { TextButton(onClick = { navigator.navigate(LocalFollowRoute) }) { Text("管理") } },
        )
        warning?.let { message ->
            ListItem(
                headlineContent = { Text(message) },
                trailingContent = { TextButton(onClick = vm.list::retry) { Text("重试") } },
            )
        }
        if (users.isEmpty()) {
            MessageBox(
                if (session.isLogin) "还没有悄悄关注的用户\n在设置中开启后，长按用户主页的关注按钮即可添加"
                else "还没有本地关注的用户\n在用户主页点击“本地关注”，即可在这里查看动态",
            )
        } else {
            PagedListView(vm.list, listState = listState) { _, item -> ItemCard(item, vm.actions) }
        }
    }
}

