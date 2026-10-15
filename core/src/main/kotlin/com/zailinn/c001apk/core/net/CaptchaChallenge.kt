package com.zailinn.c001apk.core.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Protocol configuration only. Verification proofs are short lived and never
 * stored or printed. Source: coolapk-desktop 3ef2ad4, neteaseCaptcha.ts (MIT).
 */
data class CaptchaChallenge(val captchaId: String, val field: String) {
    fun proof(validate: String?): String? {
        if (validate.isNullOrEmpty() || validate.length > 8192 || controlOrSpace.containsMatchIn(validate)) return null
        return "NEC:${captchaId.substring(0, 8)}:$validate"
    }

    fun acceptsProof(proof: String): Boolean =
        proof.startsWith("NEC:${captchaId.substring(0, 8)}:") &&
            proof.length in 14..8205 &&
            !controlOrSpace.containsMatchIn(proof)

    companion object {
        const val DEFAULT_ID = "414e5c9b866a03db03f860a1a9101672"
        private val controlOrSpace = Regex("[\\s\\x00-\\x1f]")
        private val idPattern = Regex("^[a-zA-Z0-9]{16,64}$")
        private val fieldPattern = Regex("^_[a-zA-Z0-9_]{1,63}$")

        fun fromText(text: String): CaptchaChallenge? {
            val body = runCatching { lenientJson.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
            return fromBody(body)
        }

        fun fromBody(body: JsonObject): CaptchaChallenge? {
            if ((body["messageStatus"] as? JsonPrimitive)?.content != "err_request_captcha_v2") return null
            var extra = body["messageExtra"] ?: body["extra"]
            if (extra is JsonPrimitive && extra.isString) {
                extra = runCatching { lenientJson.parseToJsonElement(extra.content) }.getOrNull()
            }
            val source = extra as? JsonObject ?: body
            val id = (source["captchaId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val field = (source["captchaField"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return CaptchaChallenge(
                if (id != null && idPattern.matches(id)) id else DEFAULT_ID,
                if (field != null && fieldPattern.matches(field)) field else "_v2_post_token",
            )
        }
    }
}
