package com.zailinn.c001apk.core.net

import java.util.concurrent.atomic.AtomicInteger

/** Credentials of the active account. Never displayed or logged. */
data class AccountCredentials(val uid: String, val username: String, val token: String)

/**
 * In-memory session shared by all requests. Switching accounts bumps the
 * generation so responses of the previous account are discarded.
 */
class SessionState {
    @Volatile var account: AccountCredentials? = null
        private set

    /** `SESSID=...` last issued by the API; carries passed captcha verification. */
    @Volatile var sessid: String = ""

    /** `SESSID=...` of the account.coolapk.com password login page. */
    @Volatile var loginSession: String = ""

    private val counter = AtomicInteger(0)
    val generation: Int get() = counter.get()

    val isLogin: Boolean get() = account != null

    fun activate(account: AccountCredentials?, notify: Boolean) {
        if (notify) counter.incrementAndGet()
        this.account = account
        if (notify || account == null) sessid = ""
        if (notify) loginSession = ""
    }

    /** Token refreshed by the server for the same uid; not an account switch. */
    fun refresh(username: String?, token: String?) {
        val current = account ?: return
        account = current.copy(username = username ?: current.username, token = token ?: current.token)
    }
}
