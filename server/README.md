# ONPlay STT — Rust streaming lab

Inference offline bằng Rust + sherpa-onnx 2.0.1 (C API), dùng model Zipformer multilingual INT8.

## Streaming server

`onplay-server` (Rust, `rust/crates/onplay-server`) is the only network service of
this project. It listens on the WebSocket `ws://127.0.0.1:8766`; there is no HTTP API
and no web UI. Clients (the mobile app) send PCM16 mono 16 kHz chunks and receive
partial/final transcripts immediately. Optional translation runs on separate workers
through the local translation router; optional speech returns per-segment WAVs
synthesized in process with the Rust KorvaTTS port. Optional CAM++ live speaker labels
are provisional. After the input ends, recordings up to 10 minutes can be refined with
Pyannote segmentation, CAM++ clustering and per-turn STT; refined translations are
sent separately with segment IDs and timestamps. One recognition session runs at a
time. The protocol is in [docs/streaming-audio.md](docs/streaming-audio.md).

```sh
./scripts/setup.sh
./scripts/build.sh
./scripts/dev/translation.sh   # optional, in another terminal: translation router on 5002
./scripts/server.sh
```

STT and TTS run inside the server process; Sherpa ONNX and KorvaTTS share the ONNX
Runtime dylib in `build/lib/`. The translation router is the Rust binary
`build/onplay-translation`. KorvaTTS
assets come from the Hugging Face cache (`models--dogenthq--KorvaTTS`); if they or the
runtime are missing, the server starts without speech and sessions with
`speech: true` receive `audio_error`.

Settings: `ONPLAY_PUBLIC_ORIGIN` (comma-separated extra allowed `Origin` values; the
default allows only `http://127.0.0.1:8765`), `ONPLAY_TRANSLATE_URL` (default
`http://127.0.0.1:5002`), `ONPLAY_SESSION_DIR` (default `sessions/`),
`ONPLAY_CAPTURE_AUDIO` (for every session: `0` nothing, the default; `1` transcript
and PCM; `2` transcript; `3` PCM; other values stop the server), `ONPLAY_MAX_SESSIONS`
(recognition sessions served at once, default 4; more get an `error` event; each busy
session may load its own streaming engine; non-positive values stop the server), `ONPLAY_LOG`
(`1` prints one UTC-timestamped stdout line when a connection is refused, opens, starts
and ends: client address, `Origin`, `User-Agent`, languages, engine slot and sessions
active, outcome, audio seconds and segment count; off by default), `ONPLAY_DEBUG` (`1`
prints those lines plus every WebSocket frame in and out, with time since the connection
opened, size and the JSON fields, and pipeline step timings: queued, engine, finish,
diarization, drain; recognized and translated text appear only as character counts and
audio data is left out; off by default; neither setting prints audio or recognized text), `ONPLAY_ROOT`
and `ORT_DYLIB_PATH` (defaults derived from the binary location in `build/`).

### HTTPS origin

Behind the `stt.qiverse.top` tunnel, allow the public origin explicitly and keep the
variable on every restart; without it, connections that send that origin get 403:

```sh
ONPLAY_PUBLIC_ORIGIN=https://stt.qiverse.top ./scripts/server.sh
```

The tunnel routes the whole hostname to `127.0.0.1:8766`; the server ignores the
request path. Do not use an unrestricted origin policy. Clients must send an
allowed `Origin` header.

With `ONPLAY_LOG=1` the client address is the tunnel's `CF-Connecting-IP` header (else
the first `X-Forwarded-For` address, else the TCP peer). These headers and `User-Agent`
are logged as sent and are not verified; the protocol carries no device name.

### Đóng gói binary native

```sh
./scripts/package-native.sh
```

Tạo `dist/onplay-native-macos-arm64.tar.gz` (kèm file `.sha256`) chứa `onplay-stt`, `onplay-speakers`, `onplay-recognize`, `onplay-server`, `onplay-translation` và `lib/` (Sherpa ONNX, ONNX Runtime). Binary tìm dylib qua `@loader_path/lib` nên chạy được ở thư mục khác; `onplay-server` ở ngoài checkout cần `ONPLAY_ROOT` trỏ tới thư mục có `models/`. Archive chưa được ký Developer ID hay notarize; không gồm model hay Python runtime.

## CLI và core

