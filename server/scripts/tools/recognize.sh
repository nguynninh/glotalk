#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
m=models/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10
if [ "$#" -eq 0 ]; then
  set -- "$m/test_wavs/en.wav"
fi
exec build/onplay-stt "$m/encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx" \
  "$m/decoder-epoch-75-avg-11-chunk-16-left-128.onnx" \
  "$m/joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx" "$m/tokens.txt" "$@"
