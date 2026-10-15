package com.zailinn.c001apk.core.model

import com.zailinn.c001apk.core.protocol.Constants

/** Local blocking rules applied to every list response. */
data class ContentFilter(
    val blockedUsers: Set<String> = emptySet(),
    val blockedTopics: List<String> = emptyList(),
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

    /** Hide replies from blocked users inside a reply's nested rows. */
    fun filterReplyRows(item: Datum): Datum =
        if (!item.has("replyRows")) item
        else item.withList("replyRows", item.replyRows.filter { it.uid !in blockedUsers })
}
