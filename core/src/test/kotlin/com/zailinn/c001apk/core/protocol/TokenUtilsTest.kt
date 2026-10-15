package com.zailinn.c001apk.core.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenUtilsTest {
    @Test
    fun v3MatchesIndependentFixedTimeVectors() {
        val text = javaClass.classLoader!!.getResource("token_v3.json")!!.readText()
        val vectors = Json.parseToJsonElement(text).jsonArray
        assertTrue(vectors.isNotEmpty())
        for (vector in vectors) {
            val item = vector.jsonObject
            assertEquals(
                item["token"]!!.jsonPrimitive.content,
                TokenUtils.getTokenV3(item["device"]!!.jsonPrimitive.content, item["timestamp"]!!.jsonPrimitive.long),
            )
        }
    }

    @Test
    fun signatureFollowsTimeAndAppCode() {
        val token = TokenUtils.getTokenV3("synthetic-test-device", 1770000000)
        assertNotEquals(token, TokenUtils.getTokenV3("synthetic-test-device", 1770000001))
        assertNotEquals(token, TokenUtils.getTokenV3("synthetic-test-device", 1770000000, appCode = 2604202))
    }

    @Test
    fun deviceEncodingReversesBase64WithoutPadding() {
        // base64("test") = "dGVzdA==" -> reversed, padding removed
        assertEquals("AdzVGd", TokenUtils.encodeDevice("test"))
    }

    @Test
    fun generatedParamsAreComplete() {
        val params = ProtocolParams.generate()
        assertTrue(params.userAgent.contains("+CoolMarket/16.2.0-2604201-universal"))
        assertTrue(params.xAppDevice.isNotEmpty() && !params.xAppDevice.contains("="))
        assertTrue(params.sdkInt.toInt() in 21..34)
    }
}
