package com.zailinn.c001apk

import com.zailinn.c001apk.ui.common.HtmlPart
import com.zailinn.c001apk.ui.common.htmlToPlain
import com.zailinn.c001apk.ui.common.parseHtml
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HtmlTest {
    @Test
    fun linksBreaksAndEntities() {
        val parts = parseHtml("""前<a class="feed-link-tag" href="/t/合成?type=8">#合成#</a> &amp; &lt;b&gt;<br/>下一行<a href=/u/10>@作者</a>""")
        assertEquals(
            listOf(
                HtmlPart.Text("前", null),
                HtmlPart.Text("#合成#", "/t/合成?type=8"),
                HtmlPart.Text(" & <b>", null),
                HtmlPart.Break,
                HtmlPart.Text("下一行", null),
                HtmlPart.Text("@作者", "/u/10"),
            ),
            parts,
        )
    }

    @Test
    fun plainTextKeepsLineBreaksAndLooseAngleBrackets() {
        assertEquals("a < b\n1小时前", htmlToPlain("a < b<br />1小时前"))
        assertEquals("来自 合成手机", htmlToPlain("""来自 <span class="x">合成手机</span>"""))
        assertEquals("纯文本", htmlToPlain("纯文本"))
    }

    @Test
    fun emptyLinkStaysClickableAsTheCard() {
        assertEquals(listOf(HtmlPart.Text("查看更多", "")), parseHtml("<a href=\"\">查看更多</a>"))
    }
}
