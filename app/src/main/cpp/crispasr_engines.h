#pragma once
#include <cstddef>
#include <memory>
#include <string>

namespace dictate {

/**
 * One loaded CrispASR speech model. Every backend takes 16 kHz mono float PCM of at most
 * [kMaxSamples] and returns a trimmed UTF-8 transcript. [language] is an ISO-639-1 code or empty;
 * backends that detect the language themselves (Qwen3-ASR) or only know one (Moonshine) ignore it.
 */
class Engine {
public:
    static constexpr size_t kMaxSamples = 29 * 16000;
    virtual ~Engine() = default;
    virtual std::string decode(const float* samples, size_t count, const std::string& language) = 0;
};

/** Must match CrispAsrBackend.nativeId on the Kotlin side. */
enum class Backend : int {
    Qwen3Asr = 0,
    GraniteSpeech = 1,
    Cohere = 2,
    MoonshineStreaming = 3,
    /** Granite Speech 4.1: same runtime and template as 4.0, asked for punctuated, truecased output. */
    GraniteSpeech41 = 4,
};

/** Throws std::invalid_argument for bad input and std::runtime_error when loading fails. */
std::unique_ptr<Engine> load_engine(Backend backend, const std::string& path, int threads);

/** Validates count/finiteness shared by every backend. */
void check_audio(const float* samples, size_t count);

/** Trims surrounding whitespace. */
std::string trimmed(const std::string& text);

/** Granite steers by language name only for the languages it was trained on; otherwise empty. */
std::string granite_language_name(const std::string& language);

/** Cohere has no language detection: a supported code is used as-is, anything else falls back to "en". */
std::string cohere_language(const std::string& language, const std::string* supported, size_t count);

}
