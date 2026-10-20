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
            // The vectors pin the app code they were generated with, so they keep
            // cross-checking the algorithm no matter which version the app ships.
            assertEquals(
                item["token"]!!.jsonPrimitive.content,
                TokenUtils.getTokenV3(
                    item["device"]!!.jsonPrimitive.content,
                    item["timestamp"]!!.jsonPrimitive.long,
                    appCode = item["appCode"]!!.jsonPrimitive.content.toInt(),
                ),
            )
        }
    }

    @Test
    fun signatureFollowsTimeAndAppCode() {
        // Pin the app code so the vectors stay independent of the shipping version.
        val token = TokenUtils.getTokenV3("synthetic-test-device", 1770000000, appCode = 2604201)
        // The timestamps are more than 15s apart on purpose: getTokenV3 retries the
        // next second (up to +15) when a second yields an invalid bcrypt salt, so
        // two adjacent seconds can resolve to the same effective time and collide.
        assertNotEquals(token, TokenUtils.getTokenV3("synthetic-test-device", 1770000100, appCode = 2604201))
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
        assertTrue(params.userAgent.contains("+CoolMarket/16.6.4-2609291-universal"))
        assertTrue(params.xAppDevice.isNotEmpty() && !params.xAppDevice.contains("="))
        assertTrue(params.profile in RealDevices.profiles)
    }

    @Test
    fun deviceIdSurvivesProfileChangesUntilANewOneIsAsked() {
        val params = ProtocolParams.generate(random = kotlin.random.Random(1))
        val id = params.deviceId!!
        assertEquals(32, id.length)
        val local = DeviceProfile("Xiaomi", "Redmi", "24069RA21C", "AQ3A.240627.003", "35", "15")
        val moved = params.withProfile(local)
        assertEquals(id, moved.deviceId)
        assertEquals(local, moved.profile)
        assertTrue(moved.userAgent.contains("(#Build; Redmi; 24069RA21C; AQ3A.240627.003; 15)"))
        // No made-up MAC address in the device code.
        assertEquals("$id; ; ; ; Xiaomi; Redmi; 24069RA21C; AQ3A.240627.003; null", decode(moved.xAppDevice))
        assertNotEquals(id, moved.withDevice(newId = true).deviceId)
        assertEquals("SZLM", moved.copy(szlmId = "SZLM").withDevice().deviceId)
    }

    @Test
    fun realDevicesHaveMatchingSdkAndRelease() {
        val sdkOf = mapOf("13" to "33", "14" to "34", "15" to "35")
        for (device in RealDevices.profiles) assertEquals(device.toString(), sdkOf[device.androidVersion], device.sdkInt)
    }

    private fun decode(code: String): String {
        val reversed = code.reversed()
        return String(java.util.Base64.getDecoder().decode(reversed + "=".repeat((4 - reversed.length % 4) % 4)))
    }
}
