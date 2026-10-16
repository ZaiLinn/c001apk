package com.zailinn.c001apk.core.net

import com.zailinn.c001apk.core.protocol.Constants
import com.zailinn.c001apk.core.protocol.ProtocolParams
import com.zailinn.c001apk.core.protocol.TokenUtils
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder

/** Per-request options carried as an OkHttp tag. */
data class RequestOptions(
    val generation: Int? = null,
    val skipCaptcha: Boolean = false,
    /** Image download from the account site (login captcha). */
    val expectImage: Boolean = false,
    /**
     * A captcha proof carried as a `validate` cookie for the retry of a
     * blank-field (validate-cookie protocol) challenge. Never persisted.
     */
    val validateProof: String? = null,
)

internal class SessionChangedException : IOException("账号已切换")

/**
 * Adds client identity only to official Coolapk hosts. Third-party hosts never
 * receive the signature, device identity or cookies.
 */
class CoolapkHeaders(
    private val session: SessionState,
    private val protocol: () -> ProtocolParams,
    private val token: (ProtocolParams) -> String = { TokenUtils.getTokenV3(it.xAppDevice, appCode = it.versionCode.toInt()) },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val options = request.tag(RequestOptions::class.java)
        if (options?.generation != null && options.generation != session.generation) throw SessionChangedException()
        val signed = try {
            sign(request, options)
        } catch (_: NumberFormatException) {
            throw ApiFailureIOException(ApiFailure(ApiFailureKind.Business, "请求参数异常，请检查客户端协议设置和系统时间"))
        } catch (_: IllegalStateException) {
            throw ApiFailureIOException(ApiFailure(ApiFailureKind.Business, "请求参数异常，请检查客户端协议设置和系统时间"))
        }
        return chain.proceed(signed)
    }

    fun sign(request: Request, options: RequestOptions? = request.tag(RequestOptions::class.java)): Request {
        val url = request.url
        val params = protocol()
        val builder = request.newBuilder()
        when {
            url.host == "account.coolapk.com" -> {
                builder.header("sec-ch-ua", "\"Android WebView\";v=\"117\", \"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"117\"")
                builder.header("sec-ch-ua-mobile", "?1")
                builder.header("sec-ch-ua-platform", "Android")
                builder.header("Upgrade-Insecure-Requests", "1")
                builder.header("User-Agent", params.userAgent)
                builder.header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                )
                builder.header("X-Requested-With", Constants.APP_ID)
                if (session.loginSession.isNotEmpty()) {
                    builder.header("Cookie", "${session.loginSession}; forward=https://www.coolapk.com")
                }
                if (request.method == "POST") {
                    builder.header("X-Requested-With", Constants.REQUEST_WITH)
                } else if (options?.expectImage == true) {
                    builder.header("Accept", "image/*,*/*;q=0.8")
                    builder.header("Referer", "https://account.coolapk.com/auth/loginByCoolApk")
                }
            }
            isApiHost(request) || isPublicFeed(request) -> {
                builder.header("User-Agent", params.userAgent)
                builder.header("X-Requested-With", Constants.REQUEST_WITH)
                builder.header("X-Sdk-Int", params.sdkInt)
                builder.header("X-Sdk-Locale", Constants.LOCALE)
                builder.header("X-App-Id", Constants.APP_ID)
                builder.header("X-App-Token", token(params))
                builder.header("X-App-Version", params.versionName)
                builder.header("X-App-Code", params.versionCode)
                builder.header("X-Api-Version", params.apiVersion)
                builder.header("X-App-Device", params.xAppDevice)
                builder.header("X-Dark-Mode", "0")
                builder.header("X-App-Channel", Constants.CHANNEL)
                builder.header("X-App-Mode", Constants.MODE)
                builder.header("X-App-Supported", params.versionCode)
                if (isPublicFeed(request)) {
                    // Official public feed JSON needs signed client headers, but no
                    // account/session cookie. Restricted to numeric feed IDs.
                    builder.removeHeader("Cookie")
                } else {
                    val account = session.account
                    val sessid = session.sessid.takeIf { it.startsWith("SESSID=") }
                    val base = if (account != null) {
                        "uid=${account.uid}; username=${account.username}; token=${account.token}" + (sessid?.let { "; $it" } ?: "")
                    } else {
                        sessid.orEmpty()
                    }
                    // A blank-field captcha proof rides in a `validate` cookie, shared
                    // by the API hosts; it is per-request only and never persisted.
                    val cookie = options?.validateProof?.let { proof ->
                        (if (base.isEmpty()) "" else "$base; ") + "validate=" + URLEncoder.encode(proof, "UTF-8")
                    } ?: base
                    if (cookie.isNotEmpty()) builder.header("Cookie", cookie) else builder.removeHeader("Cookie")
                }
            }
            else -> {
                // Never forward credentials to third-party hosts.
                builder.removeHeader("Cookie")
                builder.removeHeader("X-App-Token")
            }
        }
        return builder.build()
    }

    companion object {
        private val publicFeedPath = Regex("^/feed/[0-9]+$")
        val apiHosts = setOf("api.coolapk.com", "api2.coolapk.com")

        fun isApiHost(request: Request) = request.url.host in apiHosts

        fun isPublicFeed(request: Request) =
            request.url.isHttps && request.url.host == "www.coolapk.com" && request.method == "GET" &&
                publicFeedPath.matches(request.url.encodedPath)
    }
}

/** Carries a typed failure through OkHttp, which only propagates IOExceptions. */
class ApiFailureIOException(val failure: ApiFailure) : IOException(failure.message)
