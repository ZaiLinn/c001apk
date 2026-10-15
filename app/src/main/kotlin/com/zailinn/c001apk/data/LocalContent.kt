package com.zailinn.c001apk.data

import com.zailinn.c001apk.core.model.ContentFilter
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.SensitiveMatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** A feed saved in browsing history or local favorites. */
@Serializable
data class FeedRecord(
    val id: String,
    val uid: String = "",
    val username: String = "",
    val userAvatar: String = "",
    val message: String = "",
    val device: String = "",
    val dateline: Long = 0,
    /** When it was saved, milliseconds. */
    val time: Long = 0,
) {
    companion object {
        fun of(feed: Datum) = FeedRecord(
            id = feed.id.orEmpty(),
            uid = feed.uid.orEmpty(),
            username = feed.displayName,
            userAvatar = feed.userInfo?.userAvatar ?: feed.userAvatar.orEmpty(),
            message = feed.message.orEmpty().take(150),
            device = feed.string("device_title").orEmpty(),
            dateline = feed.dateline ?: 0,
            time = System.currentTimeMillis(),
        )
    }
}

@Serializable
data class LocalFollowUser(val uid: String, val name: String = "", val avatar: String = "")

/** History, favorites, blocking, search history and local follows. Shared by all accounts. */
class LocalContent(private val store: LocalStore) {
    private val records = ListSerializer(FeedRecord.serializer())
    private val strings = ListSerializer(String.serializer())
    private val follows = ListSerializer(LocalFollowUser.serializer())

    val history: StateFlow<List<FeedRecord>> = store.state { store.readJson(it, HISTORY, records, emptyList()) }
    val favorites: StateFlow<List<FeedRecord>> = store.state { store.readJson(it, FAVORITES, records, emptyList()) }
    val blockedUsers: StateFlow<List<String>> = store.state { store.readJson(it, BLOCKED_USERS, strings, emptyList()) }
    val blockedTopics: StateFlow<List<String>> = store.state { store.readJson(it, BLOCKED_TOPICS, strings, emptyList()) }
    val searchHistory: StateFlow<List<String>> = store.state { store.readJson(it, SEARCH_HISTORY, strings, emptyList()) }
    val localFollows: StateFlow<List<LocalFollowUser>> = store.state { store.readJson(it, LOCAL_FOLLOWS, follows, emptyList()) }

    /** The user's own words for sensitive-topic folding; `#话题#` matches a topic. */
    val sensitiveWords: StateFlow<List<String>> = store.state { store.readJson(it, SENSITIVE_WORDS, strings, emptyList()) }

    private var matcherKey: Any? = null
    private var matcher: SensitiveMatcher? = null

    fun filter(settings: AppSettings? = null) =
        ContentFilter(blockedUsers.value.toSet(), blockedTopics.value, settings?.let(::sensitiveMatcher))

    /** Rebuilt only when the switch, categories or words change. */
    @Synchronized
    private fun sensitiveMatcher(settings: AppSettings): SensitiveMatcher? {
        if (!settings.sensitiveFold) return null
        val key = settings.sensitiveCategories to sensitiveWords.value
        if (key != matcherKey) {
            matcher = SensitiveMatcher(settings.enabledCategories, sensitiveWords.value).takeUnless { it.isEmpty }
            matcherKey = key
        }
        return matcher
    }

    /** Returns false when the word already exists. */
    suspend fun addSensitiveWord(word: String): Boolean {
        val value = word.trim()
        if (value.isEmpty() || value in sensitiveWords.value) return false
        store.updateJson(SENSITIVE_WORDS, strings, emptyList()) { listOf(value) + it.filter { v -> v != value } }
        return true
    }

    suspend fun removeSensitiveWord(word: String) =
        store.updateJson(SENSITIVE_WORDS, strings, emptyList()) { it.filter { v -> v != word } }

    /** The user's own example sentences for semantic folding. */
    val semanticPhrases: StateFlow<List<String>> = store.state { store.readJson(it, SEMANTIC_PHRASES, strings, emptyList()) }

