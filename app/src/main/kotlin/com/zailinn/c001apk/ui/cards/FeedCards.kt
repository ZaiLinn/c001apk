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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.ShoppingBag
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
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
import com.zailinn.c001apk.ui.common.rememberConfirm
import com.zailinn.c001apk.ui.common.hapticClick
import com.zailinn.c001apk.ui.nav.CopyRoute
import com.zailinn.c001apk.ui.nav.FeedRoute
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.nav.UserRoute
import com.zailinn.c001apk.ui.theme.TextSizes

val CardShape = RoundedCornerShape(12.dp)

@Composable
fun cardColor() = MaterialTheme.colorScheme.surfaceContainerLow

/**
 * A feed in a list, or the header of the feed detail page when [detail] is set.
 * Without [footer] the location, counts and topic chips are left for the caller
 * to place with [FeedFooter], as articles do below their body. With [authorOnly]
 * it is just the author row above an article: a tap opens the profile and a
 * long press does nothing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FeedCard(data: Datum, actions: ItemActions = ItemActions(), detail: Boolean = false, footer: Boolean = true, authorOnly: Boolean = false) {
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
                onClick = { if (authorOnly) navigator.openUser(data.uid, data.isAnonymous) else if (!detail) open() },
                // The author row above an article has no text to copy. A no-op rather
                // than null: without a long press handler the release counts as a tap.
                onLongClick = { if (!authorOnly && !press.active) navigator.navigate(CopyRoute(htmlToCopy(data.message.orEmpty()))) },
            ),
    ) {
        CompositionLocalProvider(LocalContentPress provides press) {
            Column(Modifier.padding(bottom = if (!footer) 0.dp else if (detail) 12.dp else 10.dp)) {
                FeedHeader(data, detail, actions)
                data.messageTitle?.takeIf { it.isNotEmpty() }?.let {
                    HtmlText(it, Modifier.padding(start = pad, end = pad, top = 5.dp), fontSize = TextSizes.Body, bold = true, selectable = detail)
                }
                if (!data.message.isNullOrEmpty()) {
                    HtmlText(
                        data.message.orEmpty(),
                        Modifier.padding(start = pad, end = pad, top = 5.dp),
                        fontSize = TextSizes.Body,
                        maxLines = if (detail) Int.MAX_VALUE else 8,
                        onClick = if (detail) null else open,
                        selectable = detail,
                    )
                }
                if (data.picArr.isNotEmpty()) ImageGrid(data.picArr, Modifier.padding(start = pad, end = pad, top = 10.dp), media = data.mediaImages)
                if (!data.string("forwardSourceType").isNullOrEmpty()) ForwardSource(data.obj("forwardSourceFeed"), pad, detail)
                data.string("extra_url")?.takeIf { it.isNotEmpty() }?.let { ExtraLink(data, it, pad, detail) }
                data.obj("ershou_info")?.let { TradeLink(it, pad, detail) }
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

/** Author, time and device. In a list the whole row is the author: a tap opens the profile, a long press does nothing. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedHeader(data: Datum, detail: Boolean, actions: ItemActions) {
    val navigator = LocalNavigator.current
    val session = LocalSession.current
    var menu by remember { mutableStateOf(false) }
    val author = if (detail) Modifier else Modifier.combinedClickable(
        onClick = { navigator.openUser(data.uid, data.isAnonymous) },
        // A no-op rather than null, so the card's copy page stays away and a release is not a tap.
        onLongClick = {},
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).then(author).padding(start = if (detail) 16.dp else 10.dp, top = if (detail) 12.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(data.userAvatar ?: data.userInfo?.userAvatar, 36.dp) { navigator.openUser(data.uid, data.isAnonymous) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(data.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = TextSizes.Name)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val info = if (detail) fromToday(data.dateline) else htmlToPlain(data.string("infoHtml").orEmpty())
                    if (info.isNotEmpty()) {
                        Text(info, color = MaterialTheme.colorScheme.outline, fontSize = TextSizes.Meta, maxLines = 1)
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
                color = MaterialTheme.colorScheme.primary, fontSize = TextSizes.Tag,
            )
        }
        if (!detail) {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Outlined.KeyboardArrowDown, "更多", tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
    val confirmDelete = rememberConfirm("删除这条动态？", "删除后无法恢复。", "删除")
    val backupApp = com.zailinn.c001apk.ui.screens.appContainer()
    val offlineBackup = com.zailinn.c001apk.ui.common.LocalOfflineBackup.current
    if (menu) {
        ActionSheet(
            buildList {
                if (!offlineBackup) add(SheetAction(Icons.Outlined.Archive, "备份") { backupApp.backups.request(data) })
                add(SheetAction(Icons.Outlined.ContentCopy, "复制链接") { navigator.copy("https://www.coolapk.com/feed/${data.id}") })
                data.uid?.let { uid -> add(SheetAction(Icons.Outlined.Block, "屏蔽作者") { actions.onBlock?.invoke(uid, null) }) }
                add(SheetAction(Icons.Outlined.ErrorOutline, "举报") { navigator.report(data.id.orEmpty(), "feed") })
                if (actions.onDelete != null && (actions.removable || (session.isLogin && data.uid == session.uid))) {
                    // History and favorites remove a local record; only a real post asks first.
                    add(SheetAction(Icons.Outlined.Delete, "删除", danger = true) {
                        if (actions.removable) actions.onDelete.invoke(data, null) else confirmDelete { actions.onDelete.invoke(data, null) }
                    })
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
                if (source.picArr.isNotEmpty()) ImageGrid(source.picArr, Modifier.padding(top = 10.dp), media = source.mediaImages)
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
                Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = TextSizes.Meta, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/**
 * A 交易 post's price and listing link (`ershou_info`), usually a 闲鱼 short
 * link. It opens outside the app, so the 闲鱼 app can take it when installed.
 * `ershou_status` -1 marks a finished deal: the price is greyed and tagged.
 */
