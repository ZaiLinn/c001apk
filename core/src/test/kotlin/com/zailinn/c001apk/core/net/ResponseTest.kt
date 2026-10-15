package com.zailinn.c001apk.core.net

import com.zailinn.c001apk.core.model.ContentFilter
import com.zailinn.c001apk.core.model.LoadResult
import com.zailinn.c001apk.core.model.SensitiveCategory
import com.zailinn.c001apk.core.model.SensitiveMatcher
import com.zailinn.c001apk.core.model.cursorFirstId
import com.zailinn.c001apk.core.model.cursorLastId
import com.zailinn.c001apk.core.model.foldReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class ResponseTest {
    private fun repo(code: Int = 200, body: String, filter: ContentFilter = ContentFilter()): CoolapkRepository {
        val session = SessionState()
        val client = CoolapkClient(session, CoolapkHeaders(session, { syntheticParams }), transport = FakeTransport { _, _ -> code to body })
        return CoolapkRepository(client) { filter }
    }

    private fun failureOf(body: String): ApiFailure =
        runCatching { checkApiObject(lenientJson.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject) }
            .exceptionOrNull() as ApiFailure

    @Test
    fun listCardsExpandAndKeepBlocking() = runTest {
        val body = """{"data":[{"entityType":"card","entityTemplate":"listCard","entities":[
            {"entityType":"feed","id":"1","uid":"10","message":"合成内容"},
            {"entityType":"feed","id":"2","uid":"99"},
            {"entityType":"card","entityTemplate":"listCard","entities":[{"entityType":"apk","id":"3","title":"合成应用"}]}]}]}"""
        val state = repo(body = body, filter = ContentFilter(blockedUsers = setOf("99"))).dataListFromUrl("/v6/search")
        assertEquals(listOf("1", "3"), (state as LoadResult.Success).value.map { it.id })
    }

    @Test
    fun topicKeywordsAreFiltered() = runTest {
        val body = """{"data":[{"entityType":"feed","id":"1","tags":"合成话题"},{"entityType":"feed","id":"2"}]}"""
        val state = repo(body = body, filter = ContentFilter(blockedTopics = listOf("合成"))).dataListFromUrl("/x")
        assertEquals(listOf("2"), (state as LoadResult.Success).value.map { it.id })
    }

    @Test
    fun blockedLastItemStillAdvancesTheCursor() = runTest {
        val body = """{"data":[{"entityType":"feed","id":"1","uid":"99"},{"entityType":"feed","id":"2","uid":"99"},{"entityType":"unknownAd","id":"9"}]}"""
        val items = (repo(body = body, filter = ContentFilter(blockedUsers = setOf("99"))).dataListFromUrl("/x") as LoadResult.Success).value
        assertTrue(items.isEmpty())
        assertEquals("1", items.cursorFirstId)
        assertEquals("2", items.cursorLastId)
    }

    @Test
    fun sensitiveFeedsAreFoldedNotRemoved() = runTest {
        val body = """{"data":[{"entityType":"feed","id":"1","message":"晒女友"},{"entityType":"feed","id":"2","message":"刷机"}]}"""
        val filter = ContentFilter(sensitive = SensitiveMatcher(SensitiveCategory.builtIn))
        val items = (repo(body = body, filter = filter).dataListFromUrl("/x") as LoadResult.Success).value
        assertEquals(listOf("晒对象", null), items.map { it.foldReason?.title })
    }

    @Test
    fun hiddenSensitiveFeedsAreRemovedButPagingContinues() = runTest {
        val body = """{"data":[{"entityType":"feed","id":"1","message":"刷机"},{"entityType":"feed","id":"2","message":"晒女友"}]}"""
        val filter = ContentFilter(sensitive = SensitiveMatcher(SensitiveCategory.builtIn), hideSensitive = true)
        val items = (repo(body = body, filter = filter).dataListFromUrl("/x") as LoadResult.Success).value
        assertEquals(listOf("1"), items.map { it.id })
        assertEquals("2", items.cursorLastId)
    }

    @Test
    fun businessErrorsShowTheServerReason() = runTest {
        val missing = repo(body = """{"status":-1,"error":-1,"message":"用户不存在","messageStatus":-1}""").dataFromUrl("/x")
        assertEquals("用户不存在", (missing as LoadResult.Error).message)
        // Markup is dropped; messages without readable text fall back to the generic one.
        val tagged = repo(body = """{"status":-1,"message":"<b>该用户已注销</b>"}""").dataFromUrl("/x")
        assertEquals("该用户已注销", (tagged as LoadResult.Error).message)
        val code = repo(body = """{"status":-1,"message":"err_unknown_42"}""").dataFromUrl("/x")
        assertEquals("服务器未能完成请求，请稍后重试", (code as LoadResult.Error).message)
    }

    @Test
    fun negativeStatusIsAnError() = runTest {
        assertTrue(repo(body = """{"status":-1}""").dataFromUrl("/x") is LoadResult.Error)
    }

    @Test
    fun failureKindsRemainDistinguishable() {
        assertTrue(failureOf("""{"status":-10,"messageStatus":-10,"message":"检测到您的网络环境可能异常","data":null}""").message.contains("网络环境"))
        assertTrue(failureOf("""{"status":500,"messageStatus":"err_request_need_upgrade_new_version","data":null}""").message.contains("客户端"))
        assertEquals(ApiFailureKind.Captcha, failureOf("""{"messageStatus":"err_request_captcha_v2","data":null}""").kind)
        assertEquals(ApiFailureKind.Authentication, failureOf("""{"messageStatus":"err_not_login","data":null}""").kind)
        assertEquals(ApiFailureKind.RateLimit, failureOf("""{"messageStatus":"err_rate_limit","data":null}""").kind)
        assertEquals(ApiFailureKind.InvalidResponse, failureOf("""{"status":1}""").kind)
    }

    @Test
    fun informationalMessageKeepsData() = runTest {
        val state = repo(body = """{"message":"提示","data":{"id":"1"}}""").dataFromUrl("/x")
        assertEquals("1", (state as LoadResult.Success).value.id)
    }

    @Test
    fun messageOnlyFailureIsLoginError() = runTest {
        val state = repo(body = """{"message":"请先登录","data":null}""").dataFromUrl("/x")
        assertTrue((state as LoadResult.Error).message.contains("登录"))
    }

    @Test
    fun htmlProducesChineseResponseError() = runTest {
        val state = repo(body = "<html></html>").dataFromUrl("/x")
        assertTrue((state as LoadResult.Error).message.contains("响应"))
    }

    @Test
    fun httpStatusIsReported() = runTest {
        val state = repo(code = 403, body = "{}").dataFromUrl("/x")
        assertTrue((state as LoadResult.Error).message.contains("403"))
    }

    @Test
    fun validEmptyListIsEmpty() = runTest {
        assertEquals(LoadResult.Empty, repo(body = """{"data":[]}""").dataListFromUrl("/x"))
    }

    @Test
    fun transportErrorsDoNotExposeDetails() {
        val failure = ApiFailure.from(SocketTimeoutException("https://api.coolapk.com/?token=synthetic-secret"))
        assertTrue(failure.message.contains("超时"))
        assertTrue(!failure.message.contains("synthetic-secret"))
    }
}
