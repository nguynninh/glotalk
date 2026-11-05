#!/bin/sh
# Install a relocatable CPython into .deps/python with the packages of ct2-transformers-converter,
# used once to convert NLLB to CTranslate2 (see README). Nothing at runtime uses Python.
set -eu
cd "$(dirname "$0")/../.."
if [ "$(uname -s)-$(uname -m)" != Darwin-arm64 ]; then
  echo 'This runtime setup requires Apple Silicon macOS.' >&2
  exit 1
fi
fetch() {
  url=$1 dest=$2 hash=$3
  if ! [ -f "$dest" ] || ! echo "$hash  $dest" | shasum -a 256 -c - >/dev/null 2>&1; then
    curl -fL --retry 3 "$url" -o "$dest.part"
    echo "$hash  $dest.part" | shasum -a 256 -c -
    mv "$dest.part" "$dest"
  fi
}
version=3.12.15+20261013
mkdir -p .deps
fetch "https://github.com/astral-sh/python-build-standalone/releases/download/20261013/cpython-3.12.15%2B20261013-aarch64-apple-darwin-install_only_stripped.tar.gz" \
  .deps/python.tar.gz 9866e11cf2ae9caa1e671383786f248859a52dd8bfb702fb4c0dc2c65f1d36de
python=.deps/python/bin/python3
if [ "$(cat .deps/python/.onplay-version 2>/dev/null)" != "$version" ]; then
  rm -rf .deps/python
  tar -xzf .deps/python.tar.gz -C .deps
  echo "$version" > .deps/python/.onplay-version
fi
requirements=$(shasum -a 256 ui/translation-requirements.txt | cut -d' ' -f1)
if [ "$(cat .deps/python/.onplay-requirements 2>/dev/null)" != "$requirements" ]; then
  "$python" -m pip install --disable-pip-version-check --no-warn-script-location -r ui/translation-requirements.txt
  echo "$requirements" > .deps/python/.onplay-requirements
fi
"$python" -c 'import torch, transformers, ctranslate2, sentencepiece'
echo "Python runtime ready: $python ($version)"
