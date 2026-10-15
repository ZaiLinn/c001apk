package com.zailinn.c001apk.core.net

import com.zailinn.c001apk.core.model.ContentFilter
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.DatumPage
import com.zailinn.c001apk.core.model.LoadResult
import com.zailinn.c001apk.core.protocol.Constants
import com.zailinn.c001apk.core.protocol.TokenUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val API = Constants.URL_API_SERVICE
private const val API2 = Constants.URL_API2_SERVICE

/** Paging cursor shared by every list endpoint. */
data class Page(val page: Int = 1, val firstItem: String? = null, val lastItem: String? = null) {
    fun query(): List<Pair<String, String>> = buildList {
        add("page" to page.toString())
        firstItem?.takeIf { it.isNotEmpty() }?.let { add("firstItem" to it) }
        lastItem?.takeIf { it.isNotEmpty() }?.let { add("lastItem" to it) }
    }
}

/** Result of a write action: either a server message to show or the `data` field. */
data class ActionResult(val message: String?, val data: JsonElement?, val messageStatus: String? = null) {
    /** Publishing needs the legacy image captcha before it can be retried. */
    val needsImageCaptcha: Boolean get() = messageStatus == "err_request_captcha"

    val dataText: String? get() = (data as? JsonPrimitive)?.content
    val dataObject: JsonObject? get() = data as? JsonObject
}

