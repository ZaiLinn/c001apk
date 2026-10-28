package com.zailinn.c001apk.core.model

import kotlinx.serialization.Serializable

/**
 * A user-editable group of rules for sensitive-topic filtering. [tags] match
 * topic fields exactly or `#tag#` in the text; [keywords] match anywhere in the
 * text; [patterns] are regular expressions for phrasings a plain word misses;
 * [examples] are sentences the optional semantic model compares posts with;
 * a sentence containing one of [exceptions] (举报, 反对…) is not matched.
 * [strictKeywords] are looser words that only match in 严格模式, and [authors]
 * are user ids whose every feed and reply falls in the category.
 */
@Serializable
data class SensitiveCategory(
    val id: String,
    val title: String,
    val enabled: Boolean = true,
    val tags: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val patterns: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
    val exceptions: List<String> = emptyList(),
    val strictKeywords: List<String> = emptyList(),
    val authors: List<String> = emptyList(),
) {
    companion object {
        /**
         * Draft word list. Bare "我老婆" is left out on purpose: on Coolapk it
         * usually means a phone or a game character, so only longer phrasings match.
         */
        val builtIn: List<SensitiveCategory> = listOf(
            SensitiveCategory(
                id = "couple",
                title = "晒对象",
                tags = listOf("晒对象", "晒女友", "晒女朋友", "晒老婆", "晒男友", "秀恩爱", "我的女朋友", "情侣日常", "我在酷安晒女友"),
                keywords = listOf(
                    "晒女友", "晒女朋友", "晒对象", "晒老婆", "晒媳妇", "晒男友", "晒男朋友",
                    "秀恩爱", "撒狗粮", "喂狗粮", "脱单了", "官宣恋爱",
                    "女朋友送的", "女朋友送我", "女朋友给我买", "对象送的", "对象送我", "老婆送的", "媳妇送的",
                ),
                strictKeywords = listOf("我家那位", "我家领导", "吃狗粮"),
                patterns = listOf(
                    "(我|俺)的?(女朋友|女友|对象|媳妇|老婆)(说|给我|做|让|非要|今天|昨天|生日|拍|穿|也想)",
                    "(女朋友|女友|对象|媳妇|老婆)的?(照片|自拍|美照|生图|素颜)",
                    "(和|跟|带|陪)(女朋友|女友|对象|媳妇)(去|出去|一起|约会|旅游|逛)",
                ),
                examples = listOf(
                    "晒一下我女朋友的照片", "和女朋友一起出去约会吃饭", "女朋友送我的生日礼物", "我对象今天特别可爱",
                    "情侣日常秀恩爱撒狗粮", "和老婆一起去旅游", "我家宝贝笑起来真好看",
                ),
            ),
            SensitiveCategory(
                id = "dating",
                title = "相亲恋爱",
                tags = listOf("相亲", "找对象", "征婚", "脱单", "恋爱", "情感", "婚恋", "初恋这件小事"),
                keywords = listOf(
                    "相亲", "征婚", "找对象", "找个对象", "介绍对象", "求脱单", "怎么追女生", "追女生", "追女孩",
                    "前女友", "分手", "分手了", "失恋", "谈恋爱", "恋爱经验",
                    "彩礼", "催婚", "剩男", "剩女", "大龄单身",
                    "交友软件", "交友app", "网恋", "奔现", "见网友", "网友见面",
                ),
                // 表白小米, 前任CEO.
                strictKeywords = listOf("表白", "前任"),
                examples = listOf(
                    "家里安排我去相亲见了个女生", "怎么追喜欢的女生", "表白被拒绝了", "和女朋友分手了很难受",
                    "和对象吵架冷战了", "结婚彩礼要多少钱", "约会该穿什么",
                ),
            ),
            SensitiveCategory(
                id = "lust",
                title = "性压抑/擦边",
                tags = listOf("深夜话题", "美女", "小姐姐"),
                keywords = listOf(
                    "性压抑", "压抑太久", "憋得慌", "憋坏了", "憋不住", "好寂寞", "好空虚", "寂寞难耐",
                    "想谈恋爱", "想找女朋友", "想要女朋友", "想要个女朋友", "没有女朋友", "没女朋友", "找不到女朋友",
                    "求车牌", "涩图", "瑟图", "涩涩", "擦边", "约炮", "约p", "福利姬", "美女图",
                    "撩妹", "生理需求", "性欲", "欲火", "色批", "老色批", "lsp",
                ),
                // Also everyday words: 青椒肉丝, 黑丝绒, 玩机老司机, 车牌号 in car posts, 发车 for group buys.
                strictKeywords = listOf(
                    "单身狗", "母胎solo", "母胎单身", "处男", "老司机", "车牌号", "发车了",
                    "黑丝", "白丝", "肉丝", "丝袜", "大长腿", "好身材", "身材好", "性感", "小姐姐", "荷尔蒙",
                    "女菩萨", "发福利",
                ),
                patterns = listOf(
                    "(想|好想|求)(找|要|谈)?(个|一个)?(女朋友|女友|妹子)",
                    "(寂寞|空虚|孤独)的?(夜|晚上|深夜)",
                ),
                exceptions = LUST_EXCEPTIONS,
                examples = listOf(
                    "单身太久了好寂寞想找个女朋友", "晚上一个人很孤独空虚", "身材很好的美女照片", "男生的生理需求怎么解决",
                    "单身好久了晚上好想有个女朋友陪", "年纪大了还没谈过恋爱",
                ),
            ),
            SensitiveCategory(
                id = "gender",
                title = "两性对立",
                keywords = listOf(
                    "渣女", "渣男", "绿茶婊", "普信男", "普信女", "女拳", "男拳",
                    "田园女权", "捞女", "扶弟魔", "嫁不出去", "娶不到老婆", "打光棍", "女德", "男德",
                ),
                // 接盘 is also second-hand trading.
                strictKeywords = listOf("舔狗", "小仙女", "接盘侠"),
                examples = listOf(
                    "现在的女生太物质了", "男女之间的矛盾越来越严重", "女权和男权的争论", "现在的女孩子要求太高要房要车",
                ),
            ),
        )
        fun builtIn(id: String): SensitiveCategory? = builtIn.firstOrNull { it.id == id }

        /** Words dropped in revision 6 for hiding ordinary posts: 特色图标 held 色图, 权限被拒绝了 被拒绝了. */
        val retiredKeywords: Map<String, Set<String>> = mapOf(
            "dating" to setOf("被拒绝了"),
            "lust" to setOf("色图", "色色", "瑟瑟"),
        )

        /** Words added in revision 6. */
        val addedKeywords: Map<String, List<String>> = mapOf("dating" to listOf("分手了", "找个对象"))

        /** Patterns narrowed in revision 6: 给我老婆买 is buying advice; 求对象 also means code objects. */
        val retiredPatterns: Map<String, String> = mapOf(
            "(我|俺)的?(女朋友|女友|对象|媳妇|老婆)(说|给我|送|买|做|让|非要|今天|昨天|生日|拍|穿|也想)" to
                "(我|俺)的?(女朋友|女友|对象|媳妇|老婆)(说|给我|做|让|非要|今天|昨天|生日|拍|穿|也想)",
            "(想|好想|求)(找|要|谈)?(个|一个)?(女朋友|女友|对象|妹子)" to "(想|好想|求)(找|要|谈)?(个|一个)?(女朋友|女友|妹子)",
        )

        /** Online-dating words added to 相亲恋爱 later; merged once into categories saved before them. */
        val addedDatingKeywords = listOf("交友软件", "交友app", "网恋", "奔现", "见网友", "网友见面")
    }
}

