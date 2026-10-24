package com.zailinn.c001apk.ui.nav

import kotlinx.serialization.Serializable

@Serializable data object MainRoute
/** [toReplies]: open scrolled to the replies, as from a reply count in a list. */
@Serializable data class FeedRoute(val id: String, val toReplies: Boolean = false)
@Serializable data class EventRoute(val id: String)
@Serializable data class UserRoute(val uid: String)
@Serializable data class AppRoute(val id: String)
@Serializable data class TopicRoute(val tag: String? = null, val id: String? = null)
@Serializable data class SearchRoute(val title: String? = null, val pageType: String? = null, val pageParam: String? = null)
@Serializable data class SearchResultRoute(
    val keyword: String,
    val title: String? = null,
    val pageType: String? = null,
    val pageParam: String? = null,
)
@Serializable data class CarouselRoute(val url: String, val title: String = "")
@Serializable data class DyhRoute(val id: String, val title: String = "")
@Serializable data class CoolpicRoute(val tag: String)
@Serializable data class ImageViewerRoute(val urls: List<String>, val index: Int = 0)
@Serializable data class WebRoute(val url: String, val isLogin: Boolean = false)
@Serializable data class LoginRoute(val cookie: Boolean = false)
@Serializable data object AccountsRoute
@Serializable data object SettingsRoute
@Serializable data class SettingsSectionRoute(val section: String)
@Serializable data object ParamsRoute
@Serializable data object DeviceIdentityRoute
@Serializable data object CaptchaLogRoute
@Serializable data object AboutRoute
@Serializable data class HistoryRoute(val favorites: Boolean)
@Serializable data class BlacklistRoute(val users: Boolean)
@Serializable data object LocalFollowRoute
@Serializable data object ContentFilterRoute
@Serializable data class CategoryEditRoute(val id: String)
@Serializable data class UserListRoute(val type: String, val uid: String? = null, val title: String? = null)
@Serializable data class NotificationRoute(val type: String)
@Serializable data class ChatRoute(val ukey: String, val uid: String, val username: String)
@Serializable data class CopyRoute(val text: String)
