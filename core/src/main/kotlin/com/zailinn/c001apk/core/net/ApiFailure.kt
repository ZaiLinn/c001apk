package com.zailinn.c001apk.core.net

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

enum class ApiFailureKind {
    Transport, Cancelled, Http, Business, Authentication, Captcha, RateLimit, RiskControl,
    ClientUpgrade, InvalidResponse, Parsing,
    /** "当前版本存在异常 (-419)" or 评论接口异常: the server does not accept the device's 数盟 ID, as with a stale one. */
    DeviceRejected,
}

/** App-wide hooks for failures the whole app should react to, not just the screen that asked. */
object ApiFailureEvents {
    /** Called, on the thread that read the response, whenever the device code is rejected. */
    @Volatile var onDeviceRejected: (() -> Unit)? = null
}

/**
 * Only safe, local messages enter the UI. Never include a response body,
 * request URL, headers or the underlying exception's toString().
 */
class ApiFailure(
    val kind: ApiFailureKind,
    override val message: String,
    val httpStatus: Int? = null,
    /** The server's status or code and marker, as shown after a business failure. */
    val errorCode: String? = null,
) : Exception(message) {
    companion object {
        fun http(status: Int?) = ApiFailure(
            ApiFailureKind.Http, "请求失败（HTTP ${status ?: "未知"}），请稍后重试", status,
        )

        val cancelled get() = ApiFailure(ApiFailureKind.Cancelled, "请求已取消")
        val invalidResponse get() = ApiFailure(ApiFailureKind.InvalidResponse, "服务器响应格式异常，请稍后重试")

        fun from(error: Throwable): ApiFailure = when (error) {
            is ApiFailure -> error
            is SocketTimeoutException -> ApiFailure(ApiFailureKind.Transport, "请求超时，请稍后重试")
            is SSLPeerUnverifiedException, is SSLHandshakeException ->
                ApiFailure(ApiFailureKind.Transport, "服务器证书验证失败")
            is UnknownHostException, is ConnectException, is NoRouteToHostException ->
                ApiFailure(ApiFailureKind.Transport, "连接失败，请检查网络设置")
            is InterruptedIOException -> ApiFailure(ApiFailureKind.Transport, "请求超时，请稍后重试")
            is IOException -> ApiFailure(ApiFailureKind.Transport, "请求失败，请检查网络后重试")
            is SerializationException, is IllegalArgumentException -> invalidResponse
            else -> ApiFailure(ApiFailureKind.Parsing, "数据解析失败，请更新客户端或稍后重试")
        }
    }
}

internal val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonObject.raw(key: String): String? = when (val v = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> v.content
    else -> v.toString()
}

/**
 * Checks the envelope before any model access. An HTTP 200 alone says nothing
 * about login, risk controls or the shape of the payload.
 */
fun readApiObject(response: ApiResponse): JsonObject {
    if (response.code !in 200..299) throw ApiFailure.http(response.code)
    val body = try {
        lenientJson.parseToJsonElement(response.text)
    } catch (_: Exception) {
        throw ApiFailure.invalidResponse
    }
    if (body !is JsonObject) throw ApiFailure.invalidResponse
    return checkApiObject(body)
}

/**
 * How the server answers a 数盟 ID it does not accept, as seen with stale IDs:
 * -419 on feed details, 评论接口异常 on replies and sub-replies.
 */
private val DEVICE_REJECTED_MARKERS = listOf("-419", "当前版本存在异常", "评论接口异常")

/** A short, readable server message, or null when there is none worth showing. */
private fun serverReason(message: String): String? =
    message.replace(Regex("<[^>]*>"), "").trim().takeIf { it.isNotEmpty() && it.length <= 80 && it.any { c -> c.code > 0x2E80 } }

fun checkApiObject(body: JsonObject): JsonObject {
    val marker = body.raw("messageStatus").orEmpty().lowercase()
    val message = body.raw("message").orEmpty()
    val error = body.raw("error")
    val data = body["data"]
    // Some failures come as HTTP 200 with the reason as a string in `data`, like 评论接口异常.
    val dataText = (data as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    val clues = "$marker $message ${error.orEmpty()} $dataText".lowercase()
    val code = body.raw("code")?.toIntOrNull()
    val businessStatus = body.raw("status")?.toIntOrNull()
    val deviceRejected = DEVICE_REJECTED_MARKERS.any { it in clues }
    val failed = deviceRejected || (code != null && code !in setOf(0, 1, 200)) ||
        (businessStatus != null && businessStatus !in setOf(0, 1)) ||
        marker.startsWith("err_") ||
        (error != null && error !in setOf("", "0", "false")) ||
        ((data == null || data == JsonNull) && message.isNotEmpty() && code == null && businessStatus == null)
    if (failed) {
        // The status or code, plus a non-readable marker such as err_xxx, so a
        // failure can be told apart even when the server gives no reason.
        val number = (businessStatus ?: code)?.toString() ?: error?.takeIf { it.toIntOrNull() != null }
        val tag = marker.ifEmpty { message.trim().takeIf { serverReason(it) == null && it.length in 1..40 && ' ' !in it }.orEmpty() }
            .takeIf { it.isNotEmpty() && it != number }
        val errorCode = listOfNotNull(number, tag).joinToString(" · ").ifEmpty { null }
        fun coded(text: String) = errorCode?.let { "$text（错误码 $it）" } ?: text
        // Some failures carry their reason in a string `data` rather than `message`.
        val reason = serverReason(message) ?: (data as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::serverReason)
        throw when {
            businessStatus == -10 || clues.contains("网络环境") -> ApiFailure(
                ApiFailureKind.RiskControl, coded("服务端检测到网络环境异常，请检查代理、DNS 或网络过滤设置后重试"), errorCode = errorCode,
            )
            marker == "err_request_need_upgrade_new_version" -> ApiFailure(
                ApiFailureKind.ClientUpgrade, coded("当前客户端协议未被服务端接受，请更新客户端后重试"), errorCode = errorCode,
            )
            deviceRejected -> {
                ApiFailureEvents.onDeviceRejected?.invoke()
                ApiFailure(ApiFailureKind.DeviceRejected, coded(reason ?: "服务端拒绝了当前设备标识，请稍后重试"), errorCode = errorCode)
            }
            clues.contains("captcha") || clues.contains("验证码") ->
                ApiFailure(ApiFailureKind.Captcha, "请求需要验证码验证，请完成验证后重试", errorCode = errorCode)
            clues.contains("login") || clues.contains("auth") || clues.contains("登录") || code == 401 ->
                ApiFailure(ApiFailureKind.Authentication, coded("登录状态已失效或需要登录，请重新登录"), errorCode = errorCode)
            clues.contains("rate_limit") || clues.contains("频繁") || code == 429 ->
                ApiFailure(ApiFailureKind.RateLimit, coded("请求过于频繁，请稍后重试"), errorCode = errorCode)
            // The server's own reason, such as 用户不存在 or a privacy notice, says more than a generic text.
            else -> ApiFailure(ApiFailureKind.Business, coded(reason ?: "服务器未能完成请求，请稍后重试"), errorCode = errorCode)
        }
    }
    if (!body.containsKey("data")) {
        throw ApiFailure(ApiFailureKind.InvalidResponse, "服务器响应缺少数据字段，请稍后重试")
    }
    return body
}
