package com.zailinn.c001apk.core.semantic

import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.FoldReason
import com.zailinn.c001apk.core.model.SensitiveCategory
import kotlin.math.sqrt

/** Runs the embedding model: token ids and mask in, `last_hidden_state` out. */
interface EmbeddingRuntime : AutoCloseable {
    fun hiddenStates(inputIds: Array<LongArray>, attentionMask: Array<LongArray>): Array<Array<FloatArray>>
}

/** Turns texts into unit-length sentence vectors by mean pooling the hidden states. */
class TextEncoder(
    private val tokenizer: BertTokenizer,
    private val runtime: EmbeddingRuntime,
    private val maxTokens: Int = 96,
    private val batchSize: Int = 16,
) : AutoCloseable {
    @Synchronized
    fun encode(texts: List<String>): List<FloatArray> = texts.chunked(batchSize).flatMap(::encodeBatch)

    private fun encodeBatch(texts: List<String>): List<FloatArray> {
        val tokens = texts.map { tokenizer.encode(it, maxTokens) }
        val length = tokens.maxOf { it.size }
        val ids = Array(tokens.size) { row -> LongArray(length) { col -> tokens[row].getOrElse(col) { tokenizer.pad }.toLong() } }
        val mask = Array(tokens.size) { row -> LongArray(length) { col -> if (col < tokens[row].size) 1L else 0L } }
        val states = runtime.hiddenStates(ids, mask)
        return states.mapIndexed { row, hidden ->
            val size = tokens[row].size
            val vector = FloatArray(hidden[0].size)
            for (t in 0 until size) for (d in vector.indices) vector[d] += hidden[t][d]
            normalize(vector)
        }
    }

    override fun close() = runtime.close()

    companion object {
        fun normalize(vector: FloatArray): FloatArray {
            val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
            if (norm > 0f) for (i in vector.indices) vector[i] /= norm
            return vector
        }
    }
}

/**
 * Example sentences per category for semantic folding, plus everyday Coolapk
 * topics. A post folds only when it is close to a category sentence and clearly
 * closer to it than to every everyday topic, which keeps phone and app posts
 * that merely share words ("老婆" for a phone) visible.
 */
object SemanticAnchors {
    val byCategory: Map<String, List<String>> = mapOf(
        "couple" to listOf(
            "晒一下我女朋友的照片", "和女朋友一起出去约会吃饭", "女朋友送我的生日礼物", "我对象今天特别可爱",
            "情侣日常秀恩爱撒狗粮", "和老婆一起去旅游", "我家宝贝笑起来真好看",
        ),
        "dating" to listOf(
            "家里安排我去相亲见了个女生", "怎么追喜欢的女生", "表白被拒绝了", "和女朋友分手了很难受",
            "和对象吵架冷战了", "结婚彩礼要多少钱", "约会该穿什么",
        ),
        "lust" to listOf(
            "单身太久了好寂寞想找个女朋友", "晚上一个人很孤独空虚", "身材很好的美女照片", "男生的生理需求怎么解决",
            "好想有个人陪我", "年纪大了还没谈过恋爱",
        ),
        "gender" to listOf(
            "现在的女生太物质了", "男女之间的矛盾越来越严重", "女权和男权的争论", "现在的女孩子要求太高要房要车",
        ),
    )

    val everyday: List<String> = listOf(
        "手机续航和充电速度怎么样", "系统更新后流畅了很多", "推荐一款好用的应用", "软件闪退和bug反馈",
        "数码产品价格和购买建议", "电脑显卡和硬件配置", "今天天气不错出去玩", "工作加班写代码好累",
        "耳机音质和降噪体验", "游戏好不好玩", "手机摔坏了屏幕碎了", "酷安的功能怎么设置",
        "拍照效果和相机样张", "汽车和骑行", "美食和做饭", "家里的猫狗宠物", "和家人朋友周末出去玩",
        "给爸妈和孩子买东西",
    )

