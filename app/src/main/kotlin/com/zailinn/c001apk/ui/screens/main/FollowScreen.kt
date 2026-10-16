package com.zailinn.c001apk.ui.screens.main

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.rememberCoroutineScope
import com.zailinn.c001apk.data.SettingKeys
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

/** Categories of the Follow page; [type] is the saved followType, [LOCAL_FOLLOW] for 悄悄关注. */
private val followCategories = listOf(1 to "用户关注", 2 to "话题关注", 3 to "数码关注", 4 to "应用关注", 0 to "全部关注", LOCAL_FOLLOW to "悄悄关注")

const val LOCAL_FOLLOW = 5

@Composable
fun FollowScreen(generation: Int, reselect: Int) {
    val navigator = LocalNavigator.current
    val session = LocalSession.current
    val app = com.zailinn.c001apk.ui.screens.appContainer()
    val scope = rememberCoroutineScope()
    val selected = LocalAppSettings.current.followType
    val local = selected == LOCAL_FOLLOW
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关注") },
                actions = { IconButton(onClick = { navigator.navigate(SearchRoute()) }) { Icon(Icons.Outlined.Search, "搜索") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (session.isLogin) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    followCategories.forEach { (type, label) ->
                        FilterChip(
                            selected = selected == type,
                            onClick = { scope.launch { app.settings.set(SettingKeys.followType, type) } },
                            label = { Text(label) },
                        )
                    }
                }
            }
            if (!session.isLogin || local) {
                LocalFollowFeed(generation, reselect)
            } else {
                val type = selected
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

