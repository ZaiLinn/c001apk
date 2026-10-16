package com.zailinn.c001apk.core.net

import com.zailinn.c001apk.core.model.LoadResult
import com.zailinn.c001apk.core.upload.OssUploader
import com.zailinn.c001apk.core.upload.UploadImage
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Private message images, conversation pin/delete and event pages. */
class MessagingTest {
    private val prepared = """{"data":{
        "uploadPrepareInfo":{"accessKeyId":"k","accessKeySecret":"s","securityToken":"t","bucket":"coolapk-msg","endPoint":"https://oss.example.com"},
        "fileInfo":[{"uploadFileName":"message/202610/a.png"}]}}"""

    @Test
    fun messageImageGoesToTheMessageBucket() = runTest {
        val h = Harness { _, _ -> 200 to prepared }
        val oss = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            oss += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }).build()
        val path = OssUploader(h.repo, http).uploadMessageImage(UploadImage("a.png", 10, 20, byteArrayOf(1, 2, 3), "image/png"), "42")
        assertEquals("/message/202610/a.png", path)
        val form = h.transport.bodies.single()
        fun field(name: String) = form.substringAfter("name=\"$name\"").substringAfter("\r\n\r\n").substringBefore("\r\n")
        assertEquals("message", field("uploadBucket"))
        assertEquals("message", field("uploadDir"))
        assertEquals("42", field("toUid"))
        assertEquals("https://coolapk-msg.oss.example.com/message/202610/a.png", oss.single().url.toString())
        assertEquals("PUT", oss.single().method)
    }

    @Test
    fun messageImageOutsideTheMessageFolderIsRejected() = runTest {
        val h = Harness { _, _ -> 200 to prepared.replace("message/202610", "feed/202610") }
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }).build()
        val result = runCatching {
            OssUploader(h.repo, http).uploadMessageImage(UploadImage("a.png", 1, 1, byteArrayOf(1), "image/png"), "42")
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun conversationActionsUseTheConversationKey() = runTest {
        val h = Harness { _, _ -> 200 to """{"data":{"count":1}}""" }
        for (action in ConversationAction.entries) h.repo.conversationAction(action, "10_42")
        assertEquals(
            listOf("/v6/message/addTop", "/v6/message/removeTop", "/v6/message/deleteChat"),
            h.transport.requests.map { it.url.encodedPath },
        )
        assertTrue(h.transport.requests.all { it.url.queryParameter("ukey") == "10_42" })
    }

    @Test
    fun eventDetailChecksTheId() = runTest {
        val h = Harness { _, _ -> 200 to """{"data":{"id":"7","title":"活动"}}""" }
        assertTrue(h.repo.eventDetail("../7") is LoadResult.Error)
        assertTrue(h.transport.requests.isEmpty())
        val result = h.repo.eventDetail("7") as LoadResult.Success
        assertEquals("活动", result.value.string("title"))
        assertEquals("/v6/event/detail", h.transport.requests.single().url.encodedPath)
    }
}
