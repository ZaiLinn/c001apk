package com.zailinn.c001apk.core.backup

import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.FeedArticle
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import java.text.Normalizer
import java.security.MessageDigest
import java.util.Locale

object BackupText {
    fun plain(html: String): String = Jsoup.parseBodyFragment(html.replace(Regex("(?i)<br\\s*/?>|</(?:p|div|h[1-6]|li|blockquote)>"), "$0\n"))
        .wholeText().replace(Regex("[ \\t]+"), " ").replace(Regex("\\n[ \\t]+"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    fun terms(query: String): List<String> = normalize(query).split(' ').filter { it.isNotBlank() }.distinct()
    fun like(term: String): String = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    fun title(backup: FeedBackup): String = plain(backup.data.string("message_title") ?: backup.data.title ?: backup.data.message.orEmpty()).take(60)
    fun body(backup: FeedBackup, replies: Boolean = false): String = buildString {
        val feed = backup.data
        val title = feed.string("message_title") ?: feed.title
        if (!title.isNullOrBlank()) appendLine(plain(title))
        val blocks = runCatching { FeedArticle.parse(feed) }.getOrDefault(emptyList())
        if (blocks.any { it.type == "text" }) blocks.filter { it.type == "text" }.forEach { appendLine(plain(it.message.orEmpty())); appendLine() }
        else appendLine(plain(feed.message.orEmpty()))
        if (replies && backup.includesReplies) {
            fun reply(item: Datum) { appendLine("${item.displayName}：${plain(item.message.orEmpty())}"); item.replyRows.forEach(::reply) }
            backup.replies.forEach { reply(Datum(it)) }
        }
    }.trim()
    fun searchable(backup: FeedBackup): String {
        val text = normalize(buildString {
            appendLine(backup.data.displayName)
            listOf("tags", "ttitle", "topic", "topic_title").forEach { appendLine(backup.data.json[it]?.let { if (it is JsonPrimitive) it.content else it.toString() }.orEmpty()) }
            appendLine(body(backup, backup.includesReplies))
        })
        var bytes = 0; var end = 0
        while (end < text.length) {
            val point = text.codePointAt(end)
            val count = when { point < 128 -> 1; point < 2048 -> 2; point < 65536 -> 3; else -> 4 }
            if (bytes + count > 200 * 1024) break
            bytes += count; end += Character.charCount(point)
        }
        return text.substring(0, end)
    }
    fun fingerprint(feed: Datum): String {
        val fields = listOf("id", "message", "picArr", "message_title").joinToString("\n") { feed.json[it]?.toString().orEmpty() }
        return MessageDigest.getInstance("SHA-256").digest(fields.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
