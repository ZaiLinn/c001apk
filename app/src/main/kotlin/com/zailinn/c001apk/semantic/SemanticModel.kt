package com.zailinn.c001apk.semantic

import android.content.Context
import android.os.Build
import com.zailinn.c001apk.core.model.SensitiveCategory
import com.zailinn.c001apk.core.semantic.BertTokenizer
import com.zailinn.c001apk.core.semantic.EmbeddingRuntime
import com.zailinn.c001apk.core.semantic.SemanticMatcher
import com.zailinn.c001apk.core.semantic.SemanticScore
import com.zailinn.c001apk.core.semantic.TextEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

sealed interface SemanticStatus {
    /** No compatible native runtime for this device's CPU. */
    data object Unsupported : SemanticStatus
    data class Absent(val downloadBytes: Long, val error: String? = null) : SemanticStatus
    data class Downloading(val done: Long, val total: Long) : SemanticStatus
    data class Installed(val bytes: Long) : SemanticStatus
}

/** What the model should fold: rebuilt when any of it changes. */
data class SemanticConfig(val categories: List<SensitiveCategory>, val phrases: List<String>, val threshold: Float)

/**
 * Downloads, verifies, loads and unloads the optional embedding model. Nothing
 * here runs until the user downloads the model in settings; the APK carries
 * only ONNX Runtime's Java API.
 */