```sh
./scripts/run.sh models/vi.wav
./scripts/run.sh models/vi.wav --realtime --threads=2 --chunk-ms=100
```

CLI đọc WAV mono PCM16 16 kHz tăng dần, hỗ trợ PCM thường và WAVE_FORMAT_EXTENSIBLE PCM16. Dùng `-` thay tên WAV để nhận raw PCM16 little endian 16 kHz từ stdin. CLI mặc định chạy nhanh nhất; `--realtime` chỉ dành cho benchmark file WAV theo thời gian thực.

- `rust/crates/onplay-stt/src/streaming.rs`: `Engine` / `Session` với `accept_audio()` / `finish()`, listener transcript.
- `rust/crates/onplay-stt/src/wave.rs`: đọc WAV theo chunk, không giữ toàn bộ file trong RAM.
- `rust/crates/onplay-stt/src/utterance.rs`, `diarize.rs`: nhận dạng cả câu, phân đoạn người nói + STT từng lượt.
- `rust/crates/onplay-stt-cli/src/bin/`: `onplay-stt` (WAV/stdin, partial/final, progress, benchmark), `onplay-recognize`, `onplay-speakers`.
- `rust/crates/onplay-sherpa` (+ `-sys`): binding bindgen từ `c-api.h` đã ghim và wrapper an toàn giải phóng handle trong `Drop`; là crate duy nhất có `unsafe`.
- `rust/crates/onplay-server`: WebSocket server; one recognition thread per session owns the native engine.

Core nhận float PCM mono 16 kHz trong [-1,1], tối đa một giây mỗi lần gọi. Chỉ gọi từ một worker thread; callback đồng bộ, không re-enter engine hoặc gọi inference trên UI/audio callback. `finish()` idempotent, sau đó tạo session mới.

`./scripts/build.sh` builds the Rust tools and `onplay-server` with `cargo` and places
them in `build/` with `build/lib/*.dylib` (`@loader_path/lib` rpath). The three STT
tools keep the command lines, stdout/stderr lines and pipe protocol of the earlier C++
tools. On this Mac their stdout was byte-identical to the C++ binaries for WAV and
stdin streaming (with and without live speaker labels and low latency), offline
diarization of a 3:50 clip, the `onplay-recognize` pipe protocol and eight error
cases. The C++ sources have been removed. The server does not use these tools; it
calls the same `onplay-stt` library in process.

Chưa có AAR/XCFramework, micro, training pipeline hay LID trả mã ngôn ngữ. Speaker diarization is experimental.

## Video translation (offline)

`scripts/dub-video.sh` runs the Rust `onplay-dub` pipeline (needs `cargo`): diarized
STT, per-segment translation, SRT files and dubbed speech muxed back into an MP4.
It needs no Python.

```sh
./scripts/dev/translation.sh        # in another terminal; the router must be running
./scripts/dub-video.sh input.mp4 --target vi
./scripts/dub-video.sh input.mp4 --target fr --subtitles-only
./scripts/dub-video.sh 'https://www.youtube.com/watch?v=...' --target vi   # needs yt-dlp
```

It prints the output paths: `<name>.<target>.mp4` with a soft `mov_text` subtitle
track, `<name>.<target>.srt` and `<name>.original.srt`, next to the input or
`--output path.mp4`. Each translated segment gets a per-speaker KorvaTTS voice. The
speed is chosen from the predicted duration (at most 2x) so speech fits before the
next segment, and the dub is mixed over the original audio, which is lowered to
`--background` (default 0.15) only while dubbed speech plays. Segments already in the
target language keep their original audio. All-capital STT text is sentence-cased
before translation, because the translation models garble capitals.

Stages overlap: segments are translated as `onplay-speakers` prints them and queued
to `--tts-workers` in-process synthesizers (default 3, `--tts-threads` 2 each) while
recognition continues. On an Apple M4 (4 performance + 6 efficiency cores), a 3:50
two-speaker English clip dubbed to Vietnamese took 78.6 s with sequential stages and
54-58 s with this pipeline. Diarization was fastest at 4 threads (26 s versus 35-52 s
at 6-10), so `--threads` stays at 4. Per-process CPU time during a run was about 50%
TTS, 49% `onplay-speakers`, 1% ffmpeg; the earlier Python orchestration of the same
pipeline used 0.3% and ran equally fast (54.1/58.5 s against 54.3/57.1 s for Rust),
with identical SRT output.

