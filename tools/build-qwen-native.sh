#!/usr/bin/env bash
set -euo pipefail
# Builds the native CrispASR/ggml speech runtime (Qwen3-ASR, Granite Speech, Cohere Transcribe,
# Moonshine streaming). CrispASR (MIT) and its ggml submodule (MIT), pinned for reproducibility.
# License sources: https://github.com/CrispStrobe/CrispASR/blob/eb633d908120cc5f44b8af8cd4aeb1191f16bfd8/LICENSE
# and ggml/LICENSE in that checkout. See NOTICE and app/src/main/assets/licenses/qwen-native.txt in this project.
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
revision=eb633d908120cc5f44b8af8cd4aeb1191f16bfd8
ggml_revision=c36dab89b662838f0f5d4826c399198c0b90bbfc
source_dir=${CRISPASR_SOURCE:-"$root/build/qwen-native/source"}
mode=${1:-android}
if [[ "$mode" != android && "$mode" != host ]]; then echo 'usage: build-qwen-native.sh [android|host]' >&2; exit 2; fi
if [[ ! -d "$source_dir/.git" ]]; then
    mkdir -p "$source_dir"
    git -C "$source_dir" init
    git -C "$source_dir" remote add origin https://github.com/CrispStrobe/CrispASR.git
    git -C "$source_dir" fetch --depth 1 origin "$revision"
    git -C "$source_dir" checkout --detach FETCH_HEAD
    git -C "$source_dir" submodule update --init --depth 1 ggml
fi
[[ $(git -C "$source_dir" rev-parse HEAD) == "$revision" ]] || { echo 'Wrong CrispASR revision' >&2; exit 1; }
[[ $(git -C "$source_dir/ggml" rev-parse HEAD) == "$ggml_revision" ]] || { echo 'Wrong ggml revision' >&2; exit 1; }
[[ -z $(git -C "$source_dir" status --porcelain --untracked-files=no) ]] || { echo 'CrispASR checkout must be clean' >&2; exit 1; }
build_one() {
    local build_dir=$1; shift
    local args=(-S "$root/app/src/main/cpp" -B "$build_dir" -DCMAKE_BUILD_TYPE=Release -DCRISPASR_SOURCE="$source_dir" "$@")
    if [[ "$mode" == android ]]; then
        sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"$HOME/Android/Sdk"}}
        ndk=${ANDROID_NDK_HOME:-"$sdk/ndk/27.0.12077973"}
        args+=(-DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static)
    fi
    cmake "${args[@]}"
    cmake --build "$build_dir" --parallel "${BUILD_JOBS:-2}"
}
if [[ "$mode" == android ]]; then
    # Baseline for every arm64 CPU, plus an ARMv8.2 dot-product/fp16 build the app picks at runtime
    # when /proc/cpuinfo reports asimddp and asimdhp (CrispAsrNative.kt).
    build_one "$root/build/qwen-native/android" -DDICTATE_ARM_ARCH=armv8-a -DDICTATE_LIB_NAME=dictate-crispasr
    build_one "$root/build/qwen-native/android-dotprod" -DDICTATE_ARM_ARCH=armv8.2-a+dotprod+fp16 -DDICTATE_LIB_NAME=dictate-crispasr-dotprod
    mkdir -p "$root/app/src/main/jniLibs/arm64-v8a"
    rm -f "$root/app/src/main/jniLibs/arm64-v8a/libdictate-qwen.so"
    cp "$root/build/qwen-native/android/libdictate-crispasr.so" \
       "$root/build/qwen-native/android-dotprod/libdictate-crispasr-dotprod.so" \
       "$root/app/src/main/jniLibs/arm64-v8a/"
else
    build_one "$root/build/qwen-native/host"
    ctest --test-dir "$root/build/qwen-native/host" --output-on-failure
fi
