# Offline Qwen3-ASR-1.7B Q4_K
Type: Implementation specification. Approved scope: user accepted Q4_K decoder with Q8 audio encoder, offline speech-to-text on OPPO Find N6, plus APK.

## Architecture
Keep Kotlin/Compose and existing provider/download UI. Add QWEN_GGUF batch kind and model ID qwen3-asr-1.7b-q4-k. Download model.gguf plus existing VAD separately from APK. Pin cstr/qwen3-asr-1.7b-GGUF revision 674df5d44b50a63e7102a18895ed20e3f91de301, qwen3-asr-1.7b-q4_k.gguf: 1490915200 bytes, SHA256 ec197cef7ccc589fdcae1becc3f4a3de119d0a41e790b898b519b1a048dad8d4. Inspect tensor types and run known-speech inference before accepting the artifact. This is mixed Q4_K/Q8_0, not FP4.

Use pinned CrispASR ba3499e7c7f6013a73738cad530b252d59675f49 and its pinned ggml submodule. Build a minimal Qwen CPU shared library via Android NDK for arm64-v8a, with static ggml dependencies, C++ runtime linkage isolated, hidden symbols and 16KB ELF alignment. JNI methods load(path, threads), decode(handle, float PCM), free(handle). Convert Java/native Unicode safely. Runtime's qwen3_asr_transcribe is a stub: compose implemented mel/encoder/tokenizer/embedding/KV/greedy decode APIs, deriving prompt and metadata removal from upstream Qwen C-ABI path. Limit generated tokens to 512, reject truncation rather than silently returning incomplete text, reset KV state for each utterance, use automatic language detection. No runtime network path.

Introduce LocalBatchRecognizer decode/release interface. Wrap sherpa recognizers and Qwen native handles. Reuse existing cache, VAD segmenting and idle/memory-pressure unload. Serialize the whole batch acquisition/decode/release with a coroutine Mutex, include threads in cache key, propagate CancellationException, check cancellation/deadline between native calls. Cap fallback chunks too. Native memory stays owned until blocking decode returns. Qwen picker availability is arm64-only for this build; old models remain available on other ABIs.

## Anti-patterns
| Avoid | Required |
|---|---|
| Plain Qwen language model | Qwen3-ASR speech architecture |
| Calling the upstream stub | Compose implemented decoding APIs |
| Network transcription fallback | Local PCM inference only |
| Unverified model weights | Immutable URL, exact size and SHA256 |
| Freeing active native handles | Serialized use and deferred cache unload |
| Modified UTF8 for arbitrary transcripts | Explicit UTF8/Java UTF16 conversion |
| Claiming phone benchmarks from compilation | Separate native host, Android build, and device evidence |

## Tests
| Unit | Expected |
|---|---|
| Model lookup | Qwen present, batch only, model.gguf + VAD |
| File shape | GGUF does not require tokens.txt/ONNX pair |
| Download integrity | Size/hash pinned, HTTPS, unique files |
| Existing model routing | Existing four model families retained |
| Native bridge lifecycle | Invalid paths fail, release safe, no leaked handle |
| Decode policy | Bounded token budget, metadata removed, Unicode preserved |

| Integration | Expected |
|---|---|
| Q4 export inspection + host inference | Audio encoder Q8, decoder Q4; JFK sample meaningful transcript twice |
| Catalog/provider regression tests | Unit suite passes |
| Android native cross-build | arm64 library loads dependencies only packaged/system; 16KB aligned |
| APK | assembleDebug passes, signature verifies, expected package and native entries |
| Phone | Offline dictation after download; report unavailable unless an authorized connected device exists |

## Errors
| Failure | Response |
|---|---|
| Missing/corrupt download | Existing staged downloader fails installation |
| Model load/encode/decode failure | Java exception -> existing transcription error, no cloud fallback |
| Token limit reached | Explicit error rather than partial transcript |
| Cancel or timeout | Stop between blocking passes, preserve cancellation semantics |
| Unsupported ABI | Do not offer Qwen; old providers remain |

## Delivery
Signed debug APK, source branch/worktree, reproducible native build script and attribution. Existing debug key and net.devemperor.dictate.debug identity. Model download separate. Build with bounded workers/heap to avoid host memory pressure. Original checkout untouched.

## References
- [Catalog](../../../app/src/main/kotlin/dev/patrickgold/florisboard/dictate/provider/LocalModelCatalog.kt)
- [Provider](../../../app/src/main/kotlin/dev/patrickgold/florisboard/dictate/provider/LocalTranscriptionProvider.kt)
- [Model](https://huggingface.co/cstr/qwen3-asr-1.7b-GGUF)
- [Runtime](https://github.com/CrispStrobe/CrispASR/tree/ba3499e7c7f6013a73738cad530b252d59675f49)

## Clarity review
All 13 stream-coding clarity checks reviewed. Architecture, metadata, limits, errors, verification and deliverable decided; no speculative device performance claims. Score 9/10: native API validation is an explicit execution prerequisite.
