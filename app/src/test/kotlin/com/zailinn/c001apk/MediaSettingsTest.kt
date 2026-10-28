package com.zailinn.c001apk

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.zailinn.c001apk.core.model.MediaAutoPlay
import com.zailinn.c001apk.data.LocalStore
import com.zailinn.c001apk.data.SettingKeys
import com.zailinn.c001apk.data.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaSettingsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun autoplayDefaultsToWifiAndPersistsEveryChoiceAcrossReopening() = runBlocking {
        val file = folder.newFile("media.preferences_pb")
        var expected = MediaAutoPlay.Wifi
        for (next in listOf(MediaAutoPlay.Off, MediaAutoPlay.Always, MediaAutoPlay.Wifi)) {
            val job = SupervisorJob()
            val scope = CoroutineScope(job + Dispatchers.IO)
            try {
                val settings = SettingsRepository(LocalStore(PreferenceDataStoreFactory.create(scope = scope) { file }, scope))
                assertEquals(expected, withTimeout(5_000) { settings.settings.first { it.mediaAutoPlay == expected } }.mediaAutoPlay)
                settings.set(SettingKeys.mediaAutoPlay, next.name)
                withTimeout(5_000) { settings.settings.first { it.mediaAutoPlay == next } }
                expected = next
            } finally { job.cancelAndJoin() }
        }
    }
}
