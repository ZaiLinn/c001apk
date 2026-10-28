package com.zailinn.c001apk.ui

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.zailinn.c001apk.AppContainer
import com.zailinn.c001apk.core.net.readApiObject
import com.zailinn.c001apk.core.update.UpdateResult
import com.zailinn.c001apk.core.update.UpdateStatus
import com.zailinn.c001apk.data.AppSettings
import com.zailinn.c001apk.ui.common.AppAlertDialog
import com.zailinn.c001apk.ui.common.rememberAppHapticFeedback
import com.zailinn.c001apk.ui.nav.AboutRoute
import com.zailinn.c001apk.ui.nav.AccountsRoute
import com.zailinn.c001apk.ui.nav.AppNavigator
import com.zailinn.c001apk.ui.nav.AppRoute
import com.zailinn.c001apk.ui.nav.BlacklistRoute
import com.zailinn.c001apk.ui.nav.CategoryEditRoute
import com.zailinn.c001apk.ui.nav.CaptchaLogRoute
import com.zailinn.c001apk.ui.nav.ContentFilterRoute
import com.zailinn.c001apk.ui.nav.CarouselRoute
import com.zailinn.c001apk.ui.nav.ChatRoute
import com.zailinn.c001apk.ui.nav.CoolpicRoute
import com.zailinn.c001apk.ui.nav.CopyRoute
import com.zailinn.c001apk.ui.nav.DyhRoute
import com.zailinn.c001apk.ui.nav.EventRoute
import com.zailinn.c001apk.ui.nav.ArchiveSearchRoute
import com.zailinn.c001apk.ui.nav.FeedRoute
import com.zailinn.c001apk.ui.nav.HistoryRoute
import com.zailinn.c001apk.ui.nav.ImageViewerRoute
import com.zailinn.c001apk.ui.nav.LocalFollowRoute
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.nav.LoginRoute
import com.zailinn.c001apk.ui.nav.MainRoute
import com.zailinn.c001apk.ui.nav.NotificationRoute
import com.zailinn.c001apk.ui.nav.ParamsRoute
import com.zailinn.c001apk.ui.nav.DeviceIdentityRoute
import com.zailinn.c001apk.ui.screens.settings.CaptchaLogScreen
import com.zailinn.c001apk.ui.screens.settings.DeviceIdentityScreen
import com.zailinn.c001apk.ui.nav.SearchResultRoute
import com.zailinn.c001apk.ui.nav.SearchRoute
import com.zailinn.c001apk.ui.nav.SettingsRoute
import com.zailinn.c001apk.ui.nav.SettingsSectionRoute
import com.zailinn.c001apk.ui.nav.TopicRoute
import com.zailinn.c001apk.ui.nav.UserListRoute
import com.zailinn.c001apk.ui.nav.UserRoute
import com.zailinn.c001apk.ui.nav.WebRoute
import com.zailinn.c001apk.ui.screens.account.AccountsScreen
import com.zailinn.c001apk.ui.screens.account.LoginScreen
import com.zailinn.c001apk.ui.screens.detail.AppScreen
import com.zailinn.c001apk.ui.screens.detail.TopicScreen
import com.zailinn.c001apk.ui.screens.detail.UserScreen
import com.zailinn.c001apk.ui.screens.feed.FeedScreen
import com.zailinn.c001apk.ui.screens.list.CarouselScreen
import com.zailinn.c001apk.ui.screens.list.CoolpicScreen
import com.zailinn.c001apk.ui.screens.list.DyhScreen
import com.zailinn.c001apk.ui.screens.list.HistoryScreen
import com.zailinn.c001apk.ui.screens.list.NotificationScreen
import com.zailinn.c001apk.ui.screens.list.UserListScreen
import com.zailinn.c001apk.ui.screens.main.MainScreen
import com.zailinn.c001apk.ui.screens.other.ChatScreen
import com.zailinn.c001apk.ui.screens.other.CopyScreen
import com.zailinn.c001apk.ui.screens.other.ImageViewerScreen
import com.zailinn.c001apk.ui.screens.search.SearchResultScreen
import com.zailinn.c001apk.ui.screens.search.SearchScreen
import com.zailinn.c001apk.ui.screens.settings.AboutScreen
import com.zailinn.c001apk.ui.screens.settings.BlacklistScreen
import com.zailinn.c001apk.ui.screens.settings.CategoryEditScreen
import com.zailinn.c001apk.ui.screens.settings.ContentFilterScreen
import com.zailinn.c001apk.ui.screens.settings.LocalFollowManageScreen
import com.zailinn.c001apk.ui.screens.settings.ParamsScreen
import com.zailinn.c001apk.ui.screens.settings.SettingsScreen
import com.zailinn.c001apk.ui.screens.settings.SettingsSectionScreen
import com.zailinn.c001apk.ui.screens.settings.UpdateDialog
import com.zailinn.c001apk.ui.screens.web.CaptchaDialog
import com.zailinn.c001apk.ui.screens.web.WebScreen
import com.zailinn.c001apk.ui.theme.C001apkTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class SessionInfo(val isLogin: Boolean = false, val uid: String = "")

