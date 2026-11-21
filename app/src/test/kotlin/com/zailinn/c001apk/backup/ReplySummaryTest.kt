package com.zailinn.c001apk.backup

import com.zailinn.c001apk.core.backup.FeedBackup
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplySummaryTest {
    private fun reply(id: String, vararg children: String) = buildJsonObject {
        put("id", id)
        put("replynum", 5)
        putJsonArray("replyRows") { children.forEach { add(buildJsonObject { put("id", it) }) } }
    }

    @Test fun noReplies() {
        assertEquals("此备份未包含回复", replySummary(FeedBackup(JsonObject(emptyMap()), includesReplies = false)))
    }

    @Test fun mainRepliesOnly() {
        val backup = FeedBackup(JsonObject(emptyMap()), listOf(reply("1"), reply("2")), includesReplies = true, commentLimit = 10)
        assertEquals("已备份 2 条主回复（设置上限 10 条，仅主回复）", replySummary(backup))
    }

    @Test fun nestedRepliesCounted() {
        val backup = FeedBackup(JsonObject(emptyMap()), listOf(reply("1", "3", "4")), includesReplies = true,
            commentLimit = 3, includesSubReplies = true)
        assertEquals("已备份 1 条主回复、2 条楼中楼（设置上限 3 条，含楼中楼）", replySummary(backup))
    }

    @Test fun legacyBackupHasNoLimit() {
        val backup = FeedBackup(JsonObject(emptyMap()), listOf(reply("1", "2")), includesReplies = true)
        assertEquals("已备份 1 条主回复、1 条楼中楼", replySummary(backup))
    }

    @Test fun remainingTimeAndSettingSummariesAreReadable() {
        assertEquals("12 秒", etaText(12))
        assertEquals("2 分 5 秒", etaText(125))
        assertEquals("1 小时 1 分", etaText(3700))
        assertEquals("原图", imageQualityLabel("Original"))
        assertEquals("", imageQualityLabel(""))
        val settings = com.zailinn.c001apk.data.AppSettings()
        assertEquals("关闭", autoBackupSummary(settings))
        assertEquals("关闭，仅保存帖子正文和图片", replyRuleSummary(settings))
        assertEquals("最多 50 条 · 主回复和楼中楼", replyRuleSummary(settings.copy(archiveIncludeReplies = true, archiveCommentLimit = 50, archiveIncludeSubReplies = true)))
    }
}
