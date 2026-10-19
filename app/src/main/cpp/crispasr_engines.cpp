#include "crispasr_engines.h"
#include "qwen_adapter.h"

#include "cohere.h"
#include "core/greedy_decode.h"
#include "core/ngram_loop_fix.h"
#include "granite_speech.h"
#include "moonshine_streaming.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <stdexcept>
#include <vector>

namespace dictate {
namespace {

constexpr const char* kGranitePunctuatedPrompt = "transcribe the speech with proper punctuation and capitalization.";

template<class T> using Owned = std::unique_ptr<T, decltype(&std::free)>;
template<class T> Owned<T> owned(T* value, const char* operation) {
    if (!value) throw std::runtime_error(operation);
    return Owned<T>(value, &std::free);
}

void check_gguf(const std::string& path) {
    std::ifstream file(path, std::ios::binary);
    char magic[4] = {};
    if (!file.read(magic, 4) || std::memcmp(magic, "GGUF", 4))
        throw std::invalid_argument("Model is not a readable GGUF file");
}

void check_threads(int threads) {
    if (threads < 1 || threads > 8) throw std::invalid_argument("Thread count must be 1..8");
}

std::vector<int32_t> tokenize(granite_speech_context* ctx, const std::string& text) {
    int count = 0;
    int32_t* ids = granite_speech_tokenize(ctx, text.c_str(), &count);
    std::vector<int32_t> out;
    if (ids && count > 0) out.assign(ids, ids + count);
    std::free(ids);
    return out;
}

class QwenEngine final : public Engine {
public:
    QwenEngine(const std::string& path, int threads) : qwen_(path, threads) {}
    std::string decode(const float* samples, size_t count, const std::string&) override {
        return qwen_.decode(samples, count);
    }
private:
    Qwen qwen_;
};

/**
 * IBM Granite Speech 4.0-1B and 4.1-2B. The library's granite_speech_transcribe is a stub, so this
 * drives the building blocks the same way CrispASR's own session API does: mel → conformer encoder →
 * Q-Former projector → splice the audio tokens into the chat prompt → KV prefill → greedy decode.
 */
class GraniteEngine final : public Engine {
public:
    GraniteEngine(const std::string& path, int threads, bool chat_template) : chat_template_(chat_template) {
        auto params = granite_speech_context_default_params();
        params.n_threads = threads;
        params.verbosity = 0;
        params.use_gpu = false;
        ctx_ = granite_speech_init_from_file(path.c_str(), params);
        if (!ctx_) throw std::runtime_error("Granite Speech GGUF loading failed");
    }
    ~GraniteEngine() override { granite_speech_free(ctx_); }

