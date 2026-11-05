# Local translation assets

## Router models

The active router uses pinned local Hugging Face snapshots:

| ID | Repository / revision | License |
|---|---|---|
| nllb-200 | [facebook/nllb-200-distilled-600M](https://huggingface.co/facebook/nllb-200-distilled-600M), `f8d333a098d19b4fd9a8b18f94170487ad3f821d` | CC-BY-NC-4.0; research only, not a production recommendation |
| qwen | [Qwen/Qwen3-1.7B](https://huggingface.co/Qwen/Qwen3-1.7B), `70d244cc86ccca08cf5af4e1e306ecf908b1ad5e` | Apache-2.0 |

`ui/translation-requirements.txt` pins the Python 3.12 packages used only to convert
NLLB to CTranslate2 (`ct2-transformers-converter`); the router does not use Python.
Model downloads are under `models/translation/`; `scripts/setup/translation.sh`
pins repository, exact revision and SHA-256 for each downloaded file.
These are experimental local models, not certified translations.

Verified principal weight SHA-256 values (full file manifests stay with assets):

| Model file | SHA-256 |
|---|---|
| NLLB `pytorch_model.bin` | `c266c2cfd19758b6d09c1fc31ecdf1e485509035f6b51dfe84f1ada83eefcc42` |
| Qwen `model-00001-of-00002.safetensors` | `169ad53ec313c3a34b06c0809216e4fc072cce444a5d4ff2b59690d064130ed5` |
| Qwen `model-00002-of-00002.safetensors` | `912becff8d60672aa8628ef08c05898d9adf17c2ad4ae3caf99b065622fdeff9` |

## Native OPUS

`opus-fast` runs the Argos OPUS packages in process with CTranslate2 INT8
(`ct2_opus` backend). `scripts/setup/translation.sh` downloads them into
`models/translation/opus` and verifies each archive and installed `model.bin`.

All packages declare version 1.9. Their bundled README files attribute the original
OPUS-MT models to Jörg Tiedemann and Santhosh Thottingal, EAMT 2020, under
[CC-BY 4.0](https://creativecommons.org/licenses/by/4.0/).

| Pair | Package source | SHA-256 of installed `model/model.bin` |
|---|---|---|
| Chinese → English | https://argos-net.com/v1/translate-zh_en-1_9.argosmodel | `edd8c8a6863d36959613ff291074627a1635fab2f51b872ef437e924d238921a` |
| English → Chinese | https://argos-net.com/v1/translate-en_zh-1_9.argosmodel | `1a039114d9456b6528fabb65b455b6f156319634a0f984b1f6018f7737d67598` |
| English → Vietnamese | https://argos-net.com/v1/translate-en_vi-1_9.argosmodel | `32e13c96db538f77fc7773596de75b1b4ca5e3f933a26c2cf67311f4c16ff9c8` |
| Vietnamese → English | https://argos-net.com/v1/translate-vi_en-1_9.argosmodel | `63aa5bdb275126e62d27ca43ed8c9021df0ae6b94383ca8be4d99c6d73fbcb96` |

No models or runtime binaries belong in Git. Chinese/Vietnamese translations
pivot through English. This is not an accuracy benchmark.
