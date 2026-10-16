package com.zailinn.c001apk.ui.screens.feed

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.FeedArticle
import com.zailinn.c001apk.core.model.LoadResult
import com.zailinn.c001apk.core.net.ReplySource
import com.zailinn.c001apk.ui.LocalSession
import com.zailinn.c001apk.ui.cards.CardShape
import com.zailinn.c001apk.ui.cards.FeedCard
import com.zailinn.c001apk.ui.cards.FeedFooter
import com.zailinn.c001apk.ui.cards.ItemActions
import com.zailinn.c001apk.ui.cards.ReplyCard
import com.zailinn.c001apk.ui.cards.cardColor
import com.zailinn.c001apk.ui.common.HtmlText
import com.zailinn.c001apk.ui.common.ImageGrid
import com.zailinn.c001apk.ui.common.PagedListView
import com.zailinn.c001apk.ui.common.StatusBox
import com.zailinn.c001apk.ui.common.htmlToPlain
import com.zailinn.c001apk.ui.nav.CopyRoute
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.screens.appViewModel
import com.zailinn.c001apk.ui.screens.compose.ComposeTarget
import com.zailinn.c001apk.ui.screens.compose.ComposerSheet
import com.zailinn.c001apk.ui.screens.list.SimpleTopBar

private data class ReplyRequest(val target: Datum?, val parent: Datum?)

@Composable
fun FeedScreen(id: String) {
    val navigator = LocalNavigator.current
    val session = LocalSession.current
    val vm = appViewModel(key = "feed-$id") { FeedViewModel(it, id) }
    val feed by vm.feed.collectAsStateWithLifecycle()
    val articles by vm.articles.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val repliesSource by vm.repliesSource.collectAsStateWithLifecycle()
    val repliesNotice by vm.repliesNotice.collectAsStateWithLifecycle()
    val favorite by vm.favorite.collectAsStateWithLifecycle()
    var replying by remember { mutableStateOf<ReplyRequest?>(null) }
    var thread by remember { mutableStateOf<Datum?>(null) }
    var menu by remember { mutableStateOf(false) }
    var showNotice by remember { mutableStateOf(false) }
    val data = (feed as? LoadResult.Success)?.value

    val reply: (Datum?, Datum?) -> Unit = { target, parent ->
        if (session.isLogin) replying = ReplyRequest(target, parent) else navigator.toast("登录后才能回复")
    }
    val actions = vm.actions.copy(onReply = { target, parent -> reply(target, parent) }, onShowReplies = { thread = it })

    Scaffold(
        topBar = {
            SimpleTopBar(data?.string("feedTypeName") ?: "动态") {
                if (notice.isNotEmpty()) IconButton(onClick = { showNotice = true }) { Icon(Icons.Outlined.Info, "账号详情") }
                if (data != null) {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "更多") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("复制链接") }, { menu = false; navigator.copy("https://www.coolapk.com/feed/$id") },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) })
                        DropdownMenuItem({ Text("分享") }, { menu = false; navigator.share("https://www.coolapk1s.com/feed/$id") },
                            leadingIcon = { Icon(Icons.Outlined.Share, null) })
                        DropdownMenuItem({ Text(if (favorite) "取消收藏" else "收藏") }, { menu = false; vm.toggleFavorite() },
                            leadingIcon = { Icon(if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder, null) })
                        DropdownMenuItem({ Text("屏蔽作者") }, { menu = false; vm.blockAuthor() },
                            leadingIcon = { Icon(Icons.Outlined.Block, null) })
                        DropdownMenuItem({ Text("举报") }, { menu = false; navigator.report(id, "feed") },
                            leadingIcon = { Icon(Icons.Outlined.ErrorOutline, null) })
                    }
                }
            }
        },
        floatingActionButton = {
            if (data != null && session.isLogin) {
                FloatingActionButton(onClick = { reply(null, null) }) { Icon(Icons.AutoMirrored.Outlined.Reply, "回复") }
            }
        },
    ) { padding ->
        if (data == null) {
            StatusBox(feed, onRetry = { vm.loadDetail(verify = true) }, Modifier.padding(padding))
            return@Scaffold
        }
        PagedListView(
            vm.list,
            Modifier.padding(padding),
            contentPadding = PaddingValues(bottom = 88.dp),
            spacing = 0,
            header = {
                if (articles.isEmpty()) {
                    item(key = "feed") { FeedCard(data, vm.actions, detail = true) }
                } else {
                    item(key = "article") { ArticleBody(data, articles, vm.actions) }
                }
                item(key = "sort") {
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("共 ${data.string("replynum") ?: "0"} 回复", Modifier.weight(1f))
                        SingleChoiceSegmentedButtonRow {
                            ReplySort.entries.forEachIndexed { index, item ->
                                SegmentedButton(
                                    selected = sort == item,
                                    // While replies come from the hot/web fallback the reply API's
                                    // sorting is unavailable; disable it instead of silently ignoring taps.
                                    enabled = repliesSource == ReplySource.Api,
                                    onClick = { vm.setSort(item) },
                                    shape = SegmentedButtonDefaults.itemShape(index, ReplySort.entries.size),
                                    icon = {},
                                ) { Text(item.label, fontSize = 12.sp) }
                            }
                        }
                    }
                    if (repliesSource != ReplySource.Api) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                repliesNotice.ifEmpty {
                                    if (repliesSource == ReplySource.Hot) "评论接口需要验证，以下是热门评论，排序暂不可用" else "评论接口需要验证，以下是网页版的热门评论，排序暂不可用"
                                },
                                Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.outline,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = vm::loadAllReplies) { Text("验证后查看全部") }
                        }
                    }
                }
            },
        ) { index, item ->
            Column {
                ReplyCard(item, actions, isTop = index == 0 && vm.topReply != null && sort == ReplySort.Default)
                HorizontalDivider()
            }
        }
    }

    replying?.let { request ->
        val target = request.target
        ComposerSheet(
            target = if (target == null) ComposeTarget.Reply(id, "feed", data?.displayName)
            else ComposeTarget.Reply(target.id.orEmpty(), "reply", target.displayName),
            onDismiss = { replying = null },
            onReplied = { vm.addReply(it, target, request.parent) },
        )
    }
    thread?.let { origin -> ReplyThreadSheet(origin, onDismiss = { thread = null }) }
    if (showNotice) {
        AlertDialog(
            onDismissRequest = { showNotice = false },
            title = { Text("账号信息暂不可用") },
            text = { Text(notice) },
            confirmButton = { TextButton(onClick = { showNotice = false; vm.loadDetail(verify = true) }) { Text("重试账号详情") } },
            dismissButton = { TextButton(onClick = { showNotice = false }) { Text("关闭") } },
        )
    }
}