    std::string decode(const float* samples, size_t count, const std::string& language) override {
        int n_mels = 0, frames = 0;
        auto mel = owned(granite_speech_compute_mel(ctx_, samples, int(count), &n_mels, &frames), "Mel computation failed");
        int encoded = 0, encoded_dim = 0;
        auto encoder = owned(granite_speech_run_encoder(ctx_, mel.get(), n_mels, frames, &encoded, &encoded_dim), "Audio encoder failed");
        mel.reset();
        int audio_count = 0, dim = 0;
        auto audio = owned(granite_speech_run_projector(ctx_, encoder.get(), encoded, encoded_dim, &audio_count, &dim), "Audio projector failed");
        encoder.reset();
        if (audio_count <= 0 || dim <= 0) throw std::runtime_error("Invalid projector dimensions");

        int audio_token = granite_speech_audio_token_id(ctx_);
        int eos = granite_speech_eos_token_id(ctx_);
        // Legacy granite-4.0 GGUFs predate the metadata keys (same fallbacks as CrispASR).
        if (audio_token < 0) audio_token = 100352;
        if (eos < 0) eos = 100257;

        const std::string name = granite_language_name(language);
        std::vector<int32_t> prefix, suffix;
        if (chat_template_ || audio_token < 50000) {
            // IBM's control-token chat template, byte for byte what transformers' apply_chat_template
            // emits for granite-speech 4.1 (default system turn included). Its model card gives this
            // instruction for punctuated, truecased output in every supported language — the prompt
            // itself must stay English — whereas the plain "written format" request returns a raw,
            // unpunctuated transcript, the wrong default for a keyboard.
            prefix = tokenize(ctx_, "<|start_of_role|>system<|end_of_role|>You are a helpful assistant. Please "
                                    "ensure responses are professional, accurate, and safe.<|end_of_text|>\n"
                                    "<|start_of_role|>user<|end_of_role|>");
            suffix = tokenize(ctx_, std::string(kGranitePunctuatedPrompt) +
                "<|end_of_text|>\n<|start_of_role|>assistant<|end_of_role|>");
        } else {
            // granite-4.0-1b was trained on "USER: …\n ASSISTANT:" with this pre-tokenized default
            // request; a known language steers it the way CrispASR's own session API does.
            prefix = {6584, 25, 220};
            if (name.empty()) {
                suffix = {4919, 499, 1380, 3191, 279, 8982, 1139, 264, 5439, 3645, 30, 198, 36660, 3931, 2891, 25};
            } else {
                suffix = tokenize(ctx_, "can you transcribe the speech into " + name + "?\nASSISTANT:");
            }
        }
        if (prefix.empty() || suffix.empty()) throw std::runtime_error("Prompt tokenization failed");

        std::vector<int32_t> prompt(prefix);
        prompt.insert(prompt.end(), size_t(audio_count), audio_token);
        prompt.insert(prompt.end(), suffix.begin(), suffix.end());
        const int prompt_count = int(prompt.size());
        auto embeds = owned(granite_speech_embed_tokens(ctx_, prompt.data(), prompt_count), "Prompt embedding failed");
        for (int i = 0; i < audio_count; ++i)
            std::memcpy(embeds.get() + size_t(prefix.size() + i) * dim, audio.get() + size_t(i) * dim, size_t(dim) * sizeof(float));
        audio.reset();

        constexpr int limit = 512;
        if (!granite_speech_kv_init(ctx_, std::max(4096, prompt_count + limit + 16)))
            throw std::runtime_error("KV cache allocation failed");
        granite_speech_kv_reset(ctx_);
        struct Reset { granite_speech_context* c; ~Reset() { granite_speech_kv_reset(c); } } reset{ctx_};
        int vocabulary = 0;
        auto logits = owned(granite_speech_run_llm_kv(ctx_, embeds.get(), prompt_count, 0, nullptr, &vocabulary), "Prompt evaluation failed");
        embeds.reset();
        if (vocabulary <= 0) throw std::runtime_error("Invalid logits shape");
        for (int j = 0; j < vocabulary; ++j)
            if (!std::isfinite(logits.get()[j])) throw std::runtime_error("Nonfinite decoder logits");

        core_greedy_decode::Config config;
        config.max_new_tokens = limit;
        config.eos_id = eos;
        config.vocab_size = vocabulary;
        const int first = core_greedy_decode::argmax(logits.get(), vocabulary);
        logits.reset();
        auto tokens = core_greedy_decode::run(ctx_, first, prompt_count, granite_speech_embed_tokens,
                                              granite_speech_run_llm_kv, config);
        std::vector<int32_t> text_ids;
        for (int32_t id : tokens) {
            if (id == eos) break;
            text_ids.push_back(id);
        }
        if (text_ids.empty()) return {};
        auto text = owned(granite_speech_decode_tokens(ctx_, text_ids.data(), int(text_ids.size())), "Detokenization failed");
        return trimmed(core_ngram::fix_loops(text.get()));
    }

private:
    granite_speech_context* ctx_ = nullptr;
    bool chat_template_;
};

/** Cohere Transcribe: encoder-decoder that must be told its language (it cannot detect one). */
class CohereEngine final : public Engine {
public:
    CohereEngine(const std::string& path, int threads) {
        auto params = cohere_context_default_params();
        params.n_threads = threads;
        params.verbosity = 0;
        params.use_gpu = false;
        ctx_ = cohere_init_from_file(path.c_str(), params);
        if (!ctx_) throw std::runtime_error("Cohere Transcribe GGUF loading failed");
        const int n = cohere_n_supported_languages(ctx_);
        for (int i = 0; i < n; ++i)
            if (const char* code = cohere_supported_language(ctx_, i)) supported_.emplace_back(code);
    }
    ~CohereEngine() override { cohere_free(ctx_); }

