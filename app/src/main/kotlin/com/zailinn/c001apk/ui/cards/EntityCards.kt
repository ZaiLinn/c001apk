package com.zailinn.c001apk.ui.cards

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.ui.common.ActionSheet
import com.zailinn.c001apk.ui.common.Avatar
import com.zailinn.c001apk.ui.common.ContentPress
import com.zailinn.c001apk.ui.common.HtmlText
import com.zailinn.c001apk.ui.common.LocalContentPress
import com.zailinn.c001apk.ui.common.ImageGrid
import com.zailinn.c001apk.ui.common.NetworkImage
import com.zailinn.c001apk.ui.common.RoundedImage
import com.zailinn.c001apk.ui.common.SheetAction
import com.zailinn.c001apk.ui.common.fromToday
import com.zailinn.c001apk.ui.common.htmlToCopy
import com.zailinn.c001apk.ui.common.htmlToPlain
import com.zailinn.c001apk.ui.common.rememberConfirm
import com.zailinn.c001apk.ui.nav.CopyRoute
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.nav.UserListRoute
import com.zailinn.c001apk.ui.nav.UserRoute
import com.zailinn.c001apk.ui.theme.TextSizes
import kotlinx.coroutines.delay

private fun Datum.anyUserInfo() = userInfo ?: obj("fUserInfo")

