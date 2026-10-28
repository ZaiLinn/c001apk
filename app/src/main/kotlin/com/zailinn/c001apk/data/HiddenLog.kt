package com.zailinn.c001apk.data

import com.zailinn.c001apk.core.model.HiddenItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** An item a list left out, as kept on this device. [feedId] opens it; empty when it has no feed. */
@Serializable
data class HiddenRecord(
    val key: String,
    val feedId: String = "",
    val title: String,
    val hit: String,
    val username: String = "",
    val text: String = "",
    val time: Long,
)

/**
 * The last [LIMIT] items removed by the blacklists or a hiding filter category,
 * so wrong matches can be found (设置 → 内容过滤 → 最近隐藏). Newest first.
 */
class HiddenLog(private val store: LocalStore, private val scope: CoroutineScope) {
    private val serializer = ListSerializer(HiddenRecord.serializer())

    val records: StateFlow<List<HiddenRecord>> = store.state { store.readJson(it, KEY, serializer, emptyList()) }

    fun add(items: List<HiddenItem>) {
        val now = System.currentTimeMillis()
        val added = items.map { hidden ->
            val item = hidden.item
            val feedId = if (item.entityType == "feed") item.id else item.string("fid")
            HiddenRecord(
                key = "${item.entityType}:${item.id ?: item.hashCode()}",
                feedId = feedId.orEmpty(),
                title = hidden.reason.title,
                hit = hidden.reason.hit,
                username = item.displayName,
                text = FeedRecord.preview((item.message ?: item.title).orEmpty().replace(HTML_TAG, ""), 80),
                time = now,
            )
        }.distinctBy { it.key }
        // A refreshed page hides the same items again; nothing to write then.
        val known = records.value.mapTo(HashSet()) { it.key }
        if (added.all { it.key in known }) return
        val keys = added.mapTo(HashSet()) { it.key }
        scope.launch { store.updateJson(KEY, serializer, emptyList()) { list -> (added + list.filter { it.key !in keys }).take(LIMIT) } }
    }

    suspend fun clear() = store.updateJson(KEY, serializer, emptyList()) { emptyList() }

    companion object {
        const val KEY = "hiddenLogV1"
        const val LIMIT = 200
        private val HTML_TAG = Regex("<[^>]*>")
    }
}
