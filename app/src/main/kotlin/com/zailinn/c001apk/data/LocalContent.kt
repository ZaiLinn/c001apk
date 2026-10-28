package com.zailinn.c001apk.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zailinn.c001apk.core.model.ContentFilter
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.SensitiveCategory
import com.zailinn.c001apk.core.model.SensitiveMatcher
import com.zailinn.c001apk.core.model.TopicRule
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

/** A topic followed on this device; [id] reads its feeds, [title] opens its page. */
@Serializable
data class LocalFollowTopic(val id: String, val title: String, val logo: String = "")

/** History, favorites, blocking, search history and local follows (users and topics). Shared by all accounts. */
class LocalContent(private val store: LocalStore) {
    var favoritesChanged: suspend (List<FeedRecord>) -> Unit = {}
    private val records = ListSerializer(FeedRecord.serializer())
    private val strings = ListSerializer(String.serializer())
    private val follows = ListSerializer(LocalFollowUser.serializer())
    private val topicFollows = ListSerializer(LocalFollowTopic.serializer())
    private val topicRules = ListSerializer(TopicRule.serializer())

    val history: StateFlow<List<FeedRecord>> = store.state { store.readJson(it, HISTORY, records, emptyList()) }
    val favorites: StateFlow<List<FeedRecord>> = store.state { store.readJson(it, FAVORITES, records, emptyList()) }
    val blockedUsers: StateFlow<List<String>> = store.state { store.readJson(it, BLOCKED_USERS, strings, emptyList()) }
    /** The fixed filter category "我屏蔽的话题"; always removes. */
    val blockedTopics: StateFlow<List<TopicRule>> = store.state { store.readJson(it, TOPIC_RULES, topicRules, emptyList()) }
    val searchHistory: StateFlow<List<String>> = store.state { store.readJson(it, SEARCH_HISTORY, strings, emptyList()) }
    val localFollows: StateFlow<List<LocalFollowUser>> = store.state { store.readJson(it, LOCAL_FOLLOWS, follows, emptyList()) }
    val localFollowTopics: StateFlow<List<LocalFollowTopic>> = store.state { store.readJson(it, LOCAL_FOLLOW_TOPICS, topicFollows, emptyList()) }

    private val categories = ListSerializer(SensitiveCategory.serializer())

    /** Sensitive-topic categories, editable by the user; starts from [SensitiveCategory.builtIn]. */
    val filterCategories: StateFlow<List<SensitiveCategory>> = store.state(::readCategories)

    private var matcherKey: Any? = null
    private var matcher: SensitiveMatcher? = null

    /**
     * Blacklists always remove items. Enabled categories fold them or, with
     * [AppSettings.filterFold] off, remove them; without settings they are not applied.
     */
    fun filter(settings: AppSettings? = null) = ContentFilter(
        blockedUsers.value.toSet(),
        blockedTopics.value,
        settings?.let { sensitiveMatcher(it.filterStrict) },
        fold = settings?.filterFold ?: true,
    )

