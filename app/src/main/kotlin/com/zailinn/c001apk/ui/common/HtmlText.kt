package com.zailinn.c001apk.ui.common

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
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zailinn.c001apk.ui.LocalAppSettings
import com.zailinn.c001apk.ui.nav.LocalNavigator

private val emojiPattern = Regex("\\[[^\\]]+]")
private const val PHOTO = "ic_photo.icon"

private class BuiltHtml(val text: AnnotatedString, val inline: Set<String>)

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
    val uriHandler = remember { object : UriHandler { override fun openUri(uri: String) = current(uri) } }
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
    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        Text(
            built.text,
            modifier = modifier,
            style = TextStyle(fontSize = fontSize, lineHeight = fontSize * 1.5f, fontWeight = if (bold) FontWeight.Bold else null),
            color = if (color == Color.Unspecified) LocalContentColor.current else color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            inlineContent = inline,
        )
    }
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

private fun buildHtml(html: String, showEmoji: Boolean, linkColor: Color): BuiltHtml {
    val inline = mutableSetOf<String>()
    val styles = TextLinkStyles(SpanStyle(color = linkColor))
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
                is HtmlPart.Text ->
                    if (part.href == null) appendText(part.value)
                    else withLink(LinkAnnotation.Url(part.href, styles)) { appendText(part.value) }
                is HtmlPart.Image -> {
                    inline += PHOTO
                    if (part.href == null) appendInlineContent(PHOTO, "[图片]")
                    else withLink(LinkAnnotation.Url(part.href, styles)) { appendInlineContent(PHOTO, "[图片]") }
                }
            }
        }
    }
    return BuiltHtml(text, inline)
}
