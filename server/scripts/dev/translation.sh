#!/bin/sh
# Translation router (Rust) on http://127.0.0.1:5002; reads config/translation.yaml and models/translation/.
set -eu
cd "$(dirname "$0")/../.."
if [ ! -x build/onplay-translation ]; then ./scripts/dev/build.sh; fi
exec build/onplay-translation
