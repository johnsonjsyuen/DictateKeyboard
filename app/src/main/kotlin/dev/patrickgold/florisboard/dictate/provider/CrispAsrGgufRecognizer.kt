package dev.patrickgold.florisboard.dictate.provider

import java.io.File

/**
 * The speech models the native CrispASR/ggml runtime can load. [nativeId] must match
 * `dictate::Backend` in `app/src/main/cpp/crispasr_engines.h`.
 */
enum class CrispAsrBackend(val nativeId: Int, val label: String) {
    QWEN3_ASR(0, "Qwen3-ASR"),
    /** Granite Speech 4.0-1B's plain prompt. Supported by the runtime; no catalog entry uses it today. */
    GRANITE_SPEECH(1, "Granite Speech"),
    COHERE(2, "Cohere Transcribe"),
    MOONSHINE_STREAMING(3, "Moonshine"),
    /** Same runtime and prompt template as [GRANITE_SPEECH], asked for punctuated, truecased output. */
    GRANITE_SPEECH_4_1(4, "Granite Speech"),
}

/** Native boundary kept injectable so handle ownership and UTF-8 can be tested without Android. */
internal interface CrispAsrEngine {
    fun load(path: String, backend: Int, threads: Int): Long
    fun decode(handle: Long, samples: FloatArray, language: String): ByteArray
    fun free(handle: Long)
}

internal object CrispAsrNative {
    /**
     * Two builds of the same runtime ship in the APK (tools/build-qwen-native.sh): one for any arm64
     * CPU, and one compiled for ARMv8.2 dot-product + fp16, which runs ggml's quantised matmuls
     * several times faster but would crash with SIGILL on older cores. The kernel's own feature list
     * decides which is loaded, before either is touched.
     */
    val library: String = run {
        val baseline = "dictate-crispasr"
        val preferred = if (CpuFeatures.hasDotProd()) "dictate-crispasr-dotprod" else baseline
        try {
            System.loadLibrary(preferred)
            preferred
        } catch (e: UnsatisfiedLinkError) {
            if (preferred == baseline) throw e
            System.loadLibrary(baseline)
            baseline
        }
    }

    @JvmStatic external fun load(path: String, backend: Int, threads: Int): Long
    @JvmStatic external fun decode(handle: Long, samples: FloatArray, language: String): ByteArray
    @JvmStatic external fun free(handle: Long)
}

/** Kept apart from [CrispAsrNative] so it can be tested without loading the native library. */
internal object CpuFeatures {
    /** True when every core lists `asimddp` and `asimdhp` (big.LITTLE parts list features per core). */
    fun hasDotProd(cpuinfo: String? = readCpuInfo()): Boolean {
        val featureLines = cpuinfo?.lineSequence()
            ?.filter { it.trimStart().startsWith("Features") }
            ?.map { it.substringAfter(':').trim().split(Regex("\\s+")).toSet() }
            ?.toList()
            .orEmpty()
        return featureLines.isNotEmpty() && featureLines.all { "asimddp" in it && "asimdhp" in it }
    }

    private fun readCpuInfo(): String? = try {
        java.io.File("/proc/cpuinfo").readText()
    } catch (_: Throwable) {
        null
    }
}

private object JniCrispAsrEngine : CrispAsrEngine {
    override fun load(path: String, backend: Int, threads: Int) = CrispAsrNative.load(path, backend, threads)
    override fun decode(handle: Long, samples: FloatArray, language: String) =
        CrispAsrNative.decode(handle, samples, language)
    override fun free(handle: Long) = CrispAsrNative.free(handle)
}

/**
 * A GGUF speech model on the CrispASR runtime (Qwen3-ASR, Granite Speech, Cohere Transcribe,
 * Moonshine). [language] is the user's input language as a bare ISO-639-1 code, or empty for
 * auto-detect; it is read on every decode so switching languages never reloads a model of 0.5–1.5 GB.
 * Backends that detect the language themselves, or know only one, ignore it.
 */
internal class CrispAsrGgufRecognizer(
    modelFile: File,
    val backend: CrispAsrBackend,
    threads: Int,
    private val engine: CrispAsrEngine = JniCrispAsrEngine,
) : LocalBatchRecognizer {
    @Volatile
    var language: String = ""

    private var handle = engine.load(modelFile.absolutePath, backend.nativeId, threads.coerceIn(1, 8)).also {
        check(it != 0L) { "Could not load ${backend.label} model" }
    }

    @Synchronized
    override fun decode(samples: FloatArray): String {
        check(handle != 0L) { "${backend.label} model has been released" }
        require(samples.size <= MAX_SAMPLES) { "${backend.label} audio must be segmented into at most 29 seconds" }
        if (samples.isEmpty()) return ""
        require(samples.all { it.isFinite() }) { "Audio contains non-finite samples" }
        // JNI returns raw UTF-8 bytes; NewStringUTF would corrupt supplementary Unicode characters.
        return engine.decode(handle, samples, language).toString(Charsets.UTF_8)
    }

    @Synchronized
    override fun release() {
        if (handle != 0L) {
            val owned = handle
            handle = 0L
            engine.free(owned)
        }
    }

    companion object {
        /** Mirrors `dictate::Engine::kMaxSamples`: 29 s of 16 kHz audio. */
        const val MAX_SAMPLES = 29 * 16_000
    }
}
