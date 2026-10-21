package com.zailinn.c001apk.ui.common

import android.content.ClipData
import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withAnnotation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.zailinn.c001apk.ui.LocalAppSettings
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.theme.TextSizes

private const val LINK = "url"

private val emojiPattern = Regex("\\[[^\\]]+]")
private const val PHOTO = "ic_photo.icon"

private class BuiltHtml(val text: AnnotatedString, val inline: Set<String>) {
    val hasLinks = text.getStringAnnotations(LINK, 0, text.length).isNotEmpty()
}

/** Parsed messages by (html, emoji setting, link color); shared across cards and screens. */
private val builtCache = object : LruCache<Triple<String, Boolean, Long>, BuiltHtml>(400) {}

/** Decoded emoticon bitmaps; about 150 small images, kept for the process lifetime. */
private object EmojiImages {
    private val cache = HashMap<String, ImageBitmap?>()

    fun get(context: Context, file: String): ImageBitmap? = synchronized(cache) {
        cache.getOrPut(file) {
            runCatching { context.assets.open("emojis/$file").use(BitmapFactory::decodeStream)?.asImageBitmap() }.getOrNull()
        }
    }
}

@Composable
fun emojiBitmap(file: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(file) { EmojiImages.get(context.applicationContext, file) }
}

/**
 * Renders the HTML subset used by Coolapk messages: links, line breaks and
 * emoticon markers. Link handling: an empty link is
 * the card itself, reply list links expand replies, image links open the viewer.
 *
 * A [selectable] text starts a drag selection on long press, and copies web
 * links with their addresses. A card around it that provides [LocalContentPress]
 * keeps its long press for the space outside the text. Taps off a link do nothing.
 */
@Composable
fun HtmlText(
    html: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextSizes.Body,
    bold: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    color: Color = Color.Unspecified,
    picArr: List<String> = emptyList(),
    onClick: (() -> Unit)? = null,
    onShowReplies: (() -> Unit)? = null,
    onViewFans: (() -> Unit)? = null,
    selectable: Boolean = false,
) {
    val navigator = LocalNavigator.current
    val showEmoji = LocalAppSettings.current.showEmoji
    val linkColor = MaterialTheme.colorScheme.primary
    val handler: (String) -> Unit = { url ->
        when {
            url.isEmpty() -> onClick?.invoke()
            url.contains("/feed/replyList") -> onShowReplies?.invoke()
            url.contains("image.coolapk.com") -> navigator.openImages(picArr.ifEmpty { listOf(url) }, picArr.indexOf(url).coerceAtLeast(0))
            url == "/contacts/fans" -> onViewFans?.invoke()
            else -> navigator.openLink(url)
        }
    }
    val current by rememberUpdatedState(handler)
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val built = remember(html, showEmoji, linkColor) {
        val key = Triple(html, showEmoji, linkColor.value.toLong())
        builtCache.get(key) ?: buildHtml(html, showEmoji, linkColor).also { builtCache.put(key, it) }
    }
    val inline = remember(built) {
        built.inline.associateWith { src ->
            InlineTextContent(Placeholder(1.3.em, 1.3.em, PlaceholderVerticalAlign.TextCenter)) {
                if (src == PHOTO) {
                    Icon(Icons.Outlined.Photo, null, Modifier.fillMaxSize(), tint = linkColor)
                } else {
                    emojiBitmap(src)?.let { Image(it, null, Modifier.fillMaxSize()) }
                }
            }
        }
    }
    // Links are hit-tested per character. Compose's own link handling uses the
    // bounding box of a link, so a topic wrapped onto the next line swallowed
    // taps on the plain text beside it. Taps off a link fall through to the card.
    // A long press on a web link shows its address with copy and open; it is
    // watched in the Initial pass so the text selection does not start as well.
    val press = LocalContentPress.current
    var sheetUrl by remember { mutableStateOf<String?>(null) }
    val links = if (built.hasLinks) {
        Modifier.pointerInput(built, press) {
            awaitEachGesture {
                val down = awaitFirstDown(pass = PointerEventPass.Initial)
                val url = layout.value?.let { linkAt(built.text, it, down.position) } ?: return@awaitEachGesture
                down.consume()
                press?.active = true
                try {
                    val timeout = if (isWebLink(url)) viewConfiguration.longPressTimeoutMillis else Long.MAX_VALUE
                    when (withTimeoutOrNull(timeout) { awaitTap(down) }) {
                        true -> current(url)
                        false -> Unit
                        null -> {
                            sheetUrl = url
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                } finally {
                    press?.active = false
                }
            }
        }
    } else Modifier
    sheetUrl?.let { url ->
        ActionSheet(
            listOf(
                SheetAction(Icons.Outlined.ContentCopy, "复制") { navigator.copy(url) },
                SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "打开") { current(url) },
            ),
            onDismiss = { sheetUrl = null },
            header = url,
        )
    }
    // A press on the text tells the card to leave its long press to the selection,
    // and a tap on the text is not a tap on the card.
    val taps = if (selectable) {
        Modifier.pointerInput(press) {
            awaitEachGesture {
                // The selection takes the press first, so it may already be consumed.
                awaitFirstDown(requireUnconsumed = false)
                press?.active = true
                try {
                    waitForUpOrCancellation()?.consume()
                } finally {
                    press?.active = false
                }
            }
        }
    } else Modifier
    val text = @Composable {
        Text(
            built.text,
            modifier = taps.then(modifier).then(links),
            style = TextStyle(fontSize = fontSize, lineHeight = fontSize * 1.5f, fontWeight = if (bold) FontWeight.Bold else MaterialTheme.typography.bodyLarge.fontWeight),
            color = if (color == Color.Unspecified) LocalContentColor.current else color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            inlineContent = inline,
            onTextLayout = { layout.value = it },
        )
    }
    if (selectable) {
        val clipboard = LocalClipboard.current
        val selection = rememberSelectionState()
        val linked = remember(clipboard, selection) { LinkClipboard(clipboard, selection) }
        CompositionLocalProvider(LocalClipboard provides linked) { SelectionContainer(selection) { text() } }
    } else {
        text()
    }
}

/**
 * Whether a press is on selectable text inside a card. A card provides one
 * through [LocalContentPress] and skips its own long press while it is set.
 */
class ContentPress {
    var active = false
}

val LocalContentPress = staticCompositionLocalOf<ContentPress?> { null }

/**
 * Puts web link addresses into text copied from [selection]. The selected text
 * keeps its link annotations, so each link is told apart by position even when
 * several share a label like "查看链接".
 */
private class LinkClipboard(private val base: Clipboard, private val selection: SelectionState) : Clipboard {
    override val nativeClipboard: NativeClipboard get() = base.nativeClipboard

    override suspend fun getClipEntry(): ClipEntry? = base.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val copied = clipEntry?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        val selected = selection.selectedTexts.singleOrNull()
        if (copied == null || selected == null || selected.text != copied) return base.setClipEntry(clipEntry)
        base.setClipEntry(ClipEntry(ClipData.newPlainText("c001apk", copyWithLinks(selected))))
    }
}

