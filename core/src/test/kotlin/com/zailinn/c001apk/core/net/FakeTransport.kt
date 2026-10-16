package com.zailinn.c001apk.core.net

import com.zailinn.c001apk.core.protocol.ProtocolParams
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/** Replaces only the external transport; signing, client and repository run normally. */
class FakeTransport(var respond: (Request, Int) -> Pair<Int, String> = { _, _ -> 200 to """{"data":[]}""" }) : Interceptor {
    val requests = mutableListOf<Request>()
    val bodies = mutableListOf<String>()
    var beforeResponse: (() -> Unit)? = null
    /** Optional Set-Cookie header per request number. */
    var setCookie: (Int) -> String? = { null }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requests += request
        bodies += request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
        beforeResponse?.invoke()
        val (code, body) = respond(request, requests.size)
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("OK")
            .apply { setCookie(requests.size)?.let { header("Set-Cookie", it) } }
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }
}

val syntheticParams = ProtocolParams(
    xAppDevice = "synthetic-test-device", versionCode = "2604201", versionName = "16.2.0",
    apiVersion = "16", sdkInt = "33", userAgent = "SyntheticTest/1.0",
)

class Harness(respond: (Request, Int) -> Pair<Int, String> = { _, _ -> 200 to """{"data":[]}""" }) {
    val session = SessionState()
    val transport = FakeTransport(respond)
    val client = CoolapkClient(session, CoolapkHeaders(session, { syntheticParams }), transport = transport)
    val repo = CoolapkRepository(client)
}

fun formOf(body: String): Map<String, String> = body.split("&").filter { it.isNotEmpty() }.associate {
    val (k, v) = (it.split("=", limit = 2) + "").take(2)
    java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
}
