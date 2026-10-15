package com.zailinn.c001apk.ui.screens.list

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zailinn.c001apk.AppContainer
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.data.FeedRecord
import com.zailinn.c001apk.ui.cards.FeedCard
import com.zailinn.c001apk.ui.cards.ItemActions
import com.zailinn.c001apk.ui.cards.ItemCard
import com.zailinn.c001apk.ui.common.MessageBox
import com.zailinn.c001apk.ui.common.PagedList
import com.zailinn.c001apk.ui.common.PagedListView
import com.zailinn.c001apk.ui.nav.ChatRoute
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.screens.ContentViewModel
import com.zailinn.c001apk.ui.screens.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun BackButton() {
    val navigator = LocalNavigator.current
    IconButton(onClick = { navigator.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
}

@Composable
fun SimpleTopBar(title: String, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { BackButton() },
        actions = actions,
    )
}

/** `/page/dataList` content; on first load it can discover sub-tabs and a page title. */
class DataListViewModel(app: AppContainer, url: String, title: String, private val discoverTabs: Boolean) : ContentViewModel(app) {
    val tabs = MutableStateFlow<List<Datum>>(emptyList())
    val pageTitle = MutableStateFlow<String?>(null)
    private var checked = false

    override val list = PagedList(
        viewModelScope,
        transform = { _, items ->
            if (discoverTabs && !checked) {
                checked = true
                items.firstOrNull { it.entityTemplate == "iconTabLinkGridCard" }?.let { tabs.value = it.entities }
                pageTitle.value = items.lastOrNull()?.obj("extraDataArr")?.string("pageTitle")
            }
            items
        },
    ) { page -> app.repository.dataList(url, title, page, includeConfigCard = discoverTabs) }
}

@Composable
fun DataListContent(url: String, title: String, key: String = "data-$url-$title", onSurface: Boolean = false) {
    val vm = appViewModel(key = key) { DataListViewModel(it, url, title, discoverTabs = false) }
    PagedListView(vm.list) { _, item -> ItemCard(item, vm.actions, onSurface = onSurface) }
}

@Composable
fun CarouselScreen(url: String, title: String) {
    val vm = appViewModel(key = "carousel-$url-$title") { DataListViewModel(it, url, title, discoverTabs = true) }
    val tabs by vm.tabs.collectAsStateWithLifecycle()
    val pageTitle by vm.pageTitle.collectAsStateWithLifecycle()
    Scaffold(topBar = { SimpleTopBar(pageTitle ?: title) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (tabs.isEmpty()) {
                PagedListView(vm.list) { _, item -> ItemCard(item, vm.actions) }
            } else {
                TabbedPages(tabs.map { it.title.orEmpty() }) { index ->
                    DataListContent(tabs[index].url.orEmpty(), tabs[index].title.orEmpty())
                }
            }
        }
    }
}

@Composable
fun TabbedPages(titles: List<String>, scrollable: Boolean = titles.size > 4, initial: Int = 0, page: @Composable (Int) -> Unit) {
    val pager = rememberPagerState(initialPage = initial.coerceIn(0, (titles.size - 1).coerceAtLeast(0))) { titles.size }
    val scope = rememberCoroutineScope()
    val tabs = @Composable {
        titles.forEachIndexed { index, text ->
            Tab(selected = pager.currentPage == index, onClick = { scope.launch { pager.animateScrollToPage(index) } }, text = { Text(text) })
        }
    }
    if (scrollable) PrimaryScrollableTabRow(pager.currentPage) { tabs() } else PrimaryTabRow(pager.currentPage) { tabs() }
    HorizontalPager(pager, Modifier.fillMaxSize(), key = { it }) { page(it) }
}

@Composable
fun DyhScreen(id: String, title: String) {
    Scaffold(topBar = { SimpleTopBar(title) }) { padding ->
        Column(Modifier.padding(padding)) {
            TabbedPages(listOf("精选", "广场")) { index ->
                val type = listOf("all", "square")[index]
                val vm = appViewModel(key = "dyh-$id-$type") { app ->
                    com.zailinn.c001apk.ui.screens.SimpleListViewModel(app) { page -> repository.dyhArticles(id, type, page) }
                }
                PagedListView(vm.list) { _, item -> ItemCard(item, vm.actions) }
            }
        }
    }
}

@Composable
fun CoolpicScreen(tag: String) {
    Scaffold(topBar = { SimpleTopBar(tag) }) { padding ->
        Column(Modifier.padding(padding)) {
            TabbedPages(listOf("精选", "热门", "最新")) { index ->
                val type = listOf("recommend", "hot", "newest")[index]
                val vm = appViewModel(key = "coolpic-$tag-$type") { app ->
                    com.zailinn.c001apk.ui.screens.SimpleListViewModel(app) { page -> repository.coolPic(tag, type, page) }
                }
                PagedListView(vm.list) { _, item -> ItemCard(item, vm.actions) }
            }
        }
    }
}

/** Follow, fan, like, reply and collection lists of an account. */
@Composable
fun UserListScreen(type: String, uid: String?, title: String?) {
    val app = com.zailinn.c001apk.ui.screens.appContainer()
    val owner = uid ?: app.session.account?.uid
    val isMe = owner == app.session.account?.uid
    val heading = title ?: when (type) {
        "FEED" -> "我的动态"
        "FOLLOW" -> "我的关注"
        "USER_FOLLOW" -> if (isMe) "我关注的人" else "TA关注的人"
        "FAN" -> if (isMe) "关注我的人" else "TA的粉丝"
        "RECENT" -> "我的常去"
        "LIKE" -> "我的赞"
        "REPLY" -> "我的回复"
        "COLLECTION" -> "我的收藏"
        else -> ""
    }
    Scaffold(topBar = { SimpleTopBar(heading) }) { padding ->
        Column(Modifier.padding(padding)) {
            when (type) {
                "FOLLOW" -> TabbedPages(listOf("用户", "话题", "数码", "应用")) { index ->
                    when (index) {
                        0 -> UserListContent("FOLLOW", owner)
                        1 -> DataListContent("#/topic/userFollowTagList", "我关注的话题")
                        2 -> DataListContent("#/product/followProductList", "我关注的数码吧")
                        else -> UserListContent("APK", owner)
                    }
                }
                "REPLY" -> TabbedPages(listOf("我的回复", "我收到的回复")) { index ->
                    UserListContent(if (index == 0) "REPLY" else "REPLYME", owner)
                }
                else -> UserListContent(type, if (type == "COLLECTION") null else owner, id = if (type == "COLLECTION_ITEM") uid else null)
            }
        }
    }
}

@Composable
private fun UserListContent(type: String, uid: String?, id: String? = null) {
    val url = when (type) {
        "FEED" -> "/v6/user/feedList"
        "FOLLOW", "USER_FOLLOW" -> "/v6/user/followList"
        "APK" -> "/v6/user/apkFollowList"
        "FAN" -> "/v6/user/fansList"
        "RECENT" -> "/v6/user/recentHistoryList"
        "LIKE" -> "/v6/user/likeList"
        "REPLY" -> "/v6/user/replyList"
        "REPLYME" -> "/v6/user/replyToMeList"
        "COLLECTION" -> "/v6/collection/list"
        else -> "/v6/collection/itemList"
    }
    val unique = type !in setOf("FOLLOW", "FAN", "USER_FOLLOW")
    val vm = appViewModel(key = "userlist-$type-$uid-$id") { app ->
        com.zailinn.c001apk.ui.screens.SimpleListViewModel(app, unique) { page ->
            repository.dataListFromUrl(url, buildList {
                if (type == "FEED") { add("showAnonymous" to "0"); add("isIncludeTop" to "1") }
                if (type == "COLLECTION_ITEM") id?.let { add("id" to it) } else uid?.let { add("uid" to it) }
                if (type == "COLLECTION") add("showDefault" to "0")
                addAll(page.query())
            })
        }
    }
    PagedListView(vm.list) { _, item -> ItemCard(item, vm.actions) }
}

@Composable
fun NotificationScreen(type: String) {
    val navigator = LocalNavigator.current
    val (title, url) = when (type) {
        "AT" -> "@我的动态" to "/v6/notification/atMeList"
        "COMMENT" -> "@我的评论" to "/v6/notification/atCommentMeList"
        "LIKE" -> "我收到的赞" to "/v6/notification/feedLikeList"
        "FOLLOW" -> "好友关注" to "/v6/notification/contactsFollowList"
        else -> "私信" to "/v6/message/list"
    }
    val vm = appViewModel(key = "notification-$type") { app ->
        com.zailinn.c001apk.ui.screens.SimpleListViewModel(app) { page -> repository.notifications(url, page) }
    }
    val actions = vm.actions.copy(
        onOpenMessage = { item ->
            navigator.navigate(ChatRoute(item.string("ukey").orEmpty(), item.string("messageUid").orEmpty(), item.string("messageUsername").orEmpty()))
            vm.list.replace(item.id) { it.with("unreadNum", null as Number?) }
        },
    )
    Scaffold(topBar = { SimpleTopBar(title) }) { padding ->
        Column(Modifier.padding(padding)) { PagedListView(vm.list) { _, item -> ItemCard(item, actions) } }
    }
}

fun FeedRecord.toDatum() = Datum(
    JsonObject(
        mapOf(
            "entityType" to JsonPrimitive("feed"), "id" to JsonPrimitive(id), "entityId" to JsonPrimitive(id),
            "uid" to JsonPrimitive(uid), "username" to JsonPrimitive(username), "userAvatar" to JsonPrimitive(userAvatar),
            "message" to JsonPrimitive(message), "device_title" to JsonPrimitive(device), "dateline" to JsonPrimitive(dateline),
        ),
    ),
)

@Composable
fun HistoryScreen(favorites: Boolean) {
    val app = com.zailinn.c001apk.ui.screens.appContainer()
    val records by (if (favorites) app.content.favorites else app.content.history).collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    val label = if (favorites) "本地收藏" else "浏览历史"
    val actions = ItemActions(
        onDelete = { item, _ ->
            scope.launch { item.id?.let { if (favorites) app.content.removeFavorite(it) else app.content.removeHistory(it) } }
        },
        onBlock = { uid, _ -> scope.launch { app.content.block(uid) } },
        removable = true,
    )
    Scaffold(
        topBar = {
            SimpleTopBar(label) {
                IconButton(onClick = { confirm = true }, enabled = records.isNotEmpty()) { Icon(Icons.Outlined.ClearAll, "清除全部") }
            }
        },
    ) { padding ->
        if (records.isEmpty()) {
            MessageBox("暂无内容", Modifier.padding(padding))
        } else {
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            ) {
                items(records.size, key = { records[it].id }) { index -> FeedCard(records[index].toDatum(), actions) }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("确定清除全部${if (favorites) "收藏" else "浏览历史"}？") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch { if (favorites) app.content.clearFavorites() else app.content.clearHistory() }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        )
    }
}
