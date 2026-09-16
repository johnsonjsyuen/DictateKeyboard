#include "qwen_adapter.h"
#include <chrono>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <vector>
int main(int argc, char** argv) {
    try {
        if (argc == 2 && std::string(argv[1]) == "--self-test") {
            bool failed = false;
            try { dictate::Qwen bad("/does/not/exist.gguf", 2); } catch (const std::invalid_argument&) { failed = true; }
            if (!failed) throw std::runtime_error("Invalid path accepted");
            if (dictate::clean_transcript("language Chinese<asr_text> 你好世界。") != "你好世界。") throw std::runtime_error("Unicode cleanup failed");
            if (dictate::clean_transcript(" <literal><|literal|> ") != "<literal><|literal|>") throw std::runtime_error("Literal markup was removed");
            if (dictate::clean_transcript("language none") != "") throw std::runtime_error("Silence cleanup failed");
            std::cout << "Native self-tests passed\n"; return 0;
        }
        if (argc < 3 || argc > 4) throw std::invalid_argument("usage: qwen-smoke MODEL.gguf AUDIO.f32 [threads]");
        std::ifstream file(argv[2], std::ios::binary | std::ios::ate);
        const auto bytes = file.tellg();
        if (bytes <= 0 || bytes % 4 != 0 || bytes > 29 * 16000 * 4) throw std::invalid_argument("Invalid PCM file length");
        std::vector<float> pcm(static_cast<size_t>(bytes) / 4);
        file.seekg(0); file.read(reinterpret_cast<char*>(pcm.data()), bytes);
        if (!file) throw std::runtime_error("PCM read failed");
        dictate::Qwen model(argv[1], argc == 4 ? std::stoi(argv[3]) : 2);
        std::string previous;
        for (int attempt = 0; attempt < 2; ++attempt) {
            const auto start = std::chrono::steady_clock::now();
            auto result = model.decode(pcm.data(), pcm.size());
            if (result.empty()) throw std::runtime_error("Speech fixture returned empty text");
            if (attempt && result != previous) throw std::runtime_error("Repeated decode differs");
            previous = result;
            std::cout << "decode " << attempt + 1 << " (" << std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count() << "s): " << result << std::endl;
        }
        return 0;
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
