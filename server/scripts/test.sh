#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
./scripts/build.sh
runtime="$PWD/.deps/sherpa-onnx-v2.0.1-osx-arm64-shared-no-tts-lib"
clang++ -std=c++20 -O1 -g -fsanitize=address,undefined -Wall -Wextra -Werror -Iinclude -I.deps/include \
  src/stt.cpp tests/session.cpp -L"$runtime/lib" -lsherpa-onnx-c-api -Wl,-rpath,"$runtime/lib" -o build/session-test
m=models/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10
build/session-test "$m/encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx" \
  "$m/decoder-epoch-75-avg-11-chunk-16-left-128.onnx" \
  "$m/joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx" "$m/tokens.txt"
python3 tests/smoke.py
python3 tests/recognition.py
