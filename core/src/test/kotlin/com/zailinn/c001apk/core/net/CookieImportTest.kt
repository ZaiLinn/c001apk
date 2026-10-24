package com.zailinn.c001apk.core.net

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieImportTest {
    @Test
    fun exportedCookieReadsBack() {
        val account = AccountCredentials("12345", "%E5%90%88%E6%88%90", "synthetic.token==")
        assertEquals(account, AccountCredentials.parseCookie(account.toCookie()))
    }

    @Test
    fun browserCookieWithOtherEntriesIsRead() {
        val parsed = AccountCredentials.parseCookie(
            "Cookie: DID=abc; uid=12345;username=synthetic ; token=a=b=c; SESSID=x\n",
        )
        assertEquals(AccountCredentials("12345", "synthetic", "a=b=c"), parsed)
    }

    @Test
    fun decodedUsernameIsEncodedForTheHeader() {
        assertEquals("%E5%90%88%E6%88%90%20a", AccountCredentials.parseCookie("uid=1; username=合成 a; token=t")!!.username)
    }

    @Test
    fun incompleteCookieIsRejected() {
        assertNull(AccountCredentials.parseCookie("uid=1; token=t"))
        assertNull(AccountCredentials.parseCookie("uid=abc; username=u; token=t"))
        assertNull(AccountCredentials.parseCookie("uid=1; username=u; token="))
        assertNull(AccountCredentials.parseCookie("随便一段文字"))
    }

    @Test
    fun pastedCookieIsCheckedWithoutTouchingTheSession() = runTest {
        val h = Harness { _, _ -> 200 to """{"data":{"uid":"2"}}""" }
        h.session.activate(AccountCredentials("1", "active", "active-token"), notify = true)
        h.session.sessid = "SESSID=active-session"
        h.transport.setCookie = { "SESSID=issued-to-pasted; path=/" }
        h.repo.checkLoginInfo(AccountCredentials("2", "pasted", "pasted-token"))
        val cookie = h.transport.requests.single().header("Cookie")!!
        assertTrue(cookie.contains("uid=2") && cookie.contains("token=pasted-token"))
        assertFalse(cookie.contains("active"))
        assertEquals("SESSID=active-session", h.session.sessid)
        assertEquals("1", h.session.account?.uid)
    }
}