/** Structured article: header, title, text and image blocks, then the like row. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArticleBody(data: Datum, blocks: List<FeedArticle>, actions: ItemActions) {
    val navigator = LocalNavigator.current
    val images = blocks.filter { it.type == "image" }.mapNotNull { it.url }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The article's own title block repeats message_title, so the header keeps only the author line.
        val header = data.with("message", "").withList("picArr", emptyList())
            .let { if (blocks.any { it.type == "title" }) it.with("message_title", "") else it }
        FeedCard(header, actions, detail = true, footer = false)
        blocks.forEach { block ->
            when (block.type) {
                "title" -> HtmlText(block.title.orEmpty(), Modifier.padding(horizontal = 16.dp), fontSize = 17.sp, bold = true)
                "text" -> HtmlText(
                    block.message.orEmpty(),
                    Modifier.padding(horizontal = 16.dp)
                        .combinedClickable(onClick = {}, onLongClick = { navigator.navigate(CopyRoute(htmlToPlain(block.message.orEmpty()))) }),
                    fontSize = 16.sp,
                )
                "image" -> Column(Modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val url = block.url.orEmpty()
                    ImageGridWithIndex(url, images)
                    block.description?.takeIf { it.isNotEmpty() }?.let {
                        Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
                    }
                }
                "shareUrl" -> Surface(
                    color = cardColor(), shape = CardShape,
                    onClick = { navigator.openLink(block.url.orEmpty()) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) { Text(block.title.orEmpty(), Modifier.padding(10.dp)) }
            }
        }
        Column(Modifier.padding(bottom = 12.dp)) { FeedFooter(data, actions) }
    }
}

@Composable
private fun ImageGridWithIndex(url: String, all: List<String>) = ImageGrid(listOf(url), article = true, gallery = all)

@Composable
private fun ReplyThreadSheet(origin: Datum, onDismiss: () -> Unit) {
    val session = LocalSession.current
    val navigator = LocalNavigator.current
    val vm = appViewModel(key = "thread-${origin.id}") { ReplyThreadViewModel(it, origin) }
    var replying by remember { mutableStateOf<Datum?>(null) }
    val actions = vm.actions.copy(
        onReply = { target, _ -> if (session.isLogin) replying = target else navigator.toast("登录后才能回复") },
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "回复（${origin.string("replynum").orEmpty()}）",
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        PagedListView(vm.list, Modifier.navigationBarsPadding(), contentPadding = PaddingValues(0.dp), spacing = 0) { index, item ->
            Column {
                ReplyCard(item, actions, nested = true, isTop = index == 0, floorUid = origin.uid)
                HorizontalDivider()
            }
        }
    }
    replying?.let { target ->
        ComposerSheet(
            target = ComposeTarget.Reply(target.id.orEmpty(), "reply", target.displayName),
            onDismiss = { replying = null },
            onReplied = { vm.addReply(it, target) },
        )
    }
}