/** Sentences that report or reject 约炮 and the like are not themselves 擦边. */
private val LUST_EXCEPTIONS = listOf("举报", "反对", "抵制", "警惕", "骚扰", "困扰", "拒绝")

/** Why an item is folded: the category title and the word or tag that matched. */
data class FoldReason(val title: String, val hit: String)

/** Compiled matcher for the given categories (callers pass the enabled ones); [strict] adds their strict words. */
class SensitiveMatcher(categories: List<SensitiveCategory>, strict: Boolean = false) {
    /** A keyword and, when it starts or ends with a Latin letter or digit, its whole-word form. */
    private class Keyword(val word: String, val bounded: Regex?) {
        fun hits(text: String) = word in text && (bounded == null || bounded.containsMatchIn(text))
    }

    private class Group(
        val title: String,
        val tags: List<String>,
        val keywords: List<Keyword>,
        val patterns: List<Regex>,
        val exceptions: List<String>,
        val authors: Set<String>,
    )

    private val groups: List<Group> = categories.map { category ->
        Group(
            category.title,
            category.tags.map { normalize(it.trim('#')) }.filter { it.isNotEmpty() },
            (category.keywords + if (strict) category.strictKeywords else emptyList())
                .map(::normalize).filter { it.isNotEmpty() }.distinct().map { Keyword(it, bounded(it)) },
            // A broken user pattern is skipped rather than failing every page.
            category.patterns.mapNotNull { runCatching { Regex(it) }.getOrNull() },
            category.exceptions.map(::normalize).filter { it.isNotEmpty() },
            category.authors.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        )
    }.filter { it.tags.isNotEmpty() || it.keywords.isNotEmpty() || it.patterns.isNotEmpty() || it.authors.isNotEmpty() }

