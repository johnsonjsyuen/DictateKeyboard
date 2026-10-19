#include "crispasr_engines.h"
#include <jni.h>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
namespace {
std::mutex mutex;
std::map<jlong, std::unique_ptr<dictate::Engine>> models;
jlong next_handle = 1;
void error(JNIEnv* env, const char* type, const char* message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass(type), message);
}
void failure(JNIEnv* env) {
    try { throw; }
    catch (const std::invalid_argument& e) { error(env, "java/lang/IllegalArgumentException", e.what()); }
    catch (const std::exception& e) { error(env, "java/lang/IllegalStateException", e.what()); }
    catch (...) { error(env, "java/lang/IllegalStateException", "Native speech runtime failure"); }
}
std::string utf(JNIEnv* env, jstring value, const char* what) {
    if (!value) throw std::invalid_argument(what);
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) throw std::runtime_error("Out of memory");
    std::string out;
    try { out = chars; } catch (...) { env->ReleaseStringUTFChars(value, chars); throw; }
    env->ReleaseStringUTFChars(value, chars);
    return out;
}
}
extern "C" JNIEXPORT jlong JNICALL Java_dev_patrickgold_florisboard_dictate_provider_CrispAsrNative_load(JNIEnv* env, jclass, jstring path, jint backend, jint threads) {
    try {
        auto value = utf(env, path, "Model path is null");
        auto model = dictate::load_engine(static_cast<dictate::Backend>(backend), value, threads);
        std::lock_guard<std::mutex> lock(mutex);
        jlong handle = next_handle++;
        models.emplace(handle, std::move(model));
        return handle;
    } catch (...) { failure(env); return 0; }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_patrickgold_florisboard_dictate_provider_CrispAsrNative_decode(JNIEnv* env, jclass, jlong handle, jfloatArray samples, jstring language) {
    try {
        if (!samples) throw std::invalid_argument("Audio is null");
        auto lang = language ? utf(env, language, "Language is null") : std::string();
        auto count = env->GetArrayLength(samples);
        if (count < 1 || size_t(count) > dictate::Engine::kMaxSamples) throw std::invalid_argument("Audio duration must be at most 29 seconds");
        std::vector<float> pcm(count);
        env->GetFloatArrayRegion(samples, 0, count, pcm.data());
        if (env->ExceptionCheck()) return nullptr;
        std::lock_guard<std::mutex> lock(mutex);
        auto it = models.find(handle);
        if (it == models.end()) throw std::invalid_argument("Invalid speech model handle");
        auto text = it->second->decode(pcm.data(), pcm.size(), lang);
        auto bytes = env->NewByteArray(static_cast<jsize>(text.size()));
        if (bytes) env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte*>(text.data()));
        return bytes;
    } catch (...) { failure(env); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_dev_patrickgold_florisboard_dictate_provider_CrispAsrNative_free(JNIEnv* env, jclass, jlong handle) {
    try { std::lock_guard<std::mutex> lock(mutex); models.erase(handle); }
    catch (...) { failure(env); }
}
