# Onplay STT

Offline speech recognition, translation and speech synthesis in Rust on Apple Silicon
macOS. Recognition uses Sherpa ONNX v2.0.1 (C API) with the multilingual streaming
Zipformer INT8 model; translation runs on CTranslate2 and candle; speech uses a Rust
port of KorvaTTS on ONNX Runtime.

The project provides:

- `onplay-server`: a streaming WebSocket server for mobile clients (recognition,
  live translation, optional speech and speaker labels).
- `onplay-translation`: the local translation router used by the server and the
  video pipeline.
- `onplay-stt`, `onplay-recognize`, `onplay-speakers`: command-line recognition tools.
- `onplay-dub`: offline video translation and dubbing.
- `onplay-tts`: a KorvaTTS command-line synthesizer.

Architecture and threading are described in [ARCHITECTURE.md](ARCHITECTURE.md); the
WebSocket protocol is in [docs/streaming-audio.md](docs/streaming-audio.md).

## Quick start

```sh
./scripts/setup/stt.sh          # Sherpa ONNX runtime, STT model, sample WAV (hash-checked)
./scripts/dev/build.sh          # Rust tools, server and router into build/
./scripts/dev/server.sh         # ws://127.0.0.1:8766, also starts the router on 5002
```

Optional assets:

```sh
./scripts/setup/speakers.sh     # speaker diarization models
./scripts/setup/translation.sh  # translation models (then convert NLLB, see below)
```

Requirements: Apple Silicon macOS, `cargo`, libclang (Xcode Command Line Tools) for
bindgen. `build.sh` installs a pinned CMake (`scripts/setup/cmake.sh`) to compile
CTranslate2 and SentencePiece.

## Streaming server

`onplay-server` (`rust/crates/onplay-server`) is the client-facing service. It listens
on the WebSocket `ws://127.0.0.1:8766`; there is no web UI. Dub job results are served
over HTTP on `127.0.0.1:8767` (see Dub jobs). Clients
send PCM16 mono 16 kHz chunks and receive partial and final transcripts as they are
recognized. Up to `ONPLAY_MAX_SESSIONS` recognition sessions (default 4) run at once.

Each session can additionally request:

- translation of finals on separate workers through the local router;
- per-segment speech synthesized in process with the Rust KorvaTTS port;
- provisional CAM++ live speaker labels and, for recordings up to 10 minutes, an
  end-of-input refinement with Pyannote segmentation, CAM++ clustering and per-turn STT.

STT and TTS run inside the server process; Sherpa ONNX and KorvaTTS share the ONNX
Runtime dylib in `build/lib/`. KorvaTTS assets come from the Hugging Face cache
(`models--dogenthq--KorvaTTS`); if they or the runtime are missing, the server starts
without speech and sessions with `speech: true` receive `audio_error`.

`scripts/dev/server.sh` refuses to start when port 8766 is busy and starts
`build/onplay-translation` when nothing listens on 5002 (`ONPLAY_TRANSLATION=0` skips
it); the router stops with the server.

### Settings

| Variable | Default | Meaning |
|---|---|---|
| `ONPLAY_PUBLIC_ORIGIN` | none | Comma-separated extra allowed `Origin` values; `http://127.0.0.1:8765` is always allowed |
| `ONPLAY_TRANSLATE_URL` | `http://127.0.0.1:5002` | Translation router |
| `ONPLAY_SESSION_DIR` | `sessions/` | Stored sessions |
| `ONPLAY_CAPTURE_AUDIO` | `0` | For every session: `0` nothing, `1` transcript and PCM, `2` transcript, `3` PCM; other values stop the server |
| `ONPLAY_MAX_SESSIONS` | `4` | Recognition sessions at once; extra connections get an `error` event. Each busy session may load its own streaming engine. Non-positive values stop the server |
| `ONPLAY_LOG` | off | `1` prints one UTC-timestamped stdout line when a connection is refused, opens, starts and ends |
| `ONPLAY_DEBUG` | off | `1` also prints every WebSocket frame and pipeline step timings |
| `ONPLAY_ROOT`, `ORT_DYLIB_PATH` | derived from `build/` | Checkout root and ONNX Runtime dylib |

`ONPLAY_LOG` lines contain the client address, `Origin`, `User-Agent`, languages,
engine slot, active sessions, outcome, audio seconds and segment count.
`ONPLAY_DEBUG` adds each frame's time since the connection opened, size and JSON
fields, and the queued, engine, finish, diarization and drain timings. Recognized and
translated text appear only as character counts; neither setting prints audio or text.

