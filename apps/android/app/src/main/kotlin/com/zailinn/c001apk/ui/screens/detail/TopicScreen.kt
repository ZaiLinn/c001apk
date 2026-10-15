package com.zailinn.c001apk.ui.screens.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zailinn.c001apk.AppContainer
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.LoadResult
import com.zailinn.c001apk.ui.LocalSession
import com.zailinn.c001apk.ui.common.MessageBox
import com.zailinn.c001apk.ui.common.RoundedImage
import com.zailinn.c001apk.ui.common.StatusBox
import com.zailinn.c001apk.ui.common.htmlToPlain
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.nav.SearchRoute
import com.zailinn.c001apk.ui.screens.appViewModel
import com.zailinn.c001apk.ui.screens.compose.ComposeTarget
import com.zailinn.c001apk.ui.screens.compose.ComposerSheet
import com.zailinn.c001apk.ui.screens.list.DataListContent
import com.zailinn.c001apk.ui.screens.list.SimpleTopBar
import com.zailinn.c001apk.ui.screens.list.TabbedPages
import com.zailinn.c001apk.ui.screens.runAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val productSorts = listOf(
    "默认" to "ignoreEntityById=1",
    "最新" to "ignoreEntityById=1&listType=dateline_desc",
    "热度" to "listType=rank_score",
)

/** Topic (by tag) or product (by id) with its server-defined tabs. */
class TopicViewModel(private val app: AppContainer, private val tag: String?, private val productId: String?) : ViewModel() {
    val topic = MutableStateFlow<LoadResult<Datum>>(LoadResult.Loading)
    val blocked = MutableStateFlow(false)
    val following = MutableStateFlow(false)

    init {
        load()
    }

    fun load() {
        topic.value = LoadResult.Loading
        viewModelScope.launch {
            val result = app.repository.topicDetail(tag, productId)
            if (result is LoadResult.Success) {
                blocked.value = app.content.filter().isTopicBlocked(result.value.title.orEmpty())
                following.value = result.value.userAction?.int("follow") == 1
            }
            topic.value = result
        }
    }

    fun toggleBlock() {
        val title = (topic.value as? LoadResult.Success)?.value?.title ?: return
        viewModelScope.launch {
            if (blocked.value) app.content.unblock(title, user = false) else app.content.block(title, user = false)
            blocked.value = !blocked.value
        }
    }

    fun toggleFollow() {
        val data = (topic.value as? LoadResult.Success)?.value ?: return
        if (!app.session.isLogin) return app.toast("请先登录")
        viewModelScope.launch {
            val follow = following.value
            val result = if (data.entityType == "topic") {
                app.runAction {
                    app.repository.getAction(if (follow) "/v6/feed/unFollowTag" else "/v6/feed/followTag", listOf("tag" to data.title.orEmpty()))
                }
            } else {
                app.runAction {
                    app.repository.postAction(
                        "/v6/product/changeFollowStatus",
                        fields = listOf("id" to data.id.orEmpty(), "status" to if (follow) "0" else "1"),
                    )
                }
            } ?: return@launch
            following.value = !follow
            app.toast(result.dataText?.takeIf { data.entityType != "topic" } ?: if (follow) "取消关注成功" else "关注成功")
        }
    }
}

@Composable
fun TopicScreen(tag: String?, id: String?) {
    val navigator = LocalNavigator.current
    val session = LocalSession.current
    val vm = appViewModel(key = "topic-$tag-$id") { TopicViewModel(it, tag, id) }
    val state by vm.topic.collectAsStateWithLifecycle()
    val blocked by vm.blocked.collectAsStateWithLifecycle()
    val following by vm.following.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var composing by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(0) }
    val data = (state as? LoadResult.Success)?.value
    val isTopic = data?.entityType == "topic"
    Scaffold(
        topBar = {
            SimpleTopBar(data?.title ?: tag.orEmpty()) {
                if (data != null) {
                    if (!blocked) {
                        IconButton(onClick = {
                            navigator.navigate(
                                SearchRoute(
                                    title = data.title,
                                    pageType = if (isTopic) "tag" else "product_phone",
                                    pageParam = if (isTopic) data.title else data.id,
                                ),
                            )
                        }) { Icon(Icons.Outlined.Search, "搜索") }
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "更多") }
                    DropdownMenu(menu, { menu = false }) {
                        val shareUrl = "https://www.coolapk1s.com/" + if (isTopic) "t/${data.title}" else "product/${data.id}"
                        DropdownMenuItem({ Text("复制链接") }, { menu = false; navigator.copy(shareUrl) })
                        DropdownMenuItem({ Text("分享") }, { menu = false; navigator.share(shareUrl) })
                        if (!isTopic) {
                            productSorts.forEachIndexed { index, (label, _) ->
                                DropdownMenuItem({ Text("排序：$label" + if (index == sort) " ✓" else "") }, { menu = false; sort = index })
                            }
                        }
                        DropdownMenuItem({ Text(if (following) "取消关注" else "关注") }, { menu = false; vm.toggleFollow() })
                        DropdownMenuItem({ Text(if (blocked) "取消屏蔽" else "屏蔽") }, { menu = false; vm.toggleBlock() })
                    }
                }
            }
        },
        floatingActionButton = {
            if (data != null && session.isLogin && !blocked) {
                FloatingActionButton(onClick = { composing = true }) { Icon(Icons.Filled.Add, "发布") }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (data == null) {
                StatusBox(state, vm::load)
                return@Column
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundedImage(data.logo, 52.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(data.title.orEmpty(), style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(
                            data.string("hot_num_txt")?.let { "${it}热度" },
                            data.string("commentnum_txt")?.let { "${it}讨论" },
                            data.string("follow_num_txt")?.let { "${it}关注" },
                        ).joinToString("  "),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.outline,
                    )
                    data.description?.takeIf { it.isNotEmpty() }?.let {
                        Text(htmlToPlain(it), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                    }
                }
            }
            if (blocked) {
                MessageBox("已屏蔽 ${data.title.orEmpty()}")
                return@Column
            }
            val tabs = data.list("tabList")
            if (tabs.isEmpty()) {
                MessageBox("暂无内容")
            } else {
                val selected = tabs.indexOfFirst { it.string("page_name") == data.string("selectedTab") || it.string("pageName") == data.string("selectedTab") }
                TabbedPages(tabs.map { it.title.orEmpty() }, initial = selected.coerceAtLeast(0)) { index ->
                    val tab = tabs[index]
                    if (!isTopic && tab.title == "讨论" && sort > 0) {
                        // Product discussions can be re-sorted from the menu, as in the Flutter client.
                        val (label, query) = productSorts[sort]
                        DataListContent("/page?url=/product/feedList?type=feed&id=${data.id}&$query", label, key = "topic-${data.id}-$index-$sort")
                    } else {
                        DataListContent(tab.url.orEmpty(), tab.title.orEmpty(), key = "topic-${data.id}-$index")
                    }
                }
            }
        }
    }
    if (composing && data != null) {
        ComposerSheet(
            target = ComposeTarget.NewFeed(if (isTopic) "tag" else "product_phone", data.id, data.title),
            onDismiss = { composing = false },
        )
    }
}
