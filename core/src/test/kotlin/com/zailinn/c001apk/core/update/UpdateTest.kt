package com.zailinn.c001apk.core.update

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun releaseJson(
    tag: String = "1.2.0-release",
    draft: Boolean = false,
    names: List<String> = listOf("android-arm64-v8a.apk", "android-armeabi-v7a.apk", "android-x86_64.apk", "windows-x86_64.zip"),
): String {
    val assets = names
        .joinToString(",") {
            """{"name":"c001apk-1.2.0+20261015-$it","state":"uploaded","size":1024,
            "browser_download_url":"https://github.com/ZaiLinn/c001apk/releases/download/$tag/c001apk-1.2.0%2B20261015-$it"}"""
        }
    return """{"tag_name":"$tag","html_url":"https://github.com/ZaiLinn/c001apk/releases/tag/$tag","draft":$draft,
        "prerelease":false,"body":"修复问题","published_at":"2026-10-15T00:00:00Z","assets":[$assets]}"""
}

private class MemoryStore : UpdatePreferenceStore {
    val values = mutableMapOf<String, Any>()
    override fun getLong(key: String) = values[key] as? Long
    override fun getString(key: String) = values[key] as? String
    override fun getBoolean(key: String) = values[key] as? Boolean
    override suspend fun put(key: String, value: Any) { values[key] = value }
}

class UpdateTest {
    private var now = 1_791_000_000_000L
    private var status = 200
    private var body = releaseJson()
    private var requests = 0
    private val store = MemoryStore()
    private val preferences = UpdatePreferences(store)
    private val service = UpdateService(preferences, { "1.1.5" }, { requests++; status to body }, { now })

    @Test
    fun versionsHandleTagsAndRejectPrereleases() {
        assertEquals(AppVersion(1, 2, 0), AppVersion.parse("1.2.0-release"))
        assertEquals(AppVersion(1, 2, 0), AppVersion.parse("v1.2.0"))
        assertTrue(AppVersion.parse("1.10.0") > AppVersion.parse("1.9.9"))
        assertTrue(runCatching { AppVersion.parse("1.3.0-beta") }.isFailure)
    }

    @Test
    fun releaseSelectsCompatibleAbi() {
        val release = AppRelease.fromJson(Json.parseToJsonElement(releaseJson()) as JsonObject)
        assertTrue(release.androidDownload(listOf("x86", "armeabi-v7a")).encodedPath.endsWith("android-armeabi-v7a.apk"))
        assertEquals(release.page, release.androidDownload(listOf("mips")))
        val split = AppRelease.fromJson(Json.parseToJsonElement(releaseJson(names = listOf("android-x86.apk", "android-universal.apk"))) as JsonObject)
        assertTrue(split.androidDownload(listOf("x86")).encodedPath.endsWith("android-x86.apk"))
        assertTrue(runCatching { AppRelease.fromJson(Json.parseToJsonElement(releaseJson(draft = true)) as JsonObject) }.isFailure)
    }

    @Test
    fun universalApkServesEveryAbi() {
        val json = releaseJson(names = listOf("android-universal.apk"))
        val release = AppRelease.fromJson(Json.parseToJsonElement(json) as JsonObject)
        assertTrue(release.androidDownload(listOf("arm64-v8a")).encodedPath.endsWith("android-universal.apk"))
        assertTrue(release.androidDownload(listOf("x86")).encodedPath.endsWith("android-universal.apk"))
    }

    @Test
    fun untrustedAssetsFallBackToReleasePage() {
        val json = releaseJson().replace("https://github.com/ZaiLinn/c001apk/releases/download", "https://evil.invalid/ZaiLinn/c001apk/releases/download")
        val release = AppRelease.fromJson(Json.parseToJsonElement(json) as JsonObject)
        assertEquals(release.page, release.androidDownload(listOf("arm64-v8a")))
    }

    @Test
    fun automaticChecksRunOncePerDay() = runTest {
        assertEquals(UpdateStatus.Available, service.check(automatic = true).status)
        assertEquals(UpdateStatus.Suppressed, service.check(automatic = true).status)
        now += UpdatePreferences.INTERVAL
        assertEquals(UpdateStatus.Available, service.check(automatic = true).status)
        assertEquals(2, requests)
    }

    @Test
    fun disabledAndSnoozedChecksAreBypassedManually() = runTest {
        preferences.setEnabled(false)
        assertEquals(UpdateStatus.Suppressed, service.check(automatic = true).status)
        assertEquals(0, requests)
        assertEquals(UpdateStatus.Available, service.check().status)
        preferences.setEnabled(true)
        preferences.snooze(now)
        assertEquals(UpdateStatus.Suppressed, service.check(automatic = true).status)
    }

    @Test
    fun skipAffectsOnlyThatVersion() = runTest {
        preferences.skip("1.2.0")
        assertEquals(UpdateStatus.Suppressed, service.check(automatic = true).status)
        assertEquals(UpdateStatus.Available, service.check().status)
        body = releaseJson(tag = "1.3.0-release")
        now += UpdatePreferences.INTERVAL
        assertEquals(UpdateStatus.Available, service.check(automatic = true).status)
    }

    @Test
    fun sameOrOlderVersionsNeverTriggerUpdate() = runTest {
        body = releaseJson(tag = "1.1.5-release")
        assertEquals(UpdateStatus.UpToDate, service.check().status)
    }

    @Test
    fun failuresReportReasons() = runTest {
        status = 403
        assertEquals(UpdateStatus.Failed, service.check(automatic = true).status)
        assertEquals(UpdateStatus.Suppressed, service.check(automatic = true).status)
        assertTrue(service.check().message.contains("请求过于频繁"))
        status = 404
        assertTrue(service.check().message.contains("正式版本"))
        status = 200; body = "<html>error</html>"
        assertEquals(UpdateStatus.Failed, service.check().status)
    }
}
