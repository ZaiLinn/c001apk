package com.zailinn.c001apk.semantic

/** A file downloaded as is, tried from each mirror in turn. */
data class RemoteFile(val name: String, val size: Long, val sha256: String, val urls: List<String>)

/** A file taken out of a zip on a server that supports range requests. */
data class ZipPart(val entry: String, val name: String, val size: Long, val compressedSize: Long, val sha256: String)

/**
 * Everything semantic folding downloads. Every file is pinned by size and
 * SHA-256, so a mirror can only serve the exact bytes or fail.
 */
data class SemanticSpec(
    /** Install directory name; changing any file needs a new version. */
    val version: String,
    val model: RemoteFile,
    val vocab: RemoteFile,
    /** The ONNX Runtime AAR; its native libraries are read out of it per ABI. */
    val runtimeUrls: List<String>,
    val runtimes: Map<String, List<ZipPart>>,
) {
    fun abiFor(supported: List<String>): String? = supported.firstOrNull { it in runtimes }

    fun downloadBytes(abi: String) = model.size + vocab.size + runtimes.getValue(abi).sumOf { it.compressedSize }
    fun installedBytes(abi: String) = model.size + vocab.size + runtimes.getValue(abi).sumOf { it.size }

    companion object {
        private const val MODEL_REVISION = "75c43b069aac4d136ba6bc1122f995fedcfd2781"
        private const val ORT = "com/microsoft/onnxruntime/onnxruntime-android/1.26.0/onnxruntime-android-1.26.0.aar"

        private fun huggingFace(path: String) = listOf(
            "https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/$MODEL_REVISION/$path",
            "https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/$MODEL_REVISION/$path",
        )

        private fun ort(abi: String, size: Long, compressed: Long, sha256: String, jniSize: Long, jniCompressed: Long, jniSha256: String) = listOf(
            ZipPart("jni/$abi/libonnxruntime.so", "libonnxruntime.so", size, compressed, sha256),
            ZipPart("jni/$abi/libonnxruntime4j_jni.so", "libonnxruntime4j_jni.so", jniSize, jniCompressed, jniSha256),
        )

        /** bge-small-zh-v1.5 (int8, MIT) and ONNX Runtime 1.26.0 (MIT). */
        val Default = SemanticSpec(
            version = "bge-small-zh-q8-ort1.26.0",
            model = RemoteFile(
                "model.onnx", 24_010_842, "15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc",
                huggingFace("onnx/model_quantized.onnx"),
            ),
            vocab = RemoteFile(
                "vocab.txt", 109_540, "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c",
                huggingFace("vocab.txt"),
            ),
            runtimeUrls = listOf(
                "https://maven.aliyun.com/repository/central/$ORT",
                "https://repo1.maven.org/maven2/$ORT",
                "https://maven-central.storage-download.googleapis.com/maven2/$ORT",
            ),
            runtimes = mapOf(
                "arm64-v8a" to ort(
                    "arm64-v8a", 27_408_600, 10_040_501, "9f8e49b209cbac4483c96e5fc82f0405747f39c0708fb673561cbb019db0c0bc",
                    111_648, 31_798, "0984ef3397a10837286e344256fd4982eefb71f7d36ebdd92d85395e83ac0355",
                ),
                "armeabi-v7a" to ort(
                    "armeabi-v7a", 19_502_760, 9_201_969, "43e71a854973e1427d87eea80c8c4ed59d63930001783f5b0f9fe7de119e4211",
                    81_536, 28_603, "2f4b0af2a4cc3e582ae258ed29d027c0ef751a44f97833677306b003e711d98e",
                ),
                "x86_64" to ort(
                    "x86_64", 33_204_920, 11_914_951, "7950f0bb0ba765a0e3b11c11d6449c1b993e0301156e6c8fd1b2a10c45e7baa9",
                    100_016, 29_938, "272250caada01c1ec2ff5b0a5f082f84acb1c2dabfe22a757e0a685bb47c7569",
                ),
                "x86" to ort(
                    "x86", 33_075_264, 12_019_318, "f534e650334342c3834f7cc525c341637cefbf9d7812eadd9c80959de64e2d34",
                    93_620, 28_079, "346111c6d88ef0b2178096cb8db054bfa17506f4be1f1b8219af6251f716403e",
                ),
            ),
        )
    }
}
