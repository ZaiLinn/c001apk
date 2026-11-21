package com.zailinn.c001apk.core.backup

import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.net.CoolapkRepository
import com.zailinn.c001apk.core.net.Page
import com.zailinn.c001apk.core.net.ApiFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Reply collection is opt-in and bounded across main replies and nested replies together. */
class FeedBackupCollector(private val repository: CoolapkRepository) {
    @Suppress("UNUSED_PARAMETER")
    suspend fun collect(id: String, options: BackupOptions = BackupOptions(), verify: Boolean = false,
        beforeRequest: suspend () -> Unit = {}, progress: (String) -> Unit = {}): FeedBackup {
        require(id.matches(Regex("[0-9]{1,30}"))) { "动态编号无效" }
        val limit = if (options.replies) options.commentLimit else 0
        require(limit in 0..1000) { "评论数量须在 0 至 1000 条之间" }
        progress("正在读取完整动态")
        beforeRequest()
        val feed = repository.backupFeed(id)
        require(backupFeedId(Datum(feed)) == id) { "服务器返回的动态编号不匹配" }
        val comments = linkedMapOf<String, Datum>()
        val savedIds = mutableSetOf<String>()
        val warnings = mutableListOf<String>()
        suspend fun pages(label: String, load: suspend (Page) -> List<Datum>, accept: suspend (Datum) -> Unit) {
            var last: String? = null
            try {
                for (number in 1..1000) {
                    currentCoroutineContext().ensureActive()
                    if (savedIds.size >= limit) break
                    progress("$label（${savedIds.size}/$limit）")
                    beforeRequest()
                    val rows = load(Page(number, lastItem = last)).filter {
                        it.entityType == "feed_reply" || (it.entityType == null && it.id != null && it.message != null)
                    }
                    if (rows.isEmpty()) break
                    val previous = savedIds.size
                    rows.forEach { if (savedIds.size < limit) accept(it) }
                    val next = rows.lastOrNull()?.id
                    if (next == null || next == last || (number > 1 && savedIds.size == previous)) break
                    last = next
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { warnings += "${label}失败：${ApiFailure.from(e).message}；已保存正文和 ${savedIds.size} 条评论" }
        }
        suspend fun addRoot(item: Datum) {
            val key = item.id ?: return
            if (savedIds.size >= limit || !savedIds.add(key)) return
            var root = Datum(withoutReplies(item.json) as JsonObject)
            comments[key] = root
            if (options.includeSubReplies) {
                val children = mutableListOf<Datum>()
                fun addChild(child: Datum) {
                    val childId = child.id ?: return
                    if (savedIds.size < limit && savedIds.add(childId)) children += Datum(withoutReplies(child.json) as JsonObject)
                }
                item.replyRows.forEach(::addChild)
                if (savedIds.size < limit && ((item.int("replynum") ?: 0) > item.replyRows.size || (item.int("replyRowsMore") ?: 0) > 0)) {
                    pages("读取楼中楼", { repository.backupSubReplies(key, it, verify = false) }) { addChild(it) }
                }
                root = root.withList("replyRows", children).with("replyRowsMore", 0).with("replynum", children.size)
                comments[key] = root
            }
        }
        if (limit > 0) {
            Datum(feed).list("topReplyRows").forEach { addRoot(it) }
            pages("读取主回复", { repository.backupReplies(id, it, verify = false) }, ::addRoot)
        }
        // A chosen limit, an empty page, deleted/hidden replies or a stale server
        // replynum are not evidence of a failed request. Only actual errors warn.
        return FeedBackup(JsonObject(mapOf("data" to withoutReplies(feed))), comments.values.map { it.json }, warnings = warnings,
            imageQuality = options.images, includesReplies = limit > 0, commentLimit = limit,
            includesSubReplies = limit > 0 && options.includeSubReplies)
    }

    private fun withoutReplies(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.filterKeys { it !in replyFields }.mapValues { withoutReplies(it.value) })
        is JsonArray -> JsonArray(value.map(::withoutReplies))
        else -> value
    }
    private companion object {
        val replyFields = setOf("topReplyRows", "hotReplyRows", "replyMeRows", "replyRows", "replyRowsMore", "replyList", "reply_list")
    }
}
