package com.zailinn.c001apk.core.backup

import com.zailinn.c001apk.core.model.Datum
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

@Serializable
enum class BackupImageQuality { None, Preview, Original }

@Serializable
data class BackupOptions(val images: BackupImageQuality = BackupImageQuality.Original, val replies: Boolean = true)

const val BACKUP_MEDIA_SCHEME = "c001apk-backup://"

@Serializable
data class BackupImage(val url: String, val name: String, val size: Long = 0, val sha256: String = "", val savedUrl: String = "")

/** Keeps the API envelope for compatibility with the format linked in issue #22. */
@Serializable
data class FeedBackup(
    val feed: JsonObject,
    val replies: List<JsonObject> = emptyList(),
    val formatVersion: Int = 2,
    val savedAt: Long = System.currentTimeMillis(),
    val images: List<BackupImage> = emptyList(),
    val warnings: List<String> = emptyList(),
    val imageQuality: BackupImageQuality = BackupImageQuality.Original,
    val includesReplies: Boolean = true,
    val listFingerprint: String = "",
) {
    val data: Datum get() = Datum((feed["data"] as? JsonObject) ?: feed)
    val id: String get() = backupFeedId(data) ?: error("备份缺少动态编号")

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun parse(text: String): FeedBackup {
            val root = json.parseToJsonElement(text) as? JsonObject ?: error("备份格式无效")
            val result = if (root["feed"] is JsonObject) json.decodeFromJsonElement<FeedBackup>(root)
                else FeedBackup(root, savedAt = 0)
            require(backupFeedId(result.data) != null) { "备份缺少动态编号" }
            require(result.formatVersion in 0..2) { "备份版本较新，请升级应用后重试" }
            return result.copy(savedAt = if (root["savedAt"] == null) 0 else result.savedAt,
                formatVersion = if (root["formatVersion"] == null) 0 else result.formatVersion)
        }
    }
}

/** List cards can carry a feed's identity in different places. Only numeric IDs become filenames. */
fun backupFeedId(data: Datum): String? = sequenceOf(
    data.id, data.string("fid"), data.obj("feed")?.id,
    data.url?.let { Regex("(?:^|/)feed/([0-9]+)(?:[/?#]|$)").find(it)?.groupValues?.get(1) },
).filterNotNull().firstOrNull { it.matches(Regex("[0-9]{1,30}")) }

fun backupImageUrl(raw: String): String? {
    val value = Parser.unescapeEntities(raw.trim().removeSurrounding("\""), true)
    val address = when {
        value.startsWith("//") -> "https:$value"
        value.startsWith('/') -> "https://image.coolapk.com$value"
        value.startsWith("image.coolapk.com/") -> "https://$value"
        else -> value
    }
    val url = address.toHttpUrlOrNull() ?: return null
    val path = if (url.host == "image.coolapk.com") url.encodedPath.replace(Regex("\\.[smbt]\\.jpg$"), "") else url.encodedPath
    return url.newBuilder().scheme("https").encodedPath(path).build().toString()
}

private val imageFields = setOf("pic", "picarr", "useravatar", "avatar", "logo", "messagecover", "coverpic", "cover", "image", "imageurl", "sourceurl", "originalurl", "insource", "compressedurl", "previewurl", "sdrurl", "thumbnail", "thumb", "smallurl")
private val jsonFields = setOf("message_raw_output", "messageRawOutput", "imageUriList", "image_uri_list")
private val urlTokens = Regex("(?:https?://|//|image\\.coolapk\\.com/)[^\\s\"'<>\\\\]+")

/** Includes article blocks, authors, forwarded content, every reply and its nested replies. */
fun backupImageUrls(backup: FeedBackup): List<String> {
    val found = linkedSetOf<String>()
    fun add(raw: String) { raw.split(',').forEach { backupImageUrl(it)?.let(found::add) } }
    fun visit(value: JsonElement, field: String = "", depth: Int = 0) {
        require(depth <= 64) { "动态数据嵌套过深" }
        when (value) {
            is JsonObject -> value.forEach { (key, item) ->
                val fieldName = if (key == "url" && (value["type"] as? JsonPrimitive)?.content == "image") "imageUrl" else key
                visit(item, fieldName, depth + 1)
            }
            is JsonArray -> value.forEach { visit(it, field, depth + 1) }
            is JsonPrimitive -> if (value.isString) {
                val text = value.content
                val key = field.replace("_", "").lowercase()
                if (key in imageFields) add(text)
                if (field in jsonFields && (text.startsWith('[') || text.startsWith('{'))) {
                    runCatching { FeedBackup.json.parseToJsonElement(text) }.getOrNull()?.let { visit(it, field, depth + 1) }
                }
                // Article blocks name their picture simply "url".
                if (key == "url" && text.contains("image.coolapk.com/")) add(text)
                if (text.contains("<img", true)) Jsoup.parseBodyFragment(text).select("img[src]").forEach { add(it.attr("src")) }
                Regex("!\\[[^]]*]\\(([^\\s)]+)").findAll(text).forEach { add(it.groupValues[1]) }
                urlTokens.findAll(text).forEach { token ->
                    val raw = token.value.trimEnd(')', ']', ',', ';')
                    if (raw.contains("image.coolapk.com/")) add(raw)
                }
            }
            else -> Unit
        }
    }
    visit(backup.feed)
    backup.replies.forEach { visit(it) }
    return found.toList()
}

