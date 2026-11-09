#!/bin/sh
# Build the Rust native tools (onplay-stt, onplay-speakers, onplay-recognize), the streaming
# server (onplay-server), the dubbing tool it runs for jobs (onplay-dub) and the translation
# router (onplay-translation) into build/,
# with the Sherpa ONNX and ONNX Runtime dylibs in build/lib (found via @loader_path/lib).
# The router links CTranslate2 and SentencePiece, compiled from source with the pinned CMake.
set -eu
cd "$(dirname "$0")/../.."
runtime="$PWD/.deps/sherpa-onnx-v2.0.1-osx-arm64-shared-no-tts-lib"
mkdir -p build/lib
cp "$runtime"/lib/libsherpa-onnx-c-api.dylib "$runtime"/lib/libonnxruntime.dylib build/lib/
./scripts/setup/cmake.sh >/dev/null
CMAKE="$PWD/.deps/cmake/CMake.app/Contents/bin/cmake" \
  cargo build --release --quiet --manifest-path rust/Cargo.toml -p onplay-stt-cli -p onplay-server-cli -p onplay-dub-cli -p onplay-translation-cli
for tool in onplay-stt onplay-speakers onplay-recognize onplay-server onplay-dub onplay-translation; do
  # Replace via rename: overwriting a signed Mach-O in place can get running copies killed.
  cp "rust/target/release/$tool" "build/.$tool.tmp"
  mv -f "build/.$tool.tmp" "build/$tool"
done
