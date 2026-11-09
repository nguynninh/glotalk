# Architecture

## Components

| Path | Responsibility |
|---|---|
| `rust/crates/onplay-sherpa-sys` | bindgen bindings to the pinned Sherpa ONNX v2.0.1 `c-api.h`; links the C API dylib |
| `rust/crates/onplay-sherpa` | Owned wrappers over recognizer, stream, speaker and diarization handles; the only safe wrapper allowed `unsafe` |
| `rust/crates/onplay-stt` | `Engine`/`Session` streaming API, WAV reader, utterance recognition, diarization with per-turn STT |
| `rust/crates/onplay-stt-cli` | `onplay-stt` (WAV/stdin CLI, pacing, timing), `onplay-recognize` (resident model, pipe protocol), `onplay-speakers` |
| `rust/crates/onplay-server` | Streaming WebSocket server: origin check, session options, engine slots, in-process recognition, diarization, translation and speech lanes, opt-in session store, server events; dub jobs (`jobs/`, `socket/job.rs`) and their HTTP downloads (`downloads.rs`) |
| `rust/crates/onplay-server-cli` | `onplay-server` binary: resolves the checkout and ONNX Runtime paths, validates settings, preloads speech, prints `ONPLAY_LOG`/`ONPLAY_DEBUG` lines |
| `rust/crates/onplay-translation` | Translation router library: request validation, NFC/whitespace normalization, Lingua detection, per-pair routes and fallback, placeholder-preserving line translation, LRU cache, metrics; OPUS/NLLB on CTranslate2 (INT8 CPU), Qwen3 on candle (Metal) |
| `rust/crates/onplay-translation-cli` | `onplay-translation` binary: HTTP `POST /translate`, `GET /health`, `GET /metrics` on `127.0.0.1:5002` |
| `rust/crates/onplay-translation-bench` | Black-box router benchmark: corpus from recorded sessions, fresh router per run, latency, memory, output comparison |
| `rust/crates/onplay-translation-bench-cli` | `onplay-translation-bench` binary (`corpus`, `run`, `compare`) |
| `rust/crates/onplay-tts` | KorvaTTS 0.1.3 inference on ONNX Runtime (`ort`, `load-dynamic`) |
| `rust/crates/onplay-tts-cli` | `onplay-tts` binary (single text, `--batch`, voices, devices) |
| `rust/crates/onplay-dub` | Offline video translation: fetch and link probing, diarized STT through `onplay-speakers`, translation, parallel speech, SRT, ffmpeg mux, HLS packaging |
| `rust/crates/onplay-dub-cli` | `onplay-dub` binary; `--progress` JSON lines for server jobs |
| `config/translation.yaml` | Registered models, per-pair routes, fallback and context limits |
| `scripts/setup/stt.sh` | macOS ARM64 runtime, C header, STT model and sample WAV, all hash-pinned |
| `scripts/setup/speakers.sh` | Hash-pinned speaker models |
| `scripts/setup/translation.sh` | Hash-pinned translation models (Hugging Face snapshots, Argos OPUS packages) |
| `scripts/setup/cmake.sh` | Pinned CMake in `.deps/cmake`, used to compile CTranslate2 and SentencePiece |
| `scripts/setup/python.sh` | Pinned CPython in `.deps/python`, used only to convert NLLB to CTranslate2 |
| `scripts/dev/build.sh` | cargo build of the STT tools, server, `onplay-dub` and router into `build/`; copies runtime dylibs to `build/lib` (`@loader_path/lib` rpath) |
| `scripts/dev/server.sh` | Runs `build/onplay-server` on `127.0.0.1:8766`, starting the router when port 5002 is free |
| `scripts/dev/translation.sh` | Runs `build/onplay-translation` on `127.0.0.1:5002` |
| `scripts/dev/test.sh` | Build, then all Rust unit and real-model tests |
| `scripts/prod/server.sh` | `dev/server.sh` with the public `stt.qiverse.top` origin |
| `scripts/prod/dub-token.sh` | Creates a dub tester token and appends its hash to `dub-tokens` |
| `scripts/prod/package.sh` | Relocatable macOS ARM64 archive of the native binaries and dylibs in `dist/` |
| `scripts/tools/` | `recognize.sh` (baseline model paths, default English WAV), `tts.sh`, `dub-video.sh`, `translation-bench.sh` |
| `rust/crates/onplay-stt/tests/session.rs` | Input validation, silence, finalization, low-latency and live speaker checks |
| `rust/crates/onplay-stt-cli/tests/cli.rs` | Real-model English, Vietnamese, endpoint and `onplay-speakers` checks |
| `rust/crates/onplay-server-cli/tests/socket.rs` | Real-model socket checks against a fresh `build/onplay-server` |
| `.deps/`, `models/` | Local downloaded dependencies and model assets (ignored) |
| `sessions/`, `build/`, `dist/` | Stored sessions, build output and packages (ignored) |
| `jobs/`, `dub-tokens` | Dub job files and tester token hashes (ignored) |

