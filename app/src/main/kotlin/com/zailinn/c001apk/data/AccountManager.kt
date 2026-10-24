package com.zailinn.c001apk.data

import com.zailinn.c001apk.core.net.AccountCredentials
import com.zailinn.c001apk.core.net.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.net.URLDecoder

/** Session credentials stay on this device. Passwords are never retained. */
@Serializable
data class SavedAccount(
    val uid: String,
    /** URL-encoded, as the server sends it in cookies. */
    val username: String = "",
    val token: String = "",
    val avatar: String = "",
    val level: Int = 0,
    val exp: Int = 0,
    val nextExp: Int = 1,
) {
    val needsLogin: Boolean get() = token.isEmpty()
    val displayName: String get() = runCatching { URLDecoder.decode(username, "UTF-8") }.getOrDefault(username)
}

@Serializable
data class AccountVault(val accounts: List<SavedAccount> = emptyList(), val activeUid: String? = null) {
    val active: SavedAccount? get() = accounts.firstOrNull { it.uid == activeUid && !it.needsLogin }
}

/** The API session of one account (or the guest) on one device identity. */
@Serializable
private data class SavedSession(val owner: String = "", val sessid: String = "")

/**
 * Multi-account switching. Every switch bumps the session generation so pages
 * of the previous account reload and their late responses are discarded.
 */
class AccountManager(private val store: LocalStore, val session: SessionState) {
    private val mutex = Mutex()
    private val _vault = MutableStateFlow(store.readJson(store.prefs.value, KEY, AccountVault.serializer(), AccountVault()))
    val vault: StateFlow<AccountVault> = _vault.asStateFlow()

    /** Increments when the active account changes; UI keys account-bound screens on it. */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    val active: SavedAccount? get() = _vault.value.active

    init {
        session.activate(_vault.value.active?.credentials(), notify = false)
    }

    /**
     * Keeps the API SESSID, on which the server remembers a passed captcha, across
     * restarts; without it every cold start asked again. It belongs to the account
     * and [device] identity it was issued for: either changing starts a new session.
     */
    fun keepSession(device: StateFlow<String>, scope: CoroutineScope) {
        fun owner() = "${session.account?.uid ?: "guest"}|${device.value}"
        val saved = store.readJson(store.prefs.value, SESSION_KEY, SavedSession.serializer(), SavedSession())
        if (saved.owner == owner() && saved.sessid.startsWith("SESSID=")) session.sessid = saved.sessid
        val latest = MutableStateFlow(saved)
        session.onSessidChanged = { latest.value = SavedSession(owner(), it) }
        scope.launch {
            var written = saved
            latest.collect { entry ->
                if (entry != written) {
                    store.updateJson(SESSION_KEY, SavedSession.serializer(), SavedSession()) { entry }
                    written = entry
                }
            }
        }
        val startDevice = device.value
        scope.launch {
            var current = startDevice
            device.collect { if (it != current) { current = it; session.sessid = "" } }
        }
    }

    suspend fun saveLogin(uid: String, username: String, token: String) = mutex.withLock {
        require(uid.matches(Regex("^\\d+$")) && token.isNotEmpty()) { "登录响应缺少有效账号信息" }
        val previous = _vault.value.accounts.firstOrNull { it.uid == uid }
        val account = SavedAccount(
            uid, username, token, previous?.avatar.orEmpty(), previous?.level ?: 0, previous?.exp ?: 0, previous?.nextExp ?: 1,
        )
        persist(_vault.value.accounts.filter { it.uid != uid } + account, account, notify = true)
    }

    suspend fun switchTo(uid: String) = mutex.withLock {
        val account = _vault.value.accounts.firstOrNull { it.uid == uid }
        check(account != null && !account.needsLogin) { "此账号需要重新登录" }
        if (active?.uid == uid) return@withLock
        persist(_vault.value.accounts, account, notify = true)
    }

    suspend fun useGuest() = mutex.withLock { persist(_vault.value.accounts, null, notify = true) }

    suspend fun remove(uid: String) = mutex.withLock {
        val wasActive = active?.uid == uid
        persist(_vault.value.accounts.filter { it.uid != uid }, if (wasActive) null else active, notify = wasActive)
    }

    /** Server reported invalid credentials: keep the entry, require login again. */
    suspend fun expireCurrent(generation: Int) = mutex.withLock {
        if (generation != session.generation) return@withLock
        val current = active ?: return@withLock
        val all = _vault.value.accounts.map { if (it.uid == current.uid) it.copy(token = "") else it }
        persist(all, null, notify = true)
    }

    suspend fun refreshCurrent(
        generation: Int,
        username: String? = null,
        token: String? = null,
        avatar: String? = null,
        level: Int? = null,
        exp: Int? = null,
        nextExp: Int? = null,
    ) = mutex.withLock {
        if (generation != session.generation) return@withLock
        val current = active ?: return@withLock
        val updated = current.copy(
            username = username ?: current.username,
            token = token ?: current.token,
            avatar = avatar ?: current.avatar,
            level = level ?: current.level,
            exp = exp ?: current.exp,
            nextExp = (nextExp ?: current.nextExp).coerceAtLeast(1),
        )
        persist(_vault.value.accounts.map { if (it.uid == updated.uid) updated else it }, updated, notify = false)
        session.refresh(updated.username, updated.token)
    }

    private suspend fun persist(all: List<SavedAccount>, active: SavedAccount?, notify: Boolean) {
        val vault = AccountVault(all, active?.uid)
        store.updateJson(KEY, AccountVault.serializer(), AccountVault()) { vault }
        _vault.value = vault
        if (notify) {
            session.activate(active?.credentials(), notify = true)
            _generation.value = session.generation
        }
    }

    private fun SavedAccount.credentials() = AccountCredentials(uid, username, token)

    companion object {
        private const val KEY = "accountVaultV1"
        private const val SESSION_KEY = "apiSessionV1"
    }
}