### Public HTTPS origin

Behind the `stt.qiverse.top` tunnel, which routes the whole hostname to
`127.0.0.1:8766`, the public origin must be allowed explicitly on every start;
without it, connections that send that origin get 403:

```sh
./scripts/prod/server.sh        # dev/server.sh with ONPLAY_PUBLIC_ORIGIN=https://stt.qiverse.top
```

The server ignores the request path. Do not use an unrestricted origin policy;
clients must send an allowed `Origin` header. With `ONPLAY_LOG=1` the client address
is the tunnel's `CF-Connecting-IP` header (else the first `X-Forwarded-For` address,
else the TCP peer). These headers and `User-Agent` are logged as sent and are not
verified; the protocol carries no device name.

### Dub jobs

The same socket accepts dub jobs for the iOS app: the app sends a video link and gets
back an HLS stream with dubbed audio and WebVTT subtitles. The protocol, error codes and
HTTP downloads are specified in
[docs/video-dubbing-design.md](docs/video-dubbing-design.md) (phase 1). Dubbing is off
until tester tokens exist:

```sh
./scripts/prod/dub-token.sh alice   # prints alice's token once; stores only its hash
```

The running server rereads `dub-tokens` when it changes, so adding a token or deleting
a tester's line (revoking it) takes effect on their next request without a restart;
an edit that does not parse keeps the previous tokens. Only the first token file needs a
restart, since dubbing is off when the server starts without one; the start line says
whether dub jobs are on and why not. Revoking a token stops that tester's new commands
and SRT downloads; their jobs already queued or running continue, and their HLS links
work until they expire. Jobs need `build/onplay-dub`, ffmpeg, ffprobe, yt-dlp and the
translation router. They run one at a time; `ONPLAY_JOB_DIR` (default `jobs/`),
`ONPLAY_DUB_MAX_SECONDS` (default 900) and `ONPLAY_JOB_MAX_GB` (default 20) set where
files go, the longest video and the disk limit. Files are deleted 24 h after a job
ends and at every start. For the public service the tunnel must also send
`stt.qiverse.top` paths matching `^/(jobs|auth|translate)` to `http://127.0.0.1:8767`,
before its catch-all rule for 8766.

The same port translates subtitle lines for a signed-in editor: `POST /translate` with
`Authorization: Bearer <token>` and `{"source":"auto","target":"vi","texts":[..],
"context":[..]}` returns `{"translations":[{"text":".."} | {"error":".."}]}` in order.
At most 50 lines per request; each goes to the router in `context` mode with the five
previous lines (`context` first), so send long subtitles in order, a batch at a time.

## Live translation

Translation is optional: send `target_language` in the session options, or omit it
(or send `""`) for recognition only:

```json
{"threads":2,"chunk":100,"speakers":true,"target_language":"vi"}
```

The server sends `final` events immediately and translates their text on a separate
worker, then sends `translation` events over the same socket; recognition continues
while translation is pending. Each `final` has an integer `id` starting at zero, and
translations carry the same `id` and a `revision`:

- `provisional`: the newest partial, translated while the fast lane is idle;
- `live`: the final;
- `refined`: speaker-refinement segments, which use their own zero-based IDs;
- `context`: with `translation_mode: "context"`, a retranslation with previous
  sentences as context.

Consumers must match both revision and ID. `translation_error` reports provider
errors or a full queue (64 pending segments); recognition continues and translation
stops for that session. `done` is sent after pending translations finish or are
skipped following an error. Closing the connection discards queued work; a router
request already running can take up to its 60-second timeout to end. Each translation
accepts up to 10,000 characters.

### Endpointing

Sessions with a translation target use low-latency endpointing: a final after 0.45 s
of trailing silence after speech, usually a pause between sentences, or after about
12 s of utterance audio. Recognition-only sessions keep 1.2 s / 20 s. Speech without
pauses can still reach the 12-second limit and be split mid-sentence, which reduces
translation quality. On one synthesized 30-second Vietnamese paragraph, the earlier
0.7 s / 6 s settings cut every sentence mid-clause, while these settings matched
sentence boundaries except for one leading word; real recordings have not been
evaluated. Decoding lookahead, translation, network, synthesis and playback add
latency; these thresholds are not an end-to-end latency guarantee. Partial
hypotheses are never spoken.

