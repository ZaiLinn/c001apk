package com.zailinn.c001apk

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.zailinn.c001apk.core.net.SessionState
import com.zailinn.c001apk.data.AccountManager
import com.zailinn.c001apk.data.LocalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A passed captcha lives on the API SESSID, which now survives a restart of the same account and device. */
class SessionPersistenceTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store by lazy { LocalStore(PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("s.preferences_pb") }, scope) }

    /** A new process: fresh session and account manager over the same storage. */
    private fun launch(device: MutableStateFlow<String>): Pair<SessionState, AccountManager> {
        val session = SessionState()
        val accounts = AccountManager(store, session)
        accounts.keepSession(device, scope)
        return session to accounts
    }

    private fun saved(sessid: String) = runBlocking {
        withTimeout(5_000) { while (!store.prefs.value.toString().contains(sessid)) delay(10) }
    }

    @Test
    fun guestSessionIsRestoredAfterRestart() {
        val device = MutableStateFlow("d1")
        launch(device).first.sessid = "SESSID=abc"
        saved("SESSID=abc")
        assertEquals("SESSID=abc", launch(device).first.sessid)
    }

    @Test
    fun accountSwitchStartsANewSession() = runBlocking {
        val device = MutableStateFlow("d1")
        val (session, accounts) = launch(device)
        session.sessid = "SESSID=guest"
        saved("SESSID=guest")
        accounts.saveLogin("1", "u", "t")
        session.sessid = "SESSID=account"
        saved("SESSID=account")
        assertEquals("SESSID=account", launch(device).first.sessid)
        accounts.useGuest()
        withTimeout(5_000) { while (store.prefs.value.toString().contains("SESSID=account")) delay(10) }
        assertEquals("", launch(device).first.sessid)
    }

    @Test
    fun anotherDeviceIdentityDoesNotReuseTheSession() {
        val device = MutableStateFlow("d1")
        launch(device).first.sessid = "SESSID=abc"
        saved("SESSID=abc")
        assertEquals("", launch(MutableStateFlow("d2")).first.sessid)
    }

    @Test
    fun importingADeviceIdDropsTheRunningSession() = runBlocking {
        val device = MutableStateFlow("d1")
        val (session, _) = launch(device)
        session.sessid = "SESSID=abc"
        device.value = "d2"
        withTimeout(5_000) { while (session.sessid.isNotEmpty()) delay(10) }
    }
}