    const val DEFAULT_THRESHOLD = 0.5f
    const val MARGIN = 0.08f
}

/** Result of comparing one text with the anchors; [folds] decides. */
data class SemanticScore(val title: String, val anchor: String, val score: Float, val everyday: Float, val folds: Boolean)

/**
 * Folds feeds and replies whose text means roughly the same as a category's
 * example sentences. Built once per configuration; item vectors are cached.
 */
class SemanticMatcher(
    private val encoder: TextEncoder,
    categories: List<SensitiveCategory>,
    customPhrases: List<String> = emptyList(),
    private val threshold: Float = SemanticAnchors.DEFAULT_THRESHOLD,
) {
    private class Anchor(val title: String, val text: String, val vector: FloatArray)

    private val anchors: List<Anchor>
    private val everyday: List<FloatArray>

    init {
        val labelled = categories.flatMap { c -> SemanticAnchors.byCategory[c.id].orEmpty().map { c.title to it } } +
            customPhrases.map(String::trim).filter { it.isNotEmpty() }.map { "自定义" to it }
        val vectors = encoder.encode(labelled.map { it.second } + SemanticAnchors.everyday)
        anchors = labelled.mapIndexed { i, (title, text) -> Anchor(title, text, vectors[i]) }
        everyday = vectors.drop(labelled.size)
    }

    val isEmpty: Boolean get() = anchors.isEmpty()

    private val cache = object : LinkedHashMap<String, FloatArray>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > CACHE_SIZE
    }

    /** Fold reasons by position for the items that should fold. */
    fun matchAll(items: List<Datum>): Map<Int, FoldReason> {
        if (anchors.isEmpty()) return emptyMap()
        val texts = items.map(::textOf)
        val keys = items.mapIndexed { i, item -> texts[i]?.let { "${item.entityType}:${item.id}:${it.hashCode()}" } }
        val missing = keys.indices.filter { keys[it] != null && synchronized(cache) { cache[keys[it]] } == null }
        if (missing.isNotEmpty()) {
            val vectors = encoder.encode(missing.map { texts[it]!! })
            synchronized(cache) { missing.forEachIndexed { n, i -> cache[keys[i]!!] = vectors[n] } }
        }
        return buildMap {
            keys.forEachIndexed { i, key ->
                val vector = key?.let { synchronized(cache) { cache[it] } } ?: return@forEachIndexed
                val score = score(vector)
                if (score.folds) put(i, FoldReason(score.title, "语义：${score.anchor}"))
            }
        }
    }

    /** Scores any text; used by the settings page to try a sentence. */
    fun test(text: String): SemanticScore? =
        if (anchors.isEmpty() || text.isBlank()) null else score(encoder.encode(listOf(text)).single())

    private fun score(vector: FloatArray): SemanticScore {
        var best = anchors.first()
        var bestScore = -1f
        for (anchor in anchors) {
            val s = dot(vector, anchor.vector)
            if (s > bestScore) { bestScore = s; best = anchor }
        }
        val daily = everyday.maxOfOrNull { dot(vector, it) } ?: -1f
        val folds = bestScore >= threshold && bestScore - daily >= SemanticAnchors.MARGIN
        return SemanticScore(best.title, best.text, bestScore, daily, folds)
    }

    private fun textOf(item: Datum): String? {
        if (item.entityType !in FOLDABLE) return null
        val text = listOf(item.messageTitle, item.message, item.title)
            .joinToString("\n") { it.orEmpty() }
            .replace(HTML_TAG, "")
            .trim()
        return text.takeIf { it.length >= MIN_CHARS }?.take(MAX_CHARS)
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    companion object {
        private val FOLDABLE = setOf("feed", "feed_reply")
        private val HTML_TAG = Regex("<[^>]*>")
        private const val MIN_CHARS = 4
        private const val MAX_CHARS = 300
        private const val CACHE_SIZE = 2000
    }
}
