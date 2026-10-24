package com.zailinn.c001apk.core.net

import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

/** Credentials of the active account. Never displayed or logged. */
data class AccountCredentials(val uid: String, val username: String, val token: String) {
    /** The account part of the API Cookie header, as exported for another client. */
    fun toCookie() = "uid=$uid; username=$username; token=$token"

    companion object {
        private val cookieSafe = Regex("^[A-Za-z0-9%._~*-]+$")

        /**
         * Reads `uid`, `username` and `token` from pasted Cookie text. Other
         * cookies and a leading `Cookie:` are ignored; a value is cut at its
         * first `=` only, since a token may contain more.
         */
        fun parseCookie(text: String): AccountCredentials? {
            val values = text.trim().removePrefix("Cookie:").split(';', '\n').mapNotNull {
                val item = it.trim()
                val index = item.indexOf('=')
                if (index <= 0) null else item.substring(0, index).trim() to item.substring(index + 1).trim()
            }.toMap()
            val uid = values["uid"].orEmpty()
            val token = values["token"].orEmpty()
            val username = values["username"].orEmpty()
            if (!uid.matches(Regex("^\\d+$")) || token.isEmpty() || token.any { it.isWhitespace() } || username.isEmpty()) return null
            // Saved and sent URL-encoded, as the server issues it; a decoded name is encoded again.
            val encoded = if (cookieSafe.matches(username)) username else URLEncoder.encode(username, "UTF-8").replace("+", "%20")
            return AccountCredentials(uid, encoded, token)
        }
    }
}

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