class CoolapkRepository(
    val client: CoolapkClient,
    private val filter: () -> ContentFilter = { ContentFilter() },
) {
    // region Lists

    suspend fun homeFeed(page: Page, firstLaunch: Int, installTime: String) = list {
        client.get("$API2/v6/main/indexV8", page.query() + listOf(
            "firstLaunch" to firstLaunch.toString(), "installTime" to installTime, "ids" to "",
        ))
    }

    suspend fun dataList(url: String, title: String, page: Page, subTitle: String = "", includeConfigCard: Boolean = false) =
        list(includeConfigCard) {
            client.get("$API/v6/page/dataList", listOf("url" to url, "title" to title, "subTitle" to subTitle) + page.query())
        }

    suspend fun dataListFromUrl(url: String, query: List<Pair<String, String>> = emptyList()) =
        list { client.get(API + url, query) }

    suspend fun feedReplies(id: String, listType: String, page: Page, fromFeedAuthor: Int = 0) = list {
        client.get("$API2/v6/feed/replyList", listOf(
            "id" to id, "listType" to listType,
        ) + page.query() + listOf(
            "discussMode" to "1", "feedType" to "feed", "blockStatus" to "0", "fromFeedAuthor" to fromFeedAuthor.toString(),
        ))
    }

    suspend fun replyToReply(id: String, page: Page) = list {
        client.get("$API/v6/feed/replyList", listOf(
            "id" to id, "listType" to "", "discussMode" to "0", "feedType" to "feed_reply",
            "blockStatus" to "0", "fromFeedAuthor" to "0",
        ) + page.query())
    }

    suspend fun userFeed(uid: String, page: Page, includeTop: Boolean = true) = list {
        client.get("$API/v6/user/feedList", listOf("uid" to uid) + page.query() + listOf(
            "showAnonymous" to "0", "isIncludeTop" to if (includeTop) "1" else "0", "showDoing" to "0",
        ))
    }

    suspend fun search(
        type: String, feedType: String, sort: String, keyword: String, isStrict: Int,
        pageType: String?, pageParam: String?, page: Page,
    ) = list {
        client.get("$API/v6/search", buildList {
            add("type" to type); add("feedType" to feedType); add("sort" to sort)
            add("searchValue" to keyword); add("isStrict" to isStrict.toString())
            pageType?.takeIf { it.isNotEmpty() }?.let { add("pageType" to it) }
            pageParam?.takeIf { it.isNotEmpty() }?.let { add("pageParam" to it) }
            addAll(page.query())
            add("showAnonymous" to "-1")
        })
    }

    suspend fun coolPic(tag: String, type: String, page: Page) = list {
        client.get("$API/v6/picture/list", listOf("tag" to tag, "type" to type) + page.query())
    }

    suspend fun dyhArticles(dyhId: String, type: String, page: Page) = list {
        client.get("$API/v6/dyhArticle/list", listOf("dyhId" to dyhId, "type" to type) + page.query())
    }

    suspend fun notifications(url: String, page: Page) = list { client.get(API + url, page.query()) }

    suspend fun messageList(url: String, ukey: String? = null, uid: String? = null, page: Page? = null) = list {
        client.get(API + url, buildList {
            ukey?.let { add("ukey" to it) }
            uid?.let { add("uid" to it) }
            page?.let { addAll(it.query()) }
        })
    }

    // endregion

    // region Details

    suspend fun dataFromUrl(url: String, query: List<Pair<String, String>> = emptyList()) =
        data { client.get(API + url, query) }

    suspend fun userSpace(uid: String) = dataFromUrl("/v6/user/space", listOf("uid" to uid))

    suspend fun topicDetail(tag: String?, id: String?) = when {
        !tag.isNullOrEmpty() -> dataFromUrl("/v6/topic/newTagDetail", listOf("tag" to tag))
        !id.isNullOrEmpty() -> dataFromUrl("/v6/product/detail", listOf("id" to id))
        else -> LoadResult.Error("缺少话题参数")
    }

    suspend fun appInfo(id: String) = data { client.get("$API/v6/apk/detail", listOf("id" to id, "installed" to "1")) }

    /**
     * Account detail first; on login or captcha restrictions fall back to the
     * official public representation, which cannot carry this account's like state.
     */
    suspend fun feedDetail(id: String, verify: Boolean = false): LoadResult<Datum> {
        if (!Regex("^[0-9]+$").matches(id)) return LoadResult.Error("动态编号无效")
        var verification: String? = null
        try {
            val response = client.get("$API/v6/feed/detail", listOf("id" to id), skipCaptcha = !verify)
            verification = response.captchaVerification
            return toData(readApiObject(response))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = ApiFailure.from(e)
            if (verify) {
                return LoadResult.Error(
                    when {
                        verification == "cancelled" -> "已取消验证，正文仍可阅读"
                        verification == "submitted" && failure.kind == ApiFailureKind.Captcha ->
                            "验证已完成，但服务端仍未允许读取账号信息，请稍后重试"
                        else -> failure.message
                    },
                )
            }
            if (failure.kind != ApiFailureKind.Captcha && failure.kind != ApiFailureKind.Authentication) {
                return LoadResult.Error(failure.message)
            }
            return when (val public = data { client.get("${Constants.URL_COOLAPK}feed/$id") }) {
                is LoadResult.Success -> LoadResult.Success(public.value.with("userAction", null as JsonElement?), isPublic = true)
                else -> public
            }
        }
    }

    // endregion

    // region Account

    suspend fun checkLoginInfo() = client.get("$API/v6/account/checkLoginInfo", skipCaptcha = true)

    suspend fun profile(uid: String) = client.get("$API/v6/user/profile", listOf("uid" to uid), skipCaptcha = true)

    suspend fun checkCount() = client.get("$API/v6/notification/checkCount")

    suspend fun loginPage(path: String, image: Boolean = false) =
        client.get(Constants.URL_ACCOUNT_SERVICE + path, expectImage = image)

    suspend fun login(requestHash: String, account: String, password: String, captcha: String) =
        client.post(
            "${Constants.URL_ACCOUNT_SERVICE}/auth/loginByCoolApk",
            body = Body.Form(listOf(
                "submit" to "1", "randomNumber" to TokenUtils.createRandomNumber(), "requestHash" to requestHash,
                "login" to account, "password" to password, "captcha" to captcha, "code" to "",
            )),
        )

    // endregion

    // region Actions

    /** Like, unlike, delete, follow and similar POST actions. */
    suspend fun postAction(
        url: String,
        id: String? = null,
        uid: String? = null,
        fields: List<Pair<String, String>>? = null,
    ): ActionResult {
        val isLike = url in setOf("/v6/feed/like", "/v6/feed/unlike", "/v6/feed/likeReply", "/v6/feed/unLikeReply")
        val query = buildList {
            id?.let { add("id" to it) }
            uid?.let { add("uid" to it) }
            if (isLike) add("detail" to "0")
        }
        val body = when {
            isLike -> Body.Form(listOf("trace" to ""))
            fields.isNullOrEmpty() -> Body.Form(emptyList())
            else -> Body.Multipart(fields)
        }
        return action { client.post(API + url, query, body) }
    }

    suspend fun getAction(url: String, query: List<Pair<String, String>> = emptyList()) =
        action { client.get(API + url, query) }

    suspend fun reply(id: String, type: String, fields: List<Pair<String, String>>) =
        action { client.post("$API/v6/feed/reply", listOf("id" to id, "type" to type), Body.Form(fields)) }

    /** Private posts use publish_status without marking the feed as a draft. */
    suspend fun createFeed(fields: List<Pair<String, String>>): ActionResult {
        val map = LinkedHashMap<String, String>().apply { fields.forEach { (k, v) -> put(k, v) } }
        if (!map.containsKey("publish_status")) map["publish_status"] = if (map["status"] == "-1") "1" else "0"
        map["status"] = "1"
        return action { client.post("$API/v6/feed/createFeed", body = Body.Form(map.toList())) }
    }

    suspend fun captchaImage(): ByteArray {
        val response = client.get(
            "$API/v6/account/captchaImage",
            listOf("_" to System.currentTimeMillis().toString(), "w" to "270", "h" to "113"),
        )
        if (response.code !in 200..299) throw ApiFailure.http(response.code)
        return response.bytes
    }

    suspend fun requestValidate(code: String) = action {
        client.post("$API/v6/account/requestValidate", body = Body.Multipart(listOf("type" to "err_request_captcha", "code" to code)))
    }

    suspend fun sendMessage(uid: String, fields: List<Pair<String, String>>) =
        action { client.post("$API/v6/message/send", listOf("uid" to uid), Body.Multipart(fields)) }

    suspend fun ossUploadPrepare(fields: List<Pair<String, String>>) =
        action { client.post("$API/v6/upload/ossUploadPrepare", body = Body.Multipart(fields)) }

    /** Download redirect target for an app, or null when the server refuses. */
    suspend fun appDownloadUrl(packageName: String, id: String, versionCode: String): String? {
        val response = client.get(
            "$API/v6/apk/download",
            listOf("pn" to packageName, "aid" to id, "vc" to versionCode, "extra" to ""),
            followRedirects = false,
        )
        if (response.code in 300..399) return response.headers["Location"]?.takeIf { it.isNotEmpty() }
        readApiObject(response)
        return null
    }

    suspend fun messageImageUrl(id: String): String? {
        val response = client.get("$API/v6/message/showImage", listOf("id" to id, "type" to "s"), followRedirects = false)
        return response.headers["Location"]
    }

    suspend fun appsUpdate(pkgs: String) =
        list { client.post("$API/v6/apk/checkUpdate", listOf("coolmarket_beta" to "0"), Body.Multipart(listOf("pkgs" to pkgs))) }

    // endregion

    // region Response handling

    private suspend fun data(request: suspend () -> ApiResponse): LoadResult<Datum> = try {
        toData(readApiObject(request()))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LoadResult.Error(ApiFailure.from(e).message)
    }

    private fun toData(body: JsonObject): LoadResult<Datum> =
        (body["data"] as? JsonObject)?.let { LoadResult.Success(Datum(it)) } ?: LoadResult.Empty

    private suspend fun list(
        includeConfigCard: Boolean = false,
        request: suspend () -> ApiResponse,
    ): LoadResult<List<Datum>> = try {
        val body = readApiObject(request())
        val items = expandListCards((body["data"] as? JsonArray).orEmpty()).toList()
        if (items.isEmpty()) {
            LoadResult.Empty
        } else {
            val rules = filter()
            withContext(Dispatchers.Default) {
                // Cursor ids come from every known item, including blocked ones, so a
                // fully blocked page still moves the next request forward.
                val known = ContentFilter()
                val shown = items.filter { rules.accepts(it, includeConfigCard) }.map(rules::mark)
                LoadResult.Success(DatumPage(
                    shown,
                    rawFirstId = items.firstOrNull { known.accepts(it, includeConfigCard) && it.id != null }?.id,
                    rawLastId = items.lastOrNull { known.accepts(it, includeConfigCard) && it.id != null }?.id,
                ))
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LoadResult.Error(ApiFailure.from(e).message)
    }

    private suspend fun action(request: suspend () -> ApiResponse): ActionResult {
        val response = request()
        val raw = runCatching { lenientJson.parseToJsonElement(response.text) as? JsonObject }.getOrNull()
        val status = (raw?.get("messageStatus") as? JsonPrimitive)?.content
        // The legacy image captcha is answered locally, not reported as a failure.
        val body = if (status == "err_request_captcha" && response.code in 200..299) raw!! else readApiObject(response)
        val message = (body["message"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
        return ActionResult(message, body["data"]?.takeUnless { it == JsonNull }, status)
    }

    // endregion

    companion object {
        /**
         * Search results group normal entities inside listCard containers. Expand
         * before filtering so child IDs, messages and pagination remain intact.
         */
        fun expandListCards(items: List<JsonElement>, depth: Int = 0): Sequence<Datum> = sequence {
            if (depth > 20) throw ApiFailure(ApiFailureKind.InvalidResponse, "服务器响应结构异常，请稍后重试")
            for (element in items) {
                val item = element as? JsonObject ?: continue
                val datum = Datum(item)
                if (datum.entityType == "card" && datum.entityTemplate == "listCard") {
                    (item["entities"] as? JsonArray)?.let { yieldAll(expandListCards(it, depth + 1)) }
                } else {
                    yield(datum)
                }
            }
        }
    }
}