    std::string decode(const float* samples, size_t count, const std::string& language) override {
        const std::string lang = cohere_language(language, supported_.data(), supported_.size());
        auto text = owned(cohere_transcribe(ctx_, samples, int(count), lang.c_str()), "Cohere transcription failed");
        return trimmed(core_ngram::fix_loops(text.get()));
    }

private:
    cohere_context* ctx_ = nullptr;
    std::vector<std::string> supported_;
};

/** Moonshine v2 streaming (English). tokenizer.bin is read from the model's directory. */
class MoonshineStreamingEngine final : public Engine {
public:
    MoonshineStreamingEngine(const std::string& path, int threads) {
        auto params = moonshine_streaming_context_default_params();
        params.n_threads = threads;
        params.verbosity = 0;
        params.use_gpu = false;
        params.temperature = 0.0f;
        ctx_ = moonshine_streaming_init_from_file(path.c_str(), params);
        if (!ctx_) throw std::runtime_error("Moonshine GGUF loading failed");
    }
    ~MoonshineStreamingEngine() override { moonshine_streaming_free(ctx_); }

    std::string decode(const float* samples, size_t count, const std::string&) override {
        auto text = owned(moonshine_streaming_transcribe(ctx_, samples, int(count)), "Moonshine transcription failed");
        return trimmed(core_ngram::fix_loops(text.get()));
    }

private:
    moonshine_streaming_context* ctx_ = nullptr;
};

/** Validates audio before handing it to a backend that would otherwise trust it. */
class CheckedEngine final : public Engine {
public:
    explicit CheckedEngine(std::unique_ptr<Engine> inner) : inner_(std::move(inner)) {}
    std::string decode(const float* samples, size_t count, const std::string& language) override {
        check_audio(samples, count);
        return inner_->decode(samples, count, language);
    }
private:
    std::unique_ptr<Engine> inner_;
};

}

void check_audio(const float* samples, size_t count) {
    if (!samples || count == 0 || count > Engine::kMaxSamples)
        throw std::invalid_argument("Audio must contain 1..464000 mono 16 kHz samples");
    for (size_t i = 0; i < count; ++i)
        if (!std::isfinite(samples[i])) throw std::invalid_argument("Audio contains nonfinite samples");
}

std::string trimmed(const std::string& text) {
    const auto first = text.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) return {};
    return text.substr(first, text.find_last_not_of(" \t\r\n") - first + 1);
}

std::string granite_language_name(const std::string& language) {
    if (language == "en") return "English";
    if (language == "fr") return "French";
    if (language == "de") return "German";
    if (language == "es") return "Spanish";
    if (language == "pt") return "Portuguese";
    if (language == "ja") return "Japanese";
    return {};
}

std::string cohere_language(const std::string& language, const std::string* supported, size_t count) {
    if (language.empty()) return "en";
    // An older GGUF without the language list: trust the caller rather than forcing English.
    if (count == 0) return language;
    for (size_t i = 0; i < count; ++i)
        if (supported[i] == language) return language;
    return "en";
}

std::unique_ptr<Engine> load_engine(Backend backend, const std::string& path, int threads) {
    check_threads(threads);
    check_gguf(path);
    std::unique_ptr<Engine> engine;
    switch (backend) {
        case Backend::Qwen3Asr: engine = std::make_unique<QwenEngine>(path, threads); break;
        case Backend::GraniteSpeech: engine = std::make_unique<GraniteEngine>(path, threads, false); break;
        case Backend::GraniteSpeech41: engine = std::make_unique<GraniteEngine>(path, threads, true); break;
        case Backend::Cohere: engine = std::make_unique<CohereEngine>(path, threads); break;
        case Backend::MoonshineStreaming: engine = std::make_unique<MoonshineStreamingEngine>(path, threads); break;
        default: throw std::invalid_argument("Unknown speech backend");
    }
    return std::make_unique<CheckedEngine>(std::move(engine));
}

}