### Speech

Segment speech is opt-in with `speech: true` and a Vietnamese or English target. See
[docs/streaming-audio.md](docs/streaming-audio.md) for per-segment WAV events,
playback ordering and limits.

## Translation router

`onplay-translation` (`rust/crates/onplay-translation`, binary in
`build/onplay-translation`) serves HTTP on `127.0.0.1:5002` for the server and
`onplay-dub`. It normalizes NFC Unicode and whitespace while preserving line
boundaries, resolves source metadata (or detects the language with Lingua), selects a
configured model, validates output and placeholders, and keeps a bounded in-memory
LRU cache. It supports twelve ISO 639-1 languages (`vi en zh ja ko th id ru ar fr de
es`), not every language the underlying NLLB model supports.

```sh
./scripts/dev/translation.sh    # run the router on its own
```

`ONPLAY_ROOT` sets the checkout and `ONPLAY_TRANSLATION_ADDRESS` the listen address.

### Models and routes

`config/translation.yaml` defaults to native INT8 CTranslate2 NLLB. The six directed
pairs among English, Vietnamese and Chinese route to native INT8 OPUS (`opus-fast`),
pivoting through English when needed, with `nllb-fast` as fallback. These are
latency-oriented routes over existing weights, not claims that OPUS won a quality
benchmark. Qwen3-1.7B is an explicit slow context mode and falls back to NLLB.

Live finals always use `standard` mode; `context` mode only adds the separate
refinement lane and the stored-session context pass, which keeps Qwen out of the
live path (it cannot meet the 500 ms target on this setup).

Download the assets, then convert NLLB to CTranslate2 INT8 once. The conversion is
the only step that uses Python (`ct2-transformers-converter` in `.deps/python`); the
router does not:

```sh
./scripts/setup/translation.sh
./scripts/setup/python.sh
.deps/python/bin/ct2-transformers-converter --model models/translation/nllb-200 --output_dir models/translation/nllb-200-ct2 --quantization int8
```

`setup/translation.sh` verifies the SHA-256 of every downloaded Hugging Face file,
Argos package and installed OPUS weight. Assets stay under the ignored `models/` and
`.deps/` paths. No Docker is needed.

### Runtime behavior

The router preloads and warms native models and language detection before opening
port 5002. Fast inference uses CPU INT8 with two threads and greedy decoding. Qwen is
loaded lazily for explicit context requests, f16 on Metal when available (f32 on the
CPU otherwise). Each mode lane and model is serialized; concurrent requests get 409
instead of an unbounded queue. Native outputs must end normally; truncation is
rejected. Literal placeholders are held outside inference and stitched back into
translated fragments, which may reduce context around a placeholder. The cache key
includes context and route; context holds at most five previous source segments.

The server forwards source/target, model, latency, fallback and cache metadata plus
its own `queue_ms`, `total_ms` and `within_budget`. `GET /metrics` reports requests,
cache hits, errors, fallback, model usage, placeholder failures and requests over
500 ms. No transcript text appears in metrics or router logs.

The 2026-11-02 run measured 25 uncached requests through the former HTTP API: p50
53.56 ms, p95 283.94 ms, maximum 377.64 ms. That check was removed with the HTTP API;
it covers the benchmark inputs, not arbitrary text, host contention or mobile
networks. Long segments can exceed 500 ms, which `within_budget` reports. The
comparison with the former Python router is in
[docs/translation-benchmark.md](docs/translation-benchmark.md); translation quality
still needs a representative human-reviewed evaluation.