/** [text] with each web link followed by its address. */
internal fun copyWithLinks(text: AnnotatedString): String = buildString {
    var at = 0
    text.getStringAnnotations(LINK, 0, text.length).sortedBy { it.start }.forEach { link ->
        if (link.end < at) return@forEach
        append(text.text, at, link.end)
        append(linkCopySuffix(text.text.substring(link.start, link.end), link.item))
        at = link.end
    }
    append(text.text, at, text.length)
}

/** True when [down] lifts in place, false when it moves away or is lost; the lift is consumed. */
private suspend fun AwaitPointerEventScope.awaitTap(down: PointerInputChange): Boolean {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: return false
        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return false
        if (!change.pressed) {
            change.consume()
            return true
        }
    }
}

private fun isWebLink(url: String) = url.startsWith("http://") || url.startsWith("https://")

/** The link under [position], only when a linked character itself is touched. */
internal fun linkAt(text: AnnotatedString, layout: TextLayoutResult, position: Offset): String? {
    val line = layout.getLineForVerticalPosition(position.y)
    if (position.y < layout.getLineTop(line) || position.y > layout.getLineBottom(line)) return null
    val offset = layout.getOffsetForPosition(position)
    return listOf(offset, offset - 1)
        .filter { it in 0 until layout.layoutInput.text.length && layout.getLineForOffset(it) == line }
        .firstOrNull { val box = layout.getBoundingBox(it); position.x >= box.left && position.x <= box.right }
        ?.let { text.getStringAnnotations(LINK, it, it + 1).firstOrNull()?.item }
}

/** Small outlined label used for markers like [楼主]. */
@Composable
fun Badge(text: String) {
    Text(
        text,
        Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)).padding(horizontal = 3.dp),
        color = MaterialTheme.colorScheme.primary,
        fontSize = TextSizes.Tag,
        fontWeight = FontWeight.Bold,
    )
}

/** Bold, italic and heading sizes from Markdown articles; null for plain text. */
private fun markdownStyle(part: HtmlPart.Text): SpanStyle? {
    if (!part.bold && !part.italic && part.heading == 0) return null
    return SpanStyle(
        fontWeight = if (part.bold || part.heading > 0) FontWeight.Bold else null,
        fontStyle = if (part.italic) FontStyle.Italic else null,
        fontSize = when (part.heading) {
            1 -> 1.3.em
            2 -> 1.2.em
            3 -> 1.1.em
            else -> TextUnit.Unspecified
        },
    )
}

private fun buildHtml(html: String, showEmoji: Boolean, linkColor: Color): BuiltHtml {
    val inline = mutableSetOf<String>()
    val linkStyle = SpanStyle(color = linkColor)
    val text = buildAnnotatedString {
        fun appendText(value: String) {
            if (!showEmoji || !value.contains('[')) {
                append(value)
                return
            }
            var last = 0
            for (match in emojiPattern.findAll(value)) {
                append(value, last, match.range.first)
                val src = emojiMap[match.value]
                when {
                    src == null -> append(match.value)
                    src.endsWith(".png") || src == PHOTO -> {
                        inline += src
                        appendInlineContent(src, match.value)
                    }
                    else -> withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Bold)) { append(" $src ") }
                }
                last = match.range.last + 1
            }
            append(value, last, value.length)
        }

        for (part in parseHtml(html)) {
            when (part) {
                HtmlPart.Break -> append('\n')
                is HtmlPart.Text -> {
                    val style = markdownStyle(part)
                    if (style != null) pushStyle(style)
                    if (part.href == null) appendText(part.value)
                    else withAnnotation(LINK, part.href) { withStyle(linkStyle) { appendText(part.value) } }
                    if (style != null) pop()
                }
                is HtmlPart.Image -> {
                    inline += PHOTO
                    if (part.href == null) appendInlineContent(PHOTO, "[图片]")
                    else withAnnotation(LINK, part.href) { appendInlineContent(PHOTO, "[图片]") }
                }
            }
        }
    }
    return BuiltHtml(text, inline)
}
