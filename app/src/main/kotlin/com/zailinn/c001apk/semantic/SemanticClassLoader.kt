package com.zailinn.c001apk.semantic

import android.content.Context
import com.zailinn.c001apk.core.semantic.EmbeddingRuntime
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Loads ONNX Runtime from the semantic-runtime.jar asset, a separate dex built
 * from ONNX Runtime's Java API and the OrtRuntime adapter. Its class loader has
 * the downloaded native library directory on its search path, which is where
 * ONNX Runtime's `System.loadLibrary` looks. The app never links against these
 * classes; [ReflectiveRuntime] calls the adapter by name.
 */
internal object SemanticRuntimeLoader {
    private const val ASSET = "semantic-runtime.jar"
    private const val RUNTIME_CLASS = "com.zailinn.c001apk.semantic.ort.OrtRuntime"

    /** A native library can belong to one class loader per process, so keep the first. */
    private var loader: ClassLoader? = null

    @Synchronized
    private fun loader(context: Context, libDir: File): ClassLoader = loader ?: run {
        // The code cache is cleared on every app update, so the asset is copied again then.
        val dex = File(context.codeCacheDir, ASSET)
        if (!dex.isFile) {
            val part = File(dex.path + ".part")
            context.assets.open(ASSET).use { input -> part.outputStream().use(input::copyTo) }
            // Android 14 refuses to load writable dex files.
            part.setReadOnly()
            check(part.renameTo(dex)) { "无法准备运行库" }
        }
        DexClassLoader(dex.path, null, libDir.path, SemanticRuntimeLoader::class.java.classLoader).also { loader = it }
    }

    fun create(context: Context, model: File, libDir: File, threads: Int): EmbeddingRuntime {
        val target = loader(context, libDir).loadClass(RUNTIME_CLASS)
            .getConstructor(String::class.java, Int::class.javaPrimitiveType)
            .let { constructor -> unwrap { constructor.newInstance(model.path, threads) } }
        return ReflectiveRuntime(target)
    }
}

/** [EmbeddingRuntime] over any object with `hiddenStates(long[][], long[][])` and `close()`. */
internal class ReflectiveRuntime(private val target: Any) : EmbeddingRuntime {
    private val hiddenStates: Method =
        target.javaClass.getMethod("hiddenStates", Array<LongArray>::class.java, Array<LongArray>::class.java)
    private val close: Method = target.javaClass.getMethod("close")

    @Suppress("UNCHECKED_CAST")
    override fun hiddenStates(inputIds: Array<LongArray>, attentionMask: Array<LongArray>): Array<Array<FloatArray>> =
        unwrap { hiddenStates.invoke(target, inputIds, attentionMask) } as Array<Array<FloatArray>>

    override fun close() {
        unwrap { close.invoke(target) }
    }
}

/** Rethrows what the reflected call threw instead of InvocationTargetException. */
private inline fun <T> unwrap(block: () -> T): T = try {
    block()
} catch (e: InvocationTargetException) {
    throw e.targetException
}
