package com.zailinn.c001apk.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class FeedArticle(
    val type: String? = null,
    val message: String? = null,
    val url: String? = null,
    val description: String? = null,
    val title: String? = null,
    val subTitle: String? = null,
    val logo: String? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

        /** Structured article body, with title and cover prepended as the Flutter client did. */
        fun parse(feed: Datum): List<FeedArticle> {
            val raw = feed.string("message_raw_output")
            if (raw.isNullOrEmpty() || raw == "null") return emptyList()
            val items = json.decodeFromString<List<FeedArticle>>(raw)
                .filter { it.type in setOf("text", "image", "shareUrl") }
                .toMutableList()
            if (items.isEmpty()) return items
            feed.title?.takeIf { it.isNotEmpty() }?.let { items.add(0, FeedArticle(type = "title", title = it)) }
            feed.string("message_cover")?.takeIf { it.isNotEmpty() }?.let { items.add(0, FeedArticle(type = "image", url = it)) }
            return items
        }
    }
}
