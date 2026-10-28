package com.zailinn.c001apk.core.backup

import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.FeedArticle
import org.jsoup.Jsoup
import org.jsoup.nodes.Entities
import org.jsoup.safety.Safelist
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ArchiveExport {
    fun time(seconds: Long, zone: TimeZone = TimeZone.getDefault()): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { timeZone = zone }.format(Date(seconds * 1000))
    fun txt(backup: FeedBackup, sources: List<String> = emptyList(), replies: Boolean = false, zone: TimeZone = TimeZone.getDefault()): String = buildString {
        appendLine("==============================")
        appendLine("${backup.data.displayName} · ${time(backup.data.dateline ?: 0, zone)}")
        appendLine("https://www.coolapk.com/feed/${backup.id}")
        if (sources.isNotEmpty()) appendLine("来源：${sources.joinToString("、")}")
        appendLine(); appendLine(BackupText.body(backup)); appendLine()
        val imageCount = backup.images.size.takeIf { it > 0 } ?: backupImageUrls(backup).size.takeIf { it > 0 }
            ?: Regex("c001apk-backup://[^\\s\"'<>]+\\.[a-zA-Z0-9]+").findAll(backup.feed.toString()).map { it.value }.distinct().count()
        appendLine("[图片 $imageCount 张]")
        if (replies && backup.includesReplies) {
            appendLine("------------------------------"); appendLine("回复（${backup.replies.size}）")
            fun reply(item: Datum, level: Int) {
                appendLine("${"  ".repeat(level.coerceAtMost(8))}${item.displayName}：${BackupText.plain(item.message.orEmpty())}")
                item.replyRows.forEach { reply(it, level + 1) }
            }
            backup.replies.forEach { reply(Datum(it), 1) }
        }
        if (backup.warnings.isNotEmpty()) appendLine("备份提示：${backup.warnings.joinToString("；")}")
        appendLine("=============================="); appendLine()
    }
    fun escape(text: String): String = Entities.escape(text)
    fun header(toc: List<Pair<String, String>>): String = buildString {
        append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>收藏备份</title><style>")
        append("body{max-width:900px;margin:auto;padding:24px;font:16px/1.7 sans-serif;color:#222;background:#fff}article{border-top:1px solid #888;padding:20px 0}img{max-width:100%;height:auto}pre{white-space:pre-wrap}a{color:#3566ad}.reply{margin-left:20px}nav{padding-bottom:24px}@media(prefers-color-scheme:dark){body{background:#171717;color:#eee}a{color:#a9caff}}")
        append("</style></head><body><h1>收藏备份</h1><nav><ol>")
        toc.forEach { (target, title) -> append("<li><a href=\"").append(escape(target)).append("\">").append(escape(title)).append("</a></li>") }
        append("</ol></nav>")
    }
    const val footer = "</body></html>"
    /** Only trusted exporter-provided relative image paths can enter the result. */
    fun clean(html: String, images: Map<String, String> = emptyMap()): String {
        val doc = Jsoup.parseBodyFragment(html, "https://www.coolapk.com/")
        doc.select("img").forEach { image ->
            val src = image.attr("src")
            val local = images[src] ?: backupImageUrl(src)?.let(images::get)
            if (local == null || !local.matches(Regex("images/[0-9]{1,30}_[0-9]+\\.[a-zA-Z0-9]{1,8}"))) image.remove()
            else { image.clearAttributes(); image.attr("src", local) }
        }
        val safe = Safelist.basic().addTags("img").addAttributes("img", "src", "alt").preserveRelativeLinks(false)
        val cleaned = Jsoup.parseBodyFragment(Jsoup.clean(doc.body().html(), "https://www.coolapk.com/", safe))
        cleaned.select("img").forEach { image ->
            val src = image.attr("src").removePrefix("https://www.coolapk.com/")
            if (src.matches(Regex("images/[0-9]{1,30}_[0-9]+\\.[a-zA-Z0-9]{1,8}"))) image.attr("src", src) else image.remove()
        }
        cleaned.select("a[href]").forEach { link ->
            val url = link.attr("href").toHttpUrlOrNull()
            if (url == null) link.removeAttr("href") else {
                val canonical = url.newBuilder().scheme("https").apply { if (url.host == "coolapk.com" || url.host.endsWith(".coolapk.com")) host("www.coolapk.com") }.build()
                link.attr("href", canonical.toString()).attr("rel", "noopener noreferrer")
            }
        }
        return cleaned.body().html()
    }
    fun article(backup: FeedBackup, sources: List<String> = emptyList(), replies: Boolean = false, images: Map<String, String> = emptyMap(), zone: TimeZone = TimeZone.getDefault()): String = buildString {
        append("<article id=\"feed_").append(backup.id).append("\"><h2>").append(escape(BackupText.title(backup))).append("</h2><p>")
        append(escape(backup.data.displayName)).append(" · ").append(time(backup.data.dateline ?: 0, zone)).append("</p>")
        append("<p><a rel=\"noopener noreferrer\" href=\"https://www.coolapk.com/feed/").append(backup.id).append("\">原帖链接</a></p>")
        if (sources.isNotEmpty()) append("<p>来源：").append(escape(sources.joinToString("、"))).append("</p>")
        val blocks = runCatching { FeedArticle.parse(backup.data) }.getOrDefault(emptyList())
        if (blocks.isEmpty()) {
            append(clean(backup.data.message.orEmpty(), images))
            backup.data.picArr.forEach { append(clean("<img src=\"${escape(it)}\">", images)) }
        } else blocks.forEach { block -> when (block.type) {
            "text" -> append(clean(block.message.orEmpty(), images))
            "image" -> append(clean("<img src=\"${escape(block.url.orEmpty())}\">", images))
            else -> Unit
        } }
        if (replies && backup.includesReplies) {
            append("<h3>回复</h3>")
            fun reply(item: Datum) {
                append("<div class=\"reply\"><strong>").append(escape(item.displayName)).append("</strong>：").append(clean(item.message.orEmpty(), images))
                item.picArr.forEach { append(clean("<img src=\"${escape(it)}\">", images)) }
                item.replyRows.forEach(::reply); append("</div>")
            }
            backup.replies.forEach { reply(Datum(it)) }
        }
        if (backup.warnings.isNotEmpty()) append("<p>备份提示：").append(escape(backup.warnings.joinToString("；"))).append("</p>")
        append("</article>\n")
    }
}
