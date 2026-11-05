#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
if [ "$(uname -s)-$(uname -m)" != Darwin-arm64 ]; then
  echo 'This bootstrap requires Apple Silicon macOS. Use CMake with your platform sherpa-onnx SDK.' >&2
  exit 1
fi
mkdir -p .deps/include/sherpa-onnx/c-api models
fetch() {
  url=$1 dest=$2 hash=$3
  if ! [ -f "$dest" ] || ! echo "$hash  $dest" | shasum -a 256 -c - >/dev/null 2>&1; then
    curl -fL --retry 3 "$url" -o "$dest.part"
    echo "$hash  $dest.part" | shasum -a 256 -c -
    mv "$dest.part" "$dest"
  fi
}
fetch https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-v2.0.1-osx-arm64-shared-no-tts-lib.tar.bz2 \
  .deps/runtime.tar.bz2 ac5594ad237db56ec4a6c4b3fc48700441b33a07bb71c235f35530a52a93f2e3
tar -xjf .deps/runtime.tar.bz2 -C .deps
fetch https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v2.0.1/sherpa-onnx/c-api/c-api.h \
  .deps/include/sherpa-onnx/c-api/c-api.h 5698f38ba09ad2bdf085c56a6cbbd7741acf15e25f4ce5c7097b9702cadf437f
fetch https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10.tar.bz2 \
  models/baseline.tar.bz2 28044b67324f7f831689f0a3761473dd2ade380e93aa53f1dbcd479ef71c40d4
tar -xjf models/baseline.tar.bz2 -C models
fetch https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/vi.wav \
  models/vi.wav bad24232a06f222697cc27e50bc33c7cd9c1bf7faa73847af7e6742f89b366c0
