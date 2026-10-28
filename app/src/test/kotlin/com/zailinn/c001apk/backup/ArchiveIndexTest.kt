package com.zailinn.c001apk.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zailinn.c001apk.core.backup.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArchiveIndexTest {
    @Test fun andSearchEscapesWildcardsAndSeparatesSourcesAndAccounts() {
        val index = ArchiveIndex(ApplicationProvider.getApplicationContext<Context>())
        fun backup(id: String, message: String) = FeedBackup(buildJsonObject { put("id", id); put("message", message) })
        try {
            index.put(backup("1", "ＡＢＣ 中文 100% _tag \\path"), "feed_1", 1, 1)
            index.put(backup("2", "abc 其他 1000 tags"), "feed_2", 1, 1)
            index.source("1", "local")
            index.source("1", "cloud", "account-a", "collection-1", "数码")
            index.source("2", "cloud", "account-b", "collection-2", "随笔")
            assertEquals(listOf("1"), index.search("abc 中文").map { it.record.feed.id })
            for (term in listOf("%", "_", "\\")) assertEquals(listOf("1"), index.search(term).map { it.record.feed.id })
            assertEquals(1, index.search("", "cloud", "account-a").size)
            assertTrue(index.search("中文", "cloud", "account-b").isEmpty())
            assertTrue(index.search("", "cloud", "account-a", "collection-2").isEmpty())
            assertEquals(1, index.markRemoved("account-a", "collection-1", emptySet()))
            assertEquals(1, index.records().count { it.feed.id == "1" })
            assertTrue(index.sources("1").any { it["removed_at"].orEmpty().isNotEmpty() })
            assertEquals(1, index.search("中文", "local").size)
            index.state("1", "gone")
            assertEquals("gone", index.search("中文").single().onlineState)
            index.put(backup("3", "ABC ABC 中文"), "feed_3", 1, 1)
            assertEquals(listOf("3", "1"), index.search("abc 中文").map { it.record.feed.id })
        } finally { index.close() }
    }
}
