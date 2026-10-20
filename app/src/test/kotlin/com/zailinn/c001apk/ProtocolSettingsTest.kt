package com.zailinn.c001apk

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.zailinn.c001apk.core.protocol.DeviceProfile
import com.zailinn.c001apk.core.protocol.ProtocolParams
import com.zailinn.c001apk.core.protocol.RealDevices
import com.zailinn.c001apk.data.LocalStore
import com.zailinn.c001apk.data.PROTOCOL_GENERATED
import com.zailinn.c001apk.data.PROTOCOL_LOCAL
import com.zailinn.c001apk.data.SettingKeys
import com.zailinn.c001apk.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Device parameters follow this phone by default, or a generated real model. */
class ProtocolSettingsTest {
    @get:Rule val folder = TemporaryFolder()

    private val phone = DeviceProfile("Xiaomi", "Redmi", "24069RA21C", "AQ3A.240627.003", "35", "15")

    private fun repository(): SettingsRepository {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = LocalStore(PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb") }, scope)
        return SettingsRepository(store) { phone }
    }

    private fun SettingsRepository.await(condition: SettingsRepository.() -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }

    @Test
    fun firstLaunchUsesThisPhone() = runBlocking {
        val settings = repository()
        settings.initialize()
        settings.await { current.protocol.profile == phone }
        assertEquals(PROTOCOL_LOCAL, settings.current.protocolSource)
        assertEquals(32, settings.current.protocol.deviceId?.length)
    }

    @Test
    fun existingInstallMovesToThisPhoneKeepingItsDeviceId() = runBlocking {
        val settings = repository()
        val old = ProtocolParams.generate()
        settings.set(SettingKeys.installTime, "1")
        settings.saveProtocol(old)
        settings.await { current.installTime == "1" && current.protocol == old }
        settings.initialize()
        settings.await { current.protocol.profile == phone }
        assertEquals(old.deviceId, settings.current.protocol.deviceId)
    }

    @Test
    fun regeneratingPicksAnotherRealModelAndStaysUntilLocalIsChosen() = runBlocking {
        val settings = repository()
        settings.initialize()
        settings.await { current.protocol.profile == phone }
        val localId = settings.current.protocol.deviceId
        settings.regenerateProtocol()
        settings.await { current.protocolSource == PROTOCOL_GENERATED }
        val generated = settings.current.protocol
        assertTrue(generated.profile in RealDevices.profiles)
        assertNotEquals(localId, generated.deviceId)
        // A later launch keeps the generated model.
        settings.initialize()
        assertEquals(generated, settings.current.protocol)
        settings.useLocalDevice()
        settings.await { current.protocolSource == PROTOCOL_LOCAL }
        assertEquals(phone, settings.current.protocol.profile)
        assertEquals(generated.deviceId, settings.current.protocol.deviceId)
    }
}