The library reports progress only through an optional `onplay_dub::Observer`
(`events.rs`); it does not log. The CLI attaches no observer.

Limits: speech output supports Vietnamese and English only; other targets need
`--subtitles-only`. Audio is diarized in 590-second pieces, so speaker voices may
change between pieces. Subtitles are not burned in (the Homebrew ffmpeg checked
here has no libass). Only use videos you have the right to translate.

## Rust workspace

`rust/` is a Cargo workspace with the STT engine and tools, the streaming server, the
video translation pipeline above and a Rust reimplementation of KorvaTTS 0.1.3 inference. The TTS reads the published KorvaTTS assets (`~/.cache/huggingface/hub/models--dogenthq--KorvaTTS`,
Apache-2.0) without modifying them or the Python package, and loads the ONNX Runtime
dylib from the Sherpa ONNX SDK in `.deps/` (`ort` with `load-dynamic`).

```sh
./scripts/tts-rust.sh --text 'Xin chào' --voice khanh_vy --steps 8 --output out.wav
./scripts/tts-rust.sh --list-voices
echo '{"text":"Xin chào","lang":"vi","voice":"khanh_vy","output":"/tmp/a.wav","max_seconds":1.0}' \
  | ./scripts/tts-rust.sh --batch --steps 8
```

`--batch` keeps the models loaded and answers one JSON line per request.
`max_seconds` raises the speed, up to 2x, so the predicted speech fits, without a
second synthesis pass.

`--device` accepts `cpu` (default), `coreml`, `coreml-gpu` and `coreml-ane`. On the
M4, five Vietnamese sentences (24.7 s of audio, 8 steps) took 3.6 s on the CPU with
default threads (RTF 0.147), 3.9-5.8 s with 2, 4 or 10 threads, and 4.4-6.8 s with
CoreML after 4-13 s of model compilation, because CoreML accepts only part of each
graph. The MLProgram CoreML format failed to build these graphs.

```
rust/
├── Cargo.toml                    workspace, shared dependency versions and lints
└── crates/
    ├── onplay-tts/               library
    │   ├── src/
    │   │   ├── assets/           asset directory, tts.json config, JSON reading
    │   │   ├── text/             normalization, chunking, language tags, indexer
    │   │   ├── runtime/          ONNX Runtime init, device selection, typed graph calls
    │   │   ├── synth/            pipeline, duration fitting, initial latent, noise
    │   │   ├── audio/            tail cleanup, PCM16 WAV output
    │   │   ├── tensor.rs         buffers shared by runtime and synth
    │   │   ├── voice.rs          voice style loading
    │   │   └── error.rs
    │   └── tests/                Python-generated text parity fixture and test
    ├── onplay-tts-cli/           `onplay-tts` binary (args, batch mode, noise replay)
    ├── onplay-dub/               video translation library
    │   └── src/
    │       ├── pipeline.rs       options, stage overlap, output naming
    │       ├── fetch.rs          local file or yt-dlp download
    │       ├── media.rs          ffmpeg extract, cut, duck-and-mix mux
    │       ├── recognize.rs      streamed onplay-speakers segments
    │       ├── translate.rs      translation router client, sentence casing
    │       ├── speech.rs         parallel in-process synthesizers
    │       ├── timeline.rs       clip placement on the dub track
    │       ├── srt.rs, voices.rs, segment.rs
    │       ├── events.rs         optional observer, no logging
    │       ├── project.rs        checkout paths, embedding checksum
    │       ├── workdir.rs        temp directory removed on drop
    │       └── command.rs, error.rs
    ├── onplay-dub-cli/           `onplay-dub` binary used by dub-video.sh
    ├── onplay-server/            streaming WebSocket server library
    │   └── src/
    │       ├── socket/           listener and origin check, connection I/O, options, outbox
    │       ├── pipeline/         recognition thread, diarization, translation and speech lanes
    │       ├── translation/      router client, busy retry
    │       ├── speech/           shared in-process KorvaTTS, voices
    │       ├── sessions/         opt-in transcript store, capture WAV, context pass
    │       └── config.rs, engine_lock.rs, languages.rs, models.rs, numbers.rs
    ├── onplay-server-cli/        `onplay-server` binary used by server.sh
    ├── onplay-translation/       translation router library
    │   └── src/
    │       ├── engine.rs         lanes, routing, cache lookup, fallback, line/placeholder translation
    │       ├── request.rs        /translate body validation
    │       ├── config.rs         config/translation.yaml and its rules
    │       ├── models/           CTranslate2 OPUS and NLLB, candle Qwen3, lazy loading
    │       ├── text/             normalization, Python whitespace rules, placeholders
    │       ├── detect.rs         Lingua source detection
    │       └── cache.rs, metrics.rs, language.rs, error.rs
    ├── onplay-translation-cli/   `onplay-translation` binary (HTTP on 127.0.0.1:5002)
    ├── onplay-translation-bench/ router benchmark library
    │   └── src/
    │       ├── corpus.rs         unique segments from sessions/*.jsonl
    │       ├── router.rs         router child process, killed with its group
    │       ├── client.rs         /health and /translate over HTTP
    │       ├── run.rs, memory.rs per-run requests, process-tree RSS sampling
    │       ├── report.rs, stats.rs, compare.rs
    │       └── events.rs, error.rs
    └── onplay-translation-bench-cli/ `onplay-translation-bench` binary
```

