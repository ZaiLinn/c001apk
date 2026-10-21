package com.zailinn.c001apk.ui.cards

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.ui.LocalSession
import com.zailinn.c001apk.ui.common.ActionSheet
import com.zailinn.c001apk.ui.common.Avatar
import com.zailinn.c001apk.ui.common.ContentPress
import com.zailinn.c001apk.ui.common.HtmlText
import com.zailinn.c001apk.ui.common.IconText
import com.zailinn.c001apk.ui.common.ImageGrid
import com.zailinn.c001apk.ui.common.LocalContentPress
import com.zailinn.c001apk.ui.common.RoundedImage
import com.zailinn.c001apk.ui.common.SheetAction
import com.zailinn.c001apk.ui.common.formatCount
import com.zailinn.c001apk.ui.common.fromToday
import com.zailinn.c001apk.ui.common.htmlToCopy
import com.zailinn.c001apk.ui.common.htmlToPlain
import com.zailinn.c001apk.ui.nav.CopyRoute
import com.zailinn.c001apk.ui.nav.FeedRoute
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.nav.UserRoute

val CardShape = RoundedCornerShape(12.dp)

@Composable
fun cardColor() = MaterialTheme.colorScheme.surfaceContainerLow

/**
 * A feed in a list, or the header of the feed detail page when [detail] is set.
 * Without [footer] the location, counts and topic chips are left for the caller
 * to place with [FeedFooter], as articles do below their body.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FeedCard(data: Datum, actions: ItemActions = ItemActions(), detail: Boolean = false, footer: Boolean = true) {
    val navigator = LocalNavigator.current
    val open = { data.id?.let { navigator.navigate(FeedRoute(it)) } ?: Unit }
    val pad = if (detail) 16.dp else 10.dp
    val press = remember { ContentPress() }
    Surface(
        color = if (detail) Color.Transparent else cardColor(),
        shape = if (detail) RoundedCornerShape(0.dp) else CardShape,
        modifier = Modifier.fillMaxWidth().clip(if (detail) RoundedCornerShape(0.dp) else CardShape)
            .combinedClickable(
                enabled = true,
                onClick = { if (!detail) open() },
                onLongClick = { if (!press.active) navigator.navigate(CopyRoute(htmlToCopy(data.message.orEmpty()))) },
            ),
    ) {
        CompositionLocalProvider(LocalContentPress provides press) {
            Column(Modifier.padding(bottom = if (!footer) 0.dp else if (detail) 12.dp else 10.dp)) {
                FeedHeader(data, detail, actions)
                data.messageTitle?.takeIf { it.isNotEmpty() }?.let {
                    HtmlText(it, Modifier.padding(start = pad, end = pad, top = 5.dp), fontSize = 16.sp, bold = true, selectable = detail)
                }
                if (!data.message.isNullOrEmpty()) {
                    HtmlText(
                        data.message.orEmpty(),
                        Modifier.padding(start = pad, end = pad, top = 5.dp),
                        fontSize = 16.sp,
                        maxLines = if (detail) Int.MAX_VALUE else 8,
                        onClick = if (detail) null else open,
                        selectable = detail,
                    )
                }
                if (data.picArr.isNotEmpty()) ImageGrid(data.picArr, Modifier.padding(start = pad, end = pad, top = 10.dp))
                if (!data.string("forwardSourceType").isNullOrEmpty()) ForwardSource(data.obj("forwardSourceFeed"), pad, detail)
                data.string("extra_url")?.takeIf { it.isNotEmpty() }?.let { ExtraLink(data, it, pad, detail) }
                data.replyRows.firstOrNull()?.takeIf { !detail }?.let { HotReply(it, open) }
                if (footer) FeedFooter(data, actions, detail)
            }
        }
    }
}

/** Location or time, reply and like counts, then topic and product chips. */
@Composable
fun FeedFooter(data: Datum, actions: ItemActions, detail: Boolean = true) {
    val pad = if (detail) 16.dp else 10.dp
    // The count buttons and chips sit in a dense row; the 48dp touch-target
    // padding Material adds around them left tall gaps above and below.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        FeedBottom(data, detail, pad, actions)
        RelationRows(data, pad, detail)
    }
}

