package com.zailinn.c001apk

import com.zailinn.c001apk.ui.common.emojiGroups
import com.zailinn.c001apk.ui.common.emojiMap
import com.zailinn.c001apk.ui.screens.other.feedIdFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingUiTest {
    @Test
    fun feedLinksAndIdsAreRecognised() {
        assertEquals("123", feedIdFrom(" 123 "))
        assertEquals("456", feedIdFrom("https://www.coolapk.com/feed/456?shareKey=x"))
        assertEquals("789", feedIdFrom("coolapk.com/feed/789"))
        assertEquals("1", feedIdFrom("https://m.coolapk.com/feed/1/"))
        assertNull(feedIdFrom("https://example.com/feed/1"))
        assertNull(feedIdFrom("abc"))
        assertNull(feedIdFrom(""))
    }

    @Test
    fun emojiTabsCoverEveryImageOnce() {
        val groups = emojiGroups().toMap()
        assertEquals(listOf("常用", "牛牛", "酷币"), emojiGroups().map { it.first })
        assertEquals(32, groups.getValue("牛牛").size)
        assertTrue(groups.getValue("酷币").isNotEmpty())
        val all = groups.values.flatten()
        assertEquals(all.size, all.toSet().size)
        assertEquals(emojiMap.filterValues { it.endsWith(".png") }.keys, all.toSet())
    }
}