Verification run on this Mac:

- `cargo test` in `rust/`: unit tests plus `tests/text_parity.rs`, which compares
  normalization and chunking with a fixture produced once from Python korvatts 0.1.3
  (`tests/fixtures/text_parity.json`; its generator script was removed).
- A waveform comparison with Python KorvaTTS (same text, voice and replayed noise on
  CPU; the script has since been removed): Vietnamese and English outputs had equal
  length, max absolute difference 0.00015 and correlation 1.000000.
- One 9.3-second Vietnamese sentence at 8 steps took about 2.4 s end to end in Rust,
  similar to Python (1.3 s load + 1.0 s synthesis).

Not done yet: `onplay-dub` runs STT through the `onplay-speakers` binary as a
subprocess. The translation router is Rust; its comparison with the former Python
router is in [docs/translation-benchmark.md](docs/translation-benchmark.md).

## Kiểm tra

```sh
./scripts/dev/test.sh
```

`test.sh` builds, then runs every Rust test including the real-model ones:
`onplay-stt/tests/session.rs`, `onplay-stt-cli/tests/cli.rs` (CLI tools) and
`onplay-server-cli/tests/socket.rs`, which starts a fresh `build/onplay-server`
(port 8766 must be free). Test gồm inference thật Anh/Việt và chuyển giữa hai câu, lifecycle/silence/PCM validation, WAV lớn/truncated (test Rust) và streaming qua stdin/socket. Test socket xác minh nhận partial khi nguồn PCM vẫn mở, trước end-of-stream. Chưa đo WER/CER, RAM/pin/nhiệt trên Android/iOS hoặc video dài.

## Model và nguồn

Model `sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10` hỗ trợ Arabic, English, Indonesian, Japanese, Russian, Thai, Vietnamese, Chinese; chưa có tiếng Hàn. Tự giải mã không cần chọn ngôn ngữ, chưa trả language/confidence; the optional diarization pass adds session-local speaker labels. Chưa đánh giá đổi ngôn ngữ giữa câu hoặc nói chồng lấn.

