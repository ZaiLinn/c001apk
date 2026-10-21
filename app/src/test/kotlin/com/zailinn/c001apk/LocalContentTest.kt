package com.zailinn.c001apk

import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.zailinn.c001apk.core.model.SensitiveCategory
import com.zailinn.c001apk.data.LocalContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

        // With both switches off, categories do not filter at all.
        assertNull(app.content.filter(app.settings.current).sensitive)
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
