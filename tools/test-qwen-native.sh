#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
"$root/tools/build-qwen-native.sh" host
if [[ $# == 0 ]]; then
    exit 0
fi
if [[ $# != 2 ]]; then
    echo 'usage: test-qwen-native.sh [MODEL.gguf MONO_16KHZ.f32]' >&2
    exit 2
fi
# Runs the same recording twice through one loaded context and requires
# identical nonempty transcripts. PCM must be little-endian float32 mono 16 kHz.
"$root/build/qwen-native/host/qwen-smoke" "$1" "$2" 2
