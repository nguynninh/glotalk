#!/bin/sh
# Benchmark a translation router on recorded sessions; see docs/translation-benchmark.md.
# ./scripts/tools/translation-bench.sh corpus
# ./scripts/tools/translation-bench.sh run --label rust -- build/onplay-translation
set -eu
root="$(cd "$(dirname "$0")/../.." && pwd)"
cargo build --release --quiet --manifest-path "$root/rust/Cargo.toml" -p onplay-translation-bench-cli
cd "$root"
exec "$root/rust/target/release/onplay-translation-bench" "$@"
