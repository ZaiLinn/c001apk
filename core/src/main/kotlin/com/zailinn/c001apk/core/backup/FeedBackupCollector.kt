package com.zailinn.c001apk.core.backup

import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.net.CoolapkRepository
import com.zailinn.c001apk.core.net.Page
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Uses unfiltered API pages: UI blacklists and hot/web fallbacks must not truncate an archive. */
class FeedBackupCollector(private val repository: CoolapkRepository, private val maxPages: Int = 1000) {
    suspend fun collect(id: String, options: BackupOptions = BackupOptions(), verify: Boolean = true, beforeRequest: suspend () -> Unit = {}, progress: (String) -> Unit = {}): FeedBackup {
        require(id.matches(Regex("[0-9]{1,30}"))) { "动态编号无效" }
        progress("正在读取完整动态")
        beforeRequest()
        val feed = repository.backupFeed(id, verify)
        require(backupFeedId(Datum(feed)) == id) { "服务器返回的动态编号不匹配" }
        if (!options.replies) return FeedBackup(JsonObject(mapOf("data" to feed)),
            imageQuality = options.images, includesReplies = false)
        val warnings = mutableListOf<String>()
        val roots = linkedMapOf<String, Datum>()
        fun merge(items: List<Datum>) { items.forEach { item -> item.id?.let { roots[it] = item } } }
        listOf("topReplyRows", "replyMeRows", "replyRows").forEach { merge(Datum(feed).list(it)) }
        suspend fun pages(label: String, load: suspend (Page) -> List<Datum>): List<Datum> {
            val result = linkedMapOf<String, Datum>()
            var first: String? = null
            var last: String? = null
            try {
                for (number in 1..maxPages) {
                    currentCoroutineContext().ensureActive()
                    progress("$label（第 $number 页）")
                    beforeRequest()
                    val page = load(Page(number, first, last)).filter { it.entityType == "feed_reply" }
                    if (page.isEmpty()) return result.values.toList()
                    val new = page.filter { it.id != null && it.id !in result }
                    if (new.isEmpty()) { warnings += "$label：服务器重复返回同一页，可能未收齐"; return result.values.toList() }
                    new.forEach { result[it.id!!] = it }
                    if (first == null) first = page.first().id
                    last = page.last().id
                }
                warnings += "$label：达到分页上限，可能未收齐"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!verify && com.zailinn.c001apk.core.net.ApiFailure.from(e).kind == com.zailinn.c001apk.core.net.ApiFailureKind.Captcha) throw e
                warnings += "${label}失败，已保存成功读取的部分"
            }
            return result.values.toList()
        }
        var replies = pages("正在读取回复") { repository.backupReplies(id, it, verify = verify) }
        if (replies.isEmpty() && (Datum(feed).int("replynum") ?: 0) > 0) {
            replies = pages("正在重试回复") { repository.backupReplies(id, it, feedType = "", verify = verify) }
        }
        merge(replies)
        for ((key, item) in roots.toMap()) {
            if ((item.int("replynum") ?: 0) > item.replyRows.size) {
                val children = pages("正在读取楼中楼") { repository.backupSubReplies(key, it, verify) }
                val all = (item.replyRows + children).distinctBy { it.id }
                roots[key] = item.with("replyRows", JsonArray(all.map { it.json })).with("replyRowsMore", 0)
            }
        }
        val replyIds = mutableSetOf<String>()
        fun count(item: Datum) { item.id?.let(replyIds::add); item.replyRows.forEach(::count) }
        roots.values.forEach(::count)
        if (replyIds.size < (Datum(feed).int("replynum") ?: 0)) warnings += "服务器未返回全部回复，回复可能有缺失"
        return FeedBackup(JsonObject(mapOf("data" to feed)), roots.values.map { it.json }, warnings = warnings.distinct(), imageQuality = options.images, includesReplies = true)
    }
}
