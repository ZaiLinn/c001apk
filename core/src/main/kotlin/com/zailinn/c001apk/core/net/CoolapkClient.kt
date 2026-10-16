package com.zailinn.c001apk.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiResponse(
    val code: Int,
    val headers: Headers,
    val bytes: ByteArray,
    val url: HttpUrl,
    /** `cancelled`, `incomplete` or `submitted` when a captcha challenge was met. */
    val captchaVerification: String? = null,
) {
    val text: String by lazy { bytes.toString(Charsets.UTF_8) }
    val cookies: List<String> get() = headers.values("Set-Cookie")

    fun withVerification(value: String) = ApiResponse(code, headers, bytes, url, value)
}

/** Request body that can be rebuilt with a captcha proof added. */
sealed interface Body {
    data class Form(val fields: List<Pair<String, String>>) : Body
    data class Multipart(val fields: List<Pair<String, String>>) : Body

    fun with(field: String, value: String): Body = when (this) {
        is Form -> Form(fields.filter { it.first != field } + (field to value))
        is Multipart -> Multipart(fields.filter { it.first != field } + (field to value))
    }

    fun toRequestBody(): RequestBody = when (this) {
        is Form -> FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        is Multipart -> MultipartBody.Builder().setType(MultipartBody.FORM)
            .apply { fields.forEach { (k, v) -> addFormDataPart(k, v) } }.build()
    }
}

typealias CaptchaVerifier = suspend (CaptchaChallenge) -> String?

class CoolapkClient(
    val session: SessionState,
    headers: CoolapkHeaders,
    baseClient: OkHttpClient = OkHttpClient(),
    /** Test seam: replaces the network after signing. */
    transport: Interceptor? = null,
) {
    val http: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(headers)
        .apply { transport?.let(::addInterceptor) }
        .build()
    private val noRedirect: OkHttpClient = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    @Volatile var captchaVerifier: CaptchaVerifier? = null
    private val sessidPattern = Regex("^[A-Za-z0-9._%-]{1,256}$")
    private val verifying = AtomicBoolean(false)

    suspend fun get(
        url: String,
        query: List<Pair<String, String>> = emptyList(),
        skipCaptcha: Boolean = false,
        expectImage: Boolean = false,
        followRedirects: Boolean = true,
    ): ApiResponse {
        val generation = session.generation
        val snapshot = session.account
        val options = RequestOptions(generation, skipCaptcha, expectImage)
        val client = if (followRedirects) http else noRedirect
        fun build(extra: List<Pair<String, String>>) = Request.Builder()
            .url(url.toHttpUrl().newBuilder().apply { (query + extra).forEach { (k, v) -> addQueryParameter(k, v) } }.build())
            .tag(RequestOptions::class.java, options)
            .get().build()
        val response = execute(client, build(emptyList()), generation)
        return verifyAndRetry(response, snapshot, generation, skipCaptcha) { proof, challenge ->
            execute(client, build(listOf(challenge.field to proof)), generation)
        }.also { checkGeneration(generation) }
    }

    suspend fun post(
        url: String,
        query: List<Pair<String, String>> = emptyList(),
        body: Body = Body.Form(emptyList()),
        skipCaptcha: Boolean = false,
    ): ApiResponse {
        val generation = session.generation
        val snapshot = session.account
        val options = RequestOptions(generation, skipCaptcha)
        val httpUrl = url.toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        fun build(content: Body) = Request.Builder().url(httpUrl)
            .tag(RequestOptions::class.java, options)
            .post(content.toRequestBody()).build()
        val response = execute(http, build(body), generation)
        return verifyAndRetry(response, snapshot, generation, skipCaptcha) { proof, challenge ->
            execute(http, build(body.with(challenge.field, proof)), generation)
        }.also { checkGeneration(generation) }
    }

    /** Plain request to a third-party host, without any client identity. */
    suspend fun external(request: Request): ApiResponse = execute(http, request, null)

    private suspend fun verifyAndRetry(
        response: ApiResponse,
        snapshot: AccountCredentials?,
        generation: Int,
        skip: Boolean,
        retry: suspend (String, CaptchaChallenge) -> ApiResponse,
    ): ApiResponse {
        val verifier = captchaVerifier
        if (skip || generation != session.generation || verifier == null || snapshot != session.account ||
            !response.url.isHttps || response.url.host !in CoolapkHeaders.apiHosts || response.code !in 200..299
        ) return response
        val challenge = CaptchaChallenge.fromText(response.text) ?: return response
        if (!verifying.compareAndSet(false, true)) return response
        val proof = try {
            verifier(challenge)
        } finally {
            verifying.set(false)
        }
        if (proof == null || generation != session.generation || !challenge.acceptsProof(proof) ||
            snapshot != session.account
        ) {
            return response.withVerification(if (proof == null) "cancelled" else "incomplete")
        }
        // Single direct retry: no verification loop or replay after transport errors.
        return retry(proof, challenge).withVerification("submitted")
    }

    private fun checkGeneration(generation: Int) {
        if (generation != session.generation) throw ApiFailure.cancelled
    }

    private suspend fun execute(client: OkHttpClient, request: Request, generation: Int?): ApiResponse = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).await()
        } catch (e: ApiFailureIOException) {
            throw e.failure
        } catch (_: SessionChangedException) {
            throw ApiFailure.cancelled
        }
        val result = response.use {
            ApiResponse(it.code, it.headers, it.body?.bytes() ?: ByteArray(0), it.request.url)
        }
        if (generation != null && generation != session.generation) throw ApiFailure.cancelled
        keepSessionCookie(request, result)
        result
    }

    /**
     * The API issues a SESSID with each response, captcha challenges included,
     * and a passed verification is remembered on it. Dropping it, as earlier
     * versions did for signed-in accounts, made every page ask again.
     */
    private fun keepSessionCookie(request: Request, response: ApiResponse) {
        if (!request.url.isHttps || request.url.host !in CoolapkHeaders.apiHosts) return
        val value = response.cookies.map { it.substringBefore(';').trim() }
            .lastOrNull { it.startsWith("SESSID=") }?.removePrefix("SESSID=") ?: return
        session.sessid = if (value.isEmpty() || value == "deleted" || !sessidPattern.matches(value)) "" else "SESSID=$value"
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
    continuation.invokeOnCancellation { runCatching { cancel() } }
}
