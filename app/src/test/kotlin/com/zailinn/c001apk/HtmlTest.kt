package com.zailinn.c001apk

import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withAnnotation
import com.zailinn.c001apk.ui.common.HtmlPart
import com.zailinn.c001apk.ui.common.copyWithLinks
import com.zailinn.c001apk.ui.common.htmlToCopy
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

    @Test
    fun copiedTextKeepsWebLinkAddresses() {
        val html = """下载链接：<a href="https://pan.example.com/s/abc">查看链接</a><br/>提取码：ts <a href="/t/合成?type=8">#合成#</a>"""
        assertEquals("下载链接：查看链接（https://pan.example.com/s/abc）\n提取码：ts #合成#", htmlToCopy(html))
        assertEquals("见 https://a.com/x", htmlToCopy("""见 <a href="https://a.com/x">https://a.com/x</a>"""))
        assertEquals("见 a.com/x", htmlToCopy("""见 <a href="https://a.com/x">a.com/x</a>"""))
    }

    @Test
    fun selectionCopyAddsEachLinksOwnAddress() {
        val text = buildAnnotatedString {
            append("A：")
            withAnnotation("url", "https://pan.example.com/a") { append("查看链接") }
            append(" B：")
            withAnnotation("url", "https://pan.example.com/b") { append("查看链接") }
        }
        assertEquals("A：查看链接（https://pan.example.com/a） B：查看链接（https://pan.example.com/b）", copyWithLinks(text))
        assertEquals("链接（https://pan.example.com/b）", copyWithLinks(text.subSequence(text.length - 2, text.length)))
    }
}
