package com.zailinn.c001apk.core.backup

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class ArchiveExportTest {
    @Test fun textFormatIsReadableAndHtmlCannotInjectScriptsOrRemoteImages() {
        val backup = FeedBackup(buildJsonObject {
            put("id", "123"); put("username", "<作者>"); put("dateline", 0)
            put("message", "<p>第一段</p><p>第二段</p><script>alert(1)</script><img src='https://image.coolapk.com/a.jpg' onerror='alert(1)'><a href='javascript:alert(1)'>危险</a><a href='/feed/2'>帖子</a>")
        })
        val text = ArchiveExport.txt(backup, listOf("本地收藏"), zone = TimeZone.getTimeZone("UTC"))
        assertTrue(text.startsWith("==============================\n<作者> · 1970-01-01 00:00\nhttps://www.coolapk.com/feed/123\n来源：本地收藏\n"))
        assertTrue(text.contains("第一段\n第二段"))
        val html = ArchiveExport.header(listOf("#feed_123" to "<标题>")) + ArchiveExport.article(backup, images = mapOf("https://image.coolapk.com/a.jpg" to "images/123_1.jpg")) + ArchiveExport.footer
        assertFalse(html.contains("<script")); assertFalse(html.contains("onerror")); assertFalse(html.contains("javascript:"))
        assertTrue(html.contains("&lt;作者&gt;")); assertTrue(html.contains("images/123_1.jpg")); assertTrue(html.contains("https://www.coolapk.com/feed/2"))
        assertFalse(ArchiveExport.article(backup).contains("<img"))
        assertFalse(ArchiveExport.clean("<img src='x'>", mapOf("x" to "../secret")).contains("<img"))
    }
}
