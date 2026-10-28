package com.zailinn.c001apk.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Media identity stays with its own image, including when opened from article text. */
@Serializable
data class GalleryMedia(
    val sourceUrl: String,
    val previewUrl: String? = null,
    val sdrUrl: String? = null,
    val videoUrl: String? = null,
    val isHdr: Boolean = false,
    val isLive: Boolean = false,
    val contentId: String? = null,
    val contentType: String = "feed",
) {
    val preview: String get() = previewUrl ?: variant("s")
    val sdrHd: String get() = sdrUrl ?: variant("m")

    private fun variant(size: String): String {
        val url = sourceUrl.toHttpUrlOrNull() ?: return sourceUrl
        if (url.host != "image.coolapk.com") return sourceUrl
        return url.newBuilder().encodedPath(url.encodedPath.replace(Regex("\\.[sm]\\.jpg$"), "") + ".$size.jpg").build().toString()
    }

    fun matches(url: String) = original(url) == sourceUrl || normalized(url) in listOf(sourceUrl, previewUrl, sdrUrl).mapNotNull { it?.let(::normalized) }

    companion object {
        fun fromUrl(url: String, id: String? = null, type: String = "feed"): GalleryMedia {
            val source = original(url)
            val path = source.toHttpUrlOrNull()?.encodedPath.orEmpty()
            return GalleryMedia(source, isHdr = path.contains("-uhdr", true), isLive = path.contains("-livepic", true), contentId = id, contentType = type)
        }

        /** Retains caller order and never borrows another image's owner or video. */
        fun align(urls: List<String>, media: List<GalleryMedia>) = urls.map { url -> media.firstOrNull { it.matches(url) } ?: fromUrl(url) }
    }
}

private fun original(url: String): String {
    val value = normalized(url)
    val parsed = value.toHttpUrlOrNull() ?: return value
    return if (parsed.host == "image.coolapk.com") parsed.newBuilder().encodedPath(parsed.encodedPath.replace(Regex("\\.[smbt]\\.jpg$"), "")).build().toString() else value
}

private fun normalized(url: String): String {
    val value = if (url.startsWith("//")) "https:$url" else url
    val parsed = value.toHttpUrlOrNull() ?: return value
    return parsed.newBuilder().scheme("https").build().toString()
}

/** Parses both the legacy picArr strings and official imageUriList objects/JSON strings. */
fun galleryMedia(json: JsonObject): List<GalleryMedia> {
    val id = (json["id"] as? JsonPrimitive)?.content
    val type = when ((json["entityType"] as? JsonPrimitive)?.content) { "feed_reply" -> "reply"; else -> "feed" }
    fun flatten(value: JsonElement?, depth: Int = 0): List<GalleryMedia> {
        if (value == null || depth > 8) return emptyList()
        return when (value) {
            is JsonArray -> value.flatMap { flatten(it, depth + 1) }
            is JsonPrimitive -> {
                val text = value.content
                if (text.startsWith('[') || text.startsWith('{')) runCatching { flatten(Json.parseToJsonElement(text), depth + 1) }.getOrDefault(emptyList())
                else if (text.toHttpUrlOrNull() != null || text.startsWith("//") || text.startsWith(com.zailinn.c001apk.core.backup.BACKUP_MEDIA_SCHEME)) listOf(GalleryMedia.fromUrl(text, id, type)) else emptyList()
            }
            is JsonObject -> {
                fun field(vararg keys: String) = keys.firstNotNullOfOrNull { (value[it] as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
                fun flag(vararg keys: String) = field(*keys)?.lowercase() in listOf("true", "1")
                val source = field("sourceUrl", "source_url", "originalUrl", "original_url", "inSource", "in_source", "url", "uri", "pic", "imageUrl", "image_url")
                if (source == null) listOf("imageUriList", "image_uri_list", "items", "pics").flatMap { flatten(value[it], depth + 1) }
                else if (source.toHttpUrlOrNull() == null && !source.startsWith("//") && !source.startsWith(com.zailinn.c001apk.core.backup.BACKUP_MEDIA_SCHEME)) emptyList()
                else {
                    val base = GalleryMedia.fromUrl(source, id, type)
                    val video = field("liveVideoUrl", "live_video_url", "videoUrl", "video_url")?.let(::normalized)
                    listOf(base.copy(
                        previewUrl = field("compressedUrl", "compressed_url", "previewUrl", "thumbnail", "thumb")?.let(::normalized),
                        sdrUrl = field("sdrUrl", "sdr_url", "sdrHdUrl", "sdr_hd_url")?.let(::normalized),
                        videoUrl = video,
                        isHdr = base.isHdr || flag("isHdr", "is_hdr", "hdr"),
                        isLive = base.isLive || video != null || flag("livePhotoEnable", "live_photo_enable", "isLivePhoto", "is_live_photo"),
                    ))
                }
            }
            else -> emptyList()
        }
    }
    val metadata = listOf("imageUriList", "image_uri_list", "pics").flatMap { flatten(json[it]) }
    val legacy = flatten(json["picArr"])
    return if (legacy.isNotEmpty()) {
        val ordered = legacy.map { old -> metadata.firstOrNull { it.matches(old.sourceUrl) } ?: old }
        ordered + metadata.filter { candidate -> ordered.none { it.matches(candidate.sourceUrl) } }.distinctBy { it.sourceUrl }
    } else metadata.distinctBy { it.sourceUrl }
}

enum class MediaAutoPlay(val label: String) {
    Off("关闭"), Wifi("仅 WiFi 下"), Always("总是");
    fun allows(wifi: Boolean) = this == Always || (this == Wifi && wifi)
}
