package com.zailinn.c001apk.core.semantic

import com.zailinn.c001apk.core.model.ContentFilter
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.SensitiveCategory
import com.zailinn.c001apk.core.model.SensitiveMatcher
import com.zailinn.c001apk.core.model.foldReason
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticMatcherTest {
    @Test
    fun tokenizerSplitsCjkPunctuationAndWordPieces() {
        val vocab = listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "晒", "女", "友", "app", "##s", "，", "hello")
        val tokenizer = BertTokenizer(vocab)
        assertArrayEquals(intArrayOf(2, 4, 5, 6, 9, 7, 8, 10, 1, 3), tokenizer.encode("晒女友，Apps hello!"))
        // Truncation keeps [CLS] and [SEP].
        assertArrayEquals(intArrayOf(2, 4, 5, 3), tokenizer.encode("晒女友", maxLength = 4))
        assertArrayEquals(intArrayOf(2, 3), tokenizer.encode(" ​ "))
    }

    /** Each character adds to a fixed slot, so texts sharing characters are similar. */
    private class FakeRuntime : EmbeddingRuntime {
        var calls = 0
        override fun hiddenStates(inputIds: Array<LongArray>, attentionMask: Array<LongArray>): Array<Array<FloatArray>> {
            calls++
            return Array(inputIds.size) { row ->
                Array(inputIds[row].size) { col ->
                    FloatArray(256).also { it[(inputIds[row][col] % 256).toInt()] = attentionMask[row][col].toFloat() }
                }
            }
        }
        override fun close() = Unit
    }

    private val chars = (SemanticAnchors.byCategory.values.flatten() + SemanticAnchors.everyday + "，。")
        .joinToString("").toSet().map(Char::toString)
    private val runtime = FakeRuntime()
    private val encoder = TextEncoder(BertTokenizer(listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]") + chars), runtime)
    private val matcher = SemanticMatcher(encoder, SensitiveCategory.builtIn)

    private fun feed(id: String, message: String) = Datum(JsonObject(mapOf(
        "entityType" to JsonPrimitive("feed"), "id" to JsonPrimitive(id), "message" to JsonPrimitive(message),
    )))

    @Test
    fun anchorsFoldAndEverydayTopicsStay() {
        val reasons = matcher.matchAll(listOf(
            feed("1", "家里安排我去相亲见了个女生"),
            feed("2", "手机续航和充电速度怎么样"),
            feed("3", "短"),
        ))
        assertEquals(setOf(0), reasons.keys)
        assertEquals("相亲恋爱", reasons[0]?.title)
        assertEquals("语义：家里安排我去相亲见了个女生", reasons[0]?.hit)
        val score = matcher.test("手机续航和充电速度怎么样")!!
        assertFalse(score.folds)
        assertTrue(score.everyday > 0.99f)
    }

    @Test
    fun vectorsAreCachedPerItem() {
        val items = listOf(feed("1", "和女朋友分手了很难受"))
        val before = runtime.calls
        matcher.matchAll(items)
        matcher.matchAll(items)
        assertEquals(before + 1, runtime.calls)
    }

    @Test
    fun customPhrasesAndWordsComeFirst() {
        val custom = SemanticMatcher(encoder, emptyList(), listOf("今天天气不错出去玩吧"))
        assertEquals("自定义", custom.test("今天天气不错出去玩吧")?.title)
        // Words decide first; the model only sees what they left visible.
        val filter = ContentFilter(sensitive = SensitiveMatcher(SensitiveCategory.builtIn), semantic = matcher)
        val marked = filter.markAll(listOf(feed("1", "晒女友"), feed("2", "怎么追喜欢的女生"), feed("3", "推荐一款好用的应用")))
        assertEquals(listOf("晒女友", "语义：怎么追喜欢的女生", null), marked.map { it.foldReason?.hit })
        assertNull(SemanticMatcher(encoder, emptyList()).test("任何内容"))
    }
}
