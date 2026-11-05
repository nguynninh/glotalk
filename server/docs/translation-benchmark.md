# Translation router benchmark

`onplay-translation-bench` measures a translation router as a black box, through the
same `GET /health` and `POST /translate` calls the streaming server makes. The Python
router (`ui/translation_engine.py`, removed after this comparison) and the Rust router
(`build/onplay-translation`) were measured with the same client, corpus, models and
`config/translation.yaml`. The Rust router was to replace the Python one only with
identical outputs and a measurable gain; the results are below. The Python router is
in Git history (`a315e18`) if a baseline must be re-run.

## What it measures

- **Corpus**: unique final STT segments from recorded sessions (`sessions/*.jsonl`),
  sent as the server sends them (`source: auto`, the session target, standard mode).
  Recorded Vietnamese translations are also sent to `en` and `zh` (`--derive`) to cover
  the vi→en and vi→zh routes. The corpus holds recognized speech, so it is written to
  ignored `build/translation-bench/` and must not be committed.
- **Per run**: the router command is started fresh (empty cache), timed until
  `/health` answers (includes model warmup), then every item is sent once,
  sequentially. Memory is the RSS of the router's process tree, once idle and as a peak
  sampled every 100 ms. The router is killed with its process group afterwards.
- **Outputs**: text, detected source, model and fallback per item (`run-N.jsonl`), and
  items whose outcome changed between runs (`unstable`).
- **Comparison**: `compare` counts items whose text, detected language, model or
  fallback differ between two result folders (both failing counts as identical), and
  shows startup, memory and latency side by side. It exits non-zero on any difference.

Not measured: absolute translation quality (there are no human references), context
mode (Qwen; the recorded sessions contain no context requests), concurrent load, and
energy use.

## Running

The router listens on the fixed port 5002, so stop any running router first.

```sh
./scripts/tools/translation-bench.sh corpus
./scripts/tools/translation-bench.sh run --label rust -- build/onplay-translation
```

A candidate is run the same way with its own label and command, then compared with
an earlier result folder:

```sh
./scripts/tools/translation-bench.sh run --label candidate -- <router command>
./scripts/tools/translation-bench.sh compare build/translation-bench/rust build/translation-bench/candidate --diff build/translation-bench/diff.jsonl
```

`run` options: `--runs N` (default 3), `--corpus FILE`, `--out DIR`, `--url URL`,
`--root DIR`, `--startup-timeout SECONDS`.

## Python baseline

Run on 2026-11-05, Apple M4, 24 GB, `.deps/python` CPython 3.12.15, the configuration in
`config/translation.yaml` at that date, with 803 items (275 final segments recorded on
2026-11-04, mostly Chinese→Vietnamese, plus 264 vi→en and 264 vi→zh derived items) and
3 runs:

| Run | Startup s | Idle MB | Peak MB | p50 ms | p95 ms | Max ms | Failed |
|---|---|---|---|---|---|---|---|
| 1 | 3.5 | 2016.0 | 2016.0 | 50 | 122 | 1204 | 0 |
| 2 | 3.5 | 1394.9 | 1643.7 | 48 | 125 | 1232 | 0 |
| 3 | 3.5 | 1460.5 | 1646.3 | 47 | 113 | 1277 | 0 |

Across all 2409 requests: mean 57.7, p50 48.5, p95 122.5, p99 170.9, max 1276.7 ms.
No failures, no fallbacks, no cache hits, and no item changed output between runs.

Requests per run by model: `opus-fast` 796 (zh→vi, vi→en, vi→zh, one en→vi),
`identity` 6 (detected vi→vi), `nllb-fast` 1 (one Arabic segment). NLLB is therefore
barely covered by recorded sessions; its 3 samples (p50 163 ms) are not a benchmark.

The two slowest items (about 0.8 s and 1.2 s in every run) are vi→zh derived items
whose output is 9-10 times longer than the input, which suggests repetitive decoding in
the OPUS pivot. That is current router behavior, recorded here and not fixed.

Idle memory differed by 600 MB between identical runs; compare memory across several
runs, not single values.

## Rust router

Run on 2026-11-05 on the same Mac, corpus and configuration, 3 runs, with
`ONPLAY_TRANSLATION_ADDRESS=127.0.0.1:5012` and `--url http://127.0.0.1:5012` because a
Python router was serving 5002 at the time:

| Run | Startup s | Idle MB | Peak MB | p50 ms | p95 ms | Max ms | Failed |
|---|---|---|---|---|---|---|---|
| 1 | 1.8 | 1844.1 | 1850.4 | 49 | 123 | 1264 | 0 |
| 2 | 1.2 | 1845.8 | 1850.9 | 48 | 116 | 1118 | 0 |
| 3 | 1.2 | 1828.0 | 1833.9 | 47 | 112 | 1124 | 0 |

`compare` against the Python baseline: 803/803 identical outputs (text, detected
language, model, fallback), none unstable across runs.

| | Python | Rust | Ratio |
|---|---|---|---|
| Startup s (mean) | 3.5 | 1.4 | 0.41x |
| Idle MB (mean) | 1623.8 | 1839.3 | 1.13x |
| Peak MB (max) | 2016.0 | 1850.9 | 0.92x |
| p50 / p95 / p99 ms | 48.5 / 122.5 / 170.9 | 48.5 / 118.7 / 166.8 | 1.00x / 0.97x / 0.98x |

Latency is unchanged because both routers spend it in the same CTranslate2 inference
(Rust bundles CTranslate2 4.8.5, Python 4.6.2). Memory is close: loading the four OPUS
pairs and NLLB takes about 975 MB in both, and the first NLLB translation adds about
500 MB in both. Rust's idle memory is stable across runs, where Python's varied by
600 MB; the Rust NLLB `tokenizers` vocabulary accounts for about 170 MB.

Context mode (Qwen3-1.7B) is outside the corpus. A manual check of three
Vietnamese→English sentences with two context segments, model already loaded, gave
identical text and 0.52-0.70 s in Rust (candle, Metal f16) against 0.79-1.25 s in
Python (PyTorch MPS f16); the Rust model load took about 4.5 s. Three sentences are
a spot check, not a benchmark.