class SemanticModel(
    private val context: Context,
    http: OkHttpClient,
    private val scope: CoroutineScope,
    private val spec: SemanticSpec = SemanticSpec.Default,
    abis: List<String> = Build.SUPPORTED_ABIS.toList(),
    /** Test seam: builds the runtime instead of loading ONNX Runtime. */
    private val runtimeFactory: ((model: File, libDir: File) -> EmbeddingRuntime)? = null,
) {
    private val http = http.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val abi = spec.abiFor(abis)
    private val base = File(context.filesDir, "semantic")
    private val root = File(base, spec.version)
    private val libDir = File(root, "lib")
    private val modelFile = File(root, spec.model.name)
    private val vocabFile = File(root, spec.vocab.name)
    private val marker = File(root, "complete")

    private val _status = MutableStateFlow(initialStatus())
    val status: StateFlow<SemanticStatus> = _status.asStateFlow()

    /** Why the installed model could not be loaded; cleared by [retry]. */
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private var downloadJob: Job? = null
    private val loadMutex = Mutex()
    private var loadJob: Job? = null
    private var encoder: TextEncoder? = null
    private var matcher: SemanticMatcher? = null
    private var matcherConfig: SemanticConfig? = null

    private fun initialStatus(): SemanticStatus = when {
        abi == null -> SemanticStatus.Unsupported
        marker.readTextOrNull() == spec.version -> SemanticStatus.Installed(spec.installedBytes(abi))
        else -> SemanticStatus.Absent(spec.downloadBytes(abi))
    }

    // region Download

    fun download() {
        val abi = abi ?: return
        if (downloadJob?.isActive == true || _status.value is SemanticStatus.Installed) return
        downloadJob = scope.launch(Dispatchers.IO) {
            val total = spec.downloadBytes(abi)
            val done = AtomicLong()
            fun progress(bytes: Long) {
                _status.value = SemanticStatus.Downloading(done.addAndGet(bytes), total)
            }
            _status.value = SemanticStatus.Downloading(0, total)
            try {
                base.listFiles()?.filter { it.name != spec.version }?.forEach { it.deleteRecursively() }
                libDir.mkdirs()
                marker.delete()
                fetchRuntime(spec.runtimes.getValue(abi), ::progress)
                fetchFile(spec.vocab, vocabFile, ::progress)
                fetchFile(spec.model, modelFile, ::progress)
                marker.writeText(spec.version)
                _loadError.value = null
                _status.value = SemanticStatus.Installed(spec.installedBytes(abi))
            } catch (e: CancellationException) {
                _status.value = SemanticStatus.Absent(total)
                throw e
            } catch (e: Exception) {
                _status.value = SemanticStatus.Absent(total, e.message ?: "下载失败")
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /** Unloads and removes every downloaded file. */
    suspend fun delete() {
        downloadJob?.cancel()
        downloadJob?.join()
        release()
        withContext(Dispatchers.IO) { base.deleteRecursively() }
        _loadError.value = null
        _status.value = initialStatus()
    }

    private suspend fun fetchFile(file: RemoteFile, target: File, progress: (Long) -> Unit) {
        if (target.isFile && target.length() == file.size && sha256(target) == file.sha256) return progress(file.size)
        val errors = mutableListOf<String>()
        for (url in file.urls) {
            var counted = 0L
            try {
                val part = File(target.path + ".part")
                val digest = MessageDigest.getInstance("SHA-256")
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    response.body!!.byteStream().use { input ->
                        part.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                digest.update(buffer, 0, n)
                                out.write(buffer, 0, n)
                                counted += n
                                progress(n.toLong())
                            }
                        }
                    }
                }
                if (counted != file.size || digest.digest().toHex() != file.sha256) {
                    part.delete()
                    throw IOException("文件校验失败")
                }
                if (!part.renameTo(target)) throw IOException("无法保存 ${file.name}")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress(-counted)
                errors += "${hostOf(url)}：${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IOException("${file.name} 下载失败（${errors.joinToString("；")}）")
    }

    private suspend fun fetchRuntime(parts: List<ZipPart>, progress: (Long) -> Unit) {
        val missing = parts.filter { part ->
            val target = File(libDir, part.name)
            val ok = target.isFile && target.length() == part.size && sha256(target) == part.sha256
            if (ok) progress(part.compressedSize)
            !ok
        }
        if (missing.isEmpty()) return
        val errors = mutableListOf<String>()
        for (url in spec.runtimeUrls) {
            var counted = 0L
            try {
                val zip = RemoteZip(http, url)
                val entries = zip.entries()
                for (part in missing) {
                    val entry = entries[part.entry] ?: throw IOException("缺少 ${part.entry}")
                    val target = File(libDir, part.name)
                    val temp = File(target.path + ".part")
                    val hash = zip.extract(entry, temp) { counted += it; progress(it) }
                    if (temp.length() != part.size || hash != part.sha256) {
                        temp.delete()
                        throw IOException("文件校验失败")
                    }
                    if (!temp.renameTo(target)) throw IOException("无法保存 ${part.name}")
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress(-counted)
                errors += "${hostOf(url)}：${e.message ?: e.javaClass.simpleName}"
                missing.forEach { File(libDir, it.name).delete() }
            }
        }
        throw IOException("运行库下载失败（${errors.joinToString("；")}）")
    }

    // endregion

    // region Loading

    /**
     * The matcher for [config], or null while it is still loading or when
     * semantic folding is off. Never blocks: the first call starts loading
     * in the background and later pages pick it up.
     */
    fun matcher(enabled: Boolean, config: SemanticConfig): SemanticMatcher? {
        if (!enabled) {
            if (encoder != null) scope.launch { release() }
            return null
        }
        if (_status.value !is SemanticStatus.Installed || _loadError.value != null) return null
        synchronized(this) {
            if (matcherConfig == config) return matcher
            if (loadJob?.isActive != true) loadJob = scope.launch(Dispatchers.Default) { runCatching { build(config) } }
            return null
        }
    }

    /** Scores one sentence for the settings page, loading the model if needed. */
    suspend fun test(text: String, config: SemanticConfig): SemanticScore? = withContext(Dispatchers.Default) {
        if (_status.value !is SemanticStatus.Installed) return@withContext null
        build(config)?.test(text)
    }

    fun retry() {
        _loadError.value = null
    }

    private suspend fun build(config: SemanticConfig): SemanticMatcher? = loadMutex.withLock {
        synchronized(this) { if (matcherConfig == config) return@withLock matcher }
        try {
            val encoder = encoder ?: TextEncoder(BertTokenizer.fromVocabText(vocabFile.readText()), createRuntime()).also { encoder = it }
            val built = SemanticMatcher(encoder, config.categories, config.phrases, config.threshold).takeUnless { it.isEmpty }
            synchronized(this) {
                matcher = built
                matcherConfig = config
            }
            built
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Includes UnsatisfiedLinkError from a device the runtime does not support.
            _loadError.value = "模型加载失败：${e.message ?: e.javaClass.simpleName}"
            null
        }
    }

    private fun createRuntime(): EmbeddingRuntime {
        runtimeFactory?.let { return it(modelFile, libDir) }
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
        return SemanticRuntimeLoader.create(context, modelFile, libDir, threads)
    }

    /** Closes the model session; the native libraries stay loaded for the process. */
    suspend fun release() = loadMutex.withLock {
        synchronized(this) {
            matcher = null
            matcherConfig = null
        }
        encoder?.close()
        encoder = null
    }

    // endregion

    private companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().toHex()
        }

        fun hostOf(url: String) = url.substringAfter("://").substringBefore('/')

        fun File.readTextOrNull() = if (isFile) runCatching { readText() }.getOrNull() else null
    }
}