/** Apps, products, topics, users, contacts and recent visits share one row layout. */
@Composable
fun EntityCard(data: Datum, onSurface: Boolean = false) {
    val navigator = LocalNavigator.current
    val type = data.entityType
    val user = data.anyUserInfo()
    val (image, avatar) = when (type) {
        "apk", "product", "topic", "recentHistory" -> data.logo to (type == "recentHistory" && data.string("targetType") == "user")
        "user" -> data.userAvatar to true
        else -> user?.userAvatar to true
    }
    val title = when (type) {
        "apk", "product", "topic", "productBrand" -> data.title.orEmpty()
        "user" -> data.userInfo?.username ?: data.username.orEmpty()
        "recentHistory" -> "${data.string("target_type_title").orEmpty()}: ${data.title.orEmpty()}"
        else -> user?.username.orEmpty()
    }
    val stats = when (type) {
        "apk" -> listOf("${data.text("commentnum")}讨论", "${data.text("downCount")}下载")
        "product", "productBrand" -> listOf("${data.text("hot_num_txt")}热度", "${data.text("feed_comment_num_txt")}讨论")
        "topic" -> listOf("${data.text("hot_num_txt")}热度", "${data.text("commentnum_txt")}讨论")
        "user" -> listOf("${data.text("follow")}关注", "${data.text("fans")}粉丝", "${fromToday(data.long("logintime"))}活跃")
        "contacts" -> listOf("${user?.text("follow").orEmpty()}关注", "${user?.text("fans").orEmpty()}粉丝")
        else -> listOf(
            "${data.text("follow_num")}关注",
            if (data.string("targetType") == "user") "${data.text("fans_num")}粉丝" else "${data.text("comment_num")}讨论",
        )
    }
    Surface(
        color = if (onSurface) MaterialTheme.colorScheme.surface else cardColor(),
        shape = CardShape,
        onClick = {
            if (type == "contacts") (user?.uid)?.let { navigator.navigate(UserRoute(it)) }
            else navigator.openLink(data.url.orEmpty(), data.title)
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (avatar) Avatar(image, 40.dp) else RoundedImage(image, 40.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stats.joinToString("  "),
                    fontSize = TextSizes.Meta, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun CarouselCard(items: List<Datum>) {
    val navigator = LocalNavigator.current
    val shown = items
    if (shown.isEmpty()) return
    val pager = rememberPagerState { shown.size }
    LaunchedEffect(pager, shown.size) {
        while (shown.size > 1) {
            delay(4000)
            pager.animateScrollToPage((pager.currentPage + 1) % shown.size)
        }
    }
    // Same fixed 4.5:1 box the official client uses.
    Box(Modifier.fillMaxWidth().aspectRatio(BANNER_RATIO).clip(CardShape)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val item = shown[page]
            NetworkImage(item.pic, Modifier.fillMaxSize().clickable { navigator.openLink(item.url.orEmpty(), item.title) })
        }
        if (shown.size > 1) {
            Row(Modifier.align(Alignment.BottomEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(shown.size) {
                    Box(
                        Modifier.size(6.dp).background(
                            if (it == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            CircleShape,
                        ),
                    )
                }
            }
        }
    }
}

private const val BANNER_RATIO = 4.5f

/** Icon grid with five per row, paged when longer than two rows. */
@Composable
fun IconLinkGridCard(items: List<Datum>) {
    val navigator = LocalNavigator.current
    val pages = items.chunked(10)
    val pager = rememberPagerState { pages.size }
    Surface(color = cardColor(), shape = CardShape, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            HorizontalPager(pager) { index ->
                Column {
                    pages[index].chunked(5).forEach { row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEach { item ->
                                Column(
                                    Modifier.weight(1f).clickable { navigator.openLink(item.url.orEmpty(), item.title) }.padding(6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    RoundedImage(item.pic, 30.dp, 8.dp)
                                    Spacer(Modifier.height(4.dp))
                                    Text(item.title.orEmpty(), fontSize = TextSizes.Tag, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            if (pages.size > 1) {
                Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(pages.size) {
                        Box(
                            Modifier.size(if (it == pager.currentPage) 10.dp else 7.dp, 5.dp).background(
                                if (it == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(3.dp),
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun IconMiniScrollCard(items: List<Datum>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        items(items) { MiniChip(it.logo ?: it.pic, it.title.orEmpty(), it.url.orEmpty()) }
    }
}

@Composable
fun TitleCard(title: String, url: String) {
    val navigator = LocalNavigator.current
    Row(
        Modifier.fillMaxWidth().clip(CardShape).clickable(enabled = url.isNotEmpty()) { navigator.openLink(url, title) }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        if (url.isNotEmpty()) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun IconMiniGridCard(data: Datum) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        data.title?.takeIf { it.isNotEmpty() }?.let { TitleCard(it, data.url.orEmpty()) }
        data.entities.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pair.forEach { item ->
                    Box(Modifier.weight(1f)) { MiniChip(item.logo ?: item.pic, item.title.orEmpty(), item.url.orEmpty()) }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun ImageSquareScrollCard(items: List<Datum>) {
    val navigator = LocalNavigator.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items) { item ->
            Box(Modifier.size(90.dp).clip(CardShape).clickable { navigator.openLink(item.url.orEmpty(), item.title) }) {
                NetworkImage(item.pic, Modifier.fillMaxSize())
                Text(
                    item.title.orEmpty(),
                    Modifier.align(Alignment.Center).padding(4.dp),
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = TextSizes.Meta,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
fun IconScrollCard(data: Datum) {
    val navigator = LocalNavigator.current
    Column {
        data.title?.takeIf { it.isNotEmpty() }?.let { TitleCard(it, data.url.orEmpty()) }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(data.entities) { item ->
                Column(
                    Modifier.width(64.dp).clip(CardShape).clickable { navigator.openLink(item.url.orEmpty(), item.title) }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Avatar(item.userAvatar ?: item.logo ?: item.pic, 54.dp)
                    Spacer(Modifier.height(5.dp))
                    Text(item.username ?: item.title.orEmpty(), fontSize = TextSizes.Tag, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun ImageTextScrollCard(data: Datum) {
    val navigator = LocalNavigator.current
    Column {
        data.title?.takeIf { it.isNotEmpty() }?.let { TitleCard(it, data.url.orEmpty()) }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(data.entities) { item ->
                Surface(color = cardColor(), shape = CardShape, onClick = { navigator.openLink(item.url.orEmpty(), item.title) }) {
                    Column(Modifier.width(220.dp)) {
                        NetworkImage(item.pic, Modifier.fillMaxWidth().aspectRatio(2.22f))
                        Text("${item.title.orEmpty()}\n", Modifier.padding(8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = TextSizes.Nested)
                    }
                }
            }
        }
    }
}

@Composable
fun TextCard(text: String, end: Boolean = false) {
    Box(Modifier.fillMaxWidth().height(if (end) 80.dp else 48.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
    }
}

/** A collection; on the account's own list a long press opens its menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectionCard(data: Datum, actions: ItemActions = ItemActions()) {
    val navigator = LocalNavigator.current
    Surface(
        color = cardColor(), shape = CardShape,
        modifier = Modifier.fillMaxWidth().clip(CardShape).combinedClickable(
            onClick = { data.id?.let { navigator.navigate(UserListRoute("COLLECTION_ITEM", uid = it, title = data.title)) } },
            onLongClick = actions.onCollectionMenu?.let { { it(data) } },
        ),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            NetworkImage(data.string("cover_pic"), Modifier.size(53.dp, 40.dp).clip(RoundedCornerShape(8.dp)))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(data.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(data.text("is_open_title"), "${data.text("follow_num")}人关注", "${data.text("item_num")}个内容")
                        .filter { it.isNotEmpty() }.joinToString("  "),
                    fontSize = TextSizes.Meta, color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * The link a notification is about. Notes usually start with the sender's
 * name (`/u/…`, e.g. 酷安小秘书) and then link the feed or reply concerned, so
 * content links win over user links; a user link is the fallback.
 */
internal fun noticeLink(note: String): String? {
    val links = org.jsoup.Jsoup.parseBodyFragment(note).select("a[href]").map { it.attr("href").trim() }
        .filter { it.isNotEmpty() && !it.startsWith("javascript:", ignoreCase = true) }
    fun isUser(link: String) = Regex("^(https?://(www\\.)?coolapk1?s?\\.com)?/u/", RegexOption.IGNORE_CASE).containsMatchIn(link)
    return links.firstOrNull { !isUser(it) } ?: links.firstOrNull()
}

/** Mentions, comments and follows in the notification list. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotificationCard(data: Datum, actions: ItemActions) {
    val navigator = LocalNavigator.current
    var menu by remember { mutableStateOf(false) }
    val note = data.string("note").orEmpty()
    val fromUid = data.string("fromuid") ?: data.uid
    val press = remember { ContentPress() }
    Surface(
        color = cardColor(), shape = CardShape,
        modifier = Modifier.fillMaxWidth().clip(CardShape).combinedClickable(
            onClick = {
                if (data.string("type") == "contacts_follow") {
                    navigator.openUser(fromUid)
                } else if (!data.url.isNullOrEmpty()) {
                    navigator.openLink(data.url.orEmpty())
                } else {
                    noticeLink(note)?.let { navigator.openLink(it) }
                }
            },
            onLongClick = { if (!press.active) navigator.navigate(CopyRoute(htmlToCopy(note))) },
        ),
    ) {
        CompositionLocalProvider(LocalContentPress provides press) {
            Box {
                Row(Modifier.padding(10.dp)) {
                    Avatar(data.string("fromUserAvatar"), 36.dp) { navigator.openUser(fromUid) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f).padding(end = 24.dp)) {
                        HtmlText(data.string("fromusername").orEmpty(), fontSize = TextSizes.Name)
                        HtmlText(note, fontSize = TextSizes.Nested, picArr = data.picArr, media = data.mediaImages)
                        if (data.picArr.isNotEmpty()) ImageGrid(data.picArr, Modifier.padding(top = 6.dp), media = data.mediaImages)
                        Text(fromToday(data.dateline), fontSize = TextSizes.Meta, color = MaterialTheme.colorScheme.outline)
                    }
                }
                IconButton(onClick = { menu = true }, Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Outlined.KeyboardArrowDown, "更多", tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
    val confirmDelete = rememberConfirm("删除这条通知？", "删除后无法恢复。", "删除")
    if (menu) {
        ActionSheet(
            buildList {
                add(SheetAction(Icons.Outlined.ContentCopy, "复制") { navigator.navigate(CopyRoute(htmlToCopy(note))) })
                if (actions.onDelete != null) add(SheetAction(Icons.Outlined.Delete, "删除", danger = true) { confirmDelete { actions.onDelete.invoke(data, null) } })
                fromUid?.let { uid -> add(SheetAction(Icons.Outlined.Block, "屏蔽用户") { actions.onBlock?.invoke(uid, null) }) }
                fromUid?.let { uid -> add(SheetAction(Icons.Outlined.ErrorOutline, "举报") { navigator.report(uid, "user") }) }
            },
            onDismiss = { menu = false },
        )
    }
}

/** A private conversation in the message list; long press to pin or delete it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageCard(data: Datum, actions: ItemActions) {
    val navigator = LocalNavigator.current
    Surface(
        color = cardColor(), shape = CardShape,
        modifier = Modifier.fillMaxWidth().clip(CardShape).combinedClickable(
            onClick = { actions.onOpenMessage?.invoke(data) },
            onLongClick = actions.onMessageMenu?.let { { it(data) } },
        ),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(data.string("messageUserAvatar"), 40.dp) { navigator.openUser(data.string("messageUid")) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(data.string("messageUsername").orEmpty(), Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (data.int("isTop") == 1) {
                        Spacer(Modifier.width(6.dp))
                        Text("置顶", fontSize = TextSizes.Tag, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(fromToday(data.dateline), fontSize = TextSizes.Meta, color = MaterialTheme.colorScheme.outline)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        htmlToPlain(data.message.orEmpty()), Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = TextSizes.Meta, color = MaterialTheme.colorScheme.outline,
                    )
                    val unread = data.int("unreadNum") ?: 0
                    if (unread > 0) Badge { Text("$unread") }
                }
            }
        }
    }
}
