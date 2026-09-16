#pragma once
#include <cstddef>
#include <memory>
#include <string>
struct qwen3_asr_context;
namespace dictate {
class Qwen {
public:
    Qwen(const std::string& path, int threads);
    ~Qwen();
    Qwen(const Qwen&) = delete;
    Qwen& operator=(const Qwen&) = delete;
    std::string decode(const float* samples, size_t count);
private:
    qwen3_asr_context* context_ = nullptr;
};
std::string clean_transcript(const std::string& decoded);
}
