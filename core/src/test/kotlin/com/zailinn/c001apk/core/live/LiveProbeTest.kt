package com.zailinn.c001apk.core.live

import com.zailinn.c001apk.core.model.LoadResult
import com.zailinn.c001apk.core.net.CoolapkClient
import com.zailinn.c001apk.core.net.CoolapkHeaders
import com.zailinn.c001apk.core.net.CoolapkRepository
import com.zailinn.c001apk.core.net.Page
import com.zailinn.c001apk.core.net.SessionState
import com.zailinn.c001apk.core.protocol.ProtocolParams
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in guest read against the real API: COOLAPK_LIVE_PROBE=1. Prints only
 * the shape of the result, never content or credentials.
 */
class LiveProbeTest {
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
