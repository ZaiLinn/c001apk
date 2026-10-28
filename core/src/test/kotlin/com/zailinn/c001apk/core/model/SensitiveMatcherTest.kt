package com.zailinn.c001apk.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensitiveMatcherTest {
    private val all = SensitiveMatcher(SensitiveCategory.builtIn)

    private fun feed(json: String) = Datum(Json.parseToJsonElement("""{"entityType":"feed","id":"1",$json}""") as JsonObject)

    @Test
    fun keywordsIgnoreCaseAndExtraSpaces() {
        assertEquals(FoldReason("晒对象", "晒女友"), all.match(feed(""""message":"今天来晒女友了"""")))
        assertEquals("性压抑/擦边", all.match(feed(""""message":"想找个女\u200B朋友   啊""""))?.title)
        assertEquals("lsp", all.match(feed(""""message":"LSP 狂喜""""))?.hit)
    }

    @Test
    fun latinKeywordsMatchOnlyWholeWords() {
        assertNull(all.match(feed(""""message":"LSPosed 模块装好了，lspatch 也能用"""")))
        assertEquals("lsp", all.match(feed(""""message":"lsp yyds""""))?.hit)
        assertEquals("lsp", all.match(feed(""""message":"老lsp了""""))?.hit)
        assertEquals("约p", all.match(feed(""""message":"想约p""""))?.hit)
    }

    @Test
    fun spacesKeepWordsApart() {
        // Removing spaces once made "黑色 图片" read as 色图.
        assertNull(all.match(feed(""""message":"晒 女友"""")))
        assertNull(all.match(feed(""""message":"选黑色 图片好看还是白色"""")))
    }

    @Test
    fun ordinaryTechPostsNoLongerFold() {
        listOf(
            "安装时权限被拒绝了", "特色图标包分享", "白色色温偏冷，蓝色色调好看",
            "冷得瑟瑟发抖", "求对象存储方案", "给我老婆买什么手机好", "表白小米", "这件小姐姐同款",
        ).forEach { assertNull(it, all.match(feed(""""message":"$it""""))) }
        assertEquals("分手", all.match(feed(""""message":"昨天和她分手了""""))?.hit)
        assertEquals("找对象", all.match(feed(""""message":"家里催我找对象""""))?.hit)
    }

    @Test
    fun strictWordsOnlyInStrictMode() {
        val post = feed(""""message":"这个小姐姐拍照好看"""")
        assertNull(all.match(post))
        assertEquals("小姐姐", SensitiveMatcher(SensitiveCategory.builtIn, strict = true).match(post)?.hit)
    }

    @Test
    fun authorsFoldEverythingTheyPost() {
        val matcher = SensitiveMatcher(listOf(SensitiveCategory("own", "自定义", authors = listOf("42"))))
        val post = Datum(Json.parseToJsonElement("""{"entityType":"feed","id":"1","uid":"42","username":"某人","message":"今天天气不错"}""") as JsonObject)
        assertEquals(FoldReason("自定义", "用户 某人"), matcher.match(post))
        assertNull(matcher.match(feed(""""uid":"7","message":"今天天气不错"""")))
    }

    @Test
    fun exceptionWordsClearOnlyTheirOwnSentence() {
        assertNull(all.match(feed(""""message":"举报这个约炮引流的号"""")))
        assertEquals("约炮", all.match(feed(""""message":"举报了一个号。有人想约炮吗""""))?.hit)
    }

    @Test
    fun requestedTopicsMatchTagsAndHashText() {
        assertEquals(FoldReason("相亲恋爱", "#相亲#"), all.match(feed(""""tags":"相亲","message":"周末见了一个"""")))
        assertEquals(FoldReason("性压抑/擦边", "#深夜话题#"), all.match(feed(""""message":"<a href=\"/t/x\">#深夜话题#</a> 睡不着"""")))
        val related = feed(""""message":"聊聊","relationRows":[{"title":"我在酷安晒女友"}]""")
        assertEquals("#我在酷安晒女友#", all.match(related)?.hit)
        // #酷安夜话# as a whole topic is no longer folded (2.0.3).
        assertNull(all.match(feed(""""message":"#酷安夜话# 今天天气不错","tags":"酷安夜话"""")))
    }

    @Test
    fun patternsCatchLongerPhrasings() {
        assertEquals("晒对象", all.match(feed(""""message":"我女朋友说这个手机好看""""))?.title)
        assertEquals("性压抑/擦边", all.match(feed(""""message":"好想找个女朋友啊""""))?.title)
    }

    @Test
    fun ordinaryPostsStayVisible() {
        assertNull(all.match(feed(""""message":"我老婆 小米 15 续航真不错"""")))
        assertNull(all.match(feed(""""message":"刚刷了新系统，流畅很多","tags":"玩机"""")))
        // Only feeds and replies fold; app and topic cards never do.
        assertNull(all.match(Datum(Json.parseToJsonElement("""{"entityType":"topic","title":"相亲"}""") as JsonObject)))
    }

    @Test
    fun disabledCategoriesAndCustomWords() {
        val mine = SensitiveCategory("mine", "自定义", keywords = listOf("内卷", " "), tags = listOf("#打工人#"), patterns = listOf("(坏正则"))
        val custom = SensitiveMatcher(SensitiveCategory.builtIn.filter { it.id == "gender" } + mine)
        assertNull(custom.match(feed(""""message":"晒女友"""")))
        assertEquals(FoldReason("自定义", "内卷"), custom.match(feed(""""message":"又在内卷"""")))
        assertEquals(FoldReason("自定义", "#打工人#"), custom.match(feed(""""tags":"打工人"""")))
        // Blank words and broken user patterns are ignored instead of failing.
        assertEquals(true, SensitiveMatcher(listOf(SensitiveCategory("x", "x", keywords = listOf(" "), patterns = listOf("(")))).isEmpty)
    }

    @Test
    fun markedItemsCarryTheirReason() {
        val filter = ContentFilter(sensitive = all)
        val marked = filter.mark(feed(""""message":"撒狗粮了""""))
        assertEquals(FoldReason("晒对象", "撒狗粮"), marked.foldReason)
        assertNull(filter.mark(feed(""""message":"普通内容"""")).foldReason)
        assertNull(ContentFilter().mark(feed(""""message":"撒狗粮了"""")).foldReason)
    }
}
