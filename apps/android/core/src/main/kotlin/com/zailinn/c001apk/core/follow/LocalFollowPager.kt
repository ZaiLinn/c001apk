package com.zailinn.c001apk.core.follow

import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.LoadResult
import com.zailinn.c001apk.core.net.Page
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

typealias UserFeedFetcher = suspend (uid: String, page: Page) -> LoadResult<List<Datum>>

/**
 * Merges public user timelines, retaining each user's independent pagination.
 * Reads run at most four at a time; failed cursors remain retryable.
 */
class LocalFollowPager(uids: Iterable<String>, private val fetch: UserFeedFetcher) {
    private class Cursor(val uid: String) {
        var page = Page()
        var end = false
        val buffer = mutableListOf<Datum>()
        val seen = mutableSetOf<String>()
    }

    private val cursors = uids.toSet().map(::Cursor)
    private val seen = mutableSetOf<String>()
    var warning: String? = null
        private set

    val isEnd: Boolean get() = cursors.all { it.end && it.buffer.isEmpty() }

    suspend fun next(limit: Int = 20): List<Datum> {
        val output = mutableListOf<Datum>()
        val failures = linkedMapOf<Cursor, String>()
        val deferred = mutableSetOf<Cursor>()
        warning = null
        while (output.size < limit) {
            val pending = cursors.filter { !it.end && it.buffer.isEmpty() && it !in failures && it !in deferred }
            for (chunk in pending.chunked(4)) {
                coroutineScope {
                    chunk.map { cursor -> async { load(cursor, failures, deferred) } }.awaitAll()
                }
            }
            val available = cursors.filter { it.buffer.isNotEmpty() }.sortedByDescending { time(it.buffer.first()) }
            if (available.isEmpty()) break
            val item = available.first().buffer.removeAt(0)
            if (seen.add(item.id.toString())) output.add(item)
        }
        warning = when {
            failures.isNotEmpty() -> "${failures.size} 位用户的动态加载失败：${failures.values.first()}"
            deferred.isNotEmpty() -> "部分动态已过滤，请继续加载"
            else -> null
        }
        return output
    }

    private suspend fun load(cursor: Cursor, failures: MutableMap<Cursor, String>, deferred: MutableSet<Cursor>) {
        // Bounded work even when all pages contain blocked or duplicate cards.
        repeat(3) {
            val result = try {
                fetch(cursor.uid, cursor.page)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                LoadResult.Error("网络请求失败，请稍后重试")
            }
            when (result) {
                is LoadResult.Error -> { synchronized(failures) { failures[cursor] = result.message }; return }
                LoadResult.Empty -> { cursor.end = true; return }
                is LoadResult.Success -> {
                    val items = result.value
                    cursor.end = items.any { it.entityTemplate == "noMoreDataCard" }
                    val feeds = items.filter { it.entityType == "feed" && it.id != null && it.uid == cursor.uid }
                    cursor.page = Page(
                        page = cursor.page.page + 1,
                        firstItem = cursor.page.firstItem ?: feeds.firstOrNull()?.id,
                        lastItem = feeds.lastOrNull()?.id ?: cursor.page.lastItem,
                    )
                    cursor.buffer.addAll(feeds.filter { cursor.seen.add(it.id!!) })
                    cursor.buffer.sortByDescending(::time)
                    if (cursor.buffer.isNotEmpty() || cursor.end) return
                }
                LoadResult.Loading -> return
            }
        }
        synchronized(deferred) { deferred.add(cursor) }
    }

    private fun time(item: Datum): Long = item.dateline ?: 0
}