@Composable
private fun FeedHeader(data: Datum, detail: Boolean, actions: ItemActions) {
    val navigator = LocalNavigator.current
    val session = LocalSession.current
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).padding(start = if (detail) 16.dp else 10.dp, top = if (detail) 12.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(data.userAvatar ?: data.userInfo?.userAvatar, 36.dp) { navigator.openUser(data.uid, data.isAnonymous) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(data.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val info = if (detail) fromToday(data.dateline) else htmlToPlain(data.string("infoHtml").orEmpty())
                    if (info.isNotEmpty()) {
                        Text(info, color = MaterialTheme.colorScheme.outline, fontSize = 13.sp, maxLines = 1)
                        Spacer(Modifier.width(10.dp))
                    }
                    data.string("device_title")?.takeIf { it.isNotEmpty() }?.let {
                        IconText(Icons.Outlined.Smartphone, htmlToPlain(it), Modifier.weight(1f, fill = false))
                    }
                }
            }
        }
        if (!detail && data.int("isStickTop") == 1) {
            Text(
                "置顶",
                Modifier.border(1.25.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                color = MaterialTheme.colorScheme.primary, fontSize = 12.sp,
            )
        }
        if (!detail) {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Outlined.KeyboardArrowDown, "更多", tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
    if (menu) {
        ActionSheet(
            buildList {
                add(SheetAction(Icons.Outlined.ContentCopy, "复制链接") { navigator.copy("https://www.coolapk.com/feed/${data.id}") })
                data.uid?.let { uid -> add(SheetAction(Icons.Outlined.Block, "屏蔽作者") { actions.onBlock?.invoke(uid, null) }) }
                add(SheetAction(Icons.Outlined.ErrorOutline, "举报") { navigator.report(data.id.orEmpty(), "feed") })
                if (actions.onDelete != null && (actions.removable || (session.isLogin && data.uid == session.uid))) {
                    add(SheetAction(Icons.Outlined.Delete, "删除", danger = true) { actions.onDelete.invoke(data, null) })
                }
            },
            onDismiss = { menu = false },
        )
    }
}

@Composable
private fun ForwardSource(source: Datum?, pad: androidx.compose.ui.unit.Dp, detail: Boolean) {
    val navigator = LocalNavigator.current
    Surface(
        color = if (detail) cardColor() else MaterialTheme.colorScheme.surface,
        shape = CardShape,
        onClick = { source?.id?.let { navigator.navigate(FeedRoute(it)) } },
        modifier = Modifier.fillMaxWidth().padding(start = pad, end = pad, top = 10.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            if (source == null) {
                Text("动态已被删除", color = MaterialTheme.colorScheme.outline, maxLines = 1)
            } else {
                val user = """<a href="/u/${source.uid}">@${source.username.orEmpty()}</a>: """
                val title = source.messageTitle
                if (!title.isNullOrEmpty()) {
                    HtmlText(user + title)
                    HtmlText(source.message.orEmpty(), maxLines = 6)
                } else {
                    HtmlText(user + source.message.orEmpty(), maxLines = 8)
                }
                if (source.picArr.isNotEmpty()) ImageGrid(source.picArr, Modifier.padding(top = 10.dp))
            }
        }
    }
}

@Composable
private fun ExtraLink(data: Datum, url: String, pad: androidx.compose.ui.unit.Dp, detail: Boolean) {
    val navigator = LocalNavigator.current
    Surface(
        color = if (detail) cardColor() else MaterialTheme.colorScheme.surface,
        shape = CardShape,
        onClick = { navigator.openLink(url, data.string("extra_title")) },
        modifier = Modifier.fillMaxWidth().padding(start = pad, end = pad, top = 10.dp),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val pic = data.string("extra_pic")
            if (!pic.isNullOrEmpty()) {
                RoundedImage(pic, 40.dp)
            } else {
                Box(
                    Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Link, null, tint = MaterialTheme.colorScheme.onPrimary) }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(data.string("extra_title").orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun HotReply(reply: Datum, open: () -> Unit) {
    val pics = reply.picArr
    val picLink = if (pics.isEmpty()) "" else """ <a href="${reply.pic}">查看图片(${pics.size})</a>"""
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = CardShape,
        onClick = open,
        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 10.dp),
    ) {
        HtmlText(
            """<a href="/u/${reply.uid}">${reply.displayName}</a>: ${reply.message.orEmpty()}$picLink""",
            Modifier.padding(10.dp),
            fontSize = 14.sp,
            maxLines = 4,
            picArr = pics,
            onShowReplies = open,
        )
    }
}

@Composable
private fun FeedBottom(data: Datum, detail: Boolean, pad: androidx.compose.ui.unit.Dp, actions: ItemActions) {
    Row(Modifier.padding(start = pad, end = pad, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        val left = if (detail) data.string("ip_location")?.takeIf { it.isNotEmpty() }?.let { "发布于 $it" }.orEmpty() else fromToday(data.dateline)
        Text(left, Modifier.weight(1f), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        data.string("replynum")?.let { CountButton(Icons.AutoMirrored.Outlined.Message, it) }
        data.string("likenum")?.let { LikeButton(data, actions) }
    }
}

@Composable
fun CountButton(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, active: Boolean = false, onClick: (() -> Unit)? = null) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    TextButton(
        onClick = { onClick?.invoke() },
        modifier = Modifier.height(36.dp),
        contentPadding = PaddingValues(horizontal = 10.dp),
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = color)
        Spacer(Modifier.width(4.dp))
        Text(formatCount(value), color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun LikeButton(data: Datum, actions: ItemActions) {
    val session = LocalSession.current
    val navigator = LocalNavigator.current
    CountButton(
        if (data.isLiked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
        data.string("likenum").orEmpty(),
        active = data.isLiked,
    ) {
        when {
            !session.isLogin -> navigator.toast("登录后才能点赞")
            data.has("userAction") || actions.onLike != null -> actions.onLike?.invoke(data)
        }
    }
}

@Composable
private fun RelationRows(data: Datum, pad: androidx.compose.ui.unit.Dp, detail: Boolean) {
    val rows = listOfNotNull(data.targetRow) + data.relationRows
    if (rows.isEmpty()) return
    FlowRow(
        Modifier.padding(start = pad, end = pad, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        rows.forEach { MiniChip(it.logo, it.title.orEmpty(), it.url.orEmpty(), onSurface = !detail) }
    }
}

/** Small logo + title chip used for topics, products and apps. */
@Composable
fun MiniChip(logo: String?, title: String, url: String, onSurface: Boolean = false) {
    val navigator = LocalNavigator.current
    Surface(
        color = if (onSurface) MaterialTheme.colorScheme.surface else cardColor(),
        shape = RoundedCornerShape(50),
        onClick = { navigator.openLink(url, title) },
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!logo.isNullOrEmpty()) {
                RoundedImage(logo, 18.dp, 4.dp)
                Spacer(Modifier.width(4.dp))
            }
            Text(title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * A reply in the feed detail list. [nested] renders replies inside the
 * reply-to-reply sheet, where [floorUid] marks the floor author.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReplyCard(
    data: Datum,
    actions: ItemActions,
    nested: Boolean = false,
    isTop: Boolean = false,
    floorUid: String? = null,
) {
    val navigator = LocalNavigator.current
    val isFeedReply = data.string("fetchType") == "feed_reply"
    var menu by remember { mutableStateOf(false) }
    val feedUid = data.string("feedUid")
    val press = remember { ContentPress() }
    val clickable = Modifier.combinedClickable(
        onClick = {
            if (isFeedReply && actions.onReply != null) actions.onReply.invoke(data, null) else navigator.openLink(data.url.orEmpty())
        },
        onLongClick = { if (!press.active) menu = true },
    )
    Surface(
        color = if (!isFeedReply || (nested && !isTop)) cardColor() else Color.Transparent,
        shape = if (!isFeedReply) CardShape else RoundedCornerShape(0.dp),
        modifier = Modifier.fillMaxWidth().clip(if (!isFeedReply) CardShape else RoundedCornerShape(0.dp)).then(clickable),
    ) {
        CompositionLocalProvider(LocalContentPress provides press) {
            Box {
                Row(Modifier.padding(horizontal = if (isFeedReply) 16.dp else 10.dp, vertical = if (isFeedReply) 12.dp else 10.dp)) {
                    Avatar(data.userAvatar ?: data.userInfo?.userAvatar, 30.dp) { navigator.openUser(data.uid, data.isAnonymous) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        HtmlText(replyTitle(data, nested, isTop, feedUid, floorUid), Modifier.padding(end = 28.dp))
                        HtmlText(data.message.orEmpty(), picArr = data.picArr, selectable = true)
                        if (data.picArr.isNotEmpty()) ImageGrid(data.picArr, Modifier.padding(top = 10.dp))
                        data.obj("feed")?.takeIf { !isFeedReply && !nested }?.let { SourceFeed(it) }
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(fromToday(data.dateline), Modifier.weight(1f), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyMedium)
                            CountButton(Icons.AutoMirrored.Outlined.Message, data.string("replynum") ?: "0")
                            LikeButton(data, actions)
                        }
                        val more = data.int("replyRowsMore") ?: 0
                        if (!nested && (data.replyRows.isNotEmpty() || more != 0)) NestedReplies(data, actions, more)
                    }
                }
                IconButton(onClick = { menu = true }, Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Outlined.KeyboardArrowDown, "更多", tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
    if (menu) ReplyMenu(data, null, actions) { menu = false }
}

private fun tag(uid: String?, feedUid: String?, floorUid: String?) = when {
    uid != null && uid == feedUid -> " [楼主] "
    uid != null && uid == floorUid -> " [层主] "
    else -> ""
}

private fun replyTitle(data: Datum, nested: Boolean, isTop: Boolean, feedUid: String?, floorUid: String?): String {
    val name = data.displayName
    if (!nested) {
        return name + (if (data.uid == feedUid) " [楼主]" else "") + (if (isTop) " [置顶]" else "")
    }
    val replyTag = tag(data.uid, feedUid, if (isTop) null else floorUid)
    val self = """<a href="/u/${data.uid}">$name$replyTag</a>"""
    val ruid = data.string("ruid")
    if (isTop || ruid.isNullOrEmpty() || ruid == "0") return self
    val rname = data.string("rusername").orEmpty()
    return """${self}回复<a href="/u/$ruid">$rname${tag(ruid, feedUid, floorUid)}</a>"""
}

@Composable
private fun SourceFeed(feed: Datum) {
    val navigator = LocalNavigator.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = CardShape,
        onClick = { navigator.openLink(feed.url.orEmpty()) },
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!feed.pic.isNullOrEmpty()) {
                RoundedImage(feed.pic, 40.dp)
                Spacer(Modifier.width(10.dp))
            }
            Column {
                Text("@${feed.username.orEmpty()}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(htmlToPlain(feed.message.orEmpty()), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NestedReplies(parent: Datum, actions: ItemActions, more: Int) {
    var menuFor by remember { mutableStateOf<Datum?>(null) }
    val feedUid = parent.string("feedUid")
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(CardShape).background(cardColor()),
    ) {
        parent.replyRows.forEach { reply ->
            val ruid = reply.string("ruid")
            val rUser = if (ruid == parent.uid) "" else """<a href="/u/$ruid">${reply.string("rusername").orEmpty()}${tag(ruid, feedUid, parent.uid)}</a>"""
            val pic = if (reply.picArr.isEmpty()) "" else """ <a href="${reply.pic}">查看图片(${reply.picArr.size})</a>"""
            val press = remember { ContentPress() }
            CompositionLocalProvider(LocalContentPress provides press) {
                HtmlText(
                    """<a href="/u/${reply.uid}">${reply.displayName}${tag(reply.uid, feedUid, parent.uid)}</a>回复$rUser: ${reply.message.orEmpty()}$pic""",
                    Modifier.fillMaxWidth()
                        .combinedClickable(onClick = { actions.onReply?.invoke(reply, parent) }, onLongClick = { if (!press.active) menuFor = reply })
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    fontSize = 14.sp,
                    picArr = reply.picArr,
                    onShowReplies = { actions.onShowReplies?.invoke(parent) },
                )
            }
        }
        if (more > 0) {
            Text(
                "查看更多回复(${parent.string("replynum").orEmpty()})",
                Modifier.fillMaxWidth()
                    .combinedClickable(onClick = { actions.onShowReplies?.invoke(parent) })
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp,
            )
        }
    }
    menuFor?.let { reply -> ReplyMenu(reply, parent, actions) { menuFor = null } }
}

@Composable
fun ReplyMenu(reply: Datum, parent: Datum?, actions: ItemActions, onDismiss: () -> Unit) {
    val navigator = LocalNavigator.current
    val session = LocalSession.current
    ActionSheet(
        buildList {
            if (actions.onReply != null) add(SheetAction(Icons.AutoMirrored.Outlined.Reply, "回复") { actions.onReply.invoke(reply, parent) })
            add(SheetAction(Icons.Outlined.ContentCopy, "复制") { navigator.navigate(CopyRoute(htmlToCopy(reply.message.orEmpty()))) })
            if (parent == null && actions.onShowReplies != null && (reply.int("replynum") ?: 0) > 0) {
                add(SheetAction(Icons.AutoMirrored.Outlined.Message, "查看回复") { actions.onShowReplies.invoke(reply) })
            }
            reply.uid?.let { uid -> add(SheetAction(Icons.Outlined.Block, "屏蔽用户") { actions.onBlock?.invoke(uid, parent) }) }
            add(SheetAction(Icons.Outlined.ErrorOutline, "举报") { navigator.report(reply.id.orEmpty(), "feed_reply") })
            if (actions.onDelete != null && session.isLogin && reply.uid == session.uid) {
                add(SheetAction(Icons.Outlined.Delete, "删除", danger = true) { actions.onDelete.invoke(reply, parent) })
            }
        },
        onDismiss,
    )
}

/** "Someone liked your feed" notification. */
@Composable
fun LikeCard(data: Datum) {
    val navigator = LocalNavigator.current
    val liker = data.obj("likeUserInfo")
    Surface(color = cardColor(), shape = CardShape, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(liker?.userAvatar, 30.dp) { liker?.uid?.let { navigator.navigate(UserRoute(it)) } }
                Spacer(Modifier.width(10.dp))
                Text(liker?.username.orEmpty(), Modifier.weight(1f))
                Text(fromToday(data.long("likeTime")), color = MaterialTheme.colorScheme.outline, fontSize = 13.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text("赞了你的${data.string("infoHtml").orEmpty()}")
            Spacer(Modifier.height(10.dp))
            Surface(color = MaterialTheme.colorScheme.surface, shape = CardShape, onClick = { navigator.openLink(data.url.orEmpty()) }) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!data.pic.isNullOrEmpty()) {
                        RoundedImage(data.pic, 30.dp)
                        Spacer(Modifier.width(10.dp))
                    }
                    Column {
                        Text("@${data.username.orEmpty()}", maxLines = 1)
                        Spacer(Modifier.height(5.dp))
                        HtmlText(data.message.orEmpty(), fontSize = 13.sp, maxLines = 3)
                    }
                }
            }
        }
    }
}