/** Original URLs remain in the portable JSON; only the in-memory offline view is rewritten. */
fun offlineBackup(backup: FeedBackup, urls: Map<String, String>): FeedBackup {
    fun mapped(raw: String) = backupImageUrl(raw)?.let(urls::get)
    fun text(raw: String): String {
        mapped(raw)?.let { return it }
        val html = Regex("(?i)(<img\\b[^>]*\\bsrc\\s*=\\s*[\"'])([^\"']+)([\"'])").replace(raw) { match ->
            match.groupValues[1] + (mapped(match.groupValues[2]) ?: match.groupValues[2]) + match.groupValues[3]
        }
        return urlTokens.replace(html) { token ->
            val value = token.value.trimEnd(')', ']', ',', ';')
            (mapped(value) ?: value) + token.value.removePrefix(value)
        }
    }
    fun visit(value: JsonElement, key: String = "", depth: Int = 0): JsonElement {
        require(depth <= 64) { "备份数据嵌套过深" }
        return when (value) {
            is JsonObject -> JsonObject(value.filterKeys { it !in setOf("liveVideoUrl", "live_video_url", "videoUrl", "video_url") }.mapValues { (name, item) ->
                if (name in setOf("livePhotoEnable", "live_photo_enable", "isLivePhoto", "is_live_photo")) JsonPrimitive(false)
                else if (backup.imageQuality != BackupImageQuality.Original && name in setOf("isHdr", "is_hdr", "hdr")) JsonPrimitive(false)
                else visit(item, name, depth + 1)
            })
            is JsonArray -> if (key.equals("picArr", true)) JsonArray(value.flatMap { item ->
                if (item is JsonPrimitive && item.isString) item.content.split(',').map { JsonPrimitive(text(it)) }
                else listOf(visit(item, key, depth + 1))
            }) else JsonArray(value.map { visit(it, key, depth + 1) })
            is JsonPrimitive -> if (!value.isString) value else {
                val raw = value.content
                val nested = if (key in jsonFields) runCatching { FeedBackup.json.parseToJsonElement(raw) }.getOrNull() else null
                if (key.equals("picArr", true)) JsonArray(raw.split(',').map { JsonPrimitive(text(it)) })
                else JsonPrimitive(if (nested != null) visit(nested, key, depth + 1).toString() else text(raw))
            }
            else -> value
        }
    }
    return backup.copy(feed = visit(backup.feed) as JsonObject, replies = backup.replies.map { visit(it) as JsonObject })
}

/** Old ZIPs use sequence filenames and have no URL manifest; use the documented collection order. */
fun legacyBackupImageUrls(backup: FeedBackup): List<String> {
    val priority = listOf("picArr", "pic", "messageCover", "message_cover", "coverPic", "cover_pic", "userAvatar",
        "userInfo", "fUserInfo", "replyRows", "topReplyRows", "replyMeRows")
    fun order(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(buildMap {
            (priority.filter(value::containsKey) + value.keys.filter { it !in priority }).forEach { key -> put(key, order(value.getValue(key))) }
        })
        is JsonArray -> JsonArray(value.map(::order))
        else -> value
    }
    return backupImageUrls(backup.copy(feed = order(backup.feed) as JsonObject, replies = backup.replies.map { order(it) as JsonObject }))
}

fun backupAvatarUrls(backup: FeedBackup): Set<String> {
    val result = linkedSetOf<String>()
    fun visit(value: JsonElement) {
        when (value) {
            is JsonObject -> value.forEach { (key, item) ->
                if (key.replace("_", "").lowercase() in setOf("useravatar", "avatar") && item is JsonPrimitive) backupImageUrl(item.content)?.let(result::add)
                visit(item)
            }
            is JsonArray -> value.forEach(::visit)
            else -> Unit
        }
    }
    visit(backup.feed); backup.replies.forEach(::visit)
    return result
}