**Model limitations:** NLLB is CC-BY-NC-4.0 and its model card limits intended use to
research, excluding production deployment. This default is for local experiments,
not a production or mobile release. Qwen3-1.7B is Apache-2.0 and an experimental
context model, not a proven improvement. See [model records](docs/translation-models.md)
and the [NLLB model card](https://huggingface.co/facebook/nllb-200-distilled-600M).

## Speaker diarization (experimental)

Send `speakers: true` (or `"live"`) in the session options. Partials and finals then
carry provisional session-local speaker labels: the CAM++ embedding model compares
the most recent four seconds, refreshing after about two seconds of audio with
changing recognized text; until enough audio is available the label is 0. This live
path also runs on recordings longer than 10 minutes. One ASR utterance may contain
several voices, and rapid or overlapping turns can be misassigned.

With `speakers: true`, after the input ends the server runs offline segmentation and
clustering on the received audio, transcribes each detected turn with the same STT
model and sends a `speakers` event with timestamps and labels starting at 1. Neither
pass is guaranteed to improve word accuracy; the processing time and RTF in `done`
include the extra pass. `"live"` skips it.

The offline pass keeps the received PCM in memory (at most 10 minutes, about
19.2 MB, plus model and inference memory); longer recordings skip only that pass.
Missing or invalid embedding models fall back to plain text with a warning, and a
failed offline pass keeps the streaming transcript. Closing the connection stops the
pass after the current turn. Labels are not names and reset every session.

The server checks the pinned embedding checksum before enabling speakers. The
runtime uses the archive's `model.int8.onnx` segmentation file and CAM++ on the CPU,
with automatic speaker count and clustering threshold 0.5. Assets stay ignored under
`models/speakers/`; `scripts/setup/speakers.sh` pins:

| Asset | SHA256 | Upstream license |
|---|---|---|
| [Pyannote segmentation 3.0 archive](https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2) | `24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488` | MIT, included in archive |
| [3D-Speaker CAM++ embedding](https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx) | `f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11` | [Apache-2.0 project license](https://github.com/modelscope/3D-Speaker/blob/main/LICENSE) |

`onplay-server-cli/tests/socket.rs` runs real inference on Vietnamese/English/
Vietnamese samples and checks two distinct labels, label reuse, live-only mode,
silence, disabled mode, cancellation and the duration bound. These synthetic checks
are not a diarization error-rate or Vietnamese conversation benchmark.

## Command-line recognition

```sh
./scripts/tools/recognize.sh models/vi.wav
./scripts/tools/recognize.sh models/vi.wav --realtime --threads=2 --chunk-ms=100
./scripts/tools/recognize.sh models/vi.wav --speaker-model=models/speakers/embedding.onnx
```

`recognize.sh` runs `build/onplay-stt` with the baseline model (default input: the
model's English test WAV). It reads mono PCM16 16 kHz WAV, plain or
`WAVE_FORMAT_EXTENSIBLE`, incrementally; `-` instead of a file reads raw little-endian
PCM16 16 kHz from stdin. It runs as fast as possible; `--realtime` paces a WAV file
for benchmarks. `--speaker-model` adds live speaker labels; output is unchanged
without it.

The three STT tools keep the command lines, output and pipe protocol of the earlier
C++ tools. On this Mac their stdout was byte-identical to the C++ binaries for WAV and
stdin streaming (with and without live speaker labels and low latency), offline
diarization of a 3:50 clip, the `onplay-recognize` pipe protocol and eight error
cases. The server does not use these tools; it calls the `onplay-stt` library in
process.

### Library contract

`onplay-stt` (`Engine`/`Session`, `accept_audio()`/`finish()`) accepts mono 16 kHz
float PCM with finite values in `[-1, 1]`, at most one second per call. It is
synchronous and must be driven from one worker thread; listeners must not re-enter
the engine, and inference must not run on a UI or audio callback. `finish()` is
idempotent and closes input; start a new session afterwards. `EngineConfig.speaker_model`
enables live labels; `Transcript.speaker` is 0 when unknown or disabled.

Not available: AAR/XCFramework packaging, microphone capture, training, or language
identification that returns a language code.

## Video translation (offline)

`scripts/tools/dub-video.sh` runs the `onplay-dub` pipeline: diarized STT,
per-segment translation, SRT files and dubbed speech muxed back into an MP4. The
router must be running; no Python is needed.

```sh
./scripts/dev/translation.sh    # in another terminal
./scripts/tools/dub-video.sh input.mp4 --target vi
./scripts/tools/dub-video.sh input.mp4 --target fr --subtitles-only
./scripts/tools/dub-video.sh 'https://www.youtube.com/watch?v=...' --target vi   # needs yt-dlp
```

Outputs, next to the input or at `--output path.mp4`: `<name>.<target>.mp4` with a
soft `mov_text` subtitle track, `<name>.<target>.srt` and `<name>.original.srt`. Each
translated segment gets a per-speaker KorvaTTS voice. Speed is chosen from the
predicted duration (at most 2x) so speech fits before the next segment, and the dub
is mixed over the original audio, lowered to `--background` (default 0.15) only while
dubbed speech plays. Segments already in the target language keep their original
audio. All-capital STT text is sentence-cased before translation, because the
translation models garble capitals.

Stages overlap: segments are translated as `onplay-speakers` prints them and queued
to `--tts-workers` in-process synthesizers (default 3, `--tts-threads` 2 each) while
recognition continues. On an Apple M4 (4 performance + 6 efficiency cores), a 3:50
two-speaker English clip dubbed to Vietnamese took 78.6 s with sequential stages and
54-58 s with this pipeline. Diarization was fastest at 4 threads (26 s versus 35-52 s
at 6-10), so `--threads` stays at 4. The earlier Python orchestration of the same
pipeline ran equally fast with identical SRT output.

The library reports progress only through an optional `onplay_dub::Observer`; it does
not log. The CLI prints events only with `--progress` (JSON lines, used by server dub
jobs), and `--hls DIR` also packages the result as HLS with WebVTT subtitles. STT still runs through the
`onplay-speakers` binary as a subprocess.

Limits: speech output supports Vietnamese and English only; other targets need
`--subtitles-only`. Audio is diarized in 590-second pieces, so speaker voices may
change between pieces. Subtitles are not burned in (the Homebrew ffmpeg checked here
has no libass). Only use videos you have the right to translate.

## Speech synthesis

`onplay-tts` reimplements KorvaTTS 0.1.3 inference. It reads the published assets
(`~/.cache/huggingface/hub/models--dogenthq--KorvaTTS`, Apache-2.0) without modifying
them and loads the ONNX Runtime dylib from the Sherpa ONNX SDK in `.deps/` (`ort` with
`load-dynamic`).

```sh
./scripts/tools/tts.sh --text 'Xin chào' --voice khanh_vy --steps 8 --output out.wav
./scripts/tools/tts.sh --list-voices
echo '{"text":"Xin chào","lang":"vi","voice":"khanh_vy","output":"/tmp/a.wav","max_seconds":1.0}' \
  | ./scripts/tools/tts.sh --batch --steps 8
```

`--batch` keeps the models loaded and answers one JSON line per request.
`max_seconds` raises the speed, up to 2x, so the predicted speech fits, without a
second synthesis pass.

`--device` accepts `cpu` (default), `coreml`, `coreml-gpu` and `coreml-ane`. On the
M4, five Vietnamese sentences (24.7 s of audio, 8 steps) took 3.6 s on the CPU with
default threads (RTF 0.147), 3.9-5.8 s with 2, 4 or 10 threads, and 4.4-6.8 s with
CoreML after 4-13 s of model compilation, because CoreML accepts only part of each
graph. The MLProgram CoreML format failed to build these graphs.

Parity with Python KorvaTTS was checked once: text normalization and chunking against
a fixture (`onplay-tts/tests/fixtures/text_parity.json`), and waveforms for the same
text, voice and replayed noise on the CPU (equal length, maximum absolute difference
0.00015, correlation 1.000000; the comparison script has since been removed). One
9.3-second Vietnamese sentence at 8 steps took about 2.4 s end to end, similar to
Python.

## Packaging

```sh
./scripts/prod/package.sh
```

Creates `dist/onplay-native-macos-arm64.tar.gz` and its `.sha256`, containing
`onplay-stt`, `onplay-speakers`, `onplay-recognize`, `onplay-server`,
`onplay-translation` and `lib/` (Sherpa ONNX, ONNX Runtime). Binaries find the dylibs
through `@loader_path/lib`, so they run from any directory; `onplay-server` outside
the checkout needs `ONPLAY_ROOT` pointing at a directory with `models/`. The archive
is not signed with a Developer ID or notarized and contains no models or Python.

## Workspace layout

```
rust/
├── Cargo.toml                    workspace, shared version, dependency versions and lints
└── crates/
    ├── onplay-sherpa-sys/        bindgen bindings to the pinned Sherpa ONNX c-api.h
    ├── onplay-sherpa/            safe owned handles; the only crate allowed `unsafe`
    ├── onplay-stt/               Engine/Session, WAV reader, utterance recognition, diarization
    ├── onplay-stt-cli/           onplay-stt, onplay-recognize, onplay-speakers
    ├── onplay-server/            streaming WebSocket server library
    │   └── src/
    │       ├── socket/           listener, origin check, client options, connection I/O, outbox
    │       ├── pipeline/         recognition workers, diarization, translation and speech lanes
    │       ├── translation/      router client, busy retry
    │       ├── speech/           shared in-process KorvaTTS, voices
    │       ├── sessions/         opt-in transcript store, capture WAV, context pass
    │       ├── engine_slots.rs   concurrent session limit
    │       ├── events.rs, trace.rs  server events for logging and debug output
    │       └── config.rs, languages.rs, models.rs, numbers.rs
    ├── onplay-server-cli/        onplay-server binary and its stdout log
    ├── onplay-translation/       translation router library
    │   └── src/
    │       ├── engine.rs         lanes, routing, cache lookup, fallback, line/placeholder translation
    │       ├── request.rs        /translate body validation
    │       ├── config.rs         config/translation.yaml and its rules
    │       ├── models/           CTranslate2 OPUS and NLLB, candle Qwen3, lazy loading
    │       ├── text/             normalization, whitespace rules, placeholders
    │       ├── detect.rs         Lingua source detection
    │       └── cache.rs, metrics.rs, language.rs, error.rs
    ├── onplay-translation-cli/   onplay-translation binary (HTTP on 127.0.0.1:5002)
    ├── onplay-translation-bench/ router benchmark: corpus, child router, latency, memory, comparison
    ├── onplay-translation-bench-cli/ onplay-translation-bench binary
    ├── onplay-tts/               KorvaTTS library
    │   ├── src/
    │   │   ├── assets/           asset directory, tts.json config, JSON reading
    │   │   ├── text/             normalization, chunking, language tags, indexer
    │   │   ├── runtime/          ONNX Runtime init, device selection, typed graph calls
    │   │   ├── synth/            pipeline, duration fitting, initial latent, noise
    │   │   ├── audio/            tail cleanup, PCM16 WAV output
    │   │   └── tensor.rs, voice.rs, error.rs
    │   └── tests/                text parity fixture and test
    ├── onplay-tts-cli/           onplay-tts binary (args, batch mode, noise replay)
    ├── onplay-dub/               video translation library
    │   └── src/
    │       ├── pipeline.rs       options, stage overlap, output naming
    │       ├── fetch.rs          local file or yt-dlp download
    │       ├── media.rs          ffmpeg extract, cut, duck-and-mix mux
    │       ├── recognize.rs      streamed onplay-speakers segments
    │       ├── translate.rs      router client, sentence casing
    │       ├── speech.rs         parallel in-process synthesizers
    │       ├── timeline.rs       clip placement on the dub track
    │       ├── events.rs         optional observer, no logging
    │       ├── project.rs        checkout paths, embedding checksum
    │       ├── workdir.rs        temp directory removed on drop
    │       └── srt.rs, voices.rs, segment.rs, command.rs, error.rs
    └── onplay-dub-cli/           onplay-dub binary used by dub-video.sh
```

Scripts are grouped by purpose: `scripts/setup/` (downloads, all hash-checked),
`scripts/dev/` (build, run, test), `scripts/prod/` (public server, packaging) and
`scripts/tools/` (recognition, TTS, dubbing, router benchmark).

## Tests

```sh
./scripts/dev/test.sh
```

`test.sh` builds, then runs every Rust test including the real-model ones marked
`#[ignore]`: `onplay-stt/tests/session.rs` (lifecycle, silence, PCM validation,
finalization, low latency, live speakers), `onplay-stt-cli/tests/cli.rs` (English and
Vietnamese inference, switching between sentences, large and truncated WAVs, stdin
streaming, `onplay-speakers`) and `onplay-server-cli/tests/socket.rs`, which starts a
fresh `build/onplay-server` (ports 8766 and 8767 must be free) and checks, among
others, that partials arrive while the PCM source is still open, and runs a dub job on
a generated clip end to end when the router is running. `cargo test -p onplay-translation`
checks request validation, normalization, placeholders, config rules, the cache and
the Qwen prompt, not translation quality.

Not measured: WER/CER, RAM, battery or thermals on Android/iOS, or long videos.

## Model and sources

The model `sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10` covers
Arabic, English, Indonesian, Japanese, Russian, Thai, Vietnamese and Chinese; Korean
is not included. It decodes without a language choice and returns no language or
confidence. Language switches within a sentence and overlapping speech have not been
evaluated.

- [sherpa-onnx runtime/C API](https://github.com/k2-fsa/sherpa-onnx/tree/v2.0.1)
- [Model/WAV release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models)
- [PengChengStarling](https://github.com/yangb05/PengChengStarling)

Weights and runtimes are third-party components; keep their licenses when
redistributing. The project itself has no license file yet.
