package com.zailinn.c001apk.ui.screens.detail

import androidx.compose.material3.ExperimentalMaterial3Api
import com.zailinn.c001apk.ui.screens.list.BackButton
import kotlin.math.roundToInt
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.zailinn.c001apk.ui.cards.LocalProductConfigs
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zailinn.c001apk.AppContainer
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.LoadResult
import com.zailinn.c001apk.ui.LocalAppSettings
import com.zailinn.c001apk.ui.LocalSession
import com.zailinn.c001apk.ui.common.AppDropdownMenu
import com.zailinn.c001apk.ui.common.MessageBox
import com.zailinn.c001apk.ui.common.RoundedImage
import com.zailinn.c001apk.ui.common.StatusBox
import com.zailinn.c001apk.ui.common.htmlToPlain
import com.zailinn.c001apk.ui.common.hapticClick
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.nav.SearchRoute
import com.zailinn.c001apk.ui.screens.appViewModel
import com.zailinn.c001apk.ui.screens.compose.ComposeTarget
import com.zailinn.c001apk.ui.screens.compose.ComposerSheet
import com.zailinn.c001apk.ui.screens.list.DataListContent
import com.zailinn.c001apk.ui.screens.list.SimpleTopBar
import com.zailinn.c001apk.ui.screens.list.TabbedPages
import com.zailinn.c001apk.ui.screens.runAction
import com.zailinn.c001apk.ui.theme.TextSizes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val productSorts = listOf(
    "默认" to "ignoreEntityById=1",
    "最新" to "ignoreEntityById=1&listType=dateline_desc",
    "热度" to "listType=rank_score",
)

