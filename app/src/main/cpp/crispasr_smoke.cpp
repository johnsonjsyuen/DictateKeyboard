// Host checks for the multi-backend engine layer, plus an optional real-model run:
//   crispasr-smoke --self-test
//   crispasr-smoke BACKEND MODEL.gguf AUDIO.f32 [language] [threads]
// BACKEND is qwen3 | granite | granite-4.1 | cohere | moonshine-streaming; AUDIO is little-endian float32 mono 16 kHz.
#include "crispasr_engines.h"
#include <chrono>
#include <fstream>
#include <iostream>
#include <limits>
#include <map>
#include <stdexcept>
#include <unistd.h>
#include <vector>
namespace {
void require(bool value, const char* message) { if (!value) throw std::runtime_error(message); }
template<class F> void rejects(F fn, const char* message) {
    bool caught = false;
    try { fn(); } catch (const std::invalid_argument&) { caught = true; }
    require(caught, message);
}
}
int main(int argc, char** argv) {
    using dictate::Backend;
    try {
        if (argc == 2 && std::string(argv[1]) == "--self-test") {
            for (int b = 0; b <= 4; ++b)
                rejects([&] { dictate::load_engine(Backend(b), "/does/not/exist.gguf", 2); }, "Invalid path accepted");
            const std::string fake = "/tmp/dictate-engine-test-" + std::to_string(getpid()) + ".bin";
            { std::ofstream file(fake); file << "NOPE"; }
            rejects([&] { dictate::load_engine(Backend::Cohere, fake, 2); }, "Non-GGUF file accepted");
            rejects([&] { dictate::load_engine(Backend::GraniteSpeech, fake, 0); }, "Zero threads accepted");
            rejects([&] { dictate::load_engine(Backend(42), fake, 2); }, "Unknown backend accepted");
            std::remove(fake.c_str());
            float ok[2] = {0.1f, 0.2f};
            dictate::check_audio(ok, 2);
            rejects([&] { dictate::check_audio(ok, 0); }, "Empty audio accepted");
            rejects([&] { dictate::check_audio(ok, dictate::Engine::kMaxSamples + 1); }, "Overlong audio accepted");
            ok[1] = std::numeric_limits<float>::infinity();
            rejects([&] { dictate::check_audio(ok, 2); }, "Nonfinite audio accepted");
            require(dictate::trimmed("  hi there \n") == "hi there", "Trim failed");
            require(dictate::trimmed(" \t ") == "", "Blank trim failed");
            require(dictate::granite_language_name("de") == "German", "Granite language name");
            require(dictate::granite_language_name("zh").empty(), "Granite must not steer unsupported languages");
            const std::string langs[] = {"en", "de", "ja"};
            require(dictate::cohere_language("de", langs, 3) == "de", "Cohere supported language");
            require(dictate::cohere_language("zh", langs, 3) == "en", "Cohere unsupported language fallback");
            require(dictate::cohere_language("", langs, 3) == "en", "Cohere empty language fallback");
            require(dictate::cohere_language("it", nullptr, 0) == "it", "Cohere without metadata must trust caller");
            std::cout << "Engine self-tests passed\n";
            return 0;
        }
        if (argc < 4 || argc > 6) throw std::invalid_argument("usage: crispasr-smoke BACKEND MODEL.gguf AUDIO.f32 [language] [threads]");
        const std::map<std::string, Backend> names = {
            {"qwen3", Backend::Qwen3Asr}, {"granite", Backend::GraniteSpeech}, {"granite-4.1", Backend::GraniteSpeech41},
            {"cohere", Backend::Cohere}, {"moonshine-streaming", Backend::MoonshineStreaming}};
        auto backend = names.find(argv[1]);
        if (backend == names.end()) throw std::invalid_argument("Unknown backend name");
        std::ifstream file(argv[3], std::ios::binary | std::ios::ate);
        const auto bytes = file.tellg();
        if (bytes <= 0 || bytes % 4 != 0 || bytes > std::streamoff(dictate::Engine::kMaxSamples * 4)) throw std::invalid_argument("Invalid PCM file length");
        std::vector<float> pcm(static_cast<size_t>(bytes) / 4);
        file.seekg(0); file.read(reinterpret_cast<char*>(pcm.data()), bytes);
        auto engine = dictate::load_engine(backend->second, argv[2], argc == 6 ? std::stoi(argv[5]) : 2);
        const std::string language = argc >= 5 ? argv[4] : "";
        for (int attempt = 0; attempt < 2; ++attempt) {
            const auto start = std::chrono::steady_clock::now();
            auto text = engine->decode(pcm.data(), pcm.size(), language);
            std::cout << "decode " << attempt + 1 << " ("
                      << std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count()
                      << "s): " << text << std::endl;
        }
        return 0;
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