val LocalSession = compositionLocalOf { SessionInfo() }
val LocalAppSettings = compositionLocalOf { AppSettings() }

@Composable
fun C001apkApp(app: AppContainer, deepLink: Uri?, onDeepLinkHandled: () -> Unit) {
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val vault by app.accounts.vault.collectAsStateWithLifecycle()
    val generation by app.accounts.generation.collectAsStateWithLifecycle()
    val captcha by app.captcha.pending.collectAsStateWithLifecycle()
    val deviceIdRejected by app.deviceIdRejected.collectAsStateWithLifecycle()
    // Asked once per run; "暂不处理" leaves the ID until the app starts again.
    var deviceIdAsked by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val session = SessionInfo(vault.active != null, vault.active?.uid.orEmpty())
    val context = LocalContext.current
    val nav = rememberNavController()
    val navigator = remember(nav) { AppNavigator(context, nav, app.settings) }
    var update by remember { mutableStateOf<UpdateResult?>(null) }

    // Account switches close pages that belong to the previous account.
    var lastGeneration by remember { mutableStateOf(generation) }
    LaunchedEffect(generation) {
        if (generation != lastGeneration) {
            lastGeneration = generation
            navigator.backToMain()
        }
        checkLoginInfo(app)
    }
    LaunchedEffect(Unit) {
        if (!com.zailinn.c001apk.BuildConfig.UPDATE_CHECK) return@LaunchedEffect
        val result = app.updates.check(automatic = true)
        if (result.status == UpdateStatus.Available) update = result
    }
    LaunchedEffect(deepLink) {
        deepLink?.let {
            navigator.openLink(it.toString())
            onDeepLinkHandled()
        }
    }

    C001apkTheme(settings) {
        CompositionLocalProvider(
            com.zailinn.c001apk.ui.screens.LocalAppContainer provides app,
            LocalNavigator provides navigator,
            LocalSession provides session,
            LocalAppSettings provides settings,
            LocalHapticFeedback provides rememberAppHapticFeedback(settings.hapticFeedback),
        ) {
            NavHost(
                nav,
                startDestination = MainRoute,
                // Shared-axis transitions; going back mirrors going forward.
                enterTransition = { slideInHorizontally(tween(300)) { it / 4 } + fadeIn(tween(300)) },
                exitTransition = { slideOutHorizontally(tween(300)) { -it / 4 } + fadeOut(tween(300)) },
                popEnterTransition = { slideInHorizontally(tween(300)) { -it / 4 } + fadeIn(tween(300)) },
                popExitTransition = { slideOutHorizontally(tween(300)) { it / 4 } + fadeOut(tween(300)) },
            ) {
                composable<MainRoute> { MainScreen(generation) }
                composable<FeedRoute> { it.toRoute<FeedRoute>().let { r -> FeedScreen(r.id, r.toReplies, r.backupFallback) } }
                composable<EventRoute> { com.zailinn.c001apk.ui.screens.detail.EventScreen(it.toRoute<EventRoute>().id) }
                composable<UserRoute> { UserScreen(it.toRoute<UserRoute>().uid) }
                composable<AppRoute> { AppScreen(it.toRoute<AppRoute>().id) }
                composable<TopicRoute> { it.toRoute<TopicRoute>().let { r -> TopicScreen(r.tag, r.id) } }
                composable<SearchRoute> { it.toRoute<SearchRoute>().let { r -> SearchScreen(r.title, r.pageType, r.pageParam) } }
                composable<SearchResultRoute> {
                    it.toRoute<SearchResultRoute>().let { r -> SearchResultScreen(r.keyword, r.title, r.pageType, r.pageParam) }
                }
                composable<CarouselRoute> { it.toRoute<CarouselRoute>().let { r -> CarouselScreen(r.url, r.title) } }
                composable<DyhRoute> { it.toRoute<DyhRoute>().let { r -> DyhScreen(r.id, r.title) } }
                composable<CoolpicRoute> { CoolpicScreen(it.toRoute<CoolpicRoute>().tag) }
                composable<ImageViewerRoute> { it.toRoute<ImageViewerRoute>().let { r -> ImageViewerScreen(r.urls, r.index, r.mediaJson) } }
                composable<WebRoute> { it.toRoute<WebRoute>().let { r -> WebScreen(r.url, r.isLogin) } }
                composable<LoginRoute> { LoginScreen(it.toRoute<LoginRoute>().cookie) }
                composable<AccountsRoute> { AccountsScreen() }
                composable<SettingsRoute> { SettingsScreen() }
                composable<SettingsSectionRoute> { SettingsSectionScreen(it.toRoute<SettingsSectionRoute>().section) }
                composable<ParamsRoute> { ParamsScreen() }
                composable<DeviceIdentityRoute> { DeviceIdentityScreen() }
                composable<CaptchaLogRoute> { CaptchaLogScreen() }
                composable<AboutRoute> { AboutScreen() }
                composable<com.zailinn.c001apk.ui.nav.ArchiveExportRoute> { it.toRoute<com.zailinn.c001apk.ui.nav.ArchiveExportRoute>().let { r -> com.zailinn.c001apk.backup.ArchiveExportScreen(r.scope, r.query, r.collection) } }
                composable<com.zailinn.c001apk.ui.nav.ArchiveSyncRoute> { com.zailinn.c001apk.backup.ArchiveSyncScreen(it.toRoute<com.zailinn.c001apk.ui.nav.ArchiveSyncRoute>().local) }
                composable<ArchiveSearchRoute> { com.zailinn.c001apk.backup.ArchiveSearchScreen(it.toRoute<ArchiveSearchRoute>().scope) }
                composable<com.zailinn.c001apk.ui.nav.BackupsRoute> { com.zailinn.c001apk.backup.BackupsScreen() }
                composable<com.zailinn.c001apk.ui.nav.BackupDetailRoute> { com.zailinn.c001apk.backup.BackupDetailScreen(it.toRoute<com.zailinn.c001apk.ui.nav.BackupDetailRoute>().id) }
                composable<HistoryRoute> { HistoryScreen(it.toRoute<HistoryRoute>().favorites) }
                composable<BlacklistRoute> { BlacklistScreen(it.toRoute<BlacklistRoute>().users) }
                composable<com.zailinn.c001apk.ui.nav.TopicBlockRoute> { com.zailinn.c001apk.ui.screens.settings.TopicBlockScreen() }
                composable<com.zailinn.c001apk.ui.nav.HiddenLogRoute> { com.zailinn.c001apk.ui.screens.settings.HiddenLogScreen() }
                composable<LocalFollowRoute> { LocalFollowManageScreen() }
                composable<ContentFilterRoute> { ContentFilterScreen() }
                composable<CategoryEditRoute> { CategoryEditScreen(it.toRoute<CategoryEditRoute>().id) }
                composable<UserListRoute> { it.toRoute<UserListRoute>().let { r -> UserListScreen(r.type, r.uid, r.title) } }
                composable<NotificationRoute> { NotificationScreen(it.toRoute<NotificationRoute>().type) }
                composable<ChatRoute> { it.toRoute<ChatRoute>().let { r -> ChatScreen(r.ukey, r.uid, r.username) } }
                composable<CopyRoute> { CopyScreen(it.toRoute<CopyRoute>().text) }
            }
            com.zailinn.c001apk.backup.BackupHost(app)
            captcha?.let { CaptchaDialog(it) }
            if (deviceIdRejected && !deviceIdAsked) {
                DeviceIdRejectedDialog(
                    onClear = {
                        deviceIdAsked = true
                        app.deviceIdRejected.value = false
                        scope.launch {
                            app.settings.setSzlmId("")
                            app.toast("已清除数盟 ID，下拉刷新即可")
                        }
                    },
                    onKeep = {
                        deviceIdAsked = true
                        app.deviceIdRejected.value = false
                    },
                )
            }
            update?.let { UpdateDialog(it) { update = null } }
        }
    }
}