## Recognition flow

The CLI reads a WAV incrementally (`wave.rs`), creates an `Engine` and `Session`, and
feeds 100 ms chunks. Optional realtime pacing waits for the corresponding input timestamp.
The library accepts mono 16 kHz float PCM, validates values and chunk size, then
feeds and decodes the online stream synchronously.

Recognition uses the CPU provider, greedy search and endpoint detection. The
baseline selected by scripts is the streaming Zipformer model dated 2025-02-10,
with an INT8 encoder and joiner, decoder and tokens file. The model name includes
`ar_en_id_ja_ru_th_vi_zh`; current smoke checks exercise English and Vietnamese,
not the full language set.

Changed non-empty text produces partial callbacks. An endpoint produces a final
callback and resets the stream for the next utterance. `finish()` appends 0.8 s
of synthetic silence for encoder lookahead, marks input finished and decodes the
last result. Empty text is not emitted.

## Ownership and threading

The `Engine` owns the recognizer (and speaker extractor); each `Session` owns a stream
that borrows the engine, so the borrow checker guarantees the stream is destroyed
before its recognizer. Temporary results, embeddings and search strings are copied
and released immediately. Model configuration validates file existence and thread
count (1–16, default 2); creation failures return the same messages as the former
C++ tools.

The public contract requires a single worker thread. Callbacks run synchronously
and must not re-enter the engine. There is no asynchronous queue, cancellation
API or internal locking. `finish()` is idempotent; further audio is rejected.
Each accepted chunk is at most one second, with finite samples in `[-1, 1]`.

## Build and deployment

The shell path targets Apple Silicon macOS with Sherpa ONNX v2.0.1 shared
libraries. `scripts/dev/build.sh` needs `cargo` and libclang (Xcode Command Line Tools) for
bindgen; `SHERPA_ONNX_INCLUDE_DIR` and `SHERPA_ONNX_LIB_DIR` override the `.deps/` paths.
The binaries keep the shared runtime dependency.
Do not assume portability or deployable library paths without a platform check.

Setup scripts verify SHA-256 hashes for every download: the runtime archive, the C
header, the STT model, the Vietnamese sample WAV, speaker models, translation models,
CMake and CPython. Keep downloaded assets outside source ownership and review their
licenses before redistribution.

## Verification

```sh
./scripts/dev/build.sh
./scripts/dev/test.sh
```

The test script uses real model assets for the Rust session tests, the CLI smoke test
and the socket tests. The earlier C++ session test ran under ASan/UBSan; the Rust
port relies on ownership in safe wrappers instead and has no sanitizer run. The checks do not establish broad recognition accuracy, live
microphone behavior, cross-platform compatibility or sustained realtime performance.
Report actual execution results separately from the coverage described here.

## Streaming server

`onplay-server` is the client-facing service: a WebSocket on `127.0.0.1:8766`, with no
web UI. Besides the translation router it calls, the only HTTP it speaks is the dub job
download port (see Dub jobs).
Each accepted connection gets one thread that owns the socket (`socket/transport.rs`): it reads client messages with a short timeout and, in
between, writes events that other threads queued through an `Outbox` channel, so
socket writes never interleave. It pings idle clients every 20 s and drops a client
that does not answer within 20 s, so a vanished mobile client cannot hold the engine.
The handshake requires an `Origin` from the configured list; messages are limited to
32,000 bytes (one second of PCM16).

