package com.zailinn.c001apk.data

import com.zailinn.c001apk.core.net.CaptchaEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A captcha challenge as kept on this device. No credentials, IDs or content. */
@Serializable
data class CaptchaRecord(
    val time: Long,
    val method: String,
    val path: String,
    val signedIn: Boolean,
    val cookieProtocol: Boolean,
    val outcome: String,
    val sincePreviousMs: Long? = null,
    val inLastMinute: Int = 0,
    val sinceStart: Int = 0,
    /** Device parameters at the time: this phone's or generated. */
    val localDevice: Boolean = true,
    val szlmImported: Boolean = false,
)

/**
 * The last [LIMIT] captcha challenges with the request rate they were met at,
 * so what triggers them can be looked at later (设置 → 协议与设备 → 人机验证记录).
 */
class CaptchaLog(private val store: LocalStore, private val scope: CoroutineScope) {
    private val serializer = ListSerializer(CaptchaRecord.serializer())

    val records: StateFlow<List<CaptchaRecord>> = store.state { store.readJson(it, KEY, serializer, emptyList()) }

    fun add(event: CaptchaEvent, settings: AppSettings) {
        val record = CaptchaRecord(
            time = event.time,
            method = event.method,
            path = event.path,
            signedIn = event.signedIn,
            cookieProtocol = event.cookieProtocol,
            outcome = event.outcome,
            sincePreviousMs = event.stats?.sincePreviousMs,
            inLastMinute = event.stats?.inLastMinute ?: 0,
            sinceStart = event.stats?.sinceStart ?: 0,
            localDevice = settings.protocolSource == PROTOCOL_LOCAL,
            szlmImported = settings.protocol.szlmId.isNotEmpty(),
        )
        scope.launch { store.updateJson(KEY, serializer, emptyList()) { (it + record).takeLast(LIMIT) } }
    }

    suspend fun clear() = store.updateJson(KEY, serializer, emptyList()) { emptyList() }

    companion object {
        const val KEY = "captcha_log"
        const val LIMIT = 200

        val outcomeNames = mapOf(
            "skipped" to "未弹出（显示热门评论等替代内容）",
            "paused" to "未弹出（暂停期内）",
            "busy" to "未弹出（另一个验证进行中）",
            "not-prompted" to "未弹出",
            "cancelled" to "已取消",
            "incomplete" to "未完成",
            "submitted" to "验证通过",
            "rejected" to "通过后仍被拦截",
        )

        fun describe(record: CaptchaRecord): String = buildString {
            append(SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(record.time)))
            append(" · ").append(record.method).append(' ').append(record.path)
            append(" · ").append(outcomeNames[record.outcome] ?: record.outcome)
            append(" · ").append(if (record.signedIn) "已登录" else "游客")
            record.sincePreviousMs?.let { append(" · 距上个请求 ").append(seconds(it)) }
            append(" · 1 分钟内 ").append(record.inLastMinute).append(" 个请求")
            append(" · 启动后第 ").append(record.sinceStart).append(" 个")
            append(" · ").append(if (record.localDevice) "本机参数" else "生成参数")
            if (record.szlmImported) append(" · 已导入数盟 ID")
        }

        /** Plain text for an issue report, newest first. */
        fun report(records: List<CaptchaRecord>): String = buildString {
            appendLine("c001apk 人机验证记录（${records.size} 条）")
            summary(records).forEach { appendLine(it) }
            appendLine()
            records.asReversed().forEach { appendLine(describe(it)) }
        }.trimEnd()

        /** Counts by endpoint and by outcome, most frequent first. */
        fun summary(records: List<CaptchaRecord>): List<String> {
            if (records.isEmpty()) return emptyList()
            val day = records.last().time - 24 * 3_600_000L
            val byPath = records.groupingBy { "${it.method} ${it.path}" }.eachCount().entries.sortedByDescending { it.value }
            val byOutcome = records.groupingBy { it.outcome }.eachCount().entries.sortedByDescending { it.value }
            return listOf(
                "最近 24 小时 ${records.count { it.time >= day }} 次",
                "按接口：" + byPath.take(5).joinToString("，") { "${it.key} ${it.value} 次" },
                "按结果：" + byOutcome.joinToString("，") { "${outcomeNames[it.key] ?: it.key} ${it.value} 次" },
            )
        }

        private fun seconds(ms: Long) = if (ms < 10_000) String.format(Locale.ROOT, "%.1f 秒", ms / 1000.0) else "${ms / 1000} 秒"
    }
}
