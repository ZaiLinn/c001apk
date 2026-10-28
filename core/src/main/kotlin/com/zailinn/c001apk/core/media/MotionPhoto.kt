package com.zailinn.c001apk.core.media

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Android Motion Photo 1.0: lossless JPEG (including its gain map) followed by MP4. */
object MotionPhoto {
    private const val XMP = "http://ns.adobe.com/xap/1.0/\u0000"
    private const val CAMERA = "http://ns.google.com/photos/1.0/camera/"
    private const val CONTAINER = "http://ns.google.com/photos/1.0/container/"
    private const val ITEM = "http://ns.google.com/photos/1.0/container/item/"
    private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    const val MAX_PHOTO_BYTES = 64 * 1024 * 1024L

    data class Video(val offset: Long, val length: Long, val presentationUs: Long = -1)
    private data class Segment(val start: Int, val end: Int, val marker: Int)

    /** Reads bounded JPEG metadata, then confirms that the advertised MP4 actually exists. */
    fun inspect(file: File): Video? = runCatching {
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < 4 || input.readUnsignedShort() != 0xffd8) return null
            var scanned = 2L
            val xml = mutableListOf<Element>()
            while (scanned < minOf(input.length(), 2 * 1024 * 1024L)) {
                if (input.readUnsignedByte() != 0xff) return null
                var marker = input.readUnsignedByte()
                while (marker == 0xff) marker = input.readUnsignedByte()
                if (marker == 0xda || marker == 0xd9) break
                val size = input.readUnsignedShort()
                if (size < 2 || input.filePointer + size - 2 > input.length()) return null
                if (marker == 0xe1) {
                    val bytes = ByteArray(size - 2).also(input::readFully)
                    if (bytes.startsWith(XMP.toByteArray())) xml.add(parse(bytes.copyOfRange(XMP.length, bytes.size)))
                } else input.seek(input.filePointer + size - 2)
                scanned = input.filePointer
            }
            val elements = xml.flatMap { it.getAllElements() }
            val flag = elements.firstNotNullOfOrNull { attribute(it, CAMERA, "MotionPhoto") }
            if (flag != null && flag != "1") return null
            val video = elements.firstOrNull { attribute(it, ITEM, "Semantic") == "MotionPhoto" }
            val length = video?.let { attribute(it, ITEM, "Length")?.toLongOrNull() }
                ?: elements.firstNotNullOfOrNull { attribute(it, CAMERA, "MicroVideoOffset")?.toLongOrNull() }
                ?: return null
            val offset = input.length() - length
            if (offset < input.filePointer || !validVideo(input, offset, length)) return null
            val time = elements.firstNotNullOfOrNull { attribute(it, CAMERA, "MotionPhotoPresentationTimestampUs")?.toLongOrNull() } ?: -1L
            Video(offset, length, time)
        }
    }.getOrNull()

    fun extractVideo(source: File, target: File): Video {
        val video = inspect(source) ?: error("图片不包含有效实况视频")
        copyRange(source, target, video.offset, video.length)
        return video
    }

    /** Removes the video and its XMP flags without recompressing either JPEG stream. */
    fun extractPhoto(source: File, target: File) {
        if (inspect(source) == null) copyRange(source, target, 0, source.length())
        else target.writeBytes(rewriteStill(source, null, -1))
    }

    fun isVideo(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { validVideo(it, 0, it.length()) }
    }.getOrDefault(false)

    fun write(photo: File, video: File, target: File, presentationUs: Long = -1) {
        require(isVideo(video)) { "实况视频格式不受支持" }
        val rewritten = rewriteStill(photo, video.length(), presentationUs)
        target.outputStream().use { out -> out.write(rewritten); video.inputStream().use { it.copyTo(out) } }
        check(inspect(target)?.length == video.length()) { "合成实况校验失败" }
    }

    private fun rewriteStill(photo: File, videoLength: Long?, presentationUs: Long): ByteArray {
        val stillLength = inspect(photo)?.offset ?: photo.length()
        require(stillLength <= MAX_PHOTO_BYTES)
        val still = RandomAccessFile(photo, "r").use { input -> ByteArray(stillLength.toInt()).also(input::readFully) }
        val segments = segments(still)
        val xmpSegments = segments.filter { it.marker == 0xe1 && still.copyOfRange(it.start + 4, it.end).startsWith(XMP.toByteArray()) }
        val docs = xmpSegments.map { parse(still.copyOfRange(it.start + 4 + XMP.length, it.end)) }
        val root = parse("""<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="$RDF"/></x:xmpmeta>""".toByteArray())
        val rdf = root.getElementsByTag("rdf:RDF").first()!!
        var oldGainmapLength: Long? = null
        for (doc in docs) {
            for (description in doc.getAllElements().filter { it.tagName().substringAfter(':') == "Description" }) {
                val copy = description.clone()
                rdf.appendChild(copy)
                // Namespace declarations may have lived on the old xmpmeta/RDF ancestors.
                var parent: Element? = description
                while (parent != null) {
                    parent.attributes().filter { it.key.startsWith("xmlns:") }.forEach { if (!copy.hasAttr(it.key)) copy.attr(it.key, it.value) }
                    parent = parent.parent()
                }
                for (element in copy.getAllElements().toList()) {
                    if (element.tagName().substringAfter(':') == "Directory" && namespace(element, element.tagName().substringBefore(':')) == CONTAINER) {
                        oldGainmapLength = element.getAllElements().firstOrNull { attribute(it, ITEM, "Semantic") == "GainMap" }
                            ?.let { attribute(it, ITEM, "Length")?.toLongOrNull() } ?: oldGainmapLength
                        element.remove()
                    } else {
                        element.attributes().toList().filter { namespace(element, it.key.substringBefore(':')) == CAMERA }.forEach { element.removeAttr(it.key) }
                    }
                }
            }
        }
        val description = rdf.appendElement("rdf:Description").attr("xmlns:Camera", CAMERA).attr("xmlns:Container", CONTAINER).attr("xmlns:Item", ITEM)
        if (videoLength != null) description.attr("Camera:MotionPhoto", "1").attr("Camera:MotionPhotoVersion", "1")
            .attr("Camera:MotionPhotoPresentationTimestampUs", presentationUs.coerceAtLeast(-1).toString())
        val sequence = description.appendElement("Container:Directory").appendElement("rdf:Seq")
        fun add(mime: String, semantic: String, length: Long = 0) {
            sequence.appendElement("rdf:li").attr("rdf:parseType", "Resource").appendElement("Container:Item")
                .attr("Item:Mime", mime).attr("Item:Semantic", semantic).attr("Item:Length", length.toString())
        }
        add("image/jpeg", "Primary")
        if (oldGainmapLength != null) {
            add("image/jpeg", "GainMap", oldGainmapLength!!)
        } else {
            // Ultra HDR's second JPEG remains byte-for-byte identical.
            val firstEnd = primaryEnd(still, segments)
            if (firstEnd < still.size && still.getOrNull(firstEnd) == 0xff.toByte() && still.getOrNull(firstEnd + 1) == 0xd8.toByte()) {
                add("image/jpeg", "GainMap", (still.size - firstEnd).toLong())
            }
        }
        if (videoLength != null) add("video/mp4", "MotionPhoto", videoLength)
        val xml = (XMP + root.outerHtml()).toByteArray(Charsets.UTF_8)
        require(xml.size + 2 <= 65535) { "图片元数据过大，无法合成实况" }
        val packet = byteArrayOf(0xff.toByte(), 0xe1.toByte(), ((xml.size + 2) shr 8).toByte(), (xml.size + 2).toByte()) + xml
        fun mapped(offset: Int): Int = offset + (if (offset >= 2) packet.size else 0) - xmpSegments.filter { it.end <= offset }.sumOf { it.end - it.start }
        val rewritten = ByteArrayOutputStream(still.size + packet.size).use { out ->
            out.write(still, 0, 2); out.write(packet)
            var position = 2
            xmpSegments.forEach { out.write(still, position, it.start - position); position = it.end }
            out.write(still, position, still.size - position)
            out.toByteArray()
        }
        // MPF indexes are relative to the TIFF header. Replacing XMP can move
        // either side of that index, so repair it rather than flattening HDR.
        for (segment in segments.filter { it.marker == 0xe2 }) {
            val start = segment.start + 4
            if (still.copyOfRange(start, minOf(start + 4, still.size)).contentEquals(byteArrayOf(77, 80, 70, 0))) {
                repairMpf(still, rewritten, start + 4, ::mapped)
            }
        }
        return rewritten
    }

    private fun repairMpf(old: ByteArray, output: ByteArray, tiff: Int, mapped: (Int) -> Int) {
        val order = if (old[tiff] == 'I'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val source = ByteBuffer.wrap(old).order(order)
        val target = ByteBuffer.wrap(output).order(order)
        val ifd = tiff + source.getInt(tiff + 4)
        val count = source.getShort(ifd).toInt() and 0xffff
        require(count <= 256 && ifd + 2 + count * 12 <= old.size) { "MPF 索引无效" }
        for (i in 0 until count) {
            val entry = ifd + 2 + i * 12
            if ((source.getShort(entry).toInt() and 0xffff) != 0xb002) continue
            val bytes = source.getInt(entry + 4)
            val table = tiff + source.getInt(entry + 8)
            require(bytes in 16..4096 && bytes % 16 == 0 && table >= tiff && table + bytes <= old.size)
            for (n in 0 until bytes / 16) {
                val record = table + n * 16
                val offset = source.getInt(record + 8)
                val size = source.getInt(record + 4)
                val begin = if (offset == 0) 0 else tiff + offset
                require(begin >= 0 && size >= 0 && begin.toLong() + size <= old.size)
                target.putInt(mapped(record + 4), mapped(begin + size) - mapped(begin))
                target.putInt(mapped(record + 8), if (offset == 0) 0 else mapped(begin) - mapped(tiff))
            }
        }
    }

    private fun segments(bytes: ByteArray): List<Segment> {
        require(bytes.size > 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) { "实况封装需要 JPEG 照片" }
        val result = mutableListOf<Segment>()
        var pos = 2
        while (pos + 4 <= bytes.size) {
            require(bytes[pos] == 0xff.toByte()) { "JPEG 数据无效" }
            val marker = bytes[pos + 1].toInt() and 0xff
            if (marker == 0xda || marker == 0xd9) break
            val size = ((bytes[pos + 2].toInt() and 255) shl 8) or (bytes[pos + 3].toInt() and 255)
            require(size >= 2 && pos.toLong() + size + 2 <= bytes.size)
            result += Segment(pos, pos + size + 2, marker)
            pos += size + 2
        }
        return result
    }

    private fun primaryEnd(bytes: ByteArray, segments: List<Segment>): Int {
        val begin = segments.lastOrNull()?.end ?: 2
        for (i in begin until bytes.size - 1) if (bytes[i] == 0xff.toByte() && bytes[i + 1] == 0xd9.toByte()) return i + 2
        return bytes.size
    }

    private fun validVideo(input: RandomAccessFile, offset: Long, length: Long): Boolean {
        if (length < 16 || offset < 0 || offset + length > input.length()) return false
        input.seek(offset)
        val boxSize = input.readInt().toLong() and 0xffffffffL
        return boxSize in 16..length && input.readInt() == 0x66747970 // ftyp
    }

    private fun parse(bytes: ByteArray): Element = Jsoup.parse(String(bytes, Charsets.UTF_8), "", Parser.xmlParser()).apply { outputSettings().prettyPrint(false) }
    private fun namespace(element: Element, prefix: String): String? {
        var current: Element? = element
        while (current != null) {
            if (current.hasAttr("xmlns:$prefix")) return current.attr("xmlns:$prefix")
            current = current.parent()
        }
        return null
    }
    private fun attribute(element: Element, uri: String, name: String) = element.attributes().firstOrNull {
        it.key.substringAfter(':') == name && namespace(element, it.key.substringBefore(':')) == uri
    }?.value
    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    private fun copyRange(source: File, target: File, start: Long, length: Long) {
        RandomAccessFile(source, "r").use { input ->
            input.seek(start)
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    check(read > 0)
                    out.write(buffer, 0, read); remaining -= read
                }
            }
        }
    }
}
