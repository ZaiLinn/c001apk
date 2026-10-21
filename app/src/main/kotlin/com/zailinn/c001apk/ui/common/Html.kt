package com.zailinn.c001apk.ui.common

/**
 * Minimal tokenizer for the HTML subset in Coolapk messages: links, line
 * breaks, images and entities, plus the bold, italic and heading tags that
 * [markdownToHtml] produces. Much cheaper than building a DOM for every card
 * while scrolling.
 */
sealed interface HtmlPart {
    data class Text(
        val value: String,
        val href: String?,
        val bold: Boolean = false,
        val italic: Boolean = false,
        /** 1 to 6 inside a heading, otherwise 0. */
        val heading: Int = 0,
    ) : HtmlPart
    data object Break : HtmlPart
    data class Image(val src: String, val href: String?) : HtmlPart
}

private val attributePattern = Regex("""([a-zA-Z-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")

private fun attribute(tag: String, name: String): String? =
    attributePattern.findAll(tag).firstOrNull { it.groupValues[1].equals(name, ignoreCase = true) }?.let { m ->
        decodeEntities(m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } })
    }

private val unclosedTag = Regex("""</?(a|img|br|p|div|span|b|strong|i|em|h[1-6])\s[^<>]*""", RegexOption.IGNORE_CASE)

fun parseHtml(html: String): List<HtmlPart> {
    val parts = mutableListOf<HtmlPart>()
    val text = StringBuilder()
    var href: String? = null
    var bold = 0
    var italic = 0
    var heading = 0
    fun flush() {
        if (text.isNotEmpty()) {
            parts += HtmlPart.Text(decodeEntities(text.toString()), href, bold > 0, italic > 0, heading)
            text.clear()
        }
    }
    var i = 0
    while (i < html.length) {
        val c = html[i]
        val next = html.getOrNull(i + 1)
        val end = if (c == '<' && next != null && (next.isLetter() || next == '/' || next == '!')) html.indexOf('>', i) else -1
        if (end < 0) {
            // A message cut short mid-tag, as list and history previews are, would
            // otherwise show the rest of the tag as raw markup.
            if (c == '<' && unclosedTag.matches(html.substring(i))) break
            if (c == '\n') {
                flush()
                parts += HtmlPart.Break
            } else {
                text.append(c)
            }
            i++
            continue
        }
        val tag = html.substring(i + 1, end).trim()
        val name = tag.removePrefix("/").takeWhile { it.isLetterOrDigit() }.lowercase()
        val closing = tag.startsWith("/")
        when (name) {
            "br" -> { flush(); parts += HtmlPart.Break }
            "a" -> { flush(); href = if (closing) null else attribute(tag, "href").orEmpty() }
            "img" -> { flush(); attribute(tag, "src")?.takeIf { it.isNotEmpty() }?.let { parts += HtmlPart.Image(it, href) } }
            "p", "div" -> if (closing) { flush(); parts += HtmlPart.Break }
            "b", "strong" -> { flush(); bold = (bold + if (closing) -1 else 1).coerceAtLeast(0) }
            "i", "em" -> { flush(); italic = (italic + if (closing) -1 else 1).coerceAtLeast(0) }
            "h1", "h2", "h3", "h4", "h5", "h6" -> { flush(); heading = if (closing) 0 else name[1] - '0' }
        }
        i = end + 1
    }
    flush()
    return parts
}

private val entityPattern = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")
private val namedEntities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

fun decodeEntities(value: String): String {
    if (!value.contains('&')) return value
    return entityPattern.replace(value) { match ->
        val body = match.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") -> body.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            body.startsWith("#") -> body.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> namedEntities[body]
        } ?: match.value
    }
}

private val plainCache = object : android.util.LruCache<String, String>(512) {}

/** Plain text of an HTML fragment, keeping line breaks. */
fun htmlToPlain(html: String): String {
    if (html.isEmpty() || (!html.contains('<') && !html.contains('&'))) return html
    plainCache.get(html)?.let { return it }
    val value = buildString {
        parseHtml(html).forEach { part ->
            when (part) {
                is HtmlPart.Text -> append(part.value)
                HtmlPart.Break -> append('\n')
                is HtmlPart.Image -> Unit
            }
        }
    }.trim()
    plainCache.put(html, value)
    return value
}

/**
 * The address to append after a web link's label when copying, so a link
 * shown as "查看链接" still carries its URL. Labels that already show the
 * address and in-app links (topics, users) need nothing.
 */
fun linkCopySuffix(label: String, href: String): String {
    val shown = label.trim()
    if (!href.startsWith("http://") && !href.startsWith("https://")) return ""
    if (shown.isEmpty() || shown.startsWith("http") || href.contains(shown)) return ""
    return "（$href）"
}

/** Text to copy from an HTML fragment: like [htmlToPlain], with web link addresses kept. */
fun htmlToCopy(html: String): String {
    if (!html.contains('<')) return htmlToPlain(html)
    return buildString {
        parseHtml(html).forEach { part ->
            when (part) {
                is HtmlPart.Text -> append(part.value).append(part.href?.let { linkCopySuffix(part.value, it) }.orEmpty())
                HtmlPart.Break -> append('\n')
                is HtmlPart.Image -> Unit
            }
        }
    }.trim()
}

private val mdHeading = Regex("""^(#{1,6})\s+(.*)$""")
private val mdQuote = Regex("""^(?:>|&gt;)\s?(.*)$""")
private val mdBullet = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val mdLink = Regex("""\[([^\]\n]+)]\((https?://[^)\s]+)\)""")
private val mdBold = Regex("""\*\*(.+?)\*\*""")
private val mdItalic = Regex("""(?<![*\w])\*(?![*\s])(.+?)(?<![*\s])\*(?![*\w])""")

/**
 * Article text blocks arrive as Markdown, and feeds and replies may carry it
 * too: headings, bold, italic, links, lists and quotes become the subset
 * [parseHtml] reads. HTML already in the text, like links, topics and the
 * `<!--break-->` markers, passes through.
 */
fun markdownToHtml(markdown: String): String = markdown.lines().joinToString("\n") { line ->
    mdHeading.matchEntire(line)?.let { val level = it.groupValues[1].length; "<h$level>${markdownInline(it.groupValues[2])}</h$level>" }
        ?: mdQuote.matchEntire(line)?.let { "▎<i>${markdownInline(it.groupValues[1])}</i>" }
        ?: mdBullet.matchEntire(line)?.let { "${it.groupValues[1]}• ${markdownInline(it.groupValues[2])}" }
        ?: markdownInline(line)
}

private fun markdownInline(text: String): String = text
    .replace(mdLink) { """<a href="${it.groupValues[2]}">${it.groupValues[1]}</a>""" }
    .replace(mdBold) { "<b>${it.groupValues[1]}</b>" }
    .replace(mdItalic) { "<i>${it.groupValues[1]}</i>" }
