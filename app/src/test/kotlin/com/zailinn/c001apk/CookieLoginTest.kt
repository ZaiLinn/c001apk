package com.zailinn.c001apk

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.zailinn.c001apk.ui.screens.account.LoginViewModel
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

/** A pasted Cookie is checked with the server first; only an accepted one becomes an account. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CookieLoginTest {
    private val cookie = "uid=12345; username=synthetic; token=pasted-token"

    private fun app(checkLoginInfo: String): Pair<AppContainer, MutableList<Boolean>> {
        val checked = mutableListOf<Boolean>()
        val transport = Interceptor { chain ->
            val req = chain.request()
            val body = if (req.url.encodedPath.endsWith("/v6/account/checkLoginInfo")) {
                checked += req.header("Cookie").orEmpty().contains("token=pasted-token")
                checkLoginInfo
            } else "{}"
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
        return AppContainer(ApplicationProvider.getApplicationContext(), "test-${UUID.randomUUID()}", transport) to checked
    }

    private fun waitFor(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(20)
        }
        assertTrue("condition not met within timeout", predicate())
    }

    @Test
    fun acceptedCookieIsSavedWithTheRefreshedToken() {
        val (app, checked) = app("""{"data":{"uid":"12345","username":"synthetic","token":"refreshed-token"}}""")
        val vm = LoginViewModel(app)
        vm.loginWithCookie(cookie)
        waitFor { app.accounts.active != null }
        assertEquals(listOf(true), checked)
        assertEquals("refreshed-token", app.accounts.active!!.token)
    }

    @Test
    fun rejectedCookieIsNotSaved() {
        val (app, checked) = app("""{"message":"登录信息有误","data":null}""")
        val vm = LoginViewModel(app)
        vm.loginWithCookie(cookie)
        waitFor { checked.isNotEmpty() && !vm.submitting.value }
        assertTrue(app.accounts.vault.value.accounts.isEmpty())
    }

    @Test
    fun cookieOfAnotherAccountIsNotSaved() {
        val (app, checked) = app("""{"data":{"uid":"999","token":"t"}}""")
        val vm = LoginViewModel(app)
        vm.loginWithCookie(cookie)
        waitFor { checked.isNotEmpty() && !vm.submitting.value }
        assertTrue(app.accounts.vault.value.accounts.isEmpty())
    }
}