    /** Rebuilt only when the categories change. */
    @Synchronized
    private fun sensitiveMatcher(strict: Boolean): SensitiveMatcher? {
        val key = filterCategories.value.filter { it.enabled } to strict
        if (key != matcherKey) {
            matcher = SensitiveMatcher(key.first, strict).takeUnless { it.isEmpty }
            matcherKey = key
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
     * nights, sleep or company ("下夜班睡觉去了", "有纸就有床"). 2.1.6 dropped
     * #福利# (mostly giveaways), turned the topic blacklist into topic rules
     * (old entries keep matching any topic containing them) and replaced the
     * 折叠 and 直接隐藏 switches with one 折叠 switch: hiding stays hiding, and
     * both turned off now turns the categories off. Revision 5 adds the
     * online-dating words to 相亲恋爱. Revision 6 swaps words and patterns that hid ordinary
     * posts for narrower ones, moves the looser built-in words to the 严格模式 list and gives
     * 性压抑/擦边 its exception words; words the user added or already removed are left alone.
     */
    suspend fun migrateCategories() = store.edit { prefs ->
        val revision = prefs[intPreferencesKey(CATEGORIES_REVISION)] ?: 1
        if (revision >= CURRENT_REVISION) return@edit
        val oldFold = prefs[booleanPreferencesKey(OLD_FOLD)]
        val oldHide = prefs[booleanPreferencesKey(OLD_HIDE)]
        val turnedOff = revision < 4 && (oldFold != null || oldHide != null) && oldFold != true && oldHide != true
        val saved = prefs[stringPreferencesKey(FILTER_CATEGORIES)]
            ?.let { runCatching { storeJson.decodeFromString(categories, it) }.getOrNull() }
        if (saved != null || turnedOff) {
            val cleaned = (saved ?: readCategories(prefs)).map { c ->
                var fixed = c
                if (turnedOff) fixed = fixed.copy(enabled = false)
                if (c.id == "dating" && revision < 5) fixed = fixed.copy(keywords = (fixed.keywords + SensitiveCategory.addedDatingKeywords).distinct())
                if (revision < 6) fixed = retireRevision6(fixed)
                if (c.id != "lust") return@map fixed
                if (revision < 2) fixed = fixed.copy(tags = fixed.tags - setOf("酷安夜话", "夜话"))
                if (revision < 3) {
                    val examples = fixed.examples.map { if (it == DROPPED_EXAMPLE) REPLACED_EXAMPLE else it }
                    fixed = fixed.copy(examples = examples.distinct())
                }
                if (revision < 4) fixed = fixed.copy(tags = fixed.tags - "福利")
                fixed
            }
            prefs[stringPreferencesKey(FILTER_CATEGORIES)] = storeJson.encodeToString(categories, cleaned)
        }
        if (revision < 4) {
            if (oldHide != true && oldFold == true) prefs[booleanPreferencesKey(NEW_FOLD)] = true
            if (oldHide == true) prefs[booleanPreferencesKey(NEW_FOLD)] = false
            prefs.remove(booleanPreferencesKey(OLD_FOLD))
            prefs.remove(booleanPreferencesKey(OLD_HIDE))
            val old = store.readJson(prefs, BLOCKED_TOPICS, strings, emptyList()).filter { it.isNotBlank() }
            if (old.isNotEmpty()) {
                val rules = store.readJson(prefs, TOPIC_RULES, topicRules, emptyList())
                val merged = rules + old.filter { name -> rules.none { it.name == name } }.map { TopicRule(it, contains = true) }
                prefs[stringPreferencesKey(TOPIC_RULES)] = storeJson.encodeToString(topicRules, merged)
            }
            prefs.remove(stringPreferencesKey(BLOCKED_TOPICS))
        }
        prefs[intPreferencesKey(CATEGORIES_REVISION)] = CURRENT_REVISION
    }

    private fun retireRevision6(c: SensitiveCategory): SensitiveCategory {
        val builtIn = SensitiveCategory.builtIn(c.id) ?: return c
        // Built-in words that became strict move there; the user's own words stay as they were.
        val strict = builtIn.strictKeywords.filter { it in c.keywords }
        val keywords = (c.keywords - SensitiveCategory.retiredKeywords[c.id].orEmpty() - strict.toSet() +
            SensitiveCategory.addedKeywords[c.id].orEmpty()).distinct()
        val patterns = c.patterns.map { SensitiveCategory.retiredPatterns[it] ?: it }.distinct()
        return c.copy(
            keywords = keywords,
            patterns = patterns,
            exceptions = c.exceptions.ifEmpty { builtIn.exceptions },
            strictKeywords = (c.strictKeywords + strict + builtIn.strictKeywords.filter { it !in c.keywords && it in NEW_STRICT }).distinct(),
        )
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
        var updated = emptyList<FeedRecord>()
        store.updateJson(FAVORITES, records, emptyList()) { list ->
            (if (adding) listOf(FeedRecord.of(feed)) + list.filter { it.id != id } else list.filter { it.id != id }).also { updated = it }
        }
        favoritesChanged(updated)
        return adding
    }

    suspend fun removeFavorite(id: String) {
        var updated = emptyList<FeedRecord>()
        store.updateJson(FAVORITES, records, emptyList()) { it.filter { r -> r.id != id }.also { list -> updated = list } }
        favoritesChanged(updated)
    }
    suspend fun clearFavorites() {
        store.updateJson(FAVORITES, records, emptyList()) { emptyList() }
        favoritesChanged(emptyList())
    }

    fun isUserBlocked(uid: String) = uid in blockedUsers.value

    /** Returns false when the user is already blocked. */
    suspend fun block(uid: String): Boolean {
        if (uid.isBlank() || uid in blockedUsers.value) return false
        store.updateJson(BLOCKED_USERS, strings, emptyList()) { listOf(uid) + it.filter { v -> v != uid } }
        return true
    }

    suspend fun unblock(uid: String) = store.updateJson(BLOCKED_USERS, strings, emptyList()) { it.filter { v -> v != uid } }

    /** Adds or updates a topic rule; returns false when the same rule already exists. */
    suspend fun blockTopic(name: String, contains: Boolean = false): Boolean {
        val trimmed = name.trim().trim('#').trim()
        if (trimmed.isEmpty() || TopicRule(trimmed, contains) in blockedTopics.value) return false
        store.updateJson(TOPIC_RULES, topicRules, emptyList()) { listOf(TopicRule(trimmed, contains)) + it.filter { r -> r.name != trimmed } }
        return true
    }

    /** Removes every rule that blocks the topic page with this title, a 包含 rule too. */
    suspend fun unblockTopicTitle(title: String) {
        val matching = blockedTopics.value.filter { ContentFilter(blockedTopics = listOf(it)).isTopicBlocked(title) }.map { it.name }.toSet()
        store.updateJson(TOPIC_RULES, topicRules, emptyList()) { it.filter { r -> r.name !in matching } }
    }

    suspend fun unblockTopic(name: String) =
        store.updateJson(TOPIC_RULES, topicRules, emptyList()) { it.filter { r -> r.name != name } }

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

    fun isLocalFollowTopic(id: String) = localFollowTopics.value.any { it.id == id }

    suspend fun localFollowTopic(id: String, title: String, logo: String) {
        require(id.matches(Regex("^[0-9]+$")) && title.isNotBlank())
        store.updateJson(LOCAL_FOLLOW_TOPICS, topicFollows, emptyList()) { listOf(LocalFollowTopic(id, title, logo)) + it.filter { t -> t.id != id } }
    }

    suspend fun localUnfollowTopic(id: String) =
        store.updateJson(LOCAL_FOLLOW_TOPICS, topicFollows, emptyList()) { it.filter { t -> t.id != id } }

    companion object {
        private const val HISTORY = "historyFeedV1"
        private const val FAVORITES = "favFeedV1"
        private const val BLOCKED_USERS = "userBlackList"
        private const val BLOCKED_TOPICS = "topicBlackList"
        private const val SEARCH_HISTORY = "searchHistory"
        private const val LOCAL_FOLLOWS = "localFollowUsers"
        private const val LOCAL_FOLLOW_TOPICS = "localFollowTopics"
        private const val FILTER_CATEGORIES = "filterCategoriesV1"
        private const val CATEGORIES_REVISION = "filterCategoriesRevision"
        private const val TOPIC_RULES = "topicBlockRules"
        private const val CURRENT_REVISION = 6
        /** Strict words new in revision 6 rather than moved there, as on TG-Twilight/c001apk. */
        private val NEW_STRICT = setOf("女菩萨", "发福利")
        private const val OLD_FOLD = "sensitiveFold"
        private const val OLD_HIDE = "sensitiveHide"
        private const val NEW_FOLD = "filterFold"
        private const val DROPPED_EXAMPLE = "好想有个人陪我"
        private const val REPLACED_EXAMPLE = "单身好久了晚上好想有个女朋友陪"
        private const val LEGACY_ENABLED = "sensitiveFoldCategories"
        private const val LEGACY_WORDS = "sensitiveWords"
        private const val LEGACY_PHRASES = "semanticPhrases"
        const val CUSTOM_ID = "custom"
        private const val MAX_HISTORY = 1000
    }
}
