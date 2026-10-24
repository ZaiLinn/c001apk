package com.zailinn.c001apk

import com.zailinn.c001apk.data.CaptchaLog
import com.zailinn.c001apk.data.CaptchaRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptchaLogTest {
    private fun record(time: Long, path: String, outcome: String) =
        CaptchaRecord(time, "GET", path, signedIn = true, cookieProtocol = true, outcome = outcome, sincePreviousMs = 300, inLastMinute = 12, sinceStart = 40)

    @Test
    fun summaryCountsByEndpointAndOutcome() {
        val day = 24 * 3_600_000L
        val records = listOf(
            record(0, "/v6/feed/detail", "skipped"),
            record(2 * day, "/v6/feed/replyList", "skipped"),
            record(2 * day + 1, "/v6/feed/replyList", "rejected"),
        )
        val summary = CaptchaLog.summary(records)
        assertEquals("最近 24 小时 2 次", summary[0])
        assertTrue(summary[1].startsWith("按接口：GET /v6/feed/replyList 2 次"))
        assertTrue(summary[2].contains("通过后仍被拦截 1 次"))
    }

    @Test
    fun reportListsNewestFirstWithoutSecrets() {
        val report = CaptchaLog.report(listOf(record(0, "/v6/feed/detail", "cancelled"), record(1000, "/v6/feed/replyList", "submitted")))
        val lines = report.lines()
        val detail = lines.indexOfFirst { "/v6/feed/detail" in it && "已取消" in it }
        val replies = lines.indexOfFirst { "/v6/feed/replyList" in it && "验证通过" in it }
        assertTrue(replies in 1 until detail)
        assertTrue(report.contains("距上个请求 0.3 秒"))
        assertTrue(report.contains("1 分钟内 12 个请求"))
        assertFalse(report.contains("token"))
        assertTrue(CaptchaLog.summary(emptyList()).isEmpty())
    }
}
