package com.zailinn.c001apk.core.live

import com.zailinn.c001apk.core.model.LoadResult
import com.zailinn.c001apk.core.net.CoolapkClient
import com.zailinn.c001apk.core.net.CoolapkHeaders
import com.zailinn.c001apk.core.net.CoolapkRepository
import com.zailinn.c001apk.core.net.Page
import com.zailinn.c001apk.core.net.SessionState
import com.zailinn.c001apk.core.net.readApiObject
import com.zailinn.c001apk.core.protocol.ProtocolParams
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Opt-in guest reads: COOLAPK_LIVE_PROBE=1 for feeds, COOLAPK_TOPIC_PROBE=1 for topic sorts. Prints only
 * the shape of the result, never content or credentials.
 */
class LiveProbeTest {
    @Test
    fun guestTopicSortConfiguration() = runBlocking {
        assumeTrue(System.getenv("COOLAPK_TOPIC_PROBE") == "1")
        val session = SessionState()
        val params = ProtocolParams.generate()
        val client = CoolapkClient(session, CoolapkHeaders(session, { params }))
        val data = readApiObject(client.get("https://api.coolapk.com/v6/topic/newTagDetail", listOf("tag" to "酷安")))["data"] as JsonObject
        val repo = CoolapkRepository(client)
        val tab = (data["tabList"] as JsonArray).filterIsInstance<JsonObject>().first()
        val url = (tab["url"] as JsonPrimitive).content
        val page = readApiObject(client.get("https://api.coolapk.com/v6/page/dataList", listOf("url" to url, "page" to "1")))
        val card = (page["data"] as JsonArray).filterIsInstance<JsonObject>().first {
            (it["entityTemplate"] as? JsonPrimitive)?.content == "sortSelectCard"
        }
        val options = (card["entities"] as JsonArray).filterIsInstance<JsonObject>()
        check(options.map { (it["title"] as JsonPrimitive).content } == listOf("默认", "最新", "热度"))
        for (option in options) {
            val title = (option["title"] as JsonPrimitive).content
            val result = repo.dataList((option["url"] as JsonPrimitive).content, title, Page())
            println("topic sort $title: ${describe(result)}")
            check(result is LoadResult.Success) { "topic sort failed: ${describe(result)}" }
        }
    }

    @Test
    fun guestHomeFeedAndDetail() = runBlocking {
        assumeTrue(System.getenv("COOLAPK_LIVE_PROBE") == "1")
        val session = SessionState()
        val params = ProtocolParams.generate()
        val repo = CoolapkRepository(CoolapkClient(session, CoolapkHeaders(session, { params })))
        val home = repo.homeFeed(Page(), 1, System.currentTimeMillis().toString())
        println("home: ${describe(home)}")
        check(home is LoadResult.Success) { "home feed failed: ${describe(home)}" }
        val feed = home.value.firstOrNull { it.entityType == "feed" }?.id ?: return@runBlocking
        val detail = repo.feedDetail(feed)
        println("detail: ${describe(detail)}")
        check(detail is LoadResult.Success) { "detail failed: ${describe(detail)}" }
    }

    private fun describe(result: LoadResult<*>) = when (result) {
        is LoadResult.Success -> "success public=${result.isPublic} " +
            ((result.value as? List<*>)?.let { "items=${it.size}" } ?: "object")
        is LoadResult.Error -> "error ${result.message}"
        else -> result.toString()
    }
}