After the options message, the connection thread takes a free engine slot
(`engine_slots.rs`; `ONPLAY_MAX_SESSIONS`, default 4, sessions at once; a session
beyond the limit gets an `error` event and is not queued) and submits the session's
recognition to that slot's recognition worker (`pipeline/worker.rs`): one long-lived
thread per slot that owns its loaded Sherpa models, so handles never cross threads.
Sherpa handles are neither `Send` nor `Sync`, so concurrent sessions do not share a
recognizer; each worker loads its own.
The job creates a `Session` on a cached `Engine`, feeds PCM in the client's chunk size
(the same decode cadence as the earlier `onplay-stt -` child process), runs the
optional diarization pass and finally sends `done` and closes the connection. The
connection thread hands PCM over a bounded channel (64 chunks); when it is full,
reading pauses while queued events are still written. When the connection ends, the
connection thread cancels the session, drops the channel, waits for the job to finish
and only then releases the engine slot.

Models stay loaded between sessions: loading the streaming model takes about 1.1 s
and the speaker transcriber more, against about 0.2 s to decode a 4 s clip. The worker
keeps the two most recently used streaming engines (low latency and live speaker
labels change the native recognizer) and one speaker transcriber. At startup the first
worker loads the engine for 2 threads with low latency, the translating mobile default;
the lowest free slot is taken first, so other workers load models only while the first
is busy, and keep them afterwards. Memory therefore grows with the peak number of
concurrent sessions; the 2 GB figure below is for one worker. A panicking
job drops the cached models and the worker continues. Measured on an M4 with a 4 s
clip: `ready` after 0.02 s instead of about 1.2 s, end-of-input diarization 0.24 s
instead of 3.6 s; the server's resident memory was about 2 GB with two engines, the
speaker transcriber and KorvaTTS loaded.

KorvaTTS runs in process through `onplay-tts` with one shared synthesizer behind a
mutex; concurrent sessions take turns per sentence. It runs on the CPU
provider: CoreML (all units, GPU or Neural Engine) was 1.1-1.6x slower on an M4. Live speech caps runaway duration predictions
(`cap_runaway_duration`), which otherwise turn short English sentences into 5-7 s of
invented speech. Sherpa ONNX and `ort` load the same `build/lib/libonnxruntime.dylib`. The
translation router is a separate Rust HTTP service (`build/onplay-translation`).

## Dub jobs

A connection whose first message has a `type` is a dub job connection
(`socket/job.rs`); live-session options never have one. The protocol is version 1 of
[docs/video-dubbing-design.md](docs/video-dubbing-design.md) (phase 1: links and
tester tokens). The handshake's `Authorization: Bearer` token is kept apart from the
logged client headers and checked against `dub-tokens`, which holds SHA-256 hashes and
is reread when its modification time or size changes (`jobs/tokens.rs`), so tokens are
added and revoked without a restart.

`jobs/mod.rs` owns every job behind one mutex: a FIFO queue (8 at most, one queued or
running job per token) and one queue thread that runs them one at a time. A job runs
`build/onplay-dub URL --progress --hls <dir>` as a child process in its own process
group, with `TMPDIR` inside the job directory, so cancellation is `TERM` (then `KILL`
after 10 s) to the group and leaves nothing outside `jobs/<id>/`. The child's JSON
event lines become `progress` messages (`jobs/progress.rs`, at most two a second
except on stage changes), sent to every connection subscribed to the job through its
`Outbox`. A connection that drops does not affect the job; the final `job` message
closes every subscriber.

Before queuing, the connection thread checks the router's health and reads the link's
metadata with `yt-dlp` (ffprobe for direct media links) on a helper thread while it
keeps servicing the socket. Links to `localhost`, local names and IP literals are
refused unless `ONPLAY_DUB_LOCAL_LINKS=1` (tests).

