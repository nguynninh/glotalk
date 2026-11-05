#!/bin/sh
# Install a pinned CMake into .deps/cmake. Building the Rust translation router compiles
# CTranslate2 and SentencePiece from source (ct2rs, sentencepiece-sys), which needs it.
set -eu
cd "$(dirname "$0")/../.."
fetch() {
  url=$1 dest=$2 hash=$3
  if ! [ -f "$dest" ] || ! echo "$hash  $dest" | shasum -a 256 -c - >/dev/null 2>&1; then
    curl -fL --retry 3 "$url" -o "$dest.part"
    echo "$hash  $dest.part" | shasum -a 256 -c -
    mv "$dest.part" "$dest"
  fi
}
version=3.31.9
mkdir -p .deps
# SHA-256 from Kitware's cmake-3.31.9-SHA-256.txt.
fetch "https://github.com/Kitware/CMake/releases/download/v$version/cmake-$version-macos-universal.tar.gz" \
  .deps/cmake.tar.gz eb9d0b2b6a5b900e2f5811326a86ea393a146e4cb2384d15512c226e9c98f8bf
if [ "$(cat .deps/cmake/.onplay-version 2>/dev/null)" != "$version" ]; then
  rm -rf .deps/cmake .deps/cmake-$version-macos-universal
  tar -xzf .deps/cmake.tar.gz -C .deps
  mv .deps/cmake-$version-macos-universal .deps/cmake
  echo "$version" > .deps/cmake/.onplay-version
fi
echo "CMake ready: .deps/cmake/CMake.app/Contents/bin/cmake ($version)"
