package dev.patrickgold.florisboard.dictate.provider

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class CrispAsrGgufRecognizerTest {
    private class Engine : CrispAsrEngine {
        var freed = 0
        var decoded = 0
        var threads = 0
        var backend = -1
        var lastLanguage: String? = null
        var loadHandle = 7L
        override fun load(path: String, backend: Int, threads: Int): Long {
            this.threads = threads
            this.backend = backend
            return loadHandle
        }
        override fun decode(handle: Long, samples: FloatArray, language: String): ByteArray {
            assertEquals(7L, handle)
            decoded++
            lastLanguage = language
            return "你好 café 👋".toByteArray(Charsets.UTF_8)
        }
        override fun free(handle: Long) { assertEquals(7L, handle); freed++ }
    }

    @Test
    fun `decode preserves full Unicode and frees handle exactly once`() {
        val engine = Engine()
        val recognizer = CrispAsrGgufRecognizer(File("model.gguf"), CrispAsrBackend.QWEN3_ASR, 2, engine)
        assertEquals("你好 café 👋", recognizer.decode(floatArrayOf(0.1f)))
        recognizer.release()
        recognizer.release()
        assertEquals(1, engine.freed)
        assertFailsWith<IllegalStateException> { recognizer.decode(floatArrayOf(0.1f)) }
        assertEquals(1, engine.decoded)
    }

    @Test
    fun `empty audio returns without calling native decoder`() {
        val engine = Engine()
        val recognizer = CrispAsrGgufRecognizer(File("model.gguf"), CrispAsrBackend.QWEN3_ASR, 2, engine)
        assertEquals("", recognizer.decode(floatArrayOf()))
        assertEquals(0, engine.decoded)
        recognizer.release()
    }

    @Test
    fun `oversized and nonfinite audio never enters native decoder`() {
        val engine = Engine()
        val recognizer = CrispAsrGgufRecognizer(File("model.gguf"), CrispAsrBackend.QWEN3_ASR, 2, engine)
        assertFailsWith<IllegalArgumentException> { recognizer.decode(FloatArray(29 * 16000 + 1)) }
        assertFailsWith<IllegalArgumentException> { recognizer.decode(floatArrayOf(Float.NaN)) }
        assertFailsWith<IllegalArgumentException> { recognizer.decode(floatArrayOf(Float.POSITIVE_INFINITY)) }
        assertEquals(0, engine.decoded)
        recognizer.release()
    }

    @Test
    fun `failed load is rejected and thread count is bounded`() {
        val engine = Engine().apply { loadHandle = 0L }
        assertFailsWith<IllegalStateException> { CrispAsrGgufRecognizer(File("model.gguf"), CrispAsrBackend.GRANITE_SPEECH, 100, engine) }
        assertEquals(8, engine.threads)
        assertEquals(0, engine.freed)
    }

    @Test
    fun `the backend id reaches the runtime and the language is read on every decode`() {
        val engine = Engine()
        val recognizer = CrispAsrGgufRecognizer(File("model.gguf"), CrispAsrBackend.COHERE, 4, engine)
        assertEquals(CrispAsrBackend.COHERE.nativeId, engine.backend)
        recognizer.decode(floatArrayOf(0.1f))
        assertEquals("", engine.lastLanguage)
        recognizer.language = "de"
        recognizer.decode(floatArrayOf(0.1f))
        assertEquals("de", engine.lastLanguage)
        recognizer.release()
    }

    @Test
    fun `backend ids match the native enum and are unique`() {
        assertEquals(listOf(0, 1, 2, 3, 4), CrispAsrBackend.entries.map { it.nativeId })
    }

    @Test
    fun `dot-product build is chosen only when every core supports it`() {
        val modern = "processor\t: 0\nFeatures\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp\n" +
            "processor\t: 1\nFeatures\t: fp asimd asimdhp asimddp i8mm\n"
        assertTrue(CpuFeatures.hasDotProd(modern))
        val mixed = "Features\t: fp asimd asimdhp asimddp\nFeatures\t: fp asimd evtstrm crc32\n"
        assertFalse(CpuFeatures.hasDotProd(mixed))
        assertFalse(CpuFeatures.hasDotProd("Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n"))
        assertFalse(CpuFeatures.hasDotProd(""))
        assertFalse(CpuFeatures.hasDotProd(null))
    }

    @Test
    fun `batch callers cannot replace a model while another caller is using it`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val first = launch {
            LocalBatchExecution.run {
                events += "first start"
                entered.complete(Unit)
                release.await()
                events += "first end"
            }
        }
        entered.await()
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            LocalBatchExecution.run { events += "second" }
        }
        release.complete(Unit)
        first.join()
        second.join()
        assertEquals(listOf("first start", "first end", "second"), events)
    }

    @Test
    fun `cancelled queued batch does not acquire native model and leaves lock usable`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch { LocalBatchExecution.run { entered.complete(Unit); release.await() } }
        entered.await()
        var secondEntered = false
        val second = launch(start = CoroutineStart.UNDISPATCHED) { LocalBatchExecution.run { secondEntered = true } }
        second.cancelAndJoin()
        release.complete(Unit)
        first.join()
        assertTrue(!secondEntered)
        assertEquals("ready", LocalBatchExecution.run { "ready" })
    }
}
