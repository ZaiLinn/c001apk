package com.zailinn.c001apk

import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withAnnotation
import com.zailinn.c001apk.ui.common.HtmlPart
import com.zailinn.c001apk.ui.common.copyWithLinks
import com.zailinn.c001apk.ui.common.htmlToCopy
import com.zailinn.c001apk.ui.common.htmlToPlain
import com.zailinn.c001apk.ui.common.markdownToHtml
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

    /** Text blocks of the 图文 feed 74399579 (酷周报 2642): Markdown with `<!--break-->` markers. */
    @Test
    fun articleMarkdownBecomesHeadingsAndBold() {
        val block = "阶跃终端带来品牌首款智能体手机。\n\n**周五时间到，搬好小板凳<!--break-->，咱们一起闲聊本周新鲜事～**\n\n# 💫苹果发布HomeView等多款智能家居新品\n\n售价299美元"
        assertEquals(
            listOf(
                HtmlPart.Text("阶跃终端带来品牌首款智能体手机。", null),
                HtmlPart.Break, HtmlPart.Break,
                HtmlPart.Text("周五时间到，搬好小板凳，咱们一起闲聊本周新鲜事～", null, bold = true),
                HtmlPart.Break, HtmlPart.Break,
                HtmlPart.Text("💫苹果发布HomeView等多款智能家居新品", null, heading = 1),
                HtmlPart.Break, HtmlPart.Break,
                HtmlPart.Text("售价299美元", null),
            ),
            parseHtml(markdownToHtml(block)),
        )
    }

    @Test
    fun markdownLinksListsQuotesAndItalic() {
        assertEquals(
            listOf(HtmlPart.Text("下载：", null), HtmlPart.Text("网盘", "https://pan.example.com/s/a_b")),
            parseHtml(markdownToHtml("下载：[网盘](https://pan.example.com/s/a_b)")),
        )
        assertEquals("• 第一项\n  • 子项\n1. 有序", htmlToPlain(markdownToHtml("- 第一项\n  * 子项\n1. 有序")))
        assertEquals(listOf(HtmlPart.Text("▎", null), HtmlPart.Text("引用", null, italic = true)), parseHtml(markdownToHtml("> 引用")))
        assertEquals(listOf(HtmlPart.Text("很", null, italic = true), HtmlPart.Text(" 2*3*4 不变", null)), parseHtml(markdownToHtml("*很* 2*3*4 不变")))
    }
}
