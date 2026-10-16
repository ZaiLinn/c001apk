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
    fun keywordsIgnoreSpacesAndCase() {
        assertEquals(FoldReason("晒对象", "晒女友"), all.match(feed(""""message":"今天来 晒 女友 了"""")))
        assertEquals("lsp", all.match(feed(""""message":"LSP 狂喜""""))?.hit)
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
