package com.zailinn.c001apk.core.update

import com.zailinn.c001apk.core.net.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Key-value storage for update reminders, separate from account and content data. */
interface UpdatePreferenceStore {
    fun getLong(key: String): Long?
    fun getString(key: String): String?
    fun getBoolean(key: String): Boolean?
    suspend fun put(key: String, value: Any)
}

/** Separate from checkUpdate, which controls other installed Android apps. */
class UpdatePreferences(private val store: UpdatePreferenceStore) {
    val enabled: Boolean get() = store.getBoolean(ENABLED_KEY) ?: true
    suspend fun setEnabled(value: Boolean) = store.put(ENABLED_KEY, value)
    suspend fun recordCheck(now: Long) = store.put(LAST_CHECK_KEY, now)
    suspend fun snooze(now: Long) = store.put(SNOOZE_KEY, now + INTERVAL)
    suspend fun skip(version: String) = store.put(SKIP_KEY, version)
    fun skipped(version: String) = store.getString(SKIP_KEY) == version

    fun automaticAllowed(now: Long): Boolean {
        if (!enabled) return false
        val snooze = store.getLong(SNOOZE_KEY)
        if (snooze != null && snooze > now && snooze - now <= INTERVAL) return false
        val last = store.getLong(LAST_CHECK_KEY)
        // A backwards clock correction must not suppress checks indefinitely.
        return last == null || now < last || now - last >= INTERVAL
    }

    companion object {
        const val ENABLED_KEY = "autoCheckAppUpdate"
        private const val LAST_CHECK_KEY = "lastAppUpdateCheck"
        private const val SNOOZE_KEY = "appUpdateSnoozeUntil"
        private const val SKIP_KEY = "skippedAppUpdateVersion"
        const val INTERVAL = 24L * 60 * 60 * 1000
    }
}

enum class UpdateStatus { Available, UpToDate, Suppressed, Busy, Failed }

data class UpdateResult(
    val status: UpdateStatus,
    val release: AppRelease? = null,
    val currentVersion: String = "",
    val message: String = "",
)

/**
 * Checks the latest public GitHub release. Uses its own HTTP client without any
 * Coolapk interceptor, cookie, account token or device signature.
 */
class UpdateService(
    val preferences: UpdatePreferences,
    private val currentVersion: () -> String,
    private val fetch: suspend () -> Pair<Int, String> = defaultFetcher(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val checking = AtomicBoolean(false)

    suspend fun check(automatic: Boolean = false): UpdateResult {
        if (!checking.compareAndSet(false, true)) return UpdateResult(UpdateStatus.Busy)
        try {
            if (automatic && !preferences.automaticAllowed(clock())) return UpdateResult(UpdateStatus.Suppressed)
            if (automatic) preferences.recordCheck(clock())
            val current = currentVersion()
            val (status, body) = fetch()
            if (status !in 200..299) {
                return UpdateResult(
                    UpdateStatus.Failed,
                    message = when (status) {
                        403, 429 -> "检查更新请求过于频繁，请稍后重试或查看发布页面"
                        404 -> "暂未找到正式版本，请稍后重试或查看发布页面"
                        else -> "检查更新失败，请检查网络后重试"
                    },
                )
            }
            val json = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                ?: return UpdateResult(UpdateStatus.Failed, message = "更新信息格式异常，请稍后重试或查看发布页面")
            val release = try {
                AppRelease.fromJson(json)
            } catch (_: IllegalArgumentException) {
                return UpdateResult(UpdateStatus.Failed, message = "更新信息格式异常，请稍后重试或查看发布页面")
            }
            if (automatic && (!preferences.enabled || preferences.skipped(release.version.toString()))) {
                return UpdateResult(UpdateStatus.Suppressed)
            }
            val installed = runCatching { AppVersion.parse(current) }.getOrNull()
            return UpdateResult(
                if (installed == null || release.version > installed) UpdateStatus.Available else UpdateStatus.UpToDate,
                release, current,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return UpdateResult(UpdateStatus.Failed, message = "检查更新失败，请检查网络后重试")
        } finally {
            checking.set(false)
        }
    }

    companion object {
        const val LATEST_RELEASE = "https://api.github.com/repos/ZaiLinn/c001apk/releases/latest"

        fun defaultFetcher(client: OkHttpClient = OkHttpClient()): suspend () -> Pair<Int, String> {
            val http = client.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
            return {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(LATEST_RELEASE)
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "c001apk-update")
                        .build()
                    http.newCall(request).await().use { it.code to (it.body?.string().orEmpty()) }
                }
            }
        }
    }
}