@Composable
private fun TradeLink(info: Datum, pad: androidx.compose.ui.unit.Dp, detail: Boolean) {
    val navigator = LocalNavigator.current
    val link = info.string("link_url")?.takeIf { info.int("is_link") == 1 && it.startsWith("https://", ignoreCase = true) }
    val price = info.string("product_price")?.takeIf { (it.toDoubleOrNull() ?: 0.0) > 0 }
    if (link == null && price == null) return
    val done = info.int("ershou_status") == -1
    val source = info.string("link_source")?.takeIf { it.isNotBlank() }
    val label = source?.let { "在${it}查看" } ?: "打开商品链接"
    val config = info.string("product_config_data")
        ?.let { runCatching { (kotlinx.serialization.json.Json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject)?.get("configTitle") }.getOrNull() }
        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    val meta = listOfNotNull(config, info.string("city")).filter { it.isNotBlank() }.joinToString(" · ")
    Surface(
        color = if (detail) cardColor() else MaterialTheme.colorScheme.surface,
        shape = CardShape,
        onClick = { link?.let(navigator::launchBrowser) },
        enabled = link != null,
        modifier = Modifier.fillMaxWidth().padding(start = pad, end = pad, top = 10.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ShoppingBag, null, tint = if (done) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    price?.let {
                        Text(
                            "¥${it.removeSuffix(".00")}", fontWeight = FontWeight.Bold,
                            color = if (done) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (done) {
                        Text(
                            "交易完成",
                            Modifier.padding(start = if (price != null) 8.dp else 0.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                            fontSize = TextSizes.Meta, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val line = listOf(meta, if (link != null) label else "").filter { it.isNotEmpty() }.joinToString(" · ")
                if (line.isNotEmpty()) Text(line, fontSize = TextSizes.Meta, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (link != null) Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = MaterialTheme.colorScheme.outline)
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
            fontSize = TextSizes.Nested,
            maxLines = 4,
            picArr = pics, media = reply.mediaImages,
            onShowReplies = open,
        )
    }
}

@Composable
private fun FeedBottom(data: Datum, detail: Boolean, pad: androidx.compose.ui.unit.Dp, actions: ItemActions) {
    Row(Modifier.padding(start = pad, end = pad, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        val left = if (detail) data.string("ip_location")?.takeIf { it.isNotEmpty() }?.let { "发布于 $it" }.orEmpty() else fromToday(data.dateline)
        Text(left, Modifier.weight(1f), color = MaterialTheme.colorScheme.outline, fontSize = TextSizes.Meta, maxLines = 1)
        val navigator = LocalNavigator.current
        // In a list the reply count opens the feed at its replies; on the detail page they are right below.
        data.string("replynum")?.let { count ->
            CountButton(Icons.AutoMirrored.Outlined.Message, count, onClick = if (detail) null else {
                { data.id?.let { navigator.navigate(FeedRoute(it, toReplies = true)) } }
            })
        }
        data.string("likenum")?.let { LikeButton(data, actions) }
    }
}

/** Icon and count; without [onClick] it is only a label, so a tap reaches the card under it. */
@Composable
fun CountButton(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, active: Boolean = false, onClick: (() -> Unit)? = null) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, null, Modifier.size(16.dp), tint = color)
        Spacer(Modifier.width(4.dp))
        Text(formatCount(value), color = color, fontSize = TextSizes.Meta)
    }
    if (onClick == null) {
        Row(Modifier.height(36.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    } else {
        TextButton(onClick = hapticClick(onClick), modifier = Modifier.height(36.dp), contentPadding = PaddingValues(horizontal = 10.dp), content = content)
    }
}

@Composable
fun LikeButton(data: Datum, actions: ItemActions) {
    // The view model checks the login, once for every place a like starts.
    CountButton(
        if (data.isLiked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
        data.string("likenum").orEmpty(),
        active = data.isLiked,
    ) { actions.onLike?.invoke(data) }
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
            Text(title, fontSize = TextSizes.Tag, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
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
    val replyClick = hapticClick { actions.onReply?.invoke(data, null) }
    val clickable = Modifier.combinedClickable(
        onClick = {
            if (isFeedReply && actions.onReply != null) replyClick() else navigator.openLink(data.url.orEmpty())
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
                        HtmlText(replyTitle(data, nested, isTop, feedUid, floorUid), Modifier.padding(end = 28.dp), fontSize = TextSizes.Name)
                        HtmlText(data.message.orEmpty(), picArr = data.picArr, media = data.mediaImages, selectable = true)
                        if (data.picArr.isNotEmpty()) ImageGrid(data.picArr, Modifier.padding(top = 10.dp), media = data.mediaImages)
                        data.obj("feed")?.takeIf { !isFeedReply && !nested }?.let { SourceFeed(it) }
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(fromToday(data.dateline), Modifier.weight(1f), color = MaterialTheme.colorScheme.outline, fontSize = TextSizes.Meta)
                            // Opens the sub-replies when there are any, otherwise starts a reply.
                            CountButton(Icons.AutoMirrored.Outlined.Message, data.string("replynum") ?: "0") {
                                if ((data.int("replynum") ?: 0) > 0 && actions.onShowReplies != null) actions.onShowReplies.invoke(data)
                                else if (isFeedReply && actions.onReply != null) actions.onReply.invoke(data, null)
                                else navigator.openLink(data.url.orEmpty())
                            }
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
    val confirmDelete = rememberConfirm("删除这条回复？", "删除后无法恢复。", "删除")
    if (menu) ReplyMenu(data, null, actions, confirmDelete) { menu = false }
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
                Text(htmlToPlain(feed.message.orEmpty()), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = TextSizes.Meta, color = MaterialTheme.colorScheme.outline)
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
                        .combinedClickable(onClick = if (actions.onReply != null) hapticClick { actions.onReply.invoke(reply, parent) } else ({}), onLongClick = { if (!press.active) menuFor = reply })
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    fontSize = TextSizes.Nested,
                    picArr = reply.picArr, media = reply.mediaImages,
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
                fontSize = TextSizes.Nested,
            )
        }
    }
    val confirmDelete = rememberConfirm("删除这条回复？", "删除后无法恢复。", "删除")
    menuFor?.let { reply -> ReplyMenu(reply, parent, actions, confirmDelete) { menuFor = null } }
}

@Composable
fun ReplyMenu(reply: Datum, parent: Datum?, actions: ItemActions, confirmDelete: (() -> Unit) -> Unit, onDismiss: () -> Unit) {
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
                add(SheetAction(Icons.Outlined.Delete, "删除", danger = true) { confirmDelete { actions.onDelete.invoke(reply, parent) } })
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
                Text(liker?.username.orEmpty(), Modifier.weight(1f), fontSize = TextSizes.Name)
                Text(fromToday(data.long("likeTime")), color = MaterialTheme.colorScheme.outline, fontSize = TextSizes.Meta)
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
                        HtmlText(data.message.orEmpty(), fontSize = TextSizes.Nested, maxLines = 3)
                    }
                }
            }
        }
    }
}
