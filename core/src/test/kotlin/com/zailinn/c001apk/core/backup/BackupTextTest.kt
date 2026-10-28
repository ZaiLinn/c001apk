package com.zailinn.c001apk.core.backup

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BackupTextTest {
    @Test fun articleAndRepliesAndTopicsAreSearchableWithUnicodeNormalization() {
        val data = Json.parseToJsonElement("""{"id":"1","username":"作者","message_title":"标题","message":"摘要",
            "message_raw_output":"[{\"type\":\"text\",\"message\":\"<p>ＡＢＣ 正文</p><p>第二段</p>\"}]","tags":"#数码#"}""").jsonObject
        val backup = FeedBackup(data, listOf(Json.parseToJsonElement("""{"message":"回复内容","replyRows":[{"message":"子回复"}]}""").jsonObject))
        val text = BackupText.searchable(backup)
        listOf("abc", "正文", "第二段", "数码", "回复内容", "子回复", "作者").forEach { assertTrue(it, it in text) }
        assertFalse("摘要" in BackupText.body(backup))
        assertTrue(BackupText.body(backup).contains("\n"))
        assertEquals("标题", BackupText.title(backup))
        assertEquals(listOf("abc", "中文"), BackupText.terms(" ＡＢＣ   中文 "))
        assertEquals("%a\\%\\_\\\\b%", BackupText.like("a%_\\b"))
        assertFalse("回复内容" in BackupText.searchable(backup.copy(includesReplies = false)))
    }
    @Test fun searchIndexIsLimitedByUtf8BytesAndExportKeepsFullBody() {
        val data = buildJsonObject { put("id", "1"); put("message", "中".repeat(100_000)) }
        val backup = FeedBackup(data)
        assertTrue(BackupText.searchable(backup).toByteArray().size <= 200 * 1024)
        assertEquals(100_000, BackupText.body(backup).length)
    }
}
