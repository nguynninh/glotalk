#!/bin/sh
# Pack the relocatable native binaries and their runtime libraries for macOS ARM64.
set -eu
cd "$(dirname "$0")/../.."
./scripts/dev/build.sh
name=onplay-native-macos-arm64
rm -rf "dist/$name"
mkdir -p "dist/$name/lib"
cp build/onplay-stt build/onplay-speakers build/onplay-recognize build/onplay-server build/onplay-translation "dist/$name/"
cp build/lib/*.dylib "dist/$name/lib/"
for binary in "dist/$name"/onplay-*; do
  if otool -l "$binary" | grep -A2 LC_RPATH | grep -q "path /"; then
    echo "Absolute rpath in $binary" >&2
    exit 1
  fi
done
tar -czf "dist/$name.tar.gz" -C dist "$name"
(cd dist && shasum -a 256 "$name.tar.gz" > "$name.tar.gz.sha256")
cat "dist/$name.tar.gz.sha256"
