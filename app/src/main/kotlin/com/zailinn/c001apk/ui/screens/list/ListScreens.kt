package com.zailinn.c001apk.ui.screens.list

import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import com.zailinn.c001apk.ui.screens.collection.CollectionEditDialog
import com.zailinn.c001apk.ui.screens.collection.createCollection
import androidx.compose.material.icons.outlined.PushPin
import com.zailinn.c001apk.ui.screens.runAction
import com.zailinn.c001apk.ui.common.SheetAction
import com.zailinn.c001apk.ui.common.ActionSheet
import com.zailinn.c001apk.ui.common.FillingTabRow
import com.zailinn.c001apk.core.net.ConversationAction
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

/** With [scrollBehavior] the bar can slide away while reading, keeping its color when content scrolls under it. */
@Composable
fun SimpleTopBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { BackButton() },
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = if (scrollBehavior == null) TopAppBarDefaults.topAppBarColors()
        else TopAppBarDefaults.topAppBarColors(scrolledContainerColor = MaterialTheme.colorScheme.surface),
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
fun TabbedPages(titles: List<String>, initial: Int = 0, page: @Composable (Int) -> Unit) {
    val pager = rememberPagerState(initialPage = initial.coerceIn(0, (titles.size - 1).coerceAtLeast(0))) { titles.size }
    val scope = rememberCoroutineScope()
    val tabs = @Composable {
        titles.forEachIndexed { index, text ->
            Tab(selected = pager.currentPage == index, onClick = { scope.launch { pager.animateScrollToPage(index) } }, text = { Text(text) })
        }
    }
    FillingTabRow(pager.currentPage, titles.size) { tabs() }
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
    var creating by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            SimpleTopBar(heading) {
                if (type == "COLLECTION") IconButton(onClick = { creating = true }) { Icon(Icons.Outlined.Add, "新建收藏夹") }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            when (type) {
                "COLLECTION" -> MyCollections(creating, onCreated = { creating = false })
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
                else -> UserListContent(type, owner, id = if (type == "COLLECTION_ITEM") uid else null)
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
        else -> "/v6/collection/itemList"
    }
    val unique = type !in setOf("FOLLOW", "FAN", "USER_FOLLOW")
    val vm = appViewModel(key = "userlist-$type-$uid-$id") { app ->
        com.zailinn.c001apk.ui.screens.SimpleListViewModel(app, unique) { page ->
            repository.dataListFromUrl(url, buildList {
                if (type == "FEED") { add("showAnonymous" to "0"); add("isIncludeTop" to "1") }
                if (type == "COLLECTION_ITEM") id?.let { add("id" to it) } else uid?.let { add("uid" to it) }
                addAll(page.query())
            })
        }
    }
    PagedListView(vm.list) { _, item -> ItemCard(item, vm.actions) }
}

/** The account's collections; new ones from the top bar, the rest from a long press. */
@Composable
private fun MyCollections(creating: Boolean, onCreated: () -> Unit) {
    val vm = appViewModel(key = "userlist-COLLECTION-mine") { app ->
        com.zailinn.c001apk.ui.screens.SimpleListViewModel(app) { page ->
            repository.dataListFromUrl("/v6/collection/list", listOf("showDefault" to "0") + page.query())
        }
    }
    val app = vm.app
    val scope = rememberCoroutineScope()
    var menuFor by remember { mutableStateOf<Datum?>(null) }
    var editing by remember { mutableStateOf<Datum?>(null) }
    var deleting by remember { mutableStateOf<Datum?>(null) }
    PagedListView(vm.list) { _, item -> ItemCard(item, vm.actions.copy(onCollectionMenu = { menuFor = it })) }
    if (creating) {
        CollectionEditDialog("新建收藏夹", onDismiss = onCreated) { title, description, open ->
            scope.launch { if (app.createCollection(title, description, open) != null) vm.list.refresh(showIndicator = true) }
        }
    }
    menuFor?.let { item ->
        ActionSheet(
            listOfNotNull(
                SheetAction(Icons.Outlined.Edit, "编辑收藏夹") { editing = item },
                SheetAction(Icons.Outlined.CleaningServices, "清理失效内容") {
                    scope.launch { app.runAction { app.repository.clearInvalidCollectionItems(item.id.orEmpty()) }?.dataText?.let(app::toast) }
                },
                // The default collection cannot be removed.
                SheetAction(Icons.Outlined.Delete, "删除收藏夹", danger = true) { deleting = item }.takeIf { item.int("isDefault") != 1 },
            ),
            onDismiss = { menuFor = null },
        )
    }
    editing?.let { item ->
        CollectionEditDialog("编辑收藏夹", item, onDismiss = { editing = null }) { title, description, open ->
            scope.launch {
                app.runAction {
                    app.repository.updateCollection(item.id.orEmpty(), title, description, item.string("cover_pic").orEmpty(), open)
                } ?: return@launch
                app.toast("已保存")
                vm.list.refresh(showIndicator = true)
            }
        }
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除收藏夹？") },
            text = { Text("「${item.title.orEmpty()}」及其中的收藏记录将被删除，收藏的内容本身不受影响") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        val result = app.runAction { app.repository.deleteCollection(item.id.orEmpty()) } ?: return@launch
                        vm.list.update { list -> list.filter { it.id != item.id } }
                        app.toast(result.dataText ?: "已删除")
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
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
    val scope = rememberCoroutineScope()
    var menuFor by remember { mutableStateOf<Datum?>(null) }
    var deleting by remember { mutableStateOf<Datum?>(null) }
    val actions = vm.actions.copy(
        onOpenMessage = { item ->
            navigator.navigate(ChatRoute(item.string("ukey").orEmpty(), item.string("messageUid").orEmpty(), item.string("messageUsername").orEmpty()))
            vm.list.replace(item.id) { it.with("unreadNum", null as Number?) }
        },
        onMessageMenu = { item -> if (!item.string("ukey").isNullOrEmpty()) menuFor = item },
    )
    fun update(item: Datum, action: ConversationAction) {
        val ukey = item.string("ukey") ?: return
        scope.launch {
            vm.app.runAction { vm.app.repository.conversationAction(action, ukey) } ?: return@launch
            when (action) {
                ConversationAction.Delete -> vm.list.update { list -> list.filter { it.string("ukey") != ukey } }
                // Pinned conversations lead the list, in the order they were pinned.
                else -> vm.list.update { list ->
                    val changed = list.map { if (it.string("ukey") == ukey) it.with("isTop", if (action == ConversationAction.Pin) 1 else 0) else it }
                    val target = changed.first { it.string("ukey") == ukey }
                    val rest = changed.filter { it.string("ukey") != ukey }
                    val pinned = rest.takeWhile { it.int("isTop") == 1 }
                    if (action == ConversationAction.Pin) listOf(target) + rest else pinned + target + rest.drop(pinned.size)
                }
            }
            vm.app.toast(when (action) { ConversationAction.Pin -> "已置顶会话"; ConversationAction.Unpin -> "已取消置顶"; else -> "已删除会话" })
        }
    }
    Scaffold(topBar = { SimpleTopBar(title) }) { padding ->
        Column(Modifier.padding(padding)) { PagedListView(vm.list) { _, item -> ItemCard(item, actions) } }
    }
    menuFor?.let { item ->
        val pinned = item.int("isTop") == 1
        ActionSheet(
            listOf(
                SheetAction(Icons.Outlined.PushPin, if (pinned) "取消置顶" else "置顶会话") {
                    update(item, if (pinned) ConversationAction.Unpin else ConversationAction.Pin)
                },
                SheetAction(Icons.Outlined.Delete, "删除会话", danger = true) { deleting = item },
            ),
            onDismiss = { menuFor = null },
        )
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除此会话？") },
            text = { Text("与 ${item.string("messageUsername").orEmpty()} 的会话将从私信列表中删除") },
            confirmButton = { TextButton(onClick = { deleting = null; update(item, ConversationAction.Delete) }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
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
