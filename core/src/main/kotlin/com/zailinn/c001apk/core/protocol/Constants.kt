package com.zailinn.c001apk.core.protocol

object Constants {
    const val APP_NAME = "c001apk"

    const val REQUEST_WITH = "XMLHttpRequest"
    const val LOCALE = "zh-CN"
    const val APP_ID = "com.coolapk.market"
    const val CHANNEL = "coolapk"
    const val MODE = "universal"
    const val APP_LABEL = "token://com.coolapk.market/dcf01e569c1e3db93a3d0fcf191a622c"
    const val VERSION_NAME = "16.2.0"
    const val API_VERSION = "16"
    const val VERSION_CODE = "2604201"

    const val PREFIX_COOLMARKET = "coolmarket://"
    const val PREFIX_HTTP = "http"
    const val PREFIX_APP = "/apk/"
    const val PREFIX_GAME = "/game/"
    const val PREFIX_FEED = "/feed/"
    const val PREFIX_PRODUCT = "/product/"
    const val PREFIX_TOPIC = "/t/"
    const val PREFIX_USER = "/u/"
    const val PREFIX_CAROUSEL = "/page?url="
    const val PREFIX_CAROUSEL1 = "#/"
    const val PREFIX_USER_LIST = "/user/"
    const val PREFIX_DYH = "/dyh/"
    const val PREFIX_COLLECTION = "/collection/"
    const val SUFFIX_THUMBNAIL = ".s.jpg"
    const val SUFFIX_GIF = ".gif"

    const val URL_COOLAPK = "https://www.coolapk.com/"
    const val URL_LOGIN = "https://account.coolapk.com/auth/login?type=mobile"
    const val URL_SOURCE_CODE = "https://github.com/ZaiLinn/c001apk"
    const val URL_UPSTREAM = "https://github.com/bggRGjQaUbCoE/c001apk-flutter"
    const val URL_API_SERVICE = "https://api.coolapk.com"
    const val URL_API2_SERVICE = "https://api2.coolapk.com"
    const val URL_ACCOUNT_SERVICE = "https://account.coolapk.com"

    val entityTypeList = setOf(
        "feed", "apk", "product", "user", "topic", "notification", "productBrand",
        "contacts", "recentHistory", "feed_reply", "message", "collection",
    )

    val entityTemplateList = setOf(
        "imageCarouselCard_1", "iconLinkGridCard", "iconMiniScrollCard", "iconMiniGridCard",
        "imageSquareScrollCard", "titleCard", "iconScrollCard", "imageTextScrollCard",
        "iconTabLinkGridCard", "verticalColumnsFullPageCard", "noMoreDataCard", "time", "messageCard",
    )
}
