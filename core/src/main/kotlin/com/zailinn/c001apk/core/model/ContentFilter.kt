package com.zailinn.c001apk.core.model

import com.zailinn.c001apk.core.protocol.Constants
import com.zailinn.c001apk.core.semantic.SemanticMatcher
import kotlinx.serialization.Serializable

/**
 * A topic the user blocked. By default only the topic with exactly this name
 * (or `#name#` in the text); with [contains], every topic whose name contains it.
 */
@Serializable
data class TopicRule(val name: String, val contains: Boolean = false) {
    fun matches(topics: Set<String>, text: String): Boolean {
        val key = SensitiveMatcher.normalize(name.trim('#'))
        if (key.isEmpty()) return false
        return if (contains) topics.any { key in it } else key in topics || "#$key#" in text
    }

    /** How the hit is shown in the hidden-items log. */
    val label: String get() = if (contains) "含“$name”的话题" else "#$name#"
}

/** An item a page left out, with why. */
class HiddenItem(val item: Datum, val reason: FoldReason)

/** Local blocking rules applied to every list response. */
data class ContentFilter(
    val blockedUsers: Set<String> = emptySet(),
    val blockedTopics: List<TopicRule> = emptyList(),
    /** Folds or hides matching feeds and replies; null when off. */
    val sensitive: SensitiveMatcher? = null,
    /** Embedding-based matching for what the word list misses; null when off or not loaded. */
    val semantic: SemanticMatcher? = null,
    /** Show category matches folded; false drops them. Blacklists always drop. */
    val fold: Boolean = true,
    /** Told about the items [apply] left out because of a rule. */
    val onHidden: ((List<HiddenItem>) -> Unit)? = null,
) {
    /** Whether a topic page with this title is blocked. */
    fun isTopicBlocked(title: String): Boolean {
        val topics = setOf(SensitiveMatcher.normalize(title))
        return blockedTopics.any { it.matches(topics, "") }
    }

    fun accepts(item: Datum, includeConfigCard: Boolean = false): Boolean =
        isKnown(item, includeConfigCard) && blockReason(item) == null

    private fun isKnown(item: Datum, includeConfigCard: Boolean): Boolean {
        if (item.entityTemplate == "refreshCard" && item.title.orEmpty().contains("没有")) return true
        return item.entityType in Constants.entityTypeList ||
            item.entityTemplate in Constants.entityTemplateList ||
            (includeConfigCard && (item.entityTemplate == "configCard" || item.entityTemplate == "sortSelectCard"))
    }

    /** Why a blacklist removes this item, or null. */
    fun blockReason(item: Datum): FoldReason? {
        if (item.uid != null && item.uid in blockedUsers) return FoldReason(BLOCKED_USER, item.displayName.ifEmpty { item.uid.orEmpty() })
        if (blockedTopics.isEmpty()) return null
        val topics = item.topicNames()
        val text = SensitiveMatcher.normalize(listOf(item.message, item.messageTitle).joinToString("\n") { it.orEmpty() })
        return blockedTopics.firstOrNull { it.matches(topics, text) }?.let { FoldReason(BLOCKED_TOPIC, it.label) }
    }

    /**
     * The shown part of a page: unknown cards and blacklisted items removed,
     * category matches folded or, without [fold], removed. Removals by a rule go to [onHidden].
     */
    fun apply(items: List<Datum>, includeConfigCard: Boolean = false): List<Datum> {
        val hidden = mutableListOf<HiddenItem>()
        val accepted = items.filter { item ->
            if (!isKnown(item, includeConfigCard)) return@filter false
            val reason = blockReason(item) ?: return@filter true
            hidden += HiddenItem(item, reason)
            false
        }
        val marked = markPage(accepted)
        val shown = if (fold) marked else marked.filter { item ->
            val reason = item.foldReason ?: return@filter true
            hidden += HiddenItem(item, reason)
            false
        }
        if (hidden.isNotEmpty()) onHidden?.invoke(hidden)
        return shown
    }

    /** Marks an accepted item that should be shown folded. */
    fun mark(item: Datum): Datum {
        val reason = sensitive?.match(item) ?: return item
        return item.marked(reason)
    }

    /** [mark] for a page, then semantic matching for what the words missed; nothing removed. */
    fun markAll(items: List<Datum>): List<Datum> = markPage(items)

    private fun markPage(items: List<Datum>): List<Datum> {
        val marked = items.map(::mark)
        val semantic = semantic ?: return marked
        val rest = marked.indices.filter { marked[it].foldReason == null }
        // The model is optional: if it fails, the page still shows.
        val reasons = runCatching { semantic.matchAll(rest.map { marked[it] }) }.getOrElse { emptyMap() }
        if (reasons.isEmpty()) return marked
        return marked.toMutableList().apply { reasons.forEach { (n, reason) -> this[rest[n]] = this[rest[n]].marked(reason) } }
    }

    private fun Datum.marked(reason: FoldReason) = with(FOLD_TITLE, reason.title).with(FOLD_HIT, reason.hit)

    /** Hide replies from blocked users inside a reply's nested rows. */
    fun filterReplyRows(item: Datum): Datum =
        if (!item.has("replyRows")) item
        else item.withList("replyRows", item.replyRows.filter { it.uid !in blockedUsers })

    companion object {
        const val FOLD_TITLE = "c001FoldTitle"
        const val FOLD_HIT = "c001FoldHit"
        const val BLOCKED_USER = "用户黑名单"
        const val BLOCKED_TOPIC = "我屏蔽的话题"
    }
}

/** Set by [ContentFilter.mark]; lists show the item folded until it is tapped. */
val Datum.foldReason: FoldReason?
    get() = string(ContentFilter.FOLD_TITLE)?.let { FoldReason(it, string(ContentFilter.FOLD_HIT).orEmpty()) }

/**
 * A filtered page that still remembers the server's own first and last ids, so
 * the next page is requested after the last item even when it was removed.
 */
class DatumPage(
    items: List<Datum>,
    val rawFirstId: String?,
    val rawLastId: String?,
) : List<Datum> by items

/** Cursor ids for the page that follows this one. */
val List<Datum>.cursorFirstId: String? get() = if (this is DatumPage) rawFirstId else firstOrNull()?.id
val List<Datum>.cursorLastId: String? get() = if (this is DatumPage) rawLastId else lastOrNull()?.id
