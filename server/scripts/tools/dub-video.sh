#!/bin/sh
# Translate a video (file or link) with the Rust onplay-dub pipeline.
# The translation router must be running: ./scripts/dev/translation.sh
set -eu
root="$(cd "$(dirname "$0")/../.." && pwd)"
if [ ! -x "$root/build/onplay-speakers" ]; then "$root/scripts/dev/build.sh"; fi
cargo build --release --quiet --manifest-path "$root/rust/Cargo.toml" -p onplay-dub-cli
exec "$root/rust/target/release/onplay-dub" --root "$root" "$@"
