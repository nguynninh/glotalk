#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
./scripts/dev/build.sh
# Unit tests plus the real-model checks marked #[ignore] (they need models/): sessions, the
# CLI tools, and the socket tests, which start build/onplay-server and need port 8766 free.
cargo test --release --quiet --manifest-path rust/Cargo.toml -- --include-ignored
