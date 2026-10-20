package com.zailinn.c001apk.core.protocol

import java.util.Base64
import kotlin.random.Random

/** Model and system of a phone as Android reports them (`Build.*`). */
data class DeviceProfile(
    val manufacturer: String,
    val brand: String,
    val model: String,
    /** `Build.DISPLAY`. */
    val buildNumber: String,
    val sdkInt: String,
    val androidVersion: String,
)

/** Client identity sent with API requests. Persisted by the app, never logged. */
data class ProtocolParams(
    val versionName: String = Constants.VERSION_NAME,
    val versionCode: String = Constants.VERSION_CODE,
    val apiVersion: String = Constants.API_VERSION,
    val manufacturer: String = "",
    val brand: String = "",
    val model: String = "",
    val buildNumber: String = "",
    val sdkInt: String = "",
    val androidVersion: String = "",
    val userAgent: String = "",
    val xAppDevice: String = "",
    val szlmId: String = "",
) {
    val profile: DeviceProfile get() = DeviceProfile(manufacturer, brand, model, buildNumber, sdkInt, androidVersion)

    fun withUserAgent(): ProtocolParams = copy(
        userAgent = "Dalvik/2.1.0 (Linux; U; Android $androidVersion; $model $buildNumber) " +
            "(#Build; $brand; $model; $buildNumber; $androidVersion) " +
            "+CoolMarket/$versionName-$versionCode-${Constants.MODE}",
    )

    /** Takes the model and system of [profile]; the device ID stays. */
    fun withProfile(profile: DeviceProfile): ProtocolParams = copy(
        manufacturer = profile.manufacturer,
        brand = profile.brand,
        model = profile.model,
        buildNumber = profile.buildNumber,
        sdkInt = profile.sdkInt,
        androidVersion = profile.androidVersion,
    ).withUserAgent().withDevice()

    /** First field of the device code: the SZLM ID, or a random ID made once. */
    val deviceId: String?
        get() = runCatching {
            val reversed = xAppDevice.reversed()
            val padded = reversed + "=".repeat((4 - reversed.length % 4) % 4)
            String(Base64.getDecoder().decode(padded)).substringBefore(';').trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * Rebuilds the device code from the current fields. The ID is the SZLM ID
     * when set, otherwise the existing one; [newId] starts a fresh random one.
     * The MAC field stays blank, as apps cannot read it since Android 6.
     */
    fun withDevice(newId: Boolean = false, random: Random = Random.Default): ProtocolParams {
        val id = szlmId.ifEmpty { (if (newId) null else deviceId) ?: DeviceRandom.hex(32, random) }
        return copy(xAppDevice = TokenUtils.encodeDevice("$id; ; ; ; $manufacturer; $brand; $model; $buildNumber; null"))
    }

    companion object {
        /** A different, real phone model with a fresh device ID. */
        fun generate(szlmId: String = "", random: Random = Random.Default): ProtocolParams =
            fromProfile(RealDevices.profiles.random(random), szlmId, random)

        /** New parameters for [profile] with a fresh device ID. */
        fun fromProfile(profile: DeviceProfile, szlmId: String = "", random: Random = Random.Default): ProtocolParams =
            ProtocolParams(szlmId = szlmId).withProfile(profile).withDevice(newId = true, random = random)
    }
}

/**
 * Popular phones with matching model codes, build IDs and Android versions, so
 * a generated device looks like one that exists rather than a random mix.
 */
object RealDevices {
    val profiles = listOf(
        DeviceProfile("Xiaomi", "Xiaomi", "23127PN0CC", "UKQ1.230804.001", "34", "14"),
        DeviceProfile("Xiaomi", "Xiaomi", "2211133C", "TKQ1.221114.001", "33", "13"),
        DeviceProfile("Xiaomi", "Redmi", "23113RKC6C", "UKQ1.230804.001", "34", "14"),
        DeviceProfile("Xiaomi", "Redmi", "22122RK93C", "TKQ1.221114.001", "33", "13"),
        DeviceProfile("Google", "google", "Pixel 8", "AP2A.240805.005", "34", "14"),
        DeviceProfile("Google", "google", "Pixel 7", "TQ3A.230805.001", "33", "13"),
        DeviceProfile("samsung", "samsung", "SM-S9210", "UP1A.231005.007", "34", "14"),
        DeviceProfile("OnePlus", "OnePlus", "PJD110", "PJD110_14.0.0.600(CN01)", "34", "14"),
        DeviceProfile("OPPO", "OPPO", "PHZ110", "PHZ110_14.0.0.700(CN01)", "34", "14"),
        DeviceProfile("vivo", "vivo", "V2309A", "UP1A.231005.007", "34", "14"),
    )
}

object DeviceRandom {
    fun hex(length: Int, random: Random = Random.Default, upperCase: Boolean = true): String {
        val chars = "0123456789abcdef"
        val value = buildString { repeat(length) { append(chars[random.nextInt(chars.length)]) } }
        return if (upperCase) value.uppercase() else value
    }
}
