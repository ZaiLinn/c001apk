package com.zailinn.c001apk.core.model

import com.zailinn.c001apk.core.protocol.Constants
import com.zailinn.c001apk.core.semantic.SemanticMatcher

/** Local blocking rules applied to every list response. */
data class ContentFilter(
    val blockedUsers: Set<String> = emptySet(),
    val blockedTopics: List<String> = emptyList(),
    /** Folds matching feeds and replies instead of removing them; null when off. */
    val sensitive: SensitiveMatcher? = null,
    /** Embedding-based folding for what the word list misses; null when off or not loaded. */
    val semantic: SemanticMatcher? = null,
    /** Drop matched items instead of folding them. */
    val hideSensitive: Boolean = false,
) {
    fun isTopicBlocked(text: String): Boolean = blockedTopics.any { it.isNotEmpty() && text.contains(it) }

    fun accepts(item: Datum, includeConfigCard: Boolean = false): Boolean {
        if (item.entityTemplate == "refreshCard" && item.title.orEmpty().contains("没有")) return true
        val known = item.entityType in Constants.entityTypeList ||
            item.entityTemplate in Constants.entityTemplateList ||
            (includeConfigCard && item.entityTemplate == "configCard")
        if (!known) return false
        if (item.uid != null && item.uid in blockedUsers) return false
        val topicText = listOf(item.string("tags"), item.string("ttitle"), item.relationRows.firstOrNull()?.title)
            .joinToString(",") { it.orEmpty() }
        return !isTopicBlocked(topicText)
    }

    /** Marks an accepted item that should be shown folded. */
    fun mark(item: Datum): Datum {
        val reason = sensitive?.match(item) ?: return item
        return item.marked(reason)
    }

    /**
     * [mark] for a page, then semantic matching for what the words missed.
     * With [hideSensitive], matched items are removed instead of folded.
     */
    fun markAll(items: List<Datum>): List<Datum> {
        val marked = markPage(items)
        return if (hideSensitive) marked.filter { it.foldReason == null } else marked
    }

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
