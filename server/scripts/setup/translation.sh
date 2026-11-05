#!/bin/sh
# Download the translation router's models into ignored models/translation/, verifying every
# file: Hugging Face snapshots at pinned revisions and the native INT8 OPUS (Argos 1.9)
# packages. Revisions match config/translation.yaml. The INT8 NLLB conversion
# (models/translation/nllb-200-ct2) still needs the Python converter; see README.
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

# snapshot DIR REPOSITORY REVISION then "FILE SHA256" lines on stdin.
snapshot() {
  dir=$1 repository=$2 revision=$3
  mkdir -p "$dir"
  while read -r file hash; do
    fetch "https://huggingface.co/$repository/resolve/$revision/$file" "$dir/$file" "$hash"
  done
  echo "Installed $repository@$revision"
}

snapshot models/translation/nllb-200 facebook/nllb-200-distilled-600M f8d333a098d19b4fd9a8b18f94170487ad3f821d <<'EOF'
README.md 0964c46d35be00825c73b3a9ca212043b4b4e4f38e18e88c22e4490d31f9f1a3
config.json f9b4081d3d108e9d06532ea8d92b932c04ed3a15ff2f87e6982f8e14db51fbc5
generation_config.json 0bb604cdb8392649176935a447ce994128f170a6908c135a17d0e3dfb8113cb2
pytorch_model.bin c266c2cfd19758b6d09c1fc31ecdf1e485509035f6b51dfe84f1ada83eefcc42
sentencepiece.bpe.model 14bb8dfb35c0ffdea7bc01e56cea38b9e3d5efcdcb9c251d6b40538e1aab555a
special_tokens_map.json 992bd4ed610d644d6823081937bcc91bb8878dd556cea4ae5327f2480361330e
tokenizer.json e316b82de11d0f951f370943b3c438311629547285129b0b81dadabd01bca665
tokenizer_config.json d1aa8c3697d3e35674f97b5b7e9c99d22b010f528e80140257d97316be90d044
EOF
snapshot models/translation/qwen-1.7b Qwen/Qwen3-1.7B 70d244cc86ccca08cf5af4e1e306ecf908b1ad5e <<'EOF'
LICENSE 832dd9e00a68dd83b3c3fb9f5588dad7dcf337a0db50f7d9483f310cd292e92e
README.md 257e52c419dac2258852643f18af6c974f21f8c6c1b6f371b6cca6201cf29091
config.json 1ddb5b89ebc90dcb417a45c213d818577e65976454d29385c8f6140771d95197
generation_config.json 2325da0f15bb848e018c5ae071b7943332e9f871d6b60e2ed22ca97d4cb993d2
merges.txt 8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5
model-00001-of-00002.safetensors 169ad53ec313c3a34b06c0809216e4fc072cce444a5d4ff2b59690d064130ed5
model-00002-of-00002.safetensors 912becff8d60672aa8628ef08c05898d9adf17c2ad4ae3caf99b065622fdeff9
model.safetensors.index.json 0d660e94b165eb912669a5249dff44b83188c4777a07ddb9611fb78d91b0578d
tokenizer.json aeb13307a71acd8fe81861d94ad54ab689df773318809eed3cbe794b4492dae4
tokenizer_config.json d5d09f07b48c3086c508b30d1c9114bd1189145b74e982a265350c923acd8101
vocab.json ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910
EOF

# Pair, package SHA-256, then the SHA-256 of its model/model.bin (docs/translation-models.md).
opus=models/translation/opus
mkdir -p "$opus"
while read -r pair archive weights; do
  if ! echo "$weights  $opus/$pair/model/model.bin" | shasum -a 256 -c - >/dev/null 2>&1; then
    fetch "https://argos-net.com/v1/translate-$pair-1_9.argosmodel" "$opus/$pair.argosmodel" "$archive"
    rm -rf "$opus/$pair" "$opus/.extract"
    unzip -q "$opus/$pair.argosmodel" -d "$opus/.extract"
    mv "$opus/.extract/translate-$pair-1_9" "$opus/$pair"
    rm -rf "$opus/.extract"
    echo "$weights  $opus/$pair/model/model.bin" | shasum -a 256 -c -
  fi
done <<'EOF'
en_vi 86957101aa4099aa9a1a7492e41987d938d3cf0fdaf4fb684c0797a9d567dd16 32e13c96db538f77fc7773596de75b1b4ca5e3f933a26c2cf67311f4c16ff9c8
vi_en ebd51b7189b13eccb9238a5777de1d343008c4c05284a8b89610242364433953 63aa5bdb275126e62d27ca43ed8c9021df0ae6b94383ca8be4d99c6d73fbcb96
zh_en 62e7af5a3a48b530e47b7b3e5c78c2de79073ecd815750d2bf3ab35b4a67da2d edd8c8a6863d36959613ff291074627a1635fab2f51b872ef437e924d238921a
en_zh 433e7c4f034d87fbe2353161e05f18646d7999452f801a4e1f0378522b9850ab 1a039114d9456b6528fabb65b455b6f156319634a0f984b1f6018f7737d67598
EOF
echo "Translation models ready in models/translation/"
