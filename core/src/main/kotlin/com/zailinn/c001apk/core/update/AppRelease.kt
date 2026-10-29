package com.zailinn.c001apk.core.update

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Only stable semantic versions participate in public release checks. */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion) = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val pattern = Regex("^v?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-release)?(?:\\+\\d+)?$")
        fun parse(value: String): AppVersion {
            val match = pattern.matchEntire(value) ?: throw IllegalArgumentException("无法识别正式版本")
            val (a, b, c) = match.destructured
            return AppVersion(a.toInt(), b.toInt(), c.toInt())
        }
    }
}

class AppRelease private constructor(
    val version: AppVersion,
    val page: HttpUrl,
    val notes: String,
    val publishedAt: String?,
    private val assets: Map<String, HttpUrl>,
) {
    /** Best APK for the device ABIs, then the universal APK, or the release page when none matches. */
    fun androidDownload(abis: List<String>): HttpUrl {
        val suffixes = abis.filter { it in setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }.map { "android-$it.apk" } +
            "android-universal.apk"
        for (suffix in suffixes) {
            val pattern = Regex("^c001apk-${Regex.escape(version.toString())}(?:\\+\\d+)?-${Regex.escape(suffix)}$")
            assets.entries.firstOrNull { pattern.matches(it.key) }?.let { return it.value }
        }
        return page
    }

    companion object {
        private const val REPOSITORY = "/ZaiLinn/c001apk/releases/"

        private fun trusted(url: HttpUrl, prefix: String) =
            url.isHttps && url.host == "github.com" && url.username.isEmpty() && url.port == 443 &&
                url.encodedPath.startsWith(REPOSITORY + prefix)

        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        fun fromJson(json: JsonObject): AppRelease {
            val draft = (json["draft"] as? JsonPrimitive)?.booleanOrNull
            val prerelease = (json["prerelease"] as? JsonPrimitive)?.booleanOrNull
            val tag = json.str("tag_name")
            val html = json.str("html_url")
            val assetList = json["assets"] as? JsonArray
            if (draft != false || prerelease != false || tag == null || html == null || assetList == null) {
                throw IllegalArgumentException("更新信息格式异常")
            }
            val version = AppVersion.parse(tag)
            val page = html.toHttpUrlOrNull()
            if (page == null || !trusted(page, "tag/") || page.pathSegments.last() != tag) {
                throw IllegalArgumentException("更新来源异常")
            }
            val assets = linkedMapOf<String, HttpUrl>()
            for (element in assetList) {
                val asset = element as? JsonObject ?: continue
                val name = asset.str("name") ?: continue
                val url = asset.str("browser_download_url")?.toHttpUrlOrNull() ?: continue
                val size = (asset["size"] as? JsonPrimitive)?.doubleOrNull ?: continue
                if (asset.str("state") != "uploaded" || size <= 0) continue
                if (trusted(url, "download/") && url.pathSegments.size == 6 && url.pathSegments[4] == tag &&
                    url.pathSegments.last() == name
                ) assets[name] = url
            }
            val body = json.str("body")?.takeIf { it.isNotBlank() } ?: "此版本未提供更新说明，请查看发布页面。"
            return AppRelease(version, page, body, json.str("published_at"), assets)
        }
    }
}
