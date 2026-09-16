package dev.patrickgold.florisboard.dictate.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QwenModelTest {
    @Test
    fun `qwen is offered only on an ABI with its native runtime`() {
        val id = "qwen3-asr-1.7b-q4-k"
        assertTrue(LocalModelCatalog.availableForAbis(listOf("arm64-v8a", "armeabi-v7a")).any { it.id == id })
        for (abis in listOf(listOf("x86_64"), listOf("armeabi-v7a"), emptyList())) {
            assertFalse(LocalModelCatalog.availableForAbis(abis).any { it.id == id })
            assertTrue(LocalModelCatalog.WHISPER_BASE in LocalModelCatalog.availableForAbis(abis))
        }
    }

    @Test
    fun `qwen is a batch model with embedded tokenizer and verified mixed quantization weights`() {
        val spec = assertNotNull(LocalModelCatalog.byId("qwen3-asr-1.7b-q4-k"))
        assertFalse(spec.isStreaming)
        assertTrue(spec in LocalModelCatalog.batchOnly)
        assertEquals(setOf("model.gguf", "vad.onnx"), spec.files.map { it.destName }.toSet())
        val weights = spec.files.single { it.destName == "model.gguf" }
        assertEquals(1_490_915_200L, weights.sizeBytes)
        assertEquals("ec197cef7ccc589fdcae1becc3f4a3de119d0a41e790b898b519b1a048dad8d4", weights.sha256)
        assertTrue(weights.url.contains("/674df5d44b50a63e7102a18895ed20e3f91de301/"))
        assertTrue(spec.description.contains("Q8"))
    }
}
