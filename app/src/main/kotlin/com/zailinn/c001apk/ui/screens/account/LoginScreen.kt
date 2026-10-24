package com.zailinn.c001apk.ui.screens.account

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zailinn.c001apk.AppContainer
import com.zailinn.c001apk.core.net.AccountCredentials
import com.zailinn.c001apk.core.net.ApiFailure
import com.zailinn.c001apk.core.net.ApiResponse
import com.zailinn.c001apk.core.protocol.Constants
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.nav.WebRoute
import com.zailinn.c001apk.ui.screens.appViewModel
import com.zailinn.c001apk.ui.screens.list.SimpleTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup

/** Password login on account.coolapk.com. Passwords are never stored. */
class LoginViewModel(private val app: AppContainer) : ViewModel() {
    val requestHash = MutableStateFlow<String?>(null)
    val captcha = MutableStateFlow<ByteArray?>(null)
    val submitting = MutableStateFlow(false)
    /** Emits when the web login should be opened instead. */
    val openWeb = MutableStateFlow(false)

    init {
        app.session.loginSession = ""
        viewModelScope.launch {
            try {
                rememberSession(app.repository.loginPage("/auth/login?type=mobile"))
                val page = app.repository.loginPage("/auth/loginByCoolApk")
                rememberSession(page)
                val hash = Jsoup.parse(page.text).body().attr("data-request-hash")
                if (hash.isEmpty()) app.toast("登录页面缺少验证参数，请稍后重试") else requestHash.value = hash
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.toast("无法获取登录参数：${ApiFailure.from(e).message}")
            }
        }
    }

    private fun rememberSession(response: ApiResponse) {
        response.cookies.firstOrNull { it.startsWith("SESSID=") }?.let { app.session.loginSession = it.substringBefore(';') }
    }

    fun refreshCaptcha() {
        viewModelScope.launch {
            try {
                val response = app.repository.loginPage("/auth/showCaptchaImage?${System.currentTimeMillis()}", image = true)
                captcha.value = response.bytes
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.toast("无法获取验证码：${ApiFailure.from(e).message}")
            }
        }
    }

    fun login(account: String, password: String, code: String) {
        val hash = requestHash.value ?: return
        if (submitting.value) return
        if (account.isEmpty() || password.isEmpty()) return app.toast("账号或密码为空")
        submitting.value = true
        viewModelScope.launch {
            try {
                val response = app.repository.login(hash, account, password, code)
                val body = Json.parseToJsonElement(response.text) as? JsonObject ?: throw ApiFailure.invalidResponse
                val status = (body["status"] as? JsonPrimitive)?.content?.toIntOrNull()
                val message = (body["message"] as? JsonPrimitive)?.content.orEmpty()
                if (status == 1) {
                    fun cookie(name: String) = response.cookies.lastOrNull { it.contains("$name=") }
                        ?.substringBefore(';')?.substringAfter("$name=")?.trim()
                    val uid = cookie("uid")
                    val username = cookie("username")
                    val token = cookie("token")
                    if (!uid.isNullOrEmpty() && !username.isNullOrEmpty() && !token.isNullOrEmpty()) {
                        app.accounts.saveLogin(uid, username, token)
                        app.toast("登录成功")
                    }
                } else {
                    if (message.isNotEmpty()) app.toast(message)
                    val marker = (body["messageStatus"] as? JsonPrimitive)?.content
                    when {
                        message in setOf("图形验证码不能为空", "图形验证码错误") || (captcha.value != null && message == "密码错误") -> refreshCaptcha()
                        message.contains("人机") || marker == "err_request_captcha_v2" -> openWeb.value = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.toast("登录失败：${ApiFailure.from(e).message}")
            } finally {
                submitting.value = false
            }
        }
    }

    /**
     * Saves an account from pasted Cookie text. The login check after every
     * account switch then expires it if the server rejects the token.
     */
    fun loginWithCookie(text: String) {
        if (submitting.value) return
        val account = AccountCredentials.parseCookie(text) ?: return app.toast("Cookie 缺少有效的 uid、username 或 token")
        submitting.value = true
        viewModelScope.launch {
            try {
                app.accounts.saveLogin(account.uid, account.username, account.token)
                app.toast("已使用 Cookie 登录")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                app.toast("无法保存 Cookie 登录状态，请重试")
            } finally {
                submitting.value = false
            }
        }
    }

    override fun onCleared() {
        app.session.loginSession = ""
    }
}

@Composable
fun LoginScreen() {
    val navigator = LocalNavigator.current
    val vm = appViewModel(key = "login") { LoginViewModel(it) }
    val hash by vm.requestHash.collectAsStateWithLifecycle()
    val captcha by vm.captcha.collectAsStateWithLifecycle()
    val submitting by vm.submitting.collectAsStateWithLifecycle()
    val openWeb by vm.openWeb.collectAsStateWithLifecycle()
    var account by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var cookieMode by rememberSaveable { mutableStateOf(false) }
    var cookie by remember { mutableStateOf("") }
    // A saved login switches the session, which returns the app to the main page.
    if (openWeb) DisposableEffect(Unit) {
        vm.openWeb.value = false
        navigator.navigate(WebRoute(Constants.URL_LOGIN, isLogin = true))
        onDispose {}
    }
    Scaffold(
        topBar = {
            SimpleTopBar("登录") {
                TextButton(onClick = { navigator.navigate(WebRoute(Constants.URL_LOGIN, isLogin = true)) }, enabled = !submitting) {
                    Text("网页登录")
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("遇到人机验证或无法直接登录时，请使用右上角的“网页登录”，在酷安网页中完成验证与登录。")
            TextButton(onClick = { cookieMode = !cookieMode }, enabled = !submitting) {
                Text(if (cookieMode) "使用账号密码登录" else "使用 Cookie 登录")
            }
            if (cookieMode) {
                Text("粘贴包含 uid、username 和 token 的 Cookie，例如从本应用其他设备的“导出 Cookie”复制。Cookie 等同于账号密码，不要使用他人提供的 Cookie。")
                OutlinedTextField(
                    cookie, { cookie = it }, Modifier.fillMaxWidth(), label = { Text("Cookie") }, minLines = 3, maxLines = 6,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "显示 Cookie")
                        }
                    },
                )
                if (submitting) {
                    LoadingIndicator(Modifier.align(Alignment.CenterHorizontally))
                } else {
                    Button(onClick = { vm.loginWithCookie(cookie) }, enabled = cookie.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text("登录")
                    }
                }
                return@Column
            }
            OutlinedTextField(
                account, { account = it }, Modifier.fillMaxWidth(), label = { Text("账号") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                password, { password = it }, Modifier.fillMaxWidth(), label = { Text("密码") }, singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.login(account, password, code) }),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "显示密码")
                    }
                },
            )
            captcha?.let { bytes ->
                val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    bitmap?.let { Image(it, "验证码", Modifier.weight(1f).height(56.dp).clickable { vm.refreshCaptcha() }) }
                    OutlinedTextField(
                        code, { code = it.filter(Char::isLetterOrDigit).take(4) }, Modifier.weight(1f),
                        label = { Text("验证码") }, singleLine = true,
                    )
                }
            }
            if (submitting) {
                LoadingIndicator(Modifier.align(Alignment.CenterHorizontally))
            } else {
                Button(onClick = { vm.login(account, password, code) }, enabled = hash != null, modifier = Modifier.fillMaxWidth()) {
                    Text("登录")
                }
            }
        }
    }
}
