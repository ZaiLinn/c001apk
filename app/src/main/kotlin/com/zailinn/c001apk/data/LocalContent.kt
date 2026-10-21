package com.zailinn.c001apk.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zailinn.c001apk.core.model.ContentFilter
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.SensitiveCategory
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
            message = preview(feed.message.orEmpty()),
            device = feed.string("device_title").orEmpty(),
            dateline = feed.dateline ?: 0,
            time = System.currentTimeMillis(),
        )

        /**
         * The first [max] characters of an HTML message, without a tag or entity cut in half,
         * which would show as raw markup.
         */
        fun preview(html: String, max: Int = 150): String =
            html.take(max).replace(Regex("<[^>]*$"), "").replace(Regex("&#?\\w*$"), "")
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

    private val categories = ListSerializer(SensitiveCategory.serializer())

    /** Sensitive-topic categories, editable by the user; starts from [SensitiveCategory.builtIn]. */
    val filterCategories: StateFlow<List<SensitiveCategory>> = store.state(::readCategories)

    private var matcherKey: Any? = null
    private var matcher: SensitiveMatcher? = null

    /**
     * Blacklists always remove items. Sensitive categories fold or, with
     * [AppSettings.sensitiveHide], remove them; both switches off disables them.
     */
    fun filter(settings: AppSettings? = null) = ContentFilter(
        blockedUsers.value.toSet(),
        blockedTopics.value,
        settings?.takeIf { it.sensitiveFilterOn }?.let { sensitiveMatcher() },
        hideSensitive = settings?.sensitiveHide == true,
    )

    /** Rebuilt only when the categories change. */
    @Synchronized
    private fun sensitiveMatcher(): SensitiveMatcher? {
        val enabled = filterCategories.value.filter { it.enabled }
        if (enabled != matcherKey) {
            matcher = SensitiveMatcher(enabled).takeUnless { it.isEmpty }
            matcherKey = enabled
        }
        return matcher
    }

    private fun readCategories(prefs: Preferences): List<SensitiveCategory> =
        prefs[stringPreferencesKey(FILTER_CATEGORIES)]
            ?.let { runCatching { storeJson.decodeFromString(categories, it) }.getOrNull() }
            ?: legacyCategories(prefs)

    /**
     * 2.0.0 kept the enabled built-in ids, the user's words and semantic
     * phrases separately; they become the built-in categories plus "自定义".
     */
    private fun legacyCategories(prefs: Preferences): List<SensitiveCategory> {
        val enabled = prefs[stringPreferencesKey(LEGACY_ENABLED)]?.split(',')?.filter { it.isNotEmpty() }?.toSet()
        val words = store.readJson(prefs, LEGACY_WORDS, strings, emptyList())
        val phrases = store.readJson(prefs, LEGACY_PHRASES, strings, emptyList())
        val builtIn = SensitiveCategory.builtIn.map { it.copy(enabled = enabled == null || it.id in enabled) }
        if (words.isEmpty() && phrases.isEmpty()) return builtIn
        val (tags, keywords) = words.partition { it.length > 2 && it.startsWith("#") && it.endsWith("#") }
        return builtIn + SensitiveCategory(CUSTOM_ID, "自定义", tags = tags.map { it.trim('#') }, keywords = keywords, examples = phrases)
    }

    /**
     * One-time cleanups of saved categories when shipped defaults change: 2.0.3
     * dropped #酷安夜话# and #夜话# from 性压抑/擦边 (too broad for a whole topic);
     * 2.0.5 replaced its example "好想有个人陪我", which drew in any post about
     * nights, sleep or company ("下夜班睡觉去了", "有纸就有床").
     */
    suspend fun migrateCategories() = store.edit { prefs ->
        val revision = prefs[intPreferencesKey(CATEGORIES_REVISION)] ?: 1
        if (revision >= CURRENT_REVISION) return@edit
        prefs[stringPreferencesKey(FILTER_CATEGORIES)]
            ?.let { runCatching { storeJson.decodeFromString(categories, it) }.getOrNull() }
            ?.let { saved ->
                val cleaned = saved.map { c ->
                    if (c.id != "lust") return@map c
                    var fixed = c
                    if (revision < 2) fixed = fixed.copy(tags = fixed.tags - setOf("酷安夜话", "夜话"))
                    if (revision < 3) {
                        val examples = fixed.examples.map { if (it == DROPPED_EXAMPLE) REPLACED_EXAMPLE else it }
                        fixed = fixed.copy(examples = examples.distinct())
                    }
                    fixed
                }
                prefs[stringPreferencesKey(FILTER_CATEGORIES)] = storeJson.encodeToString(categories, cleaned)
            }
        prefs[intPreferencesKey(CATEGORIES_REVISION)] = CURRENT_REVISION
    }

    suspend fun updateCategories(transform: (List<SensitiveCategory>) -> List<SensitiveCategory>) =
        store.edit { prefs ->
            prefs[stringPreferencesKey(FILTER_CATEGORIES)] = storeJson.encodeToString(categories, transform(readCategories(prefs)))
        }

    suspend fun updateCategory(id: String, transform: (SensitiveCategory) -> SensitiveCategory) =
        updateCategories { list -> list.map { if (it.id == id) transform(it) else it } }

    /** Adds an empty, enabled category and returns its id. */
    suspend fun addCategory(title: String): String {
        val id = "user-${System.currentTimeMillis()}"
        updateCategories { it + SensitiveCategory(id, title.trim()) }
        return id
    }

    suspend fun deleteCategory(id: String) = updateCategories { list -> list.filter { it.id != id } }

    /** Back to the shipped lists for one built-in category, keeping whether it is on. */
    suspend fun restoreCategory(id: String) {
        val default = SensitiveCategory.builtIn(id) ?: return
        updateCategories { list ->
            if (list.any { it.id == id }) list.map { if (it.id == id) default.copy(enabled = it.enabled) else it }
            else list + default
        }
    }

    /** Restores every built-in category and keeps the user's own. */
    suspend fun restoreAllCategories() = updateCategories { list ->
        SensitiveCategory.builtIn + list.filter { SensitiveCategory.builtIn(it.id) == null }
    }

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
        private const val FILTER_CATEGORIES = "filterCategoriesV1"
        private const val CATEGORIES_REVISION = "filterCategoriesRevision"
        private const val CURRENT_REVISION = 3
        private const val DROPPED_EXAMPLE = "好想有个人陪我"
        private const val REPLACED_EXAMPLE = "单身好久了晚上好想有个女朋友陪"
        private const val LEGACY_ENABLED = "sensitiveFoldCategories"
        private const val LEGACY_WORDS = "sensitiveWords"
        private const val LEGACY_PHRASES = "semanticPhrases"
        const val CUSTOM_ID = "custom"
        private const val MAX_HISTORY = 1000
    }
}
