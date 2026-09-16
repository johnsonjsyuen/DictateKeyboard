#include "qwen_adapter.h"
#include "qwen3_asr.h"
#include "core/bpe.h"
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <stdexcept>
#include <vector>

namespace dictate {
namespace {
template<class T> using Buffer = std::unique_ptr<T, decltype(&std::free)>;
template<class T> Buffer<T> checked(T* value, const char* operation) {
    if (!value) throw std::runtime_error(operation);
    return Buffer<T>(value, &std::free);
}
int special(qwen3_asr_context* ctx, const char* token) {
    int count = 0;
    auto ids = checked(qwen3_asr_tokenize(ctx, token, &count), "Tokenization failed");
    if (count != 1) throw std::runtime_error("Model special token is invalid");
    return ids.get()[0];
}
}
Qwen::Qwen(const std::string& path, int threads) {
    if (threads < 1 || threads > 8) throw std::invalid_argument("Thread count must be 1..8");
    std::ifstream file(path, std::ios::binary);
    char magic[4] = {};
    if (!file.read(magic, 4) || std::memcmp(magic, "GGUF", 4))
        throw std::invalid_argument("Model is not a readable GGUF file");
    auto params = qwen3_asr_context_default_params();
    params.n_threads = threads;
    params.use_gpu = false;
    params.verbosity = 0;
    context_ = qwen3_asr_init_from_file(path.c_str(), params);
    if (!context_) throw std::runtime_error("Qwen GGUF loading failed");
}
Qwen::~Qwen() { if (context_) qwen3_asr_free(context_); }
std::string clean_transcript(const std::string& decoded) {
    auto marker = decoded.find("<asr_text>");
    std::string text = marker == std::string::npos ? decoded : decoded.substr(marker + 10);
    if (marker == std::string::npos && text.rfind("language ", 0) == 0) {
        if (text == "language none") return {};
        throw std::runtime_error("Missing ASR transcript marker");
    }
    const auto first = text.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) return {};
    return text.substr(first, text.find_last_not_of(" \t\r\n") - first + 1);
}
std::string Qwen::decode(const float* samples, size_t count) {
    if (count == 0 || count > 29 * 16000 || !samples)
        throw std::invalid_argument("Audio must contain 1..464000 mono 16 kHz samples");
    for (size_t i = 0; i < count; ++i)
        if (!std::isfinite(samples[i])) throw std::invalid_argument("Audio contains nonfinite samples");
    int n_mels = 0, frames = 0, audio_count = 0, dimension = 0;
    auto mel = checked(qwen3_asr_compute_mel(context_, samples, static_cast<int>(count), &n_mels, &frames), "Mel computation failed");
    auto audio = checked(qwen3_asr_run_encoder(context_, mel.get(), n_mels, frames, &audio_count, &dimension), "Audio encoder failed");
    mel.reset();
    if (audio_count <= 0 || dimension <= 0) throw std::runtime_error("Invalid encoder dimensions");
    std::string prompt = "<|im_start|>system\n<|im_end|>\n<|im_start|>user\n<|audio_start|>";
    for (int i = 0; i < audio_count; ++i) prompt += "<|audio_pad|>";
    prompt += "<|audio_end|><|im_end|>\n<|im_start|>assistant\n";
    int prompt_count = 0;
    auto ids = checked(qwen3_asr_tokenize(context_, prompt.c_str(), &prompt_count), "Prompt tokenization failed");
    if (prompt_count <= 0) throw std::runtime_error("Empty prompt");
    int pad = special(context_, "<|audio_pad|>");
    int eos = special(context_, "<|im_end|>");
    auto embeds = checked(qwen3_asr_embed_tokens(context_, ids.get(), prompt_count), "Prompt embedding failed");
    int spliced = 0;
    for (int i = 0; i < prompt_count; ++i) if (ids.get()[i] == pad) {
        if (spliced >= audio_count) throw std::runtime_error("Too many audio placeholders");
        std::memcpy(embeds.get() + size_t(i) * dimension, audio.get() + size_t(spliced++) * dimension, size_t(dimension) * sizeof(float));
    }
    if (spliced != audio_count) throw std::runtime_error("Missing audio placeholders");
    audio.reset();
    constexpr int limit = 512;
    if (!qwen3_asr_kv_init(context_, std::max(4096, prompt_count + limit + 16)))
        throw std::runtime_error("KV cache allocation failed");
    qwen3_asr_kv_reset(context_);
    struct Reset { qwen3_asr_context* c; ~Reset() { qwen3_asr_kv_reset(c); } } reset{context_};
    int positions = 0, vocabulary = 0;
    auto logits = checked(qwen3_asr_run_llm_kv(context_, embeds.get(), prompt_count, 0, &positions, &vocabulary), "Prompt evaluation failed");
    embeds.reset();
    std::string decoded;
    for (int step = 0; step < limit; ++step) {
        if (positions != 1 || vocabulary <= 0 || eos >= vocabulary) throw std::runtime_error("Invalid logits shape");
        int32_t token = 0;
        for (int j = 0; j < vocabulary; ++j) {
            if (!std::isfinite(logits.get()[j])) throw std::runtime_error("Nonfinite decoder logits");
            if (logits.get()[j] > logits.get()[token]) token = j;
        }
        logits.reset();
        if (token == eos) return clean_transcript(decoded);
        const std::string piece = qwen3_asr_token_text(context_, token);
        // Filter complete structured vocabulary tokens before byte decoding.
        // Never scan the concatenated transcript for angle-bracket spans: users
        // can dictate literal markup, assembled from ordinary text tokens.
        // Keep <asr_text> so clean_transcript can remove the language prefix.
        const bool metadata = piece == "<non_speech>" || piece == "<think>" ||
            piece == "</think>" ||
            (piece.size() >= 4 && piece.rfind("<|", 0) == 0 &&
             piece.compare(piece.size() - 2, 2, "|>") == 0) ||
            (piece.size() >= 5 && piece.rfind("[PAD", 0) == 0 && piece.back() == ']');
        if (!metadata) decoded += core_bpe::token_bytes_to_utf8(piece);
        if (step + 1 == limit) break;
        auto next = checked(qwen3_asr_embed_tokens(context_, &token, 1), "Token embedding failed");
        logits = checked(qwen3_asr_run_llm_kv(context_, next.get(), 1, prompt_count + step, &positions, &vocabulary), "Token evaluation failed");
    }
    throw std::runtime_error("Transcription exceeded the 512 token limit");
}
}
