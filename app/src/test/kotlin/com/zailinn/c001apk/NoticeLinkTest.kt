package com.zailinn.c001apk

import com.zailinn.c001apk.ui.cards.noticeLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoticeLinkTest {
    @Test
    fun contentLinksWinOverTheSender() {
        // 酷安小秘书 and reply notices name the sender first, then the feed.
        assertEquals("/feed/123", noticeLink("""<a href="/u/10002">酷安小秘书</a>：你的动态<a href="/feed/123">今天天气</a>已被推荐"""))
        assertEquals(
            "https://www.coolapk.com/feed/9",
            noticeLink("""<a href="https://www.coolapk.com/u/5">@某人</a> 回复了你：<a href="https://www.coolapk.com/feed/9">查看</a>"""),
        )
    }

    @Test
    fun fallsBackToUserOrNothing() {
        assertEquals("/u/5", noticeLink("""<a href="/u/5">@某人</a> 关注了你"""))
        assertNull(noticeLink("""系统维护通知<a href="">详情</a><a href="javascript:void(0)">x</a>"""))
    }
}
