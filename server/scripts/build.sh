#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
runtime="$PWD/.deps/sherpa-onnx-v2.0.1-osx-arm64-shared-no-tts-lib"
mkdir -p build
clang++ -std=c++20 -O3 -DNDEBUG -Wall -Wextra -Werror -Iinclude -I.deps/include src/stt.cpp src/main.cpp \
  -L"$runtime/lib" -lsherpa-onnx-c-api -Wl,-rpath,"$runtime/lib" -o build/onplay-stt
clang++ -std=c++20 -O3 -DNDEBUG -Wall -Wextra -Werror -Iinclude -I.deps/include src/stt.cpp src/speakers.cpp \
  -L"$runtime/lib" -lsherpa-onnx-c-api -Wl,-rpath,"$runtime/lib" -o build/onplay-speakers
clang++ -std=c++20 -O3 -DNDEBUG -Wall -Wextra -Werror -I.deps/include src/recognize.cpp \
  -L"$runtime/lib" -lsherpa-onnx-c-api -Wl,-rpath,"$runtime/lib" -o build/onplay-recognize
