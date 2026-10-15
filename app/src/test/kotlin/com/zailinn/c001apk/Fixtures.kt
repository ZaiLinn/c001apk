package com.zailinn.c001apk

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Synthetic Coolapk responses; no request leaves the test process. */
class FixtureTransport : Interceptor {
    val paths = mutableListOf<String>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        synchronized(paths) { paths += path }
        val body = when {
            path.endsWith("/v6/main/indexV8") -> list(
                card("imageCarouselCard_1", entities = """[{"title":"合成轮播","url":"/feed/100","pic":"https://image.coolapk.com/c.jpg"}]"""),
                feed(100, "合成动态正文 #合成话题# [哈哈哈]", pics = 1),
                feed(101, "第二条合成动态，带三张图", pics = 3),
                """{"entityType":"apk","id":"5","title":"合成应用","logo":"","commentnum":"12","downCount":"3.4万","url":"/apk/com.example.app"}""",
            )
            path.endsWith("/v6/page/dataList") -> list(feed(200, "数据列表合成动态"))
            path.endsWith("/v6/feed/detail") -> """{"data":${feed(100, "合成动态正文 #合成话题# [哈哈哈]", pics = 1, detail = true)}}"""
            path.endsWith("/v6/feed/replyList") -> list(reply(1, "合成回复内容", nested = true), reply(2, "第二条合成回复"))
            path.endsWith("/v6/user/space") -> """{"data":{"uid":"10","username":"合成作者","userAvatar":"","cover":"","level":"5","feed":"12",
                "follow":"3","fans":"40","be_like_num":"99","logintime":"1770000000","bio":"合成简介","isFollow":0}}"""
            path.endsWith("/v6/user/feedList") -> list(feed(300, "用户的合成动态"))
            path.endsWith("/v6/search") -> list(feed(400, "搜索到的合成动态"))
            path.endsWith("/v6/topic/newTagDetail") -> """{"data":{"id":"7","entityType":"topic","title":"合成话题","logo":"",
                "hot_num_txt":"1万","commentnum_txt":"20","tabList":[{"title":"热门","url":"/page?url=hot","pageName":"hot"},
                {"title":"最新","url":"/page?url=new","pageName":"new"}],"selectedTab":"hot"}}"""
            else -> """{"data":[]}"""
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }

    companion object {
        fun list(vararg items: String) = """{"data":[${items.joinToString(",")}]}"""

        fun card(template: String, entities: String) =
            """{"entityType":"card","entityTemplate":"$template","entityId":"c-$template","entities":$entities}"""

        fun feed(id: Int, message: String, pics: Int = 0, detail: Boolean = false): String {
            val picArr = (1..pics).joinToString(",") { "\"https://image.coolapk.com/feed/$id-$it@1080x720.jpg\"" }
            return """{"entityType":"feed","id":"$id","entityId":"$id","uid":"10","username":"合成作者","userAvatar":"",
                "userInfo":{"uid":10,"username":"合成作者"},"message":"$message","picArr":[$picArr],"dateline":${System.currentTimeMillis() / 1000 - 3600},
                "infoHtml":"1小时前","device_title":"合成手机","replynum":"2","likenum":"8","userAction":{"like":0},
                "feedTypeName":"动态","ip_location":"上海"${if (detail) ",\"message_raw_output\":\"null\"" else ""}}"""
        }

        fun reply(id: Int, message: String, nested: Boolean = false): String {
            val rows = if (nested) ""","replyRows":[{"id":"9$id","uid":"12","username":"楼中楼","userInfo":{"username":"楼中楼"},
                "message":"嵌套合成回复","ruid":"11"}],"replyRowsMore":1""" else ""
            return """{"entityType":"feed_reply","id":"$id","entityId":"r$id","fetchType":"feed_reply","uid":"11","feedUid":"10",
                "username":"回复者","userInfo":{"username":"回复者"},"message":"$message","dateline":${System.currentTimeMillis() / 1000 - 60},
                "replynum":"1","likenum":"3","userAction":{"like":0}$rows}"""
        }
    }
}