    val isEmpty: Boolean get() = groups.isEmpty()

    fun match(item: Datum): FoldReason? {
        if (groups.isEmpty() || item.entityType !in FOLDABLE) return null
        val topics = item.topicNames()
        val text = normalize(
            listOf(item.messageTitle, item.message, item.title, item.string("tags"), item.string("ttitle"))
                .joinToString("\n") { it.orEmpty() }
                .take(MAX_TEXT)
                .replace(HTML_TAG, ""),
        )
        val sentences by lazy { text.split(SENTENCE_END).filter { it.isNotBlank() } }
        for (group in groups) {
            item.uid?.takeIf { it in group.authors }?.let { return FoldReason(group.title, "用户 ${item.displayName.ifEmpty { it }}") }
            group.tags.firstOrNull { it in topics || "#$it#" in text }?.let { return FoldReason(group.title, "#$it#") }
            // An exception word (举报, 反对…) clears only its own sentence, never the whole post.
            val checked = if (group.exceptions.isEmpty()) listOf(text) else sentences.filter { s -> group.exceptions.none { it in s } }
            for (part in checked) {
                group.keywords.firstOrNull { it.hits(part) }?.let { return FoldReason(group.title, it.word) }
                for (pattern in group.patterns) pattern.find(part)?.let { return FoldReason(group.title, it.value) }
            }
        }
        return null
    }

    companion object {
        private val FOLDABLE = setOf("feed", "feed_reply")
        private const val MAX_TEXT = 4000
        private val HTML_TAG = Regex("<[^>]*>")
        private val ZERO_WIDTH = Regex("[\\u200B-\\u200D\\uFEFF]+")
        private val SPACES = Regex("\\s+")
        private val SENTENCE_END = Regex("[。！？!?；;\\n]+")

        private fun Char.isLatinOrDigit() = this in 'a'..'z' || this in '0'..'9'

        /** A keyword with Latin edges matches only as a whole word there, so "lsp" leaves "LSPosed" alone. */
        private fun bounded(word: String): Regex? {
            if (!word.first().isLatinOrDigit() && !word.last().isLatinOrDigit()) return null
            val start = if (word.first().isLatinOrDigit()) "(?<![a-z0-9])" else ""
            val end = if (word.last().isLatinOrDigit()) "(?![a-z0-9])" else ""
            return Regex(start + Regex.escape(word) + end)
        }

        /**
         * Lower case, one space for any run of spaces. Spaces stay: removing them joined words
         * into new ones, as "黑色 图片" into "色图". Line breaks stay as sentence ends.
         */
        fun normalize(text: String): String =
            text.replace(ZERO_WIDTH, "").lowercase().lines().joinToString("\n") { it.replace(SPACES, " ").trim() }.trim()
    }
}


/** The item's topics: its tags, its topic title and every related row, normalized. */
internal fun Datum.topicNames(): Set<String> = buildSet {
    string("tags")?.split(',', '，')?.forEach { add(SensitiveMatcher.normalize(it)) }
    string("ttitle")?.let { add(SensitiveMatcher.normalize(it)) }
    relationRows.forEach { row -> row.title?.let { add(SensitiveMatcher.normalize(it)) } }
    remove("")
}
