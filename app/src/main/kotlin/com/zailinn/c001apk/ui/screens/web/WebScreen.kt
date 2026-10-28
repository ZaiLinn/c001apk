package com.zailinn.c001apk.ui.screens.web

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.zailinn.c001apk.AppContainer
import com.zailinn.c001apk.core.protocol.Constants
import com.zailinn.c001apk.core.net.HttpsUpgrade
import com.zailinn.c001apk.ui.common.AppDropdownMenu
import com.zailinn.c001apk.ui.common.hapticClick
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.screens.appContainer
import com.zailinn.c001apk.ui.screens.list.SimpleTopBar
import kotlinx.coroutines.launch

private val coolapkHosts = listOf("www.coolapk.com", "account.coolapk.com", "coolapk.com")

/**
 * Replaces only Coolapk authentication cookies, keeping unrelated site data.
 * The login page starts without the current account so a new one can sign in.
 */
fun prepareWebSession(app: AppContainer, includeAccount: Boolean) {
    val cookies = CookieManager.getInstance()
    cookies.setAcceptCookie(true)
    for (name in listOf("uid", "username", "token", "SESSID", "ddid")) {
        cookies.setCookie("https://www.coolapk.com/", "$name=; Max-Age=0; Domain=.coolapk.com; Path=/")
        for (host in coolapkHosts) cookies.setCookie("https://$host/", "$name=; Max-Age=0; Path=/")
    }
    val account = app.session.account
    if (includeAccount && account != null) {
        val values = buildMap {
            put("DID", app.settings.current.protocol.szlmId)
            put("forward", "https://www.coolapk.com")
            put("displayVersion", "v16")
            put("uid", account.uid)
            put("username", account.username)
            put("token", account.token)
            if (app.session.sessid.startsWith("SESSID=")) put("SESSID", app.session.sessid.removePrefix("SESSID="))
        }
        values.filterValues { it.isNotEmpty() }.forEach { (name, value) ->
            cookies.setCookie("https://www.coolapk.com/", "$name=$value; Domain=.coolapk.com; Path=/; Secure")
        }
    }
    cookies.flush()
}

