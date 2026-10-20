package com.zailinn.c001apk

import androidx.compose.ui.text.font.FontWeight
import com.zailinn.c001apk.ui.screens.main.HomeTab
import com.zailinn.c001apk.ui.theme.typographyFor
import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceSettingsTest {
    @Test
    fun homeTabsKeepSavedOrderAndFallBackToAll() {
        assertEquals(listOf(HomeTab.Hot, HomeTab.Feed), HomeTab.visible(listOf("Hot", "Removed", "Feed", "Hot")))
        assertEquals(HomeTab.entries, HomeTab.visible(emptyList()))
        assertEquals(HomeTab.entries, HomeTab.visible(listOf("Removed")))
    }

    @Test
    fun fontWeightLevelThickensEveryStyle() {
        val standard = typographyFor(0)
        val bold = typographyFor(2)
        assertEquals(FontWeight.Normal, standard.bodyLarge.fontWeight)
        assertEquals(FontWeight.SemiBold, bold.bodyLarge.fontWeight)
        assertEquals(FontWeight(standard.titleMedium.fontWeight!!.weight + 200), bold.titleMedium.fontWeight)
    }
}