    suspend fun addSemanticPhrase(phrase: String): Boolean {
        val value = phrase.trim()
        if (value.isEmpty() || value in semanticPhrases.value) return false
        store.updateJson(SEMANTIC_PHRASES, strings, emptyList()) { listOf(value) + it.filter { v -> v != value } }
        return true
    }

    suspend fun removeSemanticPhrase(phrase: String) =
        store.updateJson(SEMANTIC_PHRASES, strings, emptyList()) { it.filter { v -> v != phrase } }

    suspend fun addHistory(feed: Datum) = store.updateJson(HISTORY, records, emptyList()) { list ->
        val record = FeedRecord.of(feed)
        listOf(record) + list.filter { it.id != record.id }.take(MAX_HISTORY - 1)
    }

    suspend fun removeHistory(id: String) = store.updateJson(HISTORY, records, emptyList()) { it.filter { r -> r.id != id } }
    suspend fun clearHistory() = store.updateJson(HISTORY, records, emptyList()) { emptyList() }

    fun isFavorite(id: String) = favorites.value.any { it.id == id }

    suspend fun toggleFavorite(feed: Datum): Boolean {
        val id = feed.id ?: return false
        val adding = !isFavorite(id)
        store.updateJson(FAVORITES, records, emptyList()) { list ->
            if (adding) listOf(FeedRecord.of(feed)) + list.filter { it.id != id } else list.filter { it.id != id }
        }
        return adding
    }

    suspend fun removeFavorite(id: String) = store.updateJson(FAVORITES, records, emptyList()) { it.filter { r -> r.id != id } }
    suspend fun clearFavorites() = store.updateJson(FAVORITES, records, emptyList()) { emptyList() }

    fun isUserBlocked(uid: String) = uid in blockedUsers.value

    /** Returns false when the value already exists. */
    suspend fun block(value: String, user: Boolean = true): Boolean {
        val key = if (user) BLOCKED_USERS else BLOCKED_TOPICS
        val current = if (user) blockedUsers.value else blockedTopics.value
        if (value.isBlank() || value in current) return false
        store.updateJson(key, strings, emptyList()) { listOf(value) + it.filter { v -> v != value } }
        return true
    }

    suspend fun unblock(value: String, user: Boolean = true) =
        store.updateJson(if (user) BLOCKED_USERS else BLOCKED_TOPICS, strings, emptyList()) { it.filter { v -> v != value } }

    suspend fun addSearch(keyword: String) = store.updateJson(SEARCH_HISTORY, strings, emptyList()) {
        listOf(keyword) + it.filter { v -> v != keyword }.take(49)
    }

    suspend fun removeSearch(keyword: String) =
        store.updateJson(SEARCH_HISTORY, strings, emptyList()) { it.filter { v -> v != keyword } }

    suspend fun clearSearch() = store.updateJson(SEARCH_HISTORY, strings, emptyList()) { emptyList() }

    fun isLocalFollow(uid: String) = localFollows.value.any { it.uid == uid }

    suspend fun localFollow(uid: String, name: String, avatar: String) {
        require(uid.matches(Regex("^[0-9]+$")) && uid.toLongOrNull()?.let { it > 0 } == true)
        store.updateJson(LOCAL_FOLLOWS, follows, emptyList()) { listOf(LocalFollowUser(uid, name, avatar)) + it.filter { u -> u.uid != uid } }
    }

    suspend fun localUnfollow(uid: String) =
        store.updateJson(LOCAL_FOLLOWS, follows, emptyList()) { it.filter { u -> u.uid != uid } }

    companion object {
        private const val HISTORY = "historyFeedV1"
        private const val FAVORITES = "favFeedV1"
        private const val BLOCKED_USERS = "userBlackList"
        private const val BLOCKED_TOPICS = "topicBlackList"
        private const val SEARCH_HISTORY = "searchHistory"
        private const val LOCAL_FOLLOWS = "localFollowUsers"
        private const val SENSITIVE_WORDS = "sensitiveWords"
        private const val SEMANTIC_PHRASES = "semanticPhrases"
        private const val MAX_HISTORY = 1000
    }
}
