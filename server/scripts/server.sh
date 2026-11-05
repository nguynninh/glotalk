#!/bin/sh
# Streaming WebSocket server on ws://127.0.0.1:8766 (Rust; translation needs ./scripts/translation.sh).
set -eu
cd "$(dirname "$0")/.."
if [ ! -x build/onplay-server ]; then ./scripts/build.sh; fi
exec build/onplay-server
