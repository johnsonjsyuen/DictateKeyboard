package dev.patrickgold.florisboard.dictate.provider

import java.io.File

/** Native boundary kept injectable so handle ownership and UTF-8 can be tested without Android. */
internal interface QwenEngine {
    fun load(path: String, threads: Int): Long
    fun decode(handle: Long, samples: FloatArray): ByteArray
    fun free(handle: Long)
}

internal object QwenNative {
    init { System.loadLibrary("dictate-qwen") }
    @JvmStatic external fun load(path: String, threads: Int): Long
    @JvmStatic external fun decode(handle: Long, samples: FloatArray): ByteArray
    @JvmStatic external fun free(handle: Long)
}

private object JniQwenEngine : QwenEngine {
    override fun load(path: String, threads: Int) = QwenNative.load(path, threads)
    override fun decode(handle: Long, samples: FloatArray) = QwenNative.decode(handle, samples)
    override fun free(handle: Long) = QwenNative.free(handle)
}

/** Q4_K decoder + Q8 audio encoder. Language is detected by the speech model. */
internal class QwenGgufRecognizer(
    modelFile: File,
    threads: Int,
    private val engine: QwenEngine = JniQwenEngine,
) : LocalBatchRecognizer {
    private var handle = engine.load(modelFile.absolutePath, threads.coerceIn(1, 8)).also {
        check(it != 0L) { "Could not load Qwen3-ASR model" }
    }

    @Synchronized
    override fun decode(samples: FloatArray): String {
        check(handle != 0L) { "Qwen3-ASR model has been released" }
        require(samples.size <= 29 * 16_000) { "Qwen3-ASR audio must be segmented into at most 29 seconds" }
        if (samples.isEmpty()) return ""
        require(samples.all { it.isFinite() }) { "Audio contains non-finite samples" }
        // JNI returns raw UTF-8 bytes; NewStringUTF would corrupt supplementary Unicode characters.
        return engine.decode(handle, samples).toString(Charsets.UTF_8)
    }

    @Synchronized
    override fun release() {
        if (handle != 0L) {
            val owned = handle
            handle = 0L
            engine.free(owned)
        }
    }
}
