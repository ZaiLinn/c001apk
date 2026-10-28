package com.zailinn.c001apk.core.upload

import com.zailinn.c001apk.core.net.ApiFailure
import com.zailinn.c001apk.core.net.ApiFailureKind
import com.zailinn.c001apk.core.net.CoolapkRepository
import com.zailinn.c001apk.core.net.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** An image picked for a post or reply. */
class UploadImage(val name: String, val width: Int, val height: Int, val bytes: ByteArray, val mimeType: String)

/**
 * Coolapk image upload: the API prepares temporary Aliyun OSS credentials and
 * object names, then each image is PUT directly to OSS with a V1 signature.
 */
class OssUploader(private val repository: CoolapkRepository, http: OkHttpClient = OkHttpClient()) {
    private val client = http.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).writeTimeout(5, TimeUnit.MINUTES).readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Uploads all images and returns the comma-separated `pic` field value. */
    suspend fun upload(images: List<UploadImage>, dir: String = "feed", toUid: String? = null, progress: (Int, Int) -> Unit = { _, _ -> }): String {
        val (files, prefix) = put(images, "image", dir, toUid, progress)
        return files.joinToString(",") { "$prefix/$it" }
    }

    /**
     * Uploads a private message image to the message bucket and returns the
     * `message_pic` value, the object path with a leading slash.
     */
    suspend fun uploadMessageImage(image: UploadImage, toUid: String): String {
        val (files, _) = put(listOf(image), "message", "message", toUid) { _, _ -> }
        val name = files.single()
        if (!name.startsWith("message/")) throw ApiFailure.invalidResponse
        return "/$name"
    }

    /** Prepares credentials, PUTs every image and returns the object names and the image URL prefix. */
    private suspend fun put(
        images: List<UploadImage>, bucketType: String, dir: String, toUid: String?, progress: (Int, Int) -> Unit,
    ): Pair<List<String>, String?> {
        val list = images.joinToString(", ", "[", "]") { image ->
            buildJsonObject {
                put("name", image.name)
                put("resolution", "${image.width}x${image.height}")
                put("md5", hex(MessageDigest.getInstance("MD5").digest(image.bytes)))
            }.toString()
        }
        val result = repository.ossUploadPrepare(buildList {
            add("uploadBucket" to bucketType)
            add("uploadDir" to dir)
            add("is_anonymous" to "0")
            add("uploadFileList" to list)
            toUid?.let { add("toUid" to it) }
        })
        result.message?.let { throw ApiFailure(ApiFailureKind.Business, it) }
        val data = result.dataObject ?: throw ApiFailure.invalidResponse
        val info = data["uploadPrepareInfo"] as? JsonObject ?: throw ApiFailure.invalidResponse
        val files = (data["fileInfo"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("uploadFileName") }
            ?: throw ApiFailure.invalidResponse
        if (files.size != images.size) throw ApiFailure.invalidResponse
        val key = info.str("accessKeyId")
        val secret = info.str("accessKeySecret")
        val token = info.str("securityToken")
        val bucket = info.str("bucket")
        val endpoint = info.str("endPoint")?.removePrefix("https://")?.removePrefix("http://")
        val prefix = info.str("uploadImagePrefix")
        if (key == null || secret == null || token == null || bucket == null || endpoint == null ||
            (prefix == null && bucketType == "image")
        ) {
            throw ApiFailure.invalidResponse
        }
        files.forEachIndexed { index, fileName ->
            progress(index, files.size)
            val objectPath = dir + fileName.replaceFirst(dir, "")
            put(images[index], "https://$bucket.$endpoint/$objectPath", objectPath, bucket, key, secret, token)
        }
        return files to prefix
    }

    private suspend fun put(
        image: UploadImage, url: String, objectPath: String, bucket: String, key: String, secret: String, token: String,
    ) = withContext(Dispatchers.IO) {
        val md5 = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(image.bytes))
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date()) + " GMT"
        val ossHeaders = sortedMapOf(
            "x-oss-security-token" to token,
            "x-oss-callback-var" to CALLBACK_VAR,
            "x-oss-callback" to CALLBACK,
        )
        val authorization = "OSS $key:" + signature(secret, "PUT", md5, image.mimeType, date, ossHeaders, "/$bucket/$objectPath")
        val request = Request.Builder().url(url)
            .header("Content-MD5", md5)
            .header("Date", date)
            .header("Authorization", authorization)
            .apply { ossHeaders.forEach { (k, v) -> header(k, v) } }
            .put(image.bytes.toRequestBody(image.mimeType.toMediaType()))
            .build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw ApiFailure(ApiFailureKind.Http, "图片上传失败（HTTP ${response.code}），请稍后重试", response.code)
        }
    }

    companion object {
        private const val CALLBACK_VAR = "eyJ4OnZhcjEiOiJmYWxzZSJ9"
        private const val CALLBACK =
            "eyJjYWxsYmFja0JvZHlUeXBlIjoiYXBwbGljYXRpb25cL2pzb24iLCJjYWxsYmFja0hvc3QiOiJhcGkuY29vbGFway5jb20iLCJjYWxsYmFja1VybCI6Imh0dHBzOlwvXC9hcGkuY29vbGFway5jb21cL3Y2XC9jYWxsYmFja1wvbW9iaWxlT3NzVXBsb2FkU3VjY2Vzc0NhbGxiYWNrP2NoZWNrQXJ0aWNsZUNvdmVyUmVzb2x1dGlvbj0wJnZlcnNpb25Db2RlPTIxMDIwMzEiLCJjYWxsYmFja0JvZHkiOiJ7XCJidWNrZXRcIjoke2J1Y2tldH0sXCJvYmplY3RcIjoke29iamVjdH0sXCJoYXNQcm9jZXNzXCI6JHt4OnZhcjF9fSJ9"

        /** OSS V1 header signature over method, MD5, type, date, x-oss-* headers and resource. */
        fun signature(
            secret: String, method: String, md5: String, contentType: String, date: String,
            ossHeaders: Map<String, String>, resource: String,
        ): String {
            val canonicalHeaders = ossHeaders.entries
                .map { it.key.lowercase().trim() to it.value.trim() }
                .filter { it.first.startsWith("x-oss-") }
                .sortedBy { it.first }
                .joinToString("\n") { "${it.first}:${it.second}" }
            val text = listOf(method, md5, contentType, date, canonicalHeaders, resource).joinToString("\n")
            val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA1")) }
            return Base64.getEncoder().encodeToString(mac.doFinal(text.toByteArray()))
        }

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.content
    }
}
