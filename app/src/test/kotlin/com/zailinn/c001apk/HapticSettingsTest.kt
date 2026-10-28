package com.zailinn.c001apk

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.zailinn.c001apk.data.LocalStore
import com.zailinn.c001apk.data.SettingKeys
import com.zailinn.c001apk.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HapticSettingsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun feedbackDefaultsOnAndSavedChoiceSurvivesReopening() = runBlocking {
        val file = folder.newFile("settings.preferences_pb")
        suspend fun checkStore(expected: Boolean, changeTo: Boolean) {
            val job = SupervisorJob()
            val scope = CoroutineScope(job + Dispatchers.IO)
            try {
                val store = LocalStore(PreferenceDataStoreFactory.create(scope = scope) { file }, scope)
                val repository = SettingsRepository(store)
                withTimeout(5_000) { repository.settings.first { it.hapticFeedback == expected } }
                repository.set(SettingKeys.hapticFeedback, changeTo)
                withTimeout(5_000) { repository.settings.first { it.hapticFeedback == changeTo } }
            } finally {
                job.cancelAndJoin()
            }
        }
        assertTrue(com.zailinn.c001apk.data.AppSettings().hapticFeedback)
        checkStore(expected = true, changeTo = false)
        checkStore(expected = false, changeTo = true)
        checkStore(expected = true, changeTo = true)
    }
}
