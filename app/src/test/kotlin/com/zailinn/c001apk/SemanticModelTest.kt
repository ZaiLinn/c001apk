package com.zailinn.c001apk

import androidx.test.core.app.ApplicationProvider
import com.zailinn.c001apk.core.model.SensitiveCategory
import com.zailinn.c001apk.core.semantic.EmbeddingRuntime
import com.zailinn.c001apk.core.semantic.SemanticAnchors
import com.zailinn.c001apk.semantic.RemoteFile
import com.zailinn.c001apk.semantic.SemanticConfig
import com.zailinn.c001apk.semantic.SemanticModel
import com.zailinn.c001apk.semantic.SemanticSpec
import com.zailinn.c001apk.semantic.SemanticStatus
import com.zailinn.c001apk.semantic.ZipPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Download, verification, mirror fallback and loading of the optional model, against a local server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SemanticModelTest {
    private val server = MockWebServer()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val lib = ByteArray(200_000) { (it % 251).toByte() }
    private val jni = "jni bytes".toByteArray()
    private val model = ByteArray(50_000) { (it % 7).toByte() }
    private val chars = (SensitiveCategory.builtIn.flatMap { it.examples } + SemanticAnchors.everyday).joinToString("").toSet()
    private val vocab = (listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]") + chars.map(Char::toString)).joinToString("\n").toByteArray()
    private val aar = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("classes.jar")); zip.write(ByteArray(1000)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("jni/x86_64/libonnxruntime.so")); zip.write(lib); zip.closeEntry()
            zip.putNextEntry(ZipEntry("jni/x86_64/libonnxruntime4j_jni.so")); zip.write(jni); zip.closeEntry()
        }
    }.toByteArray()

    private val requests = mutableListOf<String>()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request.path!! }
                return when (request.path) {
                    "/good/ort.aar" -> ranged(aar, request.getHeader("Range"))
                    "/norange/ort.aar" -> MockResponse().setBody(Buffer().write(aar))
                    "/good/model.onnx" -> MockResponse().setBody(Buffer().write(model))
                    "/bad/model.onnx" -> MockResponse().setBody(Buffer().write(model.copyOf().also { it[0] = 9 }))
                    "/good/vocab.txt" -> MockResponse().setBody(Buffer().write(vocab))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        File(context.filesDir, "semantic").deleteRecursively()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun ranged(body: ByteArray, range: String?): MockResponse {
        val spec = range?.removePrefix("bytes=") ?: return MockResponse().setBody(Buffer().write(body))
        val (start, end) = if (spec.startsWith("-")) {
            (body.size - spec.drop(1).toInt()).coerceAtLeast(0) to body.size - 1
        } else {
            spec.substringBefore('-').toInt() to spec.substringAfter('-').toInt().coerceAtMost(body.size - 1)
        }
        return MockResponse().setResponseCode(206)
            .setHeader("Content-Range", "bytes $start-$end/${body.size}")
            .setBody(Buffer().write(body.copyOfRange(start, end + 1)))
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun url(path: String) = server.url(path).toString()

    private fun spec() = SemanticSpec(
        version = "test-v1",
        model = RemoteFile("model.onnx", model.size.toLong(), sha(model), listOf(url("/missing/model.onnx"), url("/bad/model.onnx"), url("/good/model.onnx"))),
        vocab = RemoteFile("vocab.txt", vocab.size.toLong(), sha(vocab), listOf(url("/good/vocab.txt"))),
        runtimeUrls = listOf(url("/norange/ort.aar"), url("/good/ort.aar")),
        runtimes = mapOf(
            "x86_64" to listOf(
                ZipPart("jni/x86_64/libonnxruntime.so", "libonnxruntime.so", lib.size.toLong(), 0, sha(lib)),
                ZipPart("jni/x86_64/libonnxruntime4j_jni.so", "libonnxruntime4j_jni.so", jni.size.toLong(), 0, sha(jni)),
            ),
        ),
    )

    /** Each token fills one slot, so equal texts get equal vectors. */
    private class FakeRuntime : EmbeddingRuntime {
        override fun hiddenStates(inputIds: Array<LongArray>, attentionMask: Array<LongArray>) =
            Array(inputIds.size) { r -> Array(inputIds[r].size) { c -> FloatArray(512).also { it[(inputIds[r][c] % 512).toInt()] = 1f } } }
        override fun close() = Unit
    }

    private fun manager(abis: List<String> = listOf("arm64-v8a", "x86_64")) =
        SemanticModel(context, OkHttpClient(), scope, spec(), abis) { modelFile, libDir ->
            assertEquals(model.size.toLong(), modelFile.length())
            assertTrue(File(libDir, "libonnxruntime4j_jni.so").isFile)
            FakeRuntime()
        }

    private val config = SemanticConfig(SensitiveCategory.builtIn, SemanticAnchors.DEFAULT_THRESHOLD)

    @Test
    fun downloadsOnlyTheAbiLibrariesAndFallsBackAcrossMirrors() = runBlocking {
        val semantic = manager()
        assertTrue(semantic.status.value is SemanticStatus.Absent)
        semantic.download()
        val done = withTimeout(20_000) { semantic.status.first { it is SemanticStatus.Installed || (it as? SemanticStatus.Absent)?.error != null } }
        assertTrue("$done", done is SemanticStatus.Installed)
        val root = File(context.filesDir, "semantic/test-v1")
        assertTrue(File(root, "lib/libonnxruntime.so").readBytes().contentEquals(lib))
        assertTrue(File(root, "model.onnx").readBytes().contentEquals(model))
        // The whole AAR is never fetched: every request to the good mirror is ranged.
        assertTrue(requests.contains("/bad/model.onnx"))
        assertFalse(File(root, "model.onnx.part").exists())
        // A fresh manager sees the finished install.
        assertTrue(manager().status.value is SemanticStatus.Installed)
    }

    @Test
    fun loadsInTheBackgroundAndScoresText() = runBlocking {
        val semantic = manager()
        semantic.download()
        withTimeout(20_000) { semantic.status.first { it is SemanticStatus.Installed } }
        assertNull(semantic.matcher(enabled = false, config))
        // First call starts loading and returns nothing; a later page gets the matcher.
        assertNull(semantic.matcher(enabled = true, config))
        val matcher = withTimeout(10_000) {
            var m = semantic.matcher(enabled = true, config)
            while (m == null) { delay(20); m = semantic.matcher(enabled = true, config) }
            m
        }
        assertNotNull(matcher)
        val score = semantic.test("怎么追喜欢的女生", config)!!
        assertTrue(score.folds)
        assertEquals("相亲恋爱", score.title)
        semantic.delete()
        assertTrue(semantic.status.value is SemanticStatus.Absent)
        assertFalse(File(context.filesDir, "semantic").exists())
        assertNull(semantic.matcher(enabled = true, config))
    }

    /** Shaped like the OrtRuntime adapter, which the app only reaches by reflection. */
    class AdapterLike {
        var closed = false
        fun hiddenStates(ids: Array<LongArray>, mask: Array<LongArray>): Array<Array<FloatArray>> {
            if (ids.isEmpty()) throw IllegalStateException("empty batch")
            return Array(ids.size) { Array(ids[it].size) { FloatArray(2) { mask[0][0].toFloat() } } }
        }
        fun close() { closed = true }
    }

    @Test
    fun reflectiveRuntimeCallsTheAdapterAndUnwrapsErrors() {
        val adapter = AdapterLike()
        val runtime = com.zailinn.c001apk.semantic.ReflectiveRuntime(adapter)
        assertEquals(1f, runtime.hiddenStates(arrayOf(longArrayOf(1, 2)), arrayOf(longArrayOf(1, 1)))[0][1][0])
        val error = runCatching { runtime.hiddenStates(emptyArray(), emptyArray()) }.exceptionOrNull()
        assertEquals("empty batch", (error as IllegalStateException).message)
        runtime.close()
        assertTrue(adapter.closed)
    }

    @Test
    fun unknownAbiIsUnsupported() {
        assertEquals(SemanticStatus.Unsupported, manager(listOf("mips")).status.value)
    }
}
