package com.zailinn.c001apk.core.net

import com.zailinn.c001apk.core.model.GalleryMedia
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class LivePhotoResolverTest {
    @Test fun resolverUsesHttpsAndOwnerWithoutFollowingCredentialsToVideoHost() = runTest {
        val requests = mutableListOf<okhttp3.Request>()
        val session = SessionState().apply { activate(AccountCredentials("10", "name", "synthetic-token"), true) }
        val client = CoolapkClient(session, CoolapkHeaders(session, { syntheticParams }), transport = Interceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                .header("Location", "http://video.coolapk.com/clip.mp4?sign=a%2Bb").body("".toResponseBody()).build()
        })
        val media = GalleryMedia.fromUrl("https://image.coolapk.com/a-livepic@100x200.jpg", "42", "reply")
        val result = CoolapkRepository(client).livePhotoVideo(media)
        assertEquals("https://video.coolapk.com/clip.mp4?sign=a%2Bb", result)
        val request = requests.single()
        assertTrue(request.url.isHttps)
        assertEquals("reply_42", request.url.queryParameter("id"))
        assertEquals("http://image.coolapk.com/a-livepic@100x200.jpg", request.url.queryParameter("picUrl"))
        assertTrue(request.header("Cookie")!!.contains("synthetic-token"))
    }

    @Test fun missingOwnerAndUntrustedImageCannotTriggerAnApiRequest() = runTest {
        val h = Harness()
        assertTrue(runCatching { h.repo.livePhotoVideo(GalleryMedia.fromUrl("https://image.coolapk.com/a-livepic.jpg")) }.isFailure)
        assertTrue(runCatching { h.repo.livePhotoVideo(GalleryMedia.fromUrl("https://example.invalid/a-livepic.jpg", "42")) }.isFailure)
        assertTrue(h.transport.requests.isEmpty())
    }
}