/**
 * Confirms the saved login, refreshes a rotated token and keeps the session id.
 * A challenge or incomplete response must not erase a working session.
 */
private suspend fun checkLoginInfo(app: AppContainer) {
    val account = app.session.account ?: return
    val generation = app.session.generation
    try {
        val response = app.repository.checkLoginInfo()
        if (response.code == 200) {
            val raw = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(response.text) as? JsonObject }.getOrNull()
            if ((raw?.get("message") as? JsonPrimitive)?.content == "登录信息有误") {
                app.accounts.expireCurrent(generation)
                app.toast("登录信息已失效，请重新登录")
                return
            }
            val data = readApiObject(response)["data"] as? JsonObject
            val uid = (data?.get("uid") as? JsonPrimitive)?.content
            val token = (data?.get("token") as? JsonPrimitive)?.content
            val username = (data?.get("username") as? JsonPrimitive)?.content
            if (uid == account.uid && !token.isNullOrEmpty()) {
                app.accounts.refreshCurrent(generation, username = username?.takeIf { it.isNotEmpty() }?.let(Uri::encode), token = token)
            }
        }
        response.cookies.map { it.substringBefore(';') }.firstOrNull { it.startsWith("SESSID=") }?.let {
            if (generation == app.session.generation) app.session.sessid = it
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Login check failures leave the saved account untouched.
    }
}

/** The server rejects requests carrying the imported 数盟 ID; offers to go back to the app's own ID. */
@Composable
private fun DeviceIdRejectedDialog(onClear: () -> Unit, onKeep: () -> Unit) {
    AppAlertDialog(
        onDismissRequest = onKeep,
        title = { androidx.compose.material3.Text("数盟 ID 被服务器拒绝") },
        text = {
            androidx.compose.material3.Text(
                "服务器拒绝了带有导入数盟 ID 的请求（-419），内容可能无法加载。" +
                    "清除后改用本应用生成的设备标识，机型等参数和登录状态不变。",
            )
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onClear) { androidx.compose.material3.Text("清除数盟 ID") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onKeep) { androidx.compose.material3.Text("暂不处理") } },
    )
}
