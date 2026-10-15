package com.zailinn.c001apk.core.protocol

import kotlin.random.Random

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
    fun withUserAgent(): ProtocolParams = copy(
        userAgent = "Dalvik/2.1.0 (Linux; U; Android $androidVersion; $model $buildNumber) " +
            "(#Build; $brand; $model; $buildNumber; $androidVersion) " +
            "+CoolMarket/$versionName-$versionCode-${Constants.MODE}",
    )

    fun withDevice(random: Random = Random.Default): ProtocolParams = copy(
        xAppDevice = TokenUtils.encodeDevice(
            "${szlmId.ifEmpty { DeviceRandom.hex(32, random) }}; ; ; ${DeviceRandom.macAddress(random)}; " +
                "$manufacturer; $brand; $model; $buildNumber; null",
        ),
    )

    companion object {
        /** A fresh synthetic device, as created on first launch. */
        fun generate(szlmId: String = "", random: Random = Random.Default): ProtocolParams = ProtocolParams(
            manufacturer = DeviceRandom.manufacturers.random(random),
            brand = DeviceRandom.brands.random(random),
            model = DeviceRandom.hex(6, random),
            buildNumber = DeviceRandom.hex(32, random),
            sdkInt = (random.nextInt(14) + 21).toString(),
            androidVersion = DeviceRandom.androidReleases.random(random),
            szlmId = szlmId,
        ).withUserAgent().withDevice(random)
    }
}

object DeviceRandom {
    val manufacturers = listOf(
        "Samsung", "Google", "Huawei", "Xiaomi", "OnePlus", "Sony", "LG", "Motorola", "HTC",
        "Nokia", "Lenovo", "Asus", "ZTE", "Alcatel", "OPPO", "Vivo", "Realme",
    )
    val brands = listOf(
        "Samsung", "Google", "Huawei", "Xiaomi", "Redmi", "OnePlus", "Sony", "LG", "Motorola",
        "HTC", "Nokia", "Lenovo", "Asus", "ZTE", "Alcatel", "OPPO", "Vivo", "Realme",
    )
    val androidReleases = listOf("5.0.1", "6.0", "7.0", "7.1.1", "8.0.0", "8.1.0", "9", "10", "11", "12", "13", "14")

    fun hex(length: Int, random: Random = Random.Default, upperCase: Boolean = true): String {
        val chars = "0123456789abcdef"
        val value = buildString { repeat(length) { append(chars[random.nextInt(chars.length)]) } }
        return if (upperCase) value.uppercase() else value
    }

    fun macAddress(random: Random = Random.Default): String {
        val bytes = IntArray(6) { random.nextInt(256) }
        bytes[0] = (bytes[0] and 0xFE) or 0x02
        return bytes.joinToString(":") { "%02x".format(it) }.uppercase()
    }
}