private fun cookieValues(url: String): Map<String, String> =
    CookieManager.getInstance().getCookie(url).orEmpty().split(';').mapNotNull {
        val parts = it.trim().split('=', limit = 2)
        if (parts.size == 2) parts[0] to parts[1] else null
    }.toMap()

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(url: String, isLogin: Boolean) {
    val navigator = LocalNavigator.current
    val app = appContainer()
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val ready = remember { prepareWebSession(app, includeAccount = !isLogin); true }

    // Only the official top-level landing page can complete a login.
    fun completeLogin(page: String?) {
        if (!isLogin || saving || page == null) return
        val uri = runCatching { Uri.parse(page) }.getOrNull() ?: return
        if (uri.scheme != "https" || uri.host != "www.coolapk.com" || (uri.path.orEmpty().isNotEmpty() && uri.path != "/")) return
        val values = cookieValues(Constants.URL_COOLAPK)
        val uid = values["uid"]
        val username = values["username"]
        val token = values["token"]
        if (uid.isNullOrEmpty() || username.isNullOrEmpty() || token.isNullOrEmpty()) return
        saving = true
        scope.launch {
            try {
                app.accounts.saveLogin(uid, username, token)
                app.toast("登录成功")
                navigator.backToMain()
            } catch (_: Exception) {
                app.toast("无法保存网页登录状态，请重试")
            } finally {
                saving = false
            }
        }
    }

    // Web history first, then the page itself.
    BackHandler(canGoBack) { web?.goBack() }
    Scaffold(
        topBar = {
            Column {
                SimpleTopBar(title) {
                    IconButton(onClick = hapticClick { menu = true }) { Icon(Icons.Outlined.MoreVert, "更多") }
                    AppDropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("刷新") }, { menu = false; web?.reload() })
                        DropdownMenuItem({ Text("复制") }, { menu = false; web?.url?.let { navigator.copy(it) } })
                        DropdownMenuItem({ Text("在浏览器中打开") }, { menu = false; navigator.launchBrowser(web?.url ?: url) })
                        DropdownMenuItem({ Text("清理缓存") }, {
                            menu = false
                            web?.clearCache(true)
                            web?.clearHistory()
                            app.toast("已清理")
                        })
                    }
                }
                if (progress < 1f) LinearWavyProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (ready) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            // AndroidView's default WRAP_CONTENT height makes WebView lay pages out with zero
                            // height, so 100% and 100vh are 0 and full-height pages (配置对比) show nothing.
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = app.settings.current.protocol.userAgent.ifEmpty { settings.userAgentString }
                            // Images only: Coolapk pages still link product pictures over http. Those reach
                            // shouldInterceptRequest below and load over https; scripts stay blocked.
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress / 100f }
                                override fun onReceivedTitle(view: WebView, value: String?) { title = value.orEmpty() }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val target = request.url.toString()
                                    if (request.url.scheme == "https") return false
                                    if (request.url.scheme == "http") {
                                        // Frames stay blocked; only the page itself may move on.
                                        if (!request.isForMainFrame) return true
                                        val secureUrl = HttpsUpgrade.url(target)
                                        if (HttpsUpgrade.isCoolapk(target)) {
                                            if (request.method == "GET" && secureUrl != null) view.loadUrl(secureUrl)
                                            else error = "网页尝试使用不安全连接，已阻止"
                                        } else {
                                            // Other sites may have no HTTPS; the browser decides how to open them.
                                            app.toast("该网页未加密，已在系统中打开")
                                            navigator.launchBrowser(target)
                                        }
                                        return true
                                    }
                                    app.toast("当前网页将要打开外部链接，已在系统中打开")
                                    navigator.launchBrowser(target)
                                    return true
                                }

                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                    val target = request.url.toString()
                                    if (request.isForMainFrame || request.method != "GET" || request.url.scheme != "http" || !HttpsUpgrade.isCoolapk(target)) return null
                                    // Cleartext is not permitted, so the http address would fail; the app's client fetches it over https.
                                    val secureUrl = HttpsUpgrade.url(target) ?: return null
                                    return runCatching {
                                        val response = app.imageHttp.newCall(okhttp3.Request.Builder().url(secureUrl).build()).execute()
                                        val body = response.body ?: return@runCatching null.also { response.close() }
                                        val type = body.contentType()
                                        WebResourceResponse(
                                            type?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream",
                                            type?.charset()?.name(),
                                            response.code,
                                            response.message.ifEmpty { "OK" },
                                            response.headers.toMap(),
                                            body.byteStream(),
                                        )
                                    }.getOrNull()
                                }

                                override fun onPageStarted(view: WebView, page: String?, favicon: Bitmap?) { error = null }

                                override fun onPageFinished(view: WebView, page: String?) {
                                    canGoBack = view.canGoBack()
                                    completeLogin(page)
                                }

                                // Also covers in-page navigation that never finishes a page load.
                                override fun doUpdateVisitedHistory(view: WebView, page: String?, isReload: Boolean) {
                                    canGoBack = view.canGoBack()
                                }

                                override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                                    if (request.isForMainFrame) error = "网页加载失败，请检查网络后重新加载"
                                }

                                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                    if (request.isForMainFrame && response.statusCode >= 400) {
                                        error = "网页暂时无法访问（HTTP ${response.statusCode}），请稍后重试"
                                    }
                                }
                            }
                            setDownloadListener { link, _, disposition, mime, _ ->
                                val secureLink = HttpsUpgrade.url(link)
                                if (secureLink == null) {
                                    app.toast("不支持此下载链接")
                                    return@setDownloadListener
                                }
                                val name = URLUtil.guessFileName(secureLink, disposition, mime)
                                navigator.copy(secureLink, toast = false)
                                runCatching {
                                    val request = DownloadManager.Request(secureLink.toUri()).setTitle(name)
                                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                        .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, name)
                                    context.getSystemService(DownloadManager::class.java).enqueue(request)
                                    app.toast("已开始下载 $name")
                                }.onFailure { navigator.launchBrowser(secureLink) }
                            }
                            val secureUrl = HttpsUpgrade.url(url)
                            when {
                                secureUrl == null -> error = "不支持此网页链接"
                                // openLink sends these to the browser; other entries land here.
                                url.startsWith("http:", ignoreCase = true) && !HttpsUpgrade.isCoolapk(url) ->
                                    error = "该网页未加密，请从右上角菜单在浏览器中打开"
                                else -> loadUrl(secureUrl, mapOf("X-Requested-With" to Constants.APP_ID))
                            }
                            web = this
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { it.destroy() },
                )
            }
            error?.let { message ->
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Text(message, color = MaterialTheme.colorScheme.onSurface)
                    Button(onClick = { error = null; web?.reload() }, Modifier.padding(top = 16.dp)) { Text("重新加载") }
                }
            }
        }
    }
}
