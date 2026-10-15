package com.zailinn.c001apk.core.protocol

import org.mindrot.jbcrypt.BCrypt
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random

object TokenUtils {
    private val authBlob: ByteArray by lazy { Base64.getDecoder().decode(TOKEN_V3_DATA.trim()) }
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val saltPattern = Regex("^[./A-Za-z0-9]{22}$")

    /**
     * Compatible with coolapk-desktop auth.rs; see THIRD_PARTY_NOTICES.md.
     * A fresh signature is derived per request, including the active app code.
     */
    fun getTokenV3(
        deviceCode: String,
        timestamp: Long = System.currentTimeMillis() / 1000,
        appCode: Int = Constants.VERSION_CODE.toInt(),
    ): String {
        for (offset in 0..15) {
            val time = timestamp + offset
            val index = (((time + appCode) % 100) * 4 + 0x80).toInt()
            val window = String(authBlob, index, 0x80, Charsets.US_ASCII)
            val segment = String(Base64.getDecoder().decode(window), Charsets.UTF_8)
            val plain = "${Constants.APP_ID}&$segment&${md5(deviceCode)}&$time&$appCode"
            val password = md5(Base64.getEncoder().encodeToString(plain.toByteArray()))
            val source = base64NoPadding("${time.toString(16)}/${md5(plain)}").substring(0, 22)
            val shifted = (ALPHABET.indexOf(source[21]) - 5).mod(64)
            val salt = source.substring(0, 21) + ALPHABET[shifted]
            if (!saltPattern.matches(salt)) continue
            val hash = "\$2y" + BCrypt.hashpw(password, "\$2a\$04\$$salt").substring(3)
            return "v3" + base64NoPadding(hash)
        }
        throw IllegalStateException("无法生成请求签名，请检查系统时间")
    }

    fun createRandomNumber(): String = Random.nextDouble().toString().replace(".", "undefined")

    fun encodeDevice(deviceInfo: String): String =
        Base64.getEncoder().encodeToString(deviceInfo.toByteArray())
            .reversed()
            .replace(Regex("(\\r\\n|\\r|\\n|=)"), "")

    fun base64NoPadding(input: String): String =
        Base64.getEncoder().encodeToString(input.toByteArray()).replace("=", "")

    fun md5(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
