package com.zailinn.c001apk.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import com.zailinn.c001apk.ui.LocalAppSettings
import com.zailinn.c001apk.ui.nav.LocalNavigator
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

private val emojiPattern = Regex("\\[[^\\]]+]")

/** Plain text of an HTML fragment, keeping line breaks. */
fun htmlToPlain(html: String): String {
    if (!html.contains('<') && !html.contains('&')) return html
    val body = Jsoup.parseBodyFragment(html).body()
    return buildString { appendPlain(body) }.trim()
}

private fun StringBuilder.appendPlain(node: Node) {
    when (node) {
        is TextNode -> append(node.wholeText)
        is Element -> if (node.tagName() == "br") append('\n') else node.childNodes().forEach { appendPlain(it) }
    }
}

/**
 * Renders the HTML subset used by Coolapk messages: links, line breaks and
 * emoticon markers. Link handling follows the Flutter client: an empty link is
 * the card itself, reply list links expand replies, image links open the viewer.
 */
@Composable
fun HtmlText(
    html: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 15.sp,
    bold: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    color: Color = Color.Unspecified,
    picArr: List<String> = emptyList(),
    onClick: (() -> Unit)? = null,
    onShowReplies: (() -> Unit)? = null,
    onViewFans: (() -> Unit)? = null,
) {
    val navigator = LocalNavigator.current
    val showEmoji = LocalAppSettings.current.showEmoji
    val linkColor = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    val handler: (String) -> Unit = { url ->
        when {
            url.isEmpty() -> onClick?.invoke()
            url.contains("/feed/replyList") -> onShowReplies?.invoke()
            url.contains("image.coolapk.com") -> navigator.openImages(picArr.ifEmpty { listOf(url) }, picArr.indexOf(url).coerceAtLeast(0))
            url == "/contacts/fans" -> onViewFans?.invoke()
            else -> navigator.openLink(url)
        }
    }
    val currentHandler by rememberUpdatedState(handler)
    val built = remember(html, showEmoji, linkColor) { buildHtml(html, showEmoji, linkColor) { currentHandler(it) } }
    val inline = remember(built.second, fontSize) {
        built.second.associateWith { src ->
            InlineTextContent(Placeholder(1.3.em, 1.3.em, PlaceholderVerticalAlign.TextCenter)) {
                when {
                    src.endsWith(".icon") -> Icon(Icons.Outlined.Photo, null, Modifier.fillMaxSize(), tint = linkColor)
                    src.endsWith(".png") -> {
                        val bitmap = remember(src) {
                            runCatching { context.assets.open("emojis/$src").use(BitmapFactory::decodeStream)?.asImageBitmap() }.getOrNull()
                        }
                        if (bitmap != null) Image(bitmap, src, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
    Text(
        built.first,
        modifier = modifier,
        style = TextStyle(fontSize = fontSize, lineHeight = fontSize * 1.5f, fontWeight = if (bold) FontWeight.Bold else null),
        color = if (color == Color.Unspecified) LocalContentColor.current else color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        inlineContent = inline,
    )
}

/** Small outlined label used for markers like [楼主]. */
@Composable
fun Badge(text: String) {
    Text(
        text,
        Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)).padding(horizontal = 3.dp),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
    )
}


private fun buildHtml(
    html: String,
    showEmoji: Boolean,
    linkColor: Color,
    onLink: (String) -> Unit,
): Pair<AnnotatedString, Set<String>> {
    val inlineIds = mutableSetOf<String>()
    val body = Jsoup.parseBodyFragment(html.replace("\n", "<br/>")).body()
    val styles = TextLinkStyles(SpanStyle(color = linkColor))
    val text = buildAnnotatedString {
        fun appendText(value: String) {
            if (!showEmoji) {
                append(value)
                return
            }
            var last = 0
            for (match in emojiPattern.findAll(value)) {
                append(value.substring(last, match.range.first))
                val src = emojiMap[match.value]
                when {
                    src == null -> append(match.value)
                    src.endsWith(".png") || src.endsWith(".icon") -> {
                        inlineIds += src
                        appendInlineContent(src, match.value)
                    }
                    else -> withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Bold)) { append(" $src ") }
                }
                last = match.range.last + 1
            }
            append(value.substring(last))
        }

        fun walk(node: Node) {
            when (node) {
                is TextNode -> appendText(node.wholeText)
                is Element -> when (node.tagName()) {
                    "br" -> append('\n')
                    "a" -> {
                        val href = node.attr("href")
                        withLink(LinkAnnotation.Clickable(href, styles) { onLink(href) }) {
                            node.childNodes().forEach(::walk)
                        }
                    }
                    "img" -> {
                        val src = node.attr("src")
                        if (src.isNotEmpty()) {
                            inlineIds += ".icon"
                            appendInlineContent(".icon", "[图片]")
                        }
                    }
                    else -> node.childNodes().forEach(::walk)
                }
            }
        }
        body.childNodes().forEach(::walk)
    }
    return text to inlineIds
}

private inline fun <R : Any> AnnotatedString.Builder.withStyle(style: SpanStyle, block: AnnotatedString.Builder.() -> R): R {
    val index = pushStyle(style)
    return try { block() } finally { pop(index) }
}
