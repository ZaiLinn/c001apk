package com.zailinn.c001apk.core.follow

import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.DatumPage
import com.zailinn.c001apk.core.model.LoadResult
import com.zailinn.c001apk.core.net.Page
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

typealias UserFeedFetcher = suspend (uid: String, page: Page) -> LoadResult<List<Datum>>
typealias FollowFeedFetcher = suspend (source: FollowSource, page: Page) -> LoadResult<List<Datum>>

/** A timeline followed only on this device. */
sealed interface FollowSource {
    data class User(val uid: String) : FollowSource
    /** A topic's newest feeds; any author. */
    data class Topic(val id: String, val title: String) : FollowSource
}

/**
 * Merges public user and topic timelines, retaining each source's independent pagination.
 * Reads run at most four at a time; failed cursors remain retryable.
 */
class LocalFollowPager(sources: Iterable<FollowSource>, private val fetch: FollowFeedFetcher) {
    private class Cursor(val source: FollowSource) {
        var page = Page()
        var end = false
        val buffer = mutableListOf<Datum>()
        val seen = mutableSetOf<String>()
    }

    private val cursors = sources.toSet().map(::Cursor)
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
            failures.isNotEmpty() -> "${failures.size} 个关注的动态加载失败：${failures.values.first()}"
            deferred.isNotEmpty() -> "部分动态已过滤，请继续加载"
            else -> null
        }
        return output
    }

    private suspend fun load(cursor: Cursor, failures: MutableMap<Cursor, String>, deferred: MutableSet<Cursor>) {
        // Bounded work even when all pages contain blocked or duplicate cards.
        repeat(3) {
            val result = try {
                fetch(cursor.source, cursor.page)
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
                    val feeds = items.filter { item ->
                        item.entityType == "feed" && item.id != null &&
                            (cursor.source !is FollowSource.User || item.uid == cursor.source.uid)
                    }
                    cursor.page = Page(
                        page = cursor.page.page + 1,
                        firstItem = cursor.page.firstItem ?: feeds.firstOrNull()?.id,
                        lastItem = feeds.lastOrNull()?.id ?: (items as? DatumPage)?.rawLastId ?: cursor.page.lastItem,
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

    companion object {
        /** Users only. A factory, since a second constructor would clash on the JVM. */
        operator fun invoke(uids: Iterable<String>, fetch: UserFeedFetcher) =
            LocalFollowPager(uids.map(FollowSource::User)) { source, page -> fetch((source as FollowSource.User).uid, page) }
    }
}
