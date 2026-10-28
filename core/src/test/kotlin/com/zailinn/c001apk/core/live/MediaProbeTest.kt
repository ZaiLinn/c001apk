package com.zailinn.c001apk.core.live

import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.net.*
import com.zailinn.c001apk.core.protocol.ProtocolParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional guest reads of media configuration; no content, signed URLs or credentials are logged. */
class MediaProbeTest {
    @Test fun guestMediaMetadata() = runBlocking {
        assumeTrue(System.getenv("COOLAPK_MEDIA_PROBE") == "1")
        val session = SessionState()
        val params = ProtocolParams.generate()
        val client = CoolapkClient(session, CoolapkHeaders(session, { params }))
        val repository = CoolapkRepository(client)
        var checkedLive = false
        var checkedHdr = false
        for (term in listOf("实况", "HDR")) {
            val raw = readApiObject(client.get("https://api.coolapk.com/v6/search", listOf("searchValue" to term, "type" to "feed", "page" to "1", "sort" to "default")))
            val media = (raw["data"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().flatMap { Datum(it).mediaImages }
            println("media configuration: images=${media.size}, live=${media.count { it.isLive }}, hdr=${media.count { it.isHdr }}")
            if (!checkedLive) media.firstOrNull { it.isLive }?.let { item ->
                val url = repository.livePhotoVideo(item)
                withContext(Dispatchers.IO) {
                    client.http.newCall(Request.Builder().url(url).header("User-Agent", params.userAgent).header("Range", "bytes=0-23").build()).execute().use { response ->
                        check(response.isSuccessful) { "live video HTTP ${response.code}" }
                        val head = response.body!!.source().readByteArray(8)
                        check(String(head, 4, 4) == "ftyp") { "live video is not an MP4/MOV container" }
                        println("live video: resolved over HTTPS, valid ftyp header")
                    }
                }
                checkedLive = true
            }
            if (!checkedHdr) media.firstOrNull { it.isHdr }?.let { item ->
                withContext(Dispatchers.IO) {
                    client.http.newCall(Request.Builder().url(item.sdrHd).header("User-Agent", params.userAgent).header("Range", "bytes=0-1").build()).execute().use { response ->
                        check(response.isSuccessful) { "SDR HD fallback HTTP ${response.code}" }
                        val head = response.body!!.source().readByteArray(2)
                        check(head.contentEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte())))
                        println("HDR fallback: SDR HD JPEG available")
                    }
                }
                checkedHdr = true
            }
        }
        check(checkedLive && checkedHdr) { "search did not provide both live and HDR media for validation" }
    }
}
