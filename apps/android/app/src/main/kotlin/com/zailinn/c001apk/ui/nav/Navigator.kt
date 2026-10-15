package com.zailinn.c001apk.ui.nav

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.net.toUri
import androidx.navigation.NavHostController
import com.zailinn.c001apk.core.protocol.Constants
import com.zailinn.c001apk.data.SettingsRepository

/** App-wide navigation and small platform actions, available to any composable. */
class AppNavigator(
    private val context: Context,
    private val nav: NavHostController,
    private val settings: SettingsRepository,
) {
    fun navigate(route: Any) = nav.navigate(route) { launchSingleTop = true }
    fun back() {
        if (nav.previousBackStackEntry != null) nav.popBackStack()
    }
    fun backToMain() = nav.popBackStack(MainRoute, inclusive = false)

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    fun copy(text: String, toast: Boolean = true) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("c001apk", text))
        if (toast) toast("已复制")
    }

    fun share(text: String) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openImages(urls: List<String>, index: Int = 0) {
        if (urls.isNotEmpty()) navigate(ImageViewerRoute(urls, index.coerceIn(0, urls.lastIndex)))
    }

    fun launchBrowser(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            toast("无法打开链接，请检查浏览器设置")
        } catch (_: SecurityException) {
            toast("无法打开链接，请检查浏览器设置")
        }
    }

    fun report(id: String, type: String) {
        val c = if (type == "user") "user" else "feed"
        val suffix = when (type) {
            "feed" -> "&type=feed"
            "feed_reply" -> "&type=feed_reply"
            else -> ""
        }
        navigate(WebRoute("https://m.coolapk.com/mp/do?c=$c&m=report$suffix&id=$id"))
    }

    /** Routes Coolapk paths and links to native pages; other links open in the web view or browser. */
    fun openLink(url: String, title: String? = null, converted: Boolean = false) {
        if (url.isEmpty()) return
        var path = url
        if (converted) {
            if (url.startsWith(Constants.PREFIX_COOLMARKET)) {
                path = url.replaceFirst(Constants.PREFIX_COOLMARKET, "/")
            } else {
                val uri = runCatching { Uri.parse(url) }.getOrNull()
                if (uri?.host?.contains(Constants.CHANNEL) == true) {
                    path = uri.encodedPath.orEmpty() + (uri.encodedQuery?.let { "?$it" } ?: "")
                }
            }
        }
        val route: Any? = when {
            path.startsWith(Constants.PREFIX_FEED) ->
                path.removePrefix(Constants.PREFIX_FEED).takeWhile { it.isDigit() }.takeIf { it.isNotEmpty() }?.let(::FeedRoute)
            path.startsWith(Constants.PREFIX_USER) ->
                UserRoute(Uri.decode(path.removePrefix(Constants.PREFIX_USER).substringBefore('?')))
            path.startsWith(Constants.PREFIX_APP) -> AppRoute(path.removePrefix(Constants.PREFIX_APP).substringBefore('?'))
            path.startsWith(Constants.PREFIX_GAME) -> AppRoute(path.removePrefix(Constants.PREFIX_GAME).substringBefore('?'))
            path.startsWith(Constants.PREFIX_TOPIC) -> {
                val tag = Uri.decode(path.removePrefix(Constants.PREFIX_TOPIC).replaceFirst(Regex("\\?type=[A-Za-z0-9]+"), "").substringBefore('?'))
                if (path.contains("type=8")) CoolpicRoute(tag) else TopicRoute(tag = tag)
            }
            path.startsWith(Constants.PREFIX_PRODUCT) -> TopicRoute(id = path.removePrefix(Constants.PREFIX_PRODUCT).substringBefore('?'))
            path.startsWith(Constants.PREFIX_CAROUSEL) -> CarouselRoute(path.removePrefix(Constants.PREFIX_CAROUSEL), title.orEmpty())
            path.startsWith(Constants.PREFIX_CAROUSEL1) -> CarouselRoute(path.removePrefix("#"), title.orEmpty())
            path.startsWith(Constants.PREFIX_USER_LIST) -> UserListRoute("USER_FOLLOW")
            path.startsWith(Constants.PREFIX_DYH) -> DyhRoute(path.removePrefix(Constants.PREFIX_DYH).substringBefore('?'), title.orEmpty())
            else -> null
        }
        when {
            route != null -> navigate(route)
            !converted -> openLink(url, title, converted = true)
            url.startsWith(Constants.PREFIX_HTTP) ->
                if (settings.current.openInBrowser) launchBrowser(url) else navigate(WebRoute(url))
            else -> toast("暂不支持此链接")
        }
    }
}

val LocalNavigator = staticCompositionLocalOf<AppNavigator> { error("No navigator") }
