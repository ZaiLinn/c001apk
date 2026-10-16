package com.zailinn.c001apk.core.model

import kotlinx.serialization.Serializable

/**
 * A user-editable group of rules for sensitive-topic filtering. [tags] match
 * topic fields exactly or `#tag#` in the text; [keywords] match anywhere in the
 * text; [patterns] are regular expressions for phrasings a plain word misses;
 * [examples] are sentences the optional semantic model compares posts with.
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
                    "秀恩爱", "撒狗粮", "吃狗粮", "喂狗粮", "脱单了", "官宣恋爱",
                    "女朋友送的", "女朋友送我", "女朋友给我买", "对象送的", "对象送我", "老婆送的", "媳妇送的",
                    "我家那位", "我家领导",
                ),
                patterns = listOf(
                    "(我|俺)的?(女朋友|女友|对象|媳妇|老婆)(说|给我|送|买|做|让|非要|今天|昨天|生日|拍|穿|也想)",
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
                    "相亲", "征婚", "找对象", "介绍对象", "求脱单", "怎么追女生", "追女生", "追女孩",
                    "表白", "被拒绝了", "前女友", "前任", "分手", "失恋", "谈恋爱", "恋爱经验",
                    "彩礼", "催婚", "剩男", "剩女", "大龄单身",
                ),
                examples = listOf(
                    "家里安排我去相亲见了个女生", "怎么追喜欢的女生", "表白被拒绝了", "和女朋友分手了很难受",
                    "和对象吵架冷战了", "结婚彩礼要多少钱", "约会该穿什么",
                ),
            ),
            SensitiveCategory(
                id = "lust",
                title = "性压抑/擦边",
                tags = listOf("深夜话题", "福利", "美女", "小姐姐"),
                keywords = listOf(
                    "性压抑", "压抑太久", "憋得慌", "憋坏了", "憋不住", "好寂寞", "好空虚", "寂寞难耐",
                    "想谈恋爱", "想找女朋友", "想要女朋友", "想要个女朋友", "没有女朋友", "没女朋友", "找不到女朋友",
                    "单身狗", "母胎solo", "母胎单身", "处男", "老司机", "求车牌", "车牌号", "发车了",
                    "涩图", "色图", "瑟图", "涩涩", "瑟瑟", "色色", "擦边", "黑丝", "白丝", "肉丝", "丝袜",
                    "大长腿", "好身材", "身材好", "性感", "约炮", "约p", "福利姬", "小姐姐", "美女图",
                    "撩妹", "荷尔蒙", "生理需求", "性欲", "欲火", "色批", "老色批", "lsp",
                ),
                patterns = listOf(
                    "(想|好想|求)(找|要|谈)?(个|一个)?(女朋友|女友|对象|妹子)",
                    "(寂寞|空虚|孤独)的?(夜|晚上|深夜)",
                ),
                examples = listOf(
                    "单身太久了好寂寞想找个女朋友", "晚上一个人很孤独空虚", "身材很好的美女照片", "男生的生理需求怎么解决",
                    "单身好久了晚上好想有个女朋友陪", "年纪大了还没谈过恋爱",
                ),
            ),
            SensitiveCategory(
                id = "gender",
                title = "两性对立",
                keywords = listOf(
                    "舔狗", "渣女", "渣男", "绿茶婊", "小仙女", "普信男", "普信女", "女拳", "男拳",
                    "田园女权", "捞女", "接盘侠", "扶弟魔", "嫁不出去", "娶不到老婆", "打光棍", "女德", "男德",
                ),
                examples = listOf(
                    "现在的女生太物质了", "男女之间的矛盾越来越严重", "女权和男权的争论", "现在的女孩子要求太高要房要车",
                ),
            ),
        )
        fun builtIn(id: String): SensitiveCategory? = builtIn.firstOrNull { it.id == id }
    }
}

/** Why an item is folded: the category title and the word or tag that matched. */
data class FoldReason(val title: String, val hit: String)

/** Compiled matcher for the given categories (callers pass the enabled ones). */
class SensitiveMatcher(categories: List<SensitiveCategory>) {
    private class Group(val title: String, val tags: List<String>, val keywords: List<String>, val patterns: List<Regex>)

    private val groups: List<Group> = categories.map { category ->
        Group(
            category.title,
            category.tags.map { normalize(it.trim('#')) }.filter { it.isNotEmpty() },
            category.keywords.map(::normalize).filter { it.isNotEmpty() },
            // A broken user pattern is skipped rather than failing every page.
            category.patterns.mapNotNull { runCatching { Regex(it) }.getOrNull() },
        )
    }.filter { it.tags.isNotEmpty() || it.keywords.isNotEmpty() || it.patterns.isNotEmpty() }

    val isEmpty: Boolean get() = groups.isEmpty()

    fun match(item: Datum): FoldReason? {
        if (groups.isEmpty() || item.entityType !in FOLDABLE) return null
        val topics = topicNames(item)
        val text = normalize(
            listOf(item.messageTitle, item.message, item.title, item.string("tags"), item.string("ttitle"))
                .joinToString("\n") { it.orEmpty() }
                .take(MAX_TEXT)
                .replace(HTML_TAG, ""),
        )
        for (group in groups) {
            group.tags.firstOrNull { it in topics || "#$it#" in text }?.let { return FoldReason(group.title, "#$it#") }
            group.keywords.firstOrNull { it in text }?.let { return FoldReason(group.title, it) }
            for (pattern in group.patterns) pattern.find(text)?.let { return FoldReason(group.title, it.value) }
        }
        return null
    }

    private fun topicNames(item: Datum): Set<String> = buildSet {
        item.string("tags")?.split(',', '，')?.forEach { add(normalize(it)) }
        item.string("ttitle")?.let { add(normalize(it)) }
        item.relationRows.forEach { row -> row.title?.let { add(normalize(it)) } }
        remove("")
    }

    companion object {
        private val FOLDABLE = setOf("feed", "feed_reply")
        private const val MAX_TEXT = 4000
        private val HTML_TAG = Regex("<[^>]*>")
        private val INVISIBLE = Regex("[\\s\\u200B-\\u200D\\uFEFF]+")

        /** Lower case and no spaces, so "晒 女友" and "LSP" still match. */
        fun normalize(text: String): String = text.replace(INVISIBLE, "").lowercase()
    }
}
