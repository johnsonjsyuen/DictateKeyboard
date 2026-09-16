#!/usr/bin/env bash
set -euo pipefail
# CrispASR (MIT) and its ggml submodule (MIT), pinned for reproducibility.
# License sources: https://github.com/CrispStrobe/CrispASR/blob/ba3499e7c7f6013a73738cad530b252d59675f49/LICENSE
# and ggml/LICENSE in that checkout. See NOTICE and app/src/main/assets/licenses/qwen-native.txt in this project.
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
revision=ba3499e7c7f6013a73738cad530b252d59675f49
ggml_revision=2dd13eddc783f2cd0a29324affd78081cc6f0034
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
build_dir="$root/build/qwen-native/$mode"
args=(-S "$root/app/src/main/cpp" -B "$build_dir" -DCMAKE_BUILD_TYPE=Release -DCRISPASR_SOURCE="$source_dir")
if [[ "$mode" == android ]]; then
    sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"$HOME/Android/Sdk"}}
    ndk=${ANDROID_NDK_HOME:-"$sdk/ndk/27.0.12077973"}
    args+=(-DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static)
fi
cmake "${args[@]}"
cmake --build "$build_dir" --parallel 2
if [[ "$mode" == android ]]; then
    mkdir -p "$root/app/src/main/jniLibs/arm64-v8a"
    cp "$build_dir/libdictate-qwen.so" "$root/app/src/main/jniLibs/arm64-v8a/"
else
    ctest --test-dir "$build_dir" --output-on-failure
fi
