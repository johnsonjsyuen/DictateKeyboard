// Test the production adapter against controlled decoder outputs and shapes.
#include "qwen_adapter.h"
#include "qwen3_asr.h"
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <unistd.h>
struct qwen3_asr_context {};
namespace {
bool endless = false;
int step = 0, resets = 0;
const char* pieces[] = {"language Chinese", "<asr_text>", "<non_speech>", "<think>", "</think>", "[PAD123]", "<|audio_end|>", "ä½", "ł", "<", "literal", ">", "<", "|literal", "|>"};
constexpr int piece_count = sizeof(pieces) / sizeof(pieces[0]);
constexpr int eos = 31;
constexpr int dimension = 1792;
float* floats(size_t count) { return static_cast<float*>(std::calloc(count, sizeof(float))); }
void require(bool value, const char* message) { if (!value) throw std::runtime_error(message); }
template<class F> void rejects(F fn) { bool caught = false; try { fn(); } catch (const std::exception&) { caught = true; } require(caught, "Expected rejection"); }
}
extern "C" {
qwen3_asr_context_params qwen3_asr_context_default_params() { return {}; }
qwen3_asr_context* qwen3_asr_init_from_file(const char*, qwen3_asr_context_params p) { require(!p.use_gpu, "GPU must be disabled"); return new qwen3_asr_context; }
void qwen3_asr_free(qwen3_asr_context* c) { delete c; }
float* qwen3_asr_compute_mel(qwen3_asr_context*, const float*, int, int* m, int* t) { *m = 128; *t = 2; return floats(256); }
float* qwen3_asr_run_encoder(qwen3_asr_context*, const float*, int, int, int* n, int* d) { *n = 2; *d = dimension; auto p = floats(2 * dimension); p[0] = 12; p[dimension] = 34; return p; }
int32_t* qwen3_asr_tokenize(qwen3_asr_context*, const char* s, int* count) {
    *count = std::strlen(s) < 20 ? 1 : 4;
    auto p = static_cast<int32_t*>(std::calloc(*count, sizeof(int32_t)));
    if (*count == 4) { p[1] = 8; p[2] = 8; }
    else p[0] = std::string(s) == "<|audio_pad|>" ? 8 : eos;
    return p;
}
float* qwen3_asr_embed_tokens(qwen3_asr_context*, const int32_t*, int n) { return floats(n * dimension); }
bool qwen3_asr_kv_init(qwen3_asr_context*, int n) { require(n >= 4096, "KV context too small"); return true; }
void qwen3_asr_kv_reset(qwen3_asr_context*) { step = 0; ++resets; }
float* qwen3_asr_run_llm_kv(qwen3_asr_context*, const float* e, int n, int past, int* positions, int* vocab) {
    if (!past) require(n == 4 && e[dimension] == 12 && e[dimension * 2] == 34, "Audio splicing used wrong dimension");
    else require(n == 1 && past == 3 + step, "Wrong incremental KV position");
    *positions = 1; *vocab = 32;
    auto p = floats(32); p[endless ? 0 : step < piece_count ? step : eos] = 1; ++step; return p;
}
const char* qwen3_asr_token_text(qwen3_asr_context*, int id) {
    // GPT2 byte encoding for UTF8 e4 bd a0 (你), split over two tokens.
    return pieces[id];
}
}
int main() {
    std::string path = "/tmp/dictate-adapter-test-" + std::to_string(getpid()) + ".gguf";
    try {
        { std::ofstream file(path); file << "GGUF"; }
        dictate::Qwen model(path, 2);
        float pcm[2] = {0.1f, 0.2f};
        require(model.decode(pcm, 2) == "你<literal><|literal|>", "Split Unicode token reconstruction failed");
        require(model.decode(pcm, 2) == "你<literal><|literal|>", "Repeated decode failed");
        rejects([&] { model.decode(pcm, 0); });
        rejects([&] { model.decode(pcm, 29 * 16000 + 1); });
        pcm[0] = std::numeric_limits<float>::quiet_NaN();
        rejects([&] { model.decode(pcm, 2); });
        pcm[0] = 0.1f;
        endless = true;
        rejects([&] { model.decode(pcm, 2); });
        require(resets == 6, "KV cache was not reset after token-limit error");
        std::remove(path.c_str());
        std::cout << "Adapter shape, UTF8, repeated decode, input limits and EOS-limit tests passed\n";
        return 0;
    } catch (const std::exception& e) { std::remove(path.c_str()); std::cerr << e.what() << '\n'; return 1; }
}
