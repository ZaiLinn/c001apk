package com.zailinn.c001apk.core.backup

import com.zailinn.c001apk.core.model.ContentFilter
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.net.*
import com.zailinn.c001apk.core.protocol.ProtocolParams
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class FeedBackupTest {
    private fun datum(raw: String) = Datum(Json.parseToJsonElement(raw).jsonObject)

    @Test fun stableIdentityNeverCreatesEmptyOrUnsafeFilenames() {
        assertEquals("42", backupFeedId(datum("""{"id":"","fid":"42"}""")))
        assertEquals("43", backupFeedId(datum("""{"feed":{"id":"43"}}""")))
        assertEquals("44", backupFeedId(datum("""{"url":"https://www.coolapk.com/feed/44?from=share"}""")))
        assertNull(backupFeedId(datum("""{"id":"../../secret"}""")))
    }

    @Test fun imagesIncludeArticleBodyInlineImagesAvatarsAndAllNestedReplies() {
        val feed = datum("""{"id":"42","picArr":["http://image.coolapk.com/a.jpg.s.jpg"],
            "userInfo":{"userAvatar":"//avatar.example.com/me.png"},
            "message":"<img src='/inline.jpg'>",
            "message_raw_output":"[{\"type\":\"image\",\"url\":\"https://external.example.com/article.webp\"}]"}""").json
        val reply = datum("""{"id":"1","picArr":["https://image.coolapk.com/a.jpg","image.coolapk.com/b.jpg"],
            "replyRows":[{"id":"2","userAvatar":"https://avatar.example.com/other.png","pic":"/nested.gif"}]}""").json
        val backup = FeedBackup(feed, listOf(reply))
        val urls = backupImageUrls(backup)
        assertEquals(7, urls.size)
        assertTrue(urls.containsAll(listOf("https://image.coolapk.com/a.jpg", "https://image.coolapk.com/b.jpg",
            "https://image.coolapk.com/inline.jpg", "https://image.coolapk.com/nested.gif", "https://external.example.com/article.webp", "https://avatar.example.com/me.png")))
    }

    @Test fun legacyImagesFollowDocumentedOrderInsteadOfJsonFieldOrder() {
        val feed = datum("""{"id":"42","userInfo":{"userAvatar":"https://avatar.example.com/info.png"},
            "userAvatar":"https://avatar.example.com/me.png","picArr":["https://image.coolapk.com/body.jpg"]}""").json
        assertEquals(listOf("https://image.coolapk.com/body.jpg", "https://avatar.example.com/me.png", "https://avatar.example.com/info.png"),
            legacyBackupImageUrls(FeedBackup(feed)))
    }

    @Test fun offlineRewritePreservesPortableSourceAndArticleStructure() {
        val feed = datum("""{"id":"42","userAvatar":"http://image.coolapk.com/avatar.jpg",
            "picArr":["http://image.coolapk.com/a.jpg.s.jpg"],"message":"<img src='/inline.jpg'>",
            "imageUriList":[{"sourceUrl":"http://image.coolapk.com/a.jpg","liveVideoUrl":"https://cdn.example.com/live.mp4","livePhotoEnable":1}],
            "message_raw_output":"[{\"type\":\"image\",\"url\":\"https://image.coolapk.com/article.jpg\"}]"}""").json
        val source = FeedBackup(feed)
        val mapping = backupImageUrls(source).mapIndexed { index, url -> url to "${BACKUP_MEDIA_SCHEME}test/image_$index.jpg" }.toMap()
        val local = offlineBackup(source, mapping)
        assertEquals(mapping["https://image.coolapk.com/a.jpg"], local.data.picArr.single())
        assertEquals(mapping["https://image.coolapk.com/avatar.jpg"], local.data.userAvatar)
        assertTrue(local.data.message!!.contains(BACKUP_MEDIA_SCHEME))
        assertTrue(local.data.string("message_raw_output")!!.contains(BACKUP_MEDIA_SCHEME))
        assertFalse(local.data.json.toString().contains("live.mp4"))
        assertTrue(source.data.picArr.single().startsWith("http://"))
    }

    @Test fun importsBothDocumentedPayloadAndOlderBareApiResponse() {
        val old = FeedBackup.parse("""{"status":0,"data":{"fid":"42","message":"old"}}""")
        assertEquals("42", old.id)
        assertEquals(0, old.savedAt)
        val documented = FeedBackup.parse("""{"feed":{"data":{"id":"43"}},"replies":[{"id":"7"}]}""")
        assertEquals("43", documented.id)
        assertEquals(0, documented.savedAt)
        assertEquals(1, documented.replies.size)
        assertThrows(IllegalArgumentException::class.java) { FeedBackup.parse("""{"feed":{"data":{"id":"42"}},"formatVersion":99}""") }
    }

    @Test fun collectorPaginatesUnfilteredRepliesAndFetchesCompleteThreads() = runBlocking {
        val calls = mutableListOf<okhttp3.HttpUrl>()
        val session = SessionState()
        val transport = Interceptor { chain ->
            val request = chain.request(); calls += request.url
            val body = when {
                request.url.encodedPath.endsWith("/feed/detail") -> """{"data":{"id":"42","replynum":3}}"""
                request.url.queryParameter("page") != "1" -> """{"data":[]}"""
                request.url.queryParameter("feedType") == "feed_reply" -> """{"data":[{"entityType":"feed_reply","id":"11","uid":"9"},{"entityType":"feed_reply","id":"12","uid":"9"}]}"""
                else -> """{"data":[{"entityType":"feed_reply","id":"10","uid":"9","replynum":2,"replyRows":[{"id":"11","uid":"9"}]}]}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }
        val repository = CoolapkRepository(CoolapkClient(session, CoolapkHeaders(session, { ProtocolParams.generate() }), transport = transport)) {
            ContentFilter(blockedUsers = setOf("9"))
        }
        val backup = FeedBackupCollector(repository).collect("42")
        assertEquals(1, backup.replies.size)
        val root = Datum(backup.replies.single())
        assertEquals(listOf("11", "12"), root.replyRows.map { it.id })
        assertTrue(backup.warnings.isEmpty())
        assertEquals("10", calls.first { it.queryParameter("feedType") == "feed" && it.queryParameter("page") == "2" }.queryParameter("lastItem"))
        assertTrue(calls.any { it.queryParameter("feedType") == "feed_reply" && it.queryParameter("page") == "2" })
    }

    @Test fun repeatedPagesStopAndFailuresAreMarkedPartial() = runBlocking {
        val session = SessionState()
        val transport = Interceptor { chain ->
            val request = chain.request()
            val body = if (request.url.encodedPath.endsWith("/feed/detail")) """{"data":{"id":"42","replynum":100}}"""
                else """{"data":[{"entityType":"feed_reply","id":"10"}]}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }
        val repository = CoolapkRepository(CoolapkClient(session, CoolapkHeaders(session, { ProtocolParams.generate() }), transport = transport))
        val backup = FeedBackupCollector(repository).collect("42")
        assertEquals(1, backup.replies.size)
        assertTrue(backup.warnings.any { it.contains("重复") })
    }
}
