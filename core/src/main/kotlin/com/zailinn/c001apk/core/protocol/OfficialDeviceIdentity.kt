package com.zailinn.c001apk.core.protocol

/**
 * Reads the SZLM ID (数盟 ID) out of the text the official app copies after
 * "复制 OAID" is tapped five times on its 检查日志和测试 page. Only the
 * `ShuzlmID` field is used: OAID, AndroidID and UUID are not substitutes.
 * The import flow follows TG-Twilight/c001apk (AGPL-3.0).
 */
object OfficialDeviceIdentity {
    /** Opens the official app's 检查日志和测试 page. */
    const val TEST_PAGE = "coolmarket://www.coolapk.com/native/testSettings"
    const val OFFICIAL_PACKAGE = "com.coolapk.market"

    private val field = Regex("^\\s*ShuzlmID\\s*[:：]\\s*([^\\r\\n]*)$", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val value = Regex("^[A-Za-z0-9_+/.=-]{8,4096}$")

    /** The SZLM ID in [text]; throws [IllegalArgumentException] with a message for the user. */
    fun parse(text: String): String {
        require(text.length <= 32768) { "复制内容过长，请重新从官方酷安复制" }
        val matches = field.findAll(text.replace("﻿", "")).toList()
        require(matches.size == 1) { "未识别到 ShuzlmID。请在官方页面累计点击“复制 OAID”5 次，再粘贴完整内容" }
        val id = matches.single().groupValues[1].trim()
        require(id.lowercase() !in setOf("", "null", "undefined") && value.matches(id)) {
            "数盟 ID 为空或格式不正确，请打开官方酷安稍等片刻后重新复制"
        }
        return id
    }

    /** Head and tail only, for showing which ID is saved without exposing it. */
    fun mask(id: String): String = if (id.length <= 12) "${id.take(2)}…" else "${id.take(6)}…${id.takeLast(4)}"
}
