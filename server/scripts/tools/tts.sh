#!/bin/sh
# Build and run the Rust KorvaTTS CLI against the Sherpa ONNX runtime dylib in .deps/.
set -eu
root="$(cd "$(dirname "$0")/../.." && pwd)"
cargo build --release --quiet --manifest-path "$root/rust/Cargo.toml" -p onplay-tts-cli
ORT_DYLIB_PATH="${ORT_DYLIB_PATH:-$root/.deps/sherpa-onnx-v2.0.1-osx-arm64-shared-no-tts-lib/lib/libonnxruntime.dylib}" \
  exec "$root/rust/target/release/onplay-tts" "$@"