/** Topic (by tag) or product (by id) with its server-defined tabs. */
class TopicViewModel(val app: AppContainer, private val tag: String?, private val productId: String?) : ViewModel() {
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
            if (blocked.value) app.content.unblockTopicTitle(title) else app.content.blockTopic(title)
            blocked.value = !blocked.value
        }
    }

    /** Device-only follow of a topic: guests always, accounts when secret follow is enabled. */
    fun toggleLocalFollow() {
        val data = (topic.value as? LoadResult.Success)?.value?.takeIf { it.entityType == "topic" } ?: return
        val id = data.id ?: return
        viewModelScope.launch {
            try {
                val label = if (app.session.isLogin) "悄悄关注" else "本地关注"
                if (app.content.isLocalFollowTopic(id)) {
                    app.content.localUnfollowTopic(id)
                    app.toast("已取消$label，仅修改本机名单")
                } else {
                    app.content.localFollowTopic(id, data.title.orEmpty(), data.logo.orEmpty())
                    app.toast("已$label，仅保存在本机，不会同步到账号")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                app.toast("本地关注保存失败，请重试")
            }
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
    val localTopics by vm.app.content.localFollowTopics.collectAsStateWithLifecycle()
    val secretFollow = LocalAppSettings.current.secretFollow
    var menu by remember { mutableStateOf(false) }
    var composing by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(0) }
    // As in the official app: the details fold away while reading down and only open again at the
    // top of the list; once folded, the bar shows the topic's name and heat instead. Let go halfway,
    // it settles quickly to open or folded rather than staying cut off. The list takes the folding
    // before its pull-to-refresh, so pulling at the top opens the header before refreshing.
    val header = rememberTopAppBarState()
    val folding = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(header, snapAnimationSpec = tween(180, easing = FastOutSlowInEasing))
    val data = (state as? LoadResult.Success)?.value
    val isTopic = data?.entityType == "topic"
    val localFollow = isTopic && data != null && localTopics.any { it.id == data.id }
    // Follow on the account when signed in; otherwise, or when 悄悄关注 is on, the local list.
    val followsLocally = isTopic && (!session.isLogin || (secretFollow && !following))
    val followed = if (followsLocally) localFollow else following
    val toggleFollow = { if (followsLocally) vm.toggleLocalFollow() else vm.toggleFollow() }
    Scaffold(
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                navigationIcon = { BackButton() },
                title = {
                    if (data == null) Text(tag.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else if (header.collapsedFraction > 0.85f) Row(verticalAlignment = Alignment.CenterVertically) {
                        RoundedImage(data.logo, 28.dp)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(data.title.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            data.string("hot_num_txt")?.let { Text("${it}热度", fontSize = TextSizes.Meta, color = MaterialTheme.colorScheme.outline) }
                        }
                    }
                },
                actions = {
                if (data != null) {
                    if (header.collapsedFraction > 0.85f && !blocked) FollowButton(followed, toggleFollow)
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
                    IconButton(onClick = hapticClick { menu = true }) { Icon(Icons.Outlined.MoreVert, "更多") }
                    AppDropdownMenu(menu, { menu = false }) {
                        val shareUrl = "https://www.coolapk1s.com/" + if (isTopic) "t/${data.title}" else "product/${data.id}"
                        DropdownMenuItem({ Text("复制链接") }, { menu = false; navigator.copy(shareUrl) })
                        DropdownMenuItem({ Text("分享") }, { menu = false; navigator.share(shareUrl) })
                        if (!isTopic) {
                            productSorts.forEachIndexed { index, (label, _) ->
                                DropdownMenuItem({ Text("排序：$label" + if (index == sort) " ✓" else "") }, { menu = false; sort = index })
                            }
                        }
                        if (session.isLogin || !isTopic) {
                            DropdownMenuItem({ Text(if (following) "取消关注" else "关注") }, { menu = false; vm.toggleFollow() })
                        }
                        if (isTopic && (!session.isLogin || secretFollow || localFollow)) {
                            val label = if (session.isLogin) "悄悄关注" else "本地关注"
                            DropdownMenuItem({ Text(if (localFollow) "取消$label" else label) }, { menu = false; vm.toggleLocalFollow() })
                        }
                        DropdownMenuItem({ Text(if (blocked) "取消屏蔽" else "屏蔽") }, { menu = false; vm.toggleBlock() })
                    }
                }
                },
            )
        },
        floatingActionButton = {
            if (data != null && session.isLogin && !blocked) {
                FloatingActionButton(onClick = hapticClick { composing = true }) { Icon(Icons.Filled.Add, "发布") }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (data == null) {
                StatusBox(state, vm::load)
                return@Column
            }
            Box(
                Modifier.clipToBounds().layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    header.heightOffsetLimit = -placeable.height.toFloat()
                    val height = (placeable.height + header.heightOffset).roundToInt().coerceAtLeast(0)
                    layout(placeable.width, height) { placeable.place(0, height - placeable.height) }
                },
            ) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RoundedImage(data.logo, 56.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(data.title.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(
                                    data.string("hot_num_txt")?.let { "${it}热度" },
                                    data.string("commentnum_txt")?.let { "${it}讨论" },
                                    data.string("follow_num_txt")?.let { "${it}关注" },
                                ).joinToString("  "),
                                fontSize = TextSizes.Meta, color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        if (!blocked) FollowButton(followed, toggleFollow)
                    }
                    data.description?.takeIf { it.isNotEmpty() }?.let {
                        Text(htmlToPlain(it), Modifier.padding(top = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = TextSizes.Meta)
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
                CompositionLocalProvider(LocalProductConfigs provides data.list("configRows")) {
                    val page: @Composable (Int) -> Unit = { index ->
                        val tab = tabs[index]
                        if (!isTopic && tab.title == "讨论" && sort > 0) {
                            // Product discussions can be re-sorted from the menu.
                            val (label, query) = productSorts[sort]
                            DataListContent("/page?url=/product/feedList?type=feed&id=${data.id}&$query", label, key = "topic-${data.id}-$index-$sort", nestedScroll = folding.nestedScrollConnection)
                        } else {
                            DataListContent(tab.url.orEmpty(), tab.title.orEmpty(), key = "topic-${data.id}-$index", nestedScroll = folding.nestedScrollConnection)
                        }
                    }
                    // A lone tab (often just 讨论) needs no tab row.
                    if (tabs.size == 1) page(0)
                    else TabbedPages(tabs.map { it.title.orEmpty() }, initial = selected.coerceAtLeast(0), page = page)
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

/** The small 关注 / 已关注 button of the header and of the folded bar. */
@Composable
private fun FollowButton(followed: Boolean, onClick: () -> Unit) {
    val click = hapticClick(onClick)
    val padding = PaddingValues(horizontal = 12.dp)
    val modifier = Modifier.height(28.dp)
    val label = @Composable { text: String -> Text(text, style = MaterialTheme.typography.labelMedium) }
    if (followed) OutlinedButton(click, modifier, contentPadding = padding) { label("已关注") }
    else FilledTonalButton(click, modifier, contentPadding = padding) { label("关注") }
}
