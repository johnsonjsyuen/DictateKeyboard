#include "qwen_adapter.h"
#include <jni.h>
#include <map>
#include <mutex>
#include <stdexcept>
#include <vector>
namespace {
std::mutex mutex;
std::map<jlong, std::unique_ptr<dictate::Qwen>> models;
jlong next_handle = 1;
void error(JNIEnv* env, const char* type, const char* message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass(type), message);
}
void failure(JNIEnv* env) {
    try { throw; }
    catch (const std::invalid_argument& e) { error(env, "java/lang/IllegalArgumentException", e.what()); }
    catch (const std::exception& e) { error(env, "java/lang/IllegalStateException", e.what()); }
    catch (...) { error(env, "java/lang/IllegalStateException", "Native Qwen failure"); }
}
}
extern "C" JNIEXPORT jlong JNICALL Java_dev_patrickgold_florisboard_dictate_provider_QwenNative_load(JNIEnv* env, jclass, jstring path, jint threads) {
    try {
        if (!path) throw std::invalid_argument("Model path is null");
        const char* chars = env->GetStringUTFChars(path, nullptr);
        if (!chars) return 0;
        std::string value;
        try { value = chars; } catch (...) { env->ReleaseStringUTFChars(path, chars); throw; }
        env->ReleaseStringUTFChars(path, chars);
        std::lock_guard<std::mutex> lock(mutex);
        auto model = std::make_unique<dictate::Qwen>(value, threads);
        jlong handle = next_handle++;
        models.emplace(handle, std::move(model));
        return handle;
    } catch (...) { failure(env); return 0; }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_patrickgold_florisboard_dictate_provider_QwenNative_decode(JNIEnv* env, jclass, jlong handle, jfloatArray samples) {
    try {
        if (!samples) throw std::invalid_argument("Audio is null");
        auto count = env->GetArrayLength(samples);
        if (count < 1 || count > 29 * 16000) throw std::invalid_argument("Audio duration must be at most 29 seconds");
        std::vector<float> pcm(count);
        env->GetFloatArrayRegion(samples, 0, count, pcm.data());
        if (env->ExceptionCheck()) return nullptr;
        std::lock_guard<std::mutex> lock(mutex);
        auto it = models.find(handle);
        if (it == models.end()) throw std::invalid_argument("Invalid Qwen handle");
        auto text = it->second->decode(pcm.data(), pcm.size());
        auto bytes = env->NewByteArray(static_cast<jsize>(text.size()));
        if (bytes) env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte*>(text.data()));
        return bytes;
    } catch (...) { failure(env); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_dev_patrickgold_florisboard_dictate_provider_QwenNative_free(JNIEnv* env, jclass, jlong handle) {
    try { std::lock_guard<std::mutex> lock(mutex); models.erase(handle); }
    catch (...) { failure(env); }
}
