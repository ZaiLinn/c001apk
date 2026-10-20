package com.zailinn.c001apk.core.net

import com.zailinn.c001apk.core.model.LoadResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Cloud collections: which folders hold a feed, and adding or taking it out. */
class CollectionTest {
    @Test
    fun collectionsForAFeedMarkTheOnesHoldingIt() = runTest {
        val h = Harness { _, _ ->
            200 to """{"data":[{"id":"1","entityType":"collection","title":"默认收藏","isBeCollected":1},{"id":"2","entityType":"collection","title":"数码","isBeCollected":0}]}"""
        }
        val result = h.repo.collections("99")
        assertTrue(result is LoadResult.Success)
        assertEquals(listOf(1, 0), (result as LoadResult.Success).value.map { it.int("isBeCollected") })
        val url = h.transport.requests.single().url
        assertEquals("/v6/collection/list", url.encodedPath)
        assertEquals(listOf("99", "feed", "1"), listOf("id", "type", "showDefault").map(url::queryParameter))
    }

    @Test
    fun collectingAndUncollectingUseIdOrCancelId() = runTest {
        val h = Harness { _, _ -> 200 to """{"data":{"favnum":5,"collect":1}}""" }
        val added = h.repo.setCollected("2", "99", collected = true)
        assertEquals(JsonPrimitive(5), added.dataObject?.get("favnum"))
        h.repo.setCollected("2", "99", collected = false)
        assertEquals("/v6/collection/addItem", h.transport.requests.first().url.encodedPath)
        assertEquals(mapOf("id" to "2", "cancelId" to "", "targetId" to "99", "type" to "feed"), formOf(h.transport.bodies[0]))
        assertEquals(mapOf("id" to "", "cancelId" to "2", "targetId" to "99", "type" to "feed"), formOf(h.transport.bodies[1]))
    }

    @Test
    fun serverRefusalIsReportedWithItsMessage() = runTest {
        val h = Harness { _, _ -> 200 to """{"status":-5,"message":"该内容已存在"}""" }
        try {
            h.repo.setCollected("2", "99", collected = true)
            fail("refusal should throw")
        } catch (e: Exception) {
            assertEquals("该内容已存在", ApiFailure.from(e).message)
        }
    }
}
