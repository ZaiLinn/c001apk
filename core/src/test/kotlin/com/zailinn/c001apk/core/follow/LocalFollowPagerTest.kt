package com.zailinn.c001apk.core.follow

import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.LoadResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

private fun feed(id: Int, uid: Int, time: Int) = Datum(buildJsonObject {
    put("id", JsonPrimitive(id)); put("entityId", JsonPrimitive(id)); put("entityType", JsonPrimitive("feed"))
    put("uid", JsonPrimitive(uid)); put("dateline", JsonPrimitive(time))
})

class LocalFollowPagerTest {
    @Test
    fun mergesByTimeDeduplicatesAndExhaustsIndependently() = runTest {
        val calls = mutableListOf<String>()
        val pager = LocalFollowPager(listOf("1", "2")) { uid, page ->
            synchronized(calls) { calls += "$uid:${page.page}:${page.lastItem}" }
            when {
                uid == "1" && page.page == 1 -> LoadResult.Success(listOf(feed(11, 1, 100), feed(12, 1, 70)))
                uid == "1" && page.page == 2 -> LoadResult.Success(listOf(feed(12, 1, 70), feed(13, 1, 40)))
                uid == "2" && page.page == 1 -> LoadResult.Success(listOf(feed(21, 2, 90), feed(22, 2, 80)))
                else -> LoadResult.Empty
            }
        }
        assertEquals(listOf("11", "21", "22"), pager.next(limit = 3).map { it.id })
        assertEquals(listOf("12", "13"), pager.next(limit = 3).map { it.id })
        assertTrue(pager.isEnd)
        assertTrue("1:2:12" in calls)
        assertEquals(1, calls.count { it.startsWith("2:2") })
    }

    @Test
    fun topicsKeepEveryAuthorAndShareDeduplicationWithUsers() = runTest {
        val pager = LocalFollowPager(listOf(FollowSource.User("1"), FollowSource.Topic("7", "合成话题"))) { source, page ->
            when {
                page.page > 1 -> LoadResult.Empty
                source is FollowSource.User -> LoadResult.Success(listOf(feed(11, 1, 100), feed(31, 3, 95)))
                // Topic feeds come from anyone, including a followed user.
                else -> LoadResult.Success(listOf(feed(11, 1, 100), feed(41, 4, 90), feed(42, 5, 80)))
            }
        }
        assertEquals(listOf("11", "41", "42"), pager.next().map { it.id })
        assertTrue(pager.isEnd)
    }

    @Test
    fun failedUserPreservesOthersAndRetriesSamePage() = runTest {
        var fail = true
        val pages = mutableListOf<Int>()
        val pager = LocalFollowPager(listOf("1", "2")) { uid, page ->
            if (uid == "2") {
                pages += page.page
                if (fail) return@LocalFollowPager LoadResult.Error("需要验证")
                if (page.page == 1) return@LocalFollowPager LoadResult.Success(listOf(feed(21, 2, 90)))
            }
            if (uid == "1" && page.page == 1) LoadResult.Success(listOf(feed(11, 1, 100))) else LoadResult.Empty
        }
        assertEquals(listOf("11"), pager.next().map { it.id })
        assertTrue(pager.warning!!.contains("需要验证"))
        assertFalse(pager.isEnd)
        fail = false
        assertEquals(listOf("21"), pager.next().map { it.id })
        assertEquals(listOf(1, 1), pages.take(2))
    }

    @Test
    fun boundedConcurrency() = runTest {
        val active = AtomicInteger()
        var peak = 0
        val fetch: UserFeedFetcher = { _, _ ->
            val now = active.incrementAndGet()
            synchronized(this@LocalFollowPagerTest) { peak = maxOf(peak, now) }
            delay(1)
            active.decrementAndGet()
            LoadResult.Empty
        }
        assertTrue(LocalFollowPager(emptyList(), fetch).next().isEmpty())
        LocalFollowPager((0 until 15).map { "$it" }, fetch).next()
        assertTrue(peak <= 4)
    }
}
