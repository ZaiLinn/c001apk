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
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.toRoute
import com.zailinn.c001apk.core.net.HttpsUpgrade
import com.zailinn.c001apk.core.protocol.Constants
import com.zailinn.c001apk.data.SettingsRepository

/** App-wide navigation and small platform actions, available to any composable. */
class AppNavigator(
    private val context: Context,
    private val nav: NavHostController,
    private val settings: SettingsRepository,
) {
    /**
     * Opens [route] unless it is already the current page with the same arguments.
     * launchSingleTop would match the destination alone, so a linked feed or
     * another profile replaced the current page and back skipped it.
     */
    fun navigate(route: Any) {
        val current = nav.currentBackStackEntry
        val same = current != null && current.destination.hasRoute(route::class) &&
            runCatching { current.toRoute<Any>(route::class) == route }.getOrDefault(false)
        if (!same) nav.navigate(route)
    }
    fun back() {
        if (nav.previousBackStackEntry != null) nav.popBackStack()
    }
    fun backToMain() = nav.popBackStack(MainRoute, inclusive = false)

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    /** Opens a profile, except for anonymous posts and ids that cannot be a user. */
    fun openUser(uid: String?, anonymous: Boolean = false) = when {
        anonymous -> toast("匿名发布，无法查看用户主页")
        uid.isNullOrBlank() || uid == "0" -> toast("无法打开该用户")
        else -> navigate(UserRoute(uid))
    }

    fun copy(text: String, toast: Boolean = true) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("c001apk", text))
        if (toast) toast("已复制")
    }

    fun share(text: String) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openImages(urls: List<String>, index: Int = 0, media: List<com.zailinn.c001apk.core.model.GalleryMedia> = emptyList()) {
        if (urls.isNotEmpty()) navigate(ImageViewerRoute(urls, index.coerceIn(0, urls.lastIndex),
            media.takeIf { it.isNotEmpty() }?.let { kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(com.zailinn.c001apk.core.model.GalleryMedia.serializer()), it) }))
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
        when (val route = route(if (converted) convertedPath(url) else url, title)) {
            is UserLink -> openUser(route.name)
            null -> when {
                !converted -> openLink(url, title, converted = true)
                url.startsWith(Constants.PREFIX_HTTP) ->
                    // The in-app browser blocks cleartext; unencrypted sites go to the system browser.
                    if (settings.current.openInBrowser || (url.startsWith("http:", ignoreCase = true) && !HttpsUpgrade.isCoolapk(url))) {
                        launchBrowser(url)
                    } else navigate(WebRoute(url))
                else -> toast("暂不支持此链接")
            }
            else -> navigate(route)
        }
    }

    private class UserLink(val name: String)

    // Pure functions of the link, so lists can also leave out what has nowhere to go.
    companion object {
        /** Whether [openLink] has a page or a browser for this link, rather than 暂不支持此链接. */
        fun canOpen(url: String): Boolean =
            url.isNotEmpty() && (route(url, null) != null || route(convertedPath(url), null) != null || url.startsWith(Constants.PREFIX_HTTP))

        /** A Coolapk web or `coolmarket://` link as the app path it stands for. */
        private fun convertedPath(url: String): String {
            if (url.startsWith(Constants.PREFIX_COOLMARKET)) return url.replaceFirst(Constants.PREFIX_COOLMARKET, "/")
            val uri = runCatching { Uri.parse(url) }.getOrNull()
            return if (uri?.host?.contains(Constants.CHANNEL) == true) uri.encodedPath.orEmpty() + (uri.encodedQuery?.let { "?$it" } ?: "") else url
        }

        /** The native page for an app path, or null. */
        private fun route(path: String, title: String?): Any? = when {
            path.startsWith(Constants.PREFIX_FEED) ->
                path.removePrefix(Constants.PREFIX_FEED).takeWhile { it.isDigit() }.takeIf { it.isNotEmpty() }?.let(::FeedRoute)
            path.startsWith(PREFIX_EVENT) ->
                path.removePrefix(PREFIX_EVENT).takeWhile { it.isDigit() }.takeIf { it.isNotEmpty() }?.let(::EventRoute)
            path.startsWith(Constants.PREFIX_USER) -> UserLink(Uri.decode(path.removePrefix(Constants.PREFIX_USER).substringBefore('?')))
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
    }
}

private const val PREFIX_EVENT = "/event/"

val LocalNavigator = staticCompositionLocalOf<AppNavigator> { error("No navigator") }
