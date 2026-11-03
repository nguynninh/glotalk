#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p models/speakers
fetch() {
  url=$1 dest=$2 hash=$3
  if ! [ -f "$dest" ] || ! echo "$hash  $dest" | shasum -a 256 -c - >/dev/null 2>&1; then
    curl -fL --retry 3 "$url" -o "$dest.part"
    echo "$hash  $dest.part" | shasum -a 256 -c -
    mv "$dest.part" "$dest"
  fi
}
fetch https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2 \
  models/speakers/segmentation.tar.bz2 24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488
tar -xjf models/speakers/segmentation.tar.bz2 -C models/speakers
fetch https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx \
  models/speakers/embedding.onnx f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11
