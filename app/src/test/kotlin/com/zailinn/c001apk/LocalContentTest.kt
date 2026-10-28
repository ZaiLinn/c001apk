package com.zailinn.c001apk

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.zailinn.c001apk.core.model.SensitiveCategory
import com.zailinn.c001apk.core.model.TopicRule
import com.zailinn.c001apk.data.LocalContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Editable filter categories and the migration of 2.0.0's separate lists. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalContentTest {
    private val app = AppContainer(ApplicationProvider.getApplicationContext(), "test-${UUID.randomUUID()}")

    private fun waitFor(check: () -> Boolean) = runBlocking { withTimeout(5_000) { while (!check()) delay(20) } }

    @Test
    fun legacyListsBecomeCategories() = runBlocking {
        app.settings.set(stringPreferencesKey("sensitiveFoldCategories"), "couple,lust")
        app.settings.set(stringPreferencesKey("sensitiveWords"), """["内卷","#打工人#"]""")
        app.settings.set(stringPreferencesKey("semanticPhrases"), """["晒奢侈品"]""")
        // Each write emits on its own, so wait until all three lists have landed.
        waitFor {
            app.content.filterCategories.value.any { it.id == LocalContent.CUSTOM_ID && it.keywords.isNotEmpty() && it.examples.isNotEmpty() }
        }
        val categories = app.content.filterCategories.value
        assertEquals(listOf("couple", "dating", "lust", "gender", "custom"), categories.map { it.id })
        assertEquals(listOf(true, false, true, false, true), categories.map { it.enabled })
        val custom = categories.last()
        assertEquals(listOf("内卷"), custom.keywords)
        assertEquals(listOf("打工人"), custom.tags)
        assertEquals(listOf("晒奢侈品"), custom.examples)
    }

    @Test
    fun savedCategoriesLoseTheDroppedTopicsOnce() = runBlocking {
        val old = SensitiveCategory.builtIn.map { if (it.id == "lust") it.copy(tags = listOf("酷安夜话", "夜话") + it.tags) else it }
        app.content.updateCategories { old }
        // As saved by 2.0.2, before this cleanup existed.
        app.settings.set(androidx.datastore.preferences.core.intPreferencesKey("filterCategoriesRevision"), 1)
        waitFor { "酷安夜话" in app.content.filterCategories.value.single { it.id == "lust" }.tags }
        app.content.migrateCategories()
        waitFor { "酷安夜话" !in app.content.filterCategories.value.single { it.id == "lust" }.tags }
        assertFalse("夜话" in app.content.filterCategories.value.single { it.id == "lust" }.tags)
        // The user may add them back; the cleanup does not run twice.
        app.content.updateCategory("lust") { it.copy(tags = it.tags + "酷安夜话") }
        app.content.migrateCategories()
        waitFor { "酷安夜话" in app.content.filterCategories.value.single { it.id == "lust" }.tags }
    }

    @Test
    fun savedLustExampleIsReplacedOnce() = runBlocking {
        val old = SensitiveCategory.builtIn.map {
            if (it.id == "lust") it.copy(examples = listOf("好想有个人陪我") + it.examples.drop(1)) else it
        }
        app.content.updateCategories { old }
        // As saved by 2.0.4.
        app.settings.set(androidx.datastore.preferences.core.intPreferencesKey("filterCategoriesRevision"), 2)
        waitFor { "好想有个人陪我" in app.content.filterCategories.value.single { it.id == "lust" }.examples }
        app.content.migrateCategories()
        waitFor { "好想有个人陪我" !in app.content.filterCategories.value.single { it.id == "lust" }.examples }
        val examples = app.content.filterCategories.value.single { it.id == "lust" }.examples
        assertEquals(1, examples.count { it == "单身好久了晚上好想有个女朋友陪" })
    }

    @Test
    fun revisionFourMigratesTopicsSwitchesAndDropsTheGiveawayTag() = runBlocking {
        val old = SensitiveCategory.builtIn.map { if (it.id == "lust") it.copy(tags = it.tags + "福利") else it }
        app.content.updateCategories { old }
        // As saved by 2.1.5: a topic blacklist and only 直接隐藏 on.
        app.settings.set(stringPreferencesKey("topicBlackList"), """["游戏"]""")
        app.settings.set(booleanPreferencesKey("sensitiveHide"), true)
        app.settings.set(androidx.datastore.preferences.core.intPreferencesKey("filterCategoriesRevision"), 3)
        waitFor { "福利" in app.content.filterCategories.value.single { it.id == "lust" }.tags }
        app.content.migrateCategories()
        waitFor { app.content.blockedTopics.value.isNotEmpty() }
        // Each list is its own state flow; the categories may arrive a moment after the topics.
        waitFor { "福利" !in app.content.filterCategories.value.single { it.id == "lust" }.tags }
        // Old entries keep matching every topic that contains them.
        assertEquals(listOf(TopicRule("游戏", contains = true)), app.content.blockedTopics.value)
        assertTrue(app.content.filter().isTopicBlocked("手机游戏"))
        waitFor { !app.settings.current.filterFold }
        // A new block from a topic page matches that topic only.
        app.content.blockTopic("#数码#")
        waitFor { app.content.blockedTopics.value.size == 2 }
        assertTrue(app.content.filter().isTopicBlocked("数码"))
        assertFalse(app.content.filter().isTopicBlocked("数码评测"))
        app.content.unblockTopicTitle("手机游戏")
        waitFor { app.content.blockedTopics.value.map { it.name } == listOf("数码") }
    }

    @Test
    fun savedDatingCategoryGainsTheOnlineDatingWordsOnce() = runBlocking {
        val old = SensitiveCategory.builtIn.map { if (it.id == "dating") it.copy(keywords = it.keywords - SensitiveCategory.addedDatingKeywords.toSet()) else it }
        app.content.updateCategories { old }
        // As saved by 2.1.6 before the online-dating words.
        app.settings.set(androidx.datastore.preferences.core.intPreferencesKey("filterCategoriesRevision"), 4)
        waitFor { "交友软件" !in app.content.filterCategories.value.single { it.id == "dating" }.keywords }
        app.content.migrateCategories()
        waitFor { "交友软件" in app.content.filterCategories.value.single { it.id == "dating" }.keywords }
        val keywords = app.content.filterCategories.value.single { it.id == "dating" }.keywords
        assertEquals(1, keywords.count { it == "网恋" })
    }

    @Test
    fun savedCategoriesDropTheWordsThatHidOrdinaryPosts() = runBlocking {
        // As saved by 2.1.6: old words, a word of the user's own, the loose words still plain
        // keywords, and no exception or strict words. 丝袜 the user had already deleted.
        val old = SensitiveCategory.builtIn.map { c ->
            val plain = c.keywords - setOf("分手了", "找个对象") + (c.strictKeywords - setOf("女菩萨", "发福利", "丝袜"))
            when (c.id) {
                "dating" -> c.copy(keywords = plain + "被拒绝了" + "约会", strictKeywords = emptyList())
                "lust" -> c.copy(keywords = plain + listOf("色图", "瑟瑟"), strictKeywords = emptyList(), exceptions = emptyList())
                else -> c.copy(keywords = plain, strictKeywords = emptyList())
            }
        }
        app.content.updateCategories { old }
        app.settings.set(androidx.datastore.preferences.core.intPreferencesKey("filterCategoriesRevision"), 5)
        waitFor { "被拒绝了" in app.content.filterCategories.value.single { it.id == "dating" }.keywords }
        app.content.migrateCategories()
        waitFor { "被拒绝了" !in app.content.filterCategories.value.single { it.id == "dating" }.keywords }
        val dating = app.content.filterCategories.value.single { it.id == "dating" }
        assertTrue(listOf("分手", "分手了", "找对象", "找个对象", "约会").all { it in dating.keywords })
        assertEquals(listOf("表白", "前任"), dating.strictKeywords)
        assertFalse("表白" in dating.keywords)
        val lust = app.content.filterCategories.value.single { it.id == "lust" }
        assertFalse("色图" in lust.keywords || "瑟瑟" in lust.keywords || "小姐姐" in lust.keywords)
        assertTrue("小姐姐" in lust.strictKeywords && "女菩萨" in lust.strictKeywords)
        assertFalse("丝袜" in lust.strictKeywords || "丝袜" in lust.keywords)
        assertEquals(SensitiveCategory.builtIn("lust")!!.exceptions, lust.exceptions)
    }

    @Test
    fun bothOldSwitchesOffTurnsTheCategoriesOff() = runBlocking {
        app.settings.set(booleanPreferencesKey("sensitiveFold"), false)
        app.settings.set(booleanPreferencesKey("sensitiveHide"), false)
        app.settings.set(androidx.datastore.preferences.core.intPreferencesKey("filterCategoriesRevision"), 3)
        app.content.migrateCategories()
        waitFor { app.content.filterCategories.value.none { it.enabled } }
        assertTrue(app.settings.current.filterFold)
    }

    @Test
    fun editDeleteAndRestore() = runBlocking {
        val id = app.content.addCategory("  奢侈品 ")
        app.content.updateCategory(id) { it.copy(keywords = listOf("爱马仕")) }
        app.content.updateCategory("couple") { it.copy(keywords = emptyList(), enabled = false) }
        waitFor { app.content.filterCategories.value.any { it.keywords == listOf("爱马仕") } }
        assertEquals("奢侈品", app.content.filterCategories.value.single { it.id == id }.title)

        app.content.restoreCategory("couple")
        waitFor { app.content.filterCategories.value.single { it.id == "couple" }.keywords.isNotEmpty() }
        // Restoring keeps whether the category was switched on.
        assertFalse(app.content.filterCategories.value.single { it.id == "couple" }.enabled)

        app.content.deleteCategory("gender")
        waitFor { app.content.filterCategories.value.none { it.id == "gender" } }
        app.content.restoreAllCategories()
        waitFor { app.content.filterCategories.value.any { it.id == "gender" } }
        assertEquals(SensitiveCategory.builtIn.map { it.id } + id, app.content.filterCategories.value.map { it.id })

        // Without settings (topic pages, previews), categories do not filter.
        assertNull(app.content.filter().sensitive)
    }
}

class SplitEntriesTest {
    @Test
    fun wordsSplitOnCommonSeparators() {
        assertEquals(
            listOf("内卷", "躺平", "摆烂", "打工"),
            com.zailinn.c001apk.ui.screens.settings.splitEntries(" 内卷、躺平，摆烂 , 打工 ", multiple = true),
        )
        // Sentences and patterns keep their spaces and punctuation.
        assertEquals(listOf("晒 自己的、奢侈品"), com.zailinn.c001apk.ui.screens.settings.splitEntries("晒 自己的、奢侈品", multiple = false))
    }

    @Test
    fun historyPreviewNeverCutsATagInHalf() {
        val link = """仅拆封试听 【闲鱼】<a class="feed-link-url" href="https://m.tb.cn/h.x">https://m.tb.cn/h.x</a>"""
        assertEquals("仅拆封试听 【闲鱼】", com.zailinn.c001apk.data.FeedRecord.preview(link, max = 30))
        assertEquals("a ", com.zailinn.c001apk.data.FeedRecord.preview("a &amp; b", max = 4))
        assertEquals(link, com.zailinn.c001apk.data.FeedRecord.preview(link, max = 500))
    }
}
