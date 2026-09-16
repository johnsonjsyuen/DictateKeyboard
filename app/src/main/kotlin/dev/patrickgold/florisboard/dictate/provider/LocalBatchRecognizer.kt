package dev.patrickgold.florisboard.dictate.provider

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import dev.patrickgold.florisboard.dictate.audio.AudioDecode

/** A borrowed, process-local batch decoder. Its owner serializes use and release. */
internal interface LocalBatchRecognizer {
    fun decode(samples: FloatArray): String
    fun release()
}

internal class SherpaBatchRecognizer(private val recognizer: OfflineRecognizer) : LocalBatchRecognizer {
    override fun decode(samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, AudioDecode.TARGET_SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    override fun release() = recognizer.release()
}

/** Hold across acquire, all decode passes, and endUse so another caller cannot replace a live model. */
internal object LocalBatchExecution {
    private val mutex = Mutex()
    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}
