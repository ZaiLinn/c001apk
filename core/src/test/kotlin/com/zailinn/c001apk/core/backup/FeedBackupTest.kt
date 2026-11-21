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

    @Test fun backupsNeverRequestOrRetainCommentsEvenWithOldOptions() = runBlocking {
        val h = Harness { _, _ -> 200 to """{"data":{"id":"42","message":"完整正文","replynum":999,
            "picArr":["https://image.coolapk.com/body.png"],
            "topReplyRows":[{"id":"10","message":"置顶评论","picArr":["https://image.coolapk.com/comment.png"]}],
            "hotReplyRows":[{"id":"11"}],"replyMeRows":[{"id":"12"}],"replyRows":[{"id":"13"}],
            "relationRows":[{"replyRows":[{"message":"嵌套评论"}]}]}}""" }
        val backup = FeedBackupCollector(h.repo).collect("42", BackupOptions(replies = true))
        assertTrue(backup.replies.isEmpty())
        assertFalse(backup.includesReplies)
        assertTrue(backup.warnings.isEmpty())
        assertFalse(backup.feed.toString().contains("评论"))
        assertEquals(listOf("https://image.coolapk.com/body.png"), backupImageUrls(backup))
        assertEquals(listOf("/v6/feed/detail"), h.transport.requests.map { it.url.encodedPath })
    }

    @Test fun completePublicPostCanBeBackedUpWithoutSzlmOrCaptcha() = runBlocking {
        val h = Harness { request, _ -> 200 to if (request.url.host == "www.coolapk.com")
            """{"data":{"id":"42","message":"网页版完整正文","replynum":100}}"""
            else """{"messageStatus":"err_request_captcha_v2","data":null}""" }
        var prompts = 0
        h.client.captchaVerifier = { prompts++; null }
        val backup = FeedBackupCollector(h.repo).collect("42")
        assertEquals("网页版完整正文", backup.data.message)
        assertEquals(0, prompts)
        assertEquals(2, h.transport.requests.size)
        assertNull(h.transport.requests.last().header("Cookie"))
        assertTrue(backup.replies.isEmpty())
    }

    @Test fun summariesAreNeverSavedAsCompletePosts() = runBlocking {
        val h = Harness { _, _ -> 200 to """{"data":{"id":"42","message":"摘要","issummary":1}}""" }
        val failure = runCatching { FeedBackupCollector(h.repo).collect("42") }.exceptionOrNull()
        assertTrue(failure is BackupRestrictedException)
        // A guest sees a summary because of the missing account, not the device.
        assertTrue((failure as BackupRestrictedException).needsLogin)
    }

    @Test fun summaryForAnAccountAsksForAnIdentityNotALogin() = runBlocking {
        val h = Harness { _, _ -> 200 to """{"data":{"id":"42","message":"摘要","issummary":1}}""" }
        h.session.activate(com.zailinn.c001apk.core.net.AccountCredentials("synthetic-uid", "u", "synthetic-token"), notify = true)
        val failure = runCatching { FeedBackupCollector(h.repo).collect("42") }.exceptionOrNull()
        assertFalse((failure as BackupRestrictedException).needsLogin)
    }

    @Test fun expiredLoginAsksForLoginNotAnIdentity() = runBlocking {
        val h = Harness { request, _ -> 200 to if (request.url.host == "www.coolapk.com")
            "<html><body>请用酷安手机APP扫码查看详情</body></html>"
            else """{"status":-1,"message":"请先登录","messageStatus":"err_request_login","data":null}""" }
        val failure = runCatching { FeedBackupCollector(h.repo).collect("42") }.exceptionOrNull()
        assertTrue((failure as BackupRestrictedException).needsLogin)
    }

    @Test fun restrictedApiAndPublicPageRequestAnIdentityWithoutPromptingCaptcha() = runBlocking {
        val h = Harness { request, _ -> 200 to if (request.url.host == "www.coolapk.com")
            "<html><body>请用酷安手机APP扫码查看详情</body></html>"
            else """{"messageStatus":"err_request_captcha_v2","data":null}""" }
        var prompts = 0
        h.client.captchaVerifier = { prompts++; null }
        val failure = runCatching { FeedBackupCollector(h.repo).collect("42") }.exceptionOrNull()
        assertFalse((failure as BackupRestrictedException).needsLogin)
        assertEquals(0, prompts)
    }

    @Test fun networkFailureDoesNotRequestAnIdentity() = runBlocking {
        val h = Harness { _, _ -> throw java.io.IOException("synthetic offline") }
        val failure = runCatching { FeedBackupCollector(h.repo).collect("42") }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
        assertFalse(failure is BackupRestrictedException)
    }

    @Test fun optionalCommentsRespectTheLimitPrioritizePinnedAndExcludeNestedReplies() = runBlocking {
        val h = Harness { request, _ -> 200 to if (request.url.encodedPath.endsWith("/detail"))
            """{"data":{"id":"42","message":"正文","topReplyRows":[{"id":"10","message":"置顶","replyRows":[{"id":"99","message":"楼中楼"}]}]}}"""
            else """{"data":[{"id":"10","entityType":"feed_reply","message":"置顶"},{"id":"11","entityType":"feed_reply","message":"第二条","replyRows":[{"id":"98"}]},{"id":"12","entityType":"feed_reply","message":"超出数量"}]}""" }
        val backup = FeedBackupCollector(h.repo).collect("42", BackupOptions(replies = true, commentLimit = 2))
        assertEquals(listOf("10", "11"), backup.replies.map { Datum(it).id })
        assertTrue(backup.replies.all { Datum(it).replyRows.isEmpty() })
        assertEquals(2, backup.commentLimit)
        assertTrue(backup.includesReplies)
        assertEquals(2, h.transport.requests.size)
        assertTrue(backup.warnings.isEmpty())
    }

    @Test fun pinnedCommentsCanSatisfyTheLimitWithoutCallingTheCommentApi() = runBlocking {
        val h = Harness { _, _ -> 200 to """{"data":{"id":"42","topReplyRows":[{"id":"10","message":"置顶"}]}}""" }
        val backup = FeedBackupCollector(h.repo).collect("42", BackupOptions(replies = true, commentLimit = 1))
        assertEquals(1, backup.replies.size)
        assertEquals(1, h.transport.requests.size)
    }

    @Test fun restrictedOptionalCommentsDoNotDiscardOrPauseThePostBackup() = runBlocking {
        val h = Harness { request, _ -> 200 to if (request.url.encodedPath.endsWith("/detail"))
            """{"data":{"id":"42","message":"正文"}}"""
            else """{"messageStatus":"err_request_captcha_v2","data":null}""" }
        var prompts = 0
        h.client.captchaVerifier = { prompts++; null }
        val backup = FeedBackupCollector(h.repo).collect("42", BackupOptions(replies = true, commentLimit = 3))
        assertEquals("正文", backup.data.message)
        assertTrue(backup.replies.isEmpty())
        assertTrue(backup.warnings.single().contains("读取主回复失败"))
        assertEquals(0, prompts)
    }

    @Test fun nestedRepliesShareTheSameTotalLimitAndDoNotTriggerMissingReplyWarnings() = runBlocking {
        val h = Harness { request, _ -> 200 to if (request.url.encodedPath.endsWith("/detail"))
            """{"data":{"id":"42","replynum":999,"topReplyRows":[{"id":"10","replynum":100,"replyRows":[{"id":"11","message":"子评论一"},{"id":"12","message":"子评论二"}]}]}}"""
            else error("The limit must be satisfied without another request") }
        val backup = FeedBackupCollector(h.repo).collect("42", BackupOptions(replies = true, commentLimit = 3, includeSubReplies = true))
        assertEquals(1, backup.replies.size)
        assertEquals(listOf("11", "12"), Datum(backup.replies.single()).replyRows.map { it.id })
        assertTrue(backup.warnings.isEmpty())
        assertEquals(1, h.transport.requests.size)
    }

    @Test fun nestedPagesFillOnlyTheRemainingBudget() = runBlocking {
        val h = Harness { request, _ -> 200 to when {
            request.url.encodedPath.endsWith("/detail") -> """{"data":{"id":"42","replynum":999,"topReplyRows":[{"id":"10","replynum":10,"replyRows":[{"id":"11","message":"预览"}]}]}}"""
            request.url.queryParameter("feedType") == "feed_reply" -> """{"data":[{"id":"11","entityType":"feed_reply"},{"id":"12","entityType":"feed_reply"},{"id":"13","entityType":"feed_reply"},{"id":"14","entityType":"feed_reply"}]}"""
            else -> error("Nested replies should fill the limit before loading main replies")
        } }
        val backup = FeedBackupCollector(h.repo).collect("42", BackupOptions(replies = true, commentLimit = 4, includeSubReplies = true))
        assertEquals(listOf("11", "12", "13"), Datum(backup.replies.single()).replyRows.map { it.id })
        assertTrue(backup.warnings.isEmpty())
        assertEquals(2, h.transport.requests.size)
    }

    @Test fun fewerVisibleRepliesThanTheServerCountIsNotARequestFailure() = runBlocking {
        val h = Harness { request, _ -> 200 to if (request.url.encodedPath.endsWith("/detail"))
            """{"data":{"id":"42","replynum":999}}""" else """{"data":[]}""" }
        val backup = FeedBackupCollector(h.repo).collect("42", BackupOptions(replies = true, commentLimit = 10))
        assertTrue(backup.replies.isEmpty())
        assertTrue(backup.warnings.isEmpty())
    }
}