A finished job keeps `subtitles.srt`, `original.srt` and `hls/<key>/` (the MP4 and work
files are removed) until 24 h after it ended, `delete`, or the next start, which
removes `j_*` directories in `ONPLAY_JOB_DIR`. `downloads.rs` serves them on
`127.0.0.1:8767` with `tiny_http`, one thread per request: the SRTs need the job's
token, the HLS files only the random key in their path, because players cannot add
headers. Responses carry a strong `ETag`, `Last-Modified`, `Cache-Control: private`
up to expiry and single-range support. Job events in `ONPLAY_LOG` carry the job id,
tester name, video length, state and failure code, never the link or title; debug
frames replace links, titles and paths with their length.

## Optional speaker pass

The client sends `speakers: true` or `"live"` in its options. With `true`, the
connection thread keeps arriving PCM in memory while streaming STT continues. At the
end of input the recognition job finishes streaming STT, then runs the cached
`SpeakerTranscriber` (Pyannote/CAM++ diarization and per-turn STT). Helper recognizers
load the STT model again on every call, so they are used only from 120 s of audio
(on 285 s they saved 4.7 s of 26 s; on 4 s they cost 1.7 s). Helpers run ONNX with one
thread against two for the main recognizer, so helper-recognized turns can differ
slightly (one of 50 turns differed on the 285 s check). Labels are numbered by first
appearance. The streaming API's optional
`EngineConfig.speaker_model` and default-zero `Transcript.speaker` field carry live
labels; callers without it keep ordinary STT behavior.

The offline pass is limited to 600 seconds of PCM; longer input disables only that
pass, with a warning. Speaker results are sent as a separate `speakers` event; the
streaming finals already sent are unchanged and failure only adds a warning. When
the connection closes during the pass, the per-turn callback stops it after the
current turn and the engine slot is released when the recognition job ends.
Diarization itself is not interruptible. No speaker embeddings persist across runs.

### Live provisional labels

With speaker mode enabled and the pinned CAM++ checksum verified, the server passes
the model to the streaming engine. The session retains at most four seconds of PCM
and uses a fresh embedding stream to compare recent speech with its session-local
speaker manager. Labels refresh after approximately two seconds when recognition
text changes and at finalization. An ASR endpoint clears the audio window and
pending label while keeping speaker identities for subsequent utterances. The
10-minute limit applies only to offline refinement; live labeling continues for long
input. A label describes the recent audio window, not precise per-word attribution.

## Live translation revisions

Each session keeps up to three translation lanes, all keyed by the segment ID. A
latest-wins slot translates only the newest partial (`provisional`) and yields to
queued finals. The final queue (64 segments) translates each final on the fast lane
(`live`) and, after diarization, each speaker segment (`refined`). In context mode, a
separate bounded queue sends finals with up to five previous finals to Qwen
(`context`) and skips work older than 15 seconds. Lanes retry for up to 30 s while
the router answers 409. Lanes are not joined on disconnect; an in-flight router
request may finish later and its result is dropped. The router holds one lock per
mode lane and per model, plus a short lock for its cache and counters. Clients keep
the highest revision per segment.

## Optional speech

With `speech: true` and a translation target, live translations feed a separate
bounded TTS lane (four waiting segments). It returns complete segment WAVs, as base64
JSON events or binary frames (`audio_encoding`), keyed by segment ID, without
blocking recognition or translation. Refined translations do not produce speech.
The lane estimates the client's back-to-back playback queue, speeds synthesis up
while it is behind and skips segments that would play more than 4 seconds late or
overflow the queue (`audio_skipped`). Speech errors disable speech for the
connection but preserve text output. `done` waits for all lanes. See
[the protocol](docs/streaming-audio.md) for cancellation limits.

## Stored sessions

With `store: true` (or `ONPLAY_CAPTURE_AUDIO` `1`/`2`), the server appends JSON records to
`sessions/<id>.jsonl` (0600); with `ONPLAY_CAPTURE_AUDIO` `1`/`3` it writes the received
PCM to `<id>.wav`. After
a stored session with a target ends, one background worker translates its finals
again with context and appends the results. There is no API to read stored sessions;
they are files on the server.