- [sherpa-onnx runtime/C API](https://github.com/k2-fsa/sherpa-onnx/tree/v2.0.1)
- [Model/WAV release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models)
- [PengChengStarling](https://github.com/yangb05/PengChengStarling)

Trọng số và runtime là thành phần bên thứ ba; giữ các license đi kèm khi phân phối SDK.

## Experimental speaker diarization

```sh
./scripts/setup/speakers.sh
./scripts/dev/build.sh
# Starts its own build/onplay-server; stop any running server first.
cargo test --release --manifest-path rust/Cargo.toml -p onplay-server-cli -- --include-ignored
```

Send `speakers: true` (or `"live"`) in the session options. Streaming partials and
finals then include provisional session-local speaker labels. The CAM++ embedding
model compares the most recent four seconds, refreshing after roughly two seconds of
audio with changing recognized text; until enough audio is available the label is 0.
This bounded live path also runs on recordings longer than 10 minutes. Its labels are
approximate: one ASR utterance may contain multiple voices, and rapid or overlapping
turns can be misassigned. The server checks the pinned embedding checksum before
enabling it. With `speakers: true`, after the input ends the server runs local
offline segmentation and speaker clustering on the received audio, then transcribes
each detected turn with the existing STT model, and sends a `speakers` event with
timestamps and labels starting at 1. Neither pass is guaranteed to have better word
accuracy. The processing time and RTF in `done` include the extra pass.

The offline pass keeps the received PCM in server memory (at most 10 minutes, about
19.2 MB, plus model and inference memory); longer recordings skip only the offline
pass and retain live labels. Missing or invalid embedding models fall back to
ordinary text with a warning. Failed offline diarization retains the streaming
transcript. Closing the connection during the extra pass stops it after the current
turn. Labels do not identify real names and reset every session. Short turns, noise
and simultaneous voices can produce incorrect labels or words.

Model assets remain ignored under `models/speakers/`. The setup script pins SHA256:

| Asset | SHA256 | Upstream license |
|---|---|---|
| [Pyannote segmentation 3.0 archive](https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2) | `24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488` | MIT, included in archive |
| [3D-Speaker CAM++ embedding](https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx) | `f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11` | [Apache-2.0 project license](https://github.com/modelscope/3D-Speaker/blob/main/LICENSE) |

CLI live labels: `./scripts/run.sh models/vi.wav --speaker-model=models/speakers/embedding.onnx`.
`EngineConfig.speaker_model` enables this path; `Transcript.speaker` is zero when
unknown/disabled, otherwise a provisional positive label. CLI output is unchanged
unless `--speaker-model` is supplied.

The runtime uses the archive's `model.int8.onnx` segmentation file and CAM++ with
CPU inference, automatic speaker count and clustering threshold 0.5. `onplay-server-cli/tests/socket.rs`
uses real model inference on Vietnamese/English/Vietnamese samples and checks two
distinct labels and label reuse, live-only mode, silence, disabled mode, cancellation
and the native duration bound. These synthetic checks are not a diarization
error-rate or Vietnamese conversation benchmark.

## Low-latency live translation

WebSocket sessions with a translation target enable low-latency endpointing on the
streaming recognizer: finalize after 0.45 seconds of trailing silence after speech,
usually a pause between sentences, or after approximately twelve seconds of
utterance audio. Recognition-only sessions keep
the existing 20-second / 1.2-second settings. Short final segments are sent to the
translation worker immediately. The model's decoding/lookahead, translation,
network, synthesis and playback queue add latency; these thresholds are not an
end-to-end latency guarantee. Speech without pauses can still reach the
twelve-second limit and be split mid-sentence, which reduces translation quality.
On one synthesized 30-second Vietnamese paragraph, the previous 6-second / 0.7-second
settings cut every sentence mid-clause, while these settings matched sentence
boundaries except for one leading word; real recordings have not been evaluated. No partial hypotheses are spoken.

## Live translation

Translation is optional: send `target_language` in the session options, or omit it
(or send `""`) for recognition only. Example:

```json
{"threads":2,"chunk":100,"speakers":true,"target_language":"vi"}
```

The server sends `final` events immediately and translates their text on a separate
worker, then sends `translation` events over the same socket; recognition continues
while translation is pending. The newest partial is also translated (`revision:
"provisional"`) while the fast lane is idle. Each `final` has an integer `id`
starting at zero; translations carry the same `id` and a `revision`. Speaker
refinement sends a `speakers` list with its own zero-based IDs, translated with
`revision: "refined"`; `translation_mode: "context"` adds `revision: "context"`
retranslations with previous sentences as context. Consumers must match both
revision and ID. `translation_error` reports provider errors or a full queue (64
pending segments); recognition continues and translation stops for that session.
`done` is sent after pending translations finish or are skipped following an error.
Closing the connection discards queued work; an already running router request can
take up to its 60-second timeout to end. Each translation accepts up to 10,000
characters. Details: [docs/streaming-audio.md](docs/streaming-audio.md).

### Local translation router

The default translation endpoint is the local router at `127.0.0.1:5002`.
It normalizes NFC Unicode/whitespace while preserving line boundaries, resolves
source metadata (or detects language using Lingua), selects a configured model,
validates output/placeholders, and keeps a bounded in-memory LRU cache.
The router supports twelve ISO 639-1 languages (`vi en zh ja ko th id ru ar fr de es`), rather than
exposing every language the underlying NLLB model supports.

`config/translation.yaml` now defaults to native INT8 CTranslate2 NLLB.
The six directed pairs among English, Vietnamese and Chinese route to native
INT8 OPUS (`opus-fast`), using English as a pivot when needed, with `nllb-fast`
as fallback. These are latency-oriented routes over existing model weights,
not claims that OPUS won a semantic quality benchmark. Qwen3-1.7B remains an
explicit slow context mode and falls back to native NLLB.

Live finals always use `standard` mode; `context` mode only adds the separate
refinement lane and the stored-session context pass. This keeps Qwen outside the
live translation path; Qwen cannot meet the 500 ms target on this setup.

Download the model assets, then convert NLLB to CTranslate2 INT8 once. The
conversion is the only step that still uses Python (`ct2-transformers-converter`
from the `ctranslate2` package in `.deps/python`); the router itself does not:

```sh
./scripts/setup/translation.sh
./scripts/setup/python.sh
.deps/python/bin/ct2-transformers-converter --model models/translation/nllb-200 --output_dir models/translation/nllb-200-ct2 --quantization int8
```

Run conversion once for a new output directory. `setup/translation.sh` verifies
the SHA-256 of every downloaded Hugging Face file, Argos package and installed OPUS
weight. Assets stay under
ignored `models/` and `.deps/` paths. Native inference does not require Docker.

`./scripts/dev/server.sh` starts the router itself when nothing listens on 5002
(`ONPLAY_TRANSLATION=0` skips it). To run it on its own:

```sh
./scripts/dev/translation.sh
```

The router is `rust/crates/onplay-translation` (library) and
`rust/crates/onplay-translation-cli` (`onplay-translation` binary). It links
CTranslate2 and SentencePiece, which `./scripts/dev/build.sh` compiles from source
with the pinned CMake from `./scripts/setup/cmake.sh`; Qwen runs on candle.
`ONPLAY_ROOT` sets the checkout and `ONPLAY_TRANSLATION_ADDRESS` the listen address
(default `127.0.0.1:5002`).

The router preloads and warms native models and language detection before opening
port 5002, so first-use loading is outside audio processing. Fast inference uses
CPU INT8 with two threads and greedy decoding. Qwen is loaded lazily only for
explicit context requests, f16 on Metal when available (f32 on the CPU otherwise). The model/tokenizer
worker is serialized; concurrent router requests return 409 instead of accumulating
an unbounded queue. Native outputs must end normally; truncation is rejected.

Translation results are forwarded to clients with source/target, model, latency,
fallback/cache metadata and the server's `queue_ms`, `total_ms` and `within_budget`.

NFC/whitespace normalization preserves line boundaries. Literal placeholders are
held outside inference and stitched back into translated fragments; this may
reduce context around a placeholder. The cache is a bounded in-memory LRU and
includes context and route identity. Context contains at most five previous source
segments. No transcript text is included in aggregate metrics or router logs.

`GET http://127.0.0.1:5002/metrics` reports requests, cache hits, errors, fallback,
model usage, placeholder failures and requests over 500 ms. The client still has
a 60-second transport timeout, which is not a processing-latency promise. A native
generation can continue after its client times out.

The 2026-11-02 run measured 25 uncached requests through the former HTTP API: p50
53.56 ms, p95 283.94 ms, maximum 377.64 ms. That latency check was removed with the
HTTP API. This is coverage of the benchmark inputs, not a hard upper bound for
arbitrary text, host contention or mobile network latency. Long segments can exceed
500 ms; `within_budget` reports the actual result rather than hiding it. Quality still
requires a separate representative human-reviewed evaluation.

**Model limitations:** NLLB uses CC-BY-NC-4.0 and its model card limits intended
use to research, excluding production deployment. This default is for local
experiments, not a production/mobile release recommendation. Qwen3-1.7B is
Apache-2.0 and is an experimental small context model, not a proven improvement.
See [model records](docs/translation-models.md) and the
[NLLB model card](https://huggingface.co/facebook/nllb-200-distilled-600M).

`cargo test -p onplay-translation` checks request validation, normalization,
placeholders, config rules, the cache and the Qwen prompt; it does not validate
translation quality.

## Speech over the socket

Segment speech is opt-in with `speech: true` and a Vietnamese or English target.
See [docs/streaming-audio.md](docs/streaming-audio.md) for per-segment WAV events,
playback ordering and limits.
