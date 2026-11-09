# Changelog

All notable changes to Onplay STT are recorded here.

Entries are grouped by Added, Changed, Fixed and Removed, in that order. Keep
unreleased work under Unreleased until an actual version is assigned; do not invent
release versions or import another project's release history. The version is set
once for all crates in `rust/Cargo.toml` (`workspace.package.version`).

## [Unreleased]

### Added

- Dubbing speech for the editor on the HTTP port: `POST /speak` with an editor sign-in
  token and `{"language","text","voice","max_seconds"?}` returns one line as
  `audio/wav`, with its length in `X-Speech-Seconds`. The voice is named (one of the
  ten speaker voices) and `max_seconds` speeds speech up, at most 2x, to fit. The
  public tunnel must also send `^/speak` to `127.0.0.1:8767`.
- Subtitle translation for the editor on the HTTP port: `POST /translate` with
  `Authorization: Bearer` (an editor sign-in token) and
  `{"source","target","texts":[..],"context":[..]}` -> `{"translations":[{"text"} |
  {"error"}]}`. Up to 50 lines per request, each sent to the translation router in
  `context` mode with the five previous lines; a failed line does not fail the request.
  The public tunnel must also send `^/translate` to `127.0.0.1:8767`.
- Editor sign-in on the HTTP port (`127.0.0.1:8767`): `POST /auth/login`
  (`{"user","password"}` -> token valid 30 days), `GET /auth/me` and
  `POST /auth/logout` with `Authorization: Bearer`. Accounts are Argon2id hashes in
  `ONPLAY_AUTH_USERS` (default `auth-users`), added with `scripts/prod/auth-user.sh`
  (`onplay-server --hash-password`); sessions are kept in memory, and a name is locked
  for 10 minutes after 5 failures. The port is now opened even when dubbing is off.
- Dub jobs for the iOS app (phase 1 of `docs/video-dubbing-design.md`): a WebSocket
  connection whose first message has a `type` sends `dub`, `job`, `cancel`, `delete`
  or `list` (protocol version 1, structured error codes). A link is dubbed by a
  `build/onplay-dub` child process, one job at a time with a queue of 8 and one active
  job per tester token; jobs outlive the connection and are kept 24 h.
- Results over HTTP on `127.0.0.1:8767`: an HLS stream (`/jobs/<id>/hls/<key>/`,
  VOD fMP4 with WebVTT subtitle renditions, no header needed) and both SRT files
  (bearer token), with `ETag`, `Last-Modified`, `Range` and `If-Range`.
- Tester tokens in `dub-tokens` (`ONPLAY_DUB_TOKENS`), stored as SHA-256 hashes and
  created by `scripts/prod/dub-token.sh` and reread when the file changes, so tokens
  are added and revoked without a restart. Without them dubbing stays off. New settings:
  `ONPLAY_JOB_DIR`, `ONPLAY_DUB_MAX_SECONDS` (default 900), `ONPLAY_JOB_MAX_GB`
  (default 20).
- `onplay-dub --hls DIR` packages the result as HLS; `--progress` prints events and
  the result as JSON lines. Direct media links, for which yt-dlp reports no duration,
  get it from ffprobe.

### Changed

- `scripts/dev/build.sh` also builds `build/onplay-dub`.
- `onplay_dub::Event::Recognized` carries the segment's end time and `Transcribed` the
  number of segments queued for speech; `Outputs` has the most common detected source
  language.

## [1.0.1] - 2026-11-05

### Added

- `onplay-server` serves several recognition sessions at once instead of one.
  `ONPLAY_MAX_SESSIONS` (default 4) sets the limit; extra connections get an `error`
  event, and each busy session may load its own streaming engine.
- `ONPLAY_LOG=1` prints one stdout line per connection event (client address,
  `Origin`, `User-Agent`, languages, engine slot, outcome); `ONPLAY_DEBUG=1` also
  prints every WebSocket frame and pipeline step timings. Neither prints audio or
  recognized text.

### Changed

- The `onplay-server` start line shows the listening address (`ws://127.0.0.1:8766`)
  and labels the allowed `Origin` values, which it used to print as if they were the
  stream address.

## [1.0.0] - 2026-11-05

First version. It ports the earlier, unreleased C++/Python prototype to Rust; entries
compare against that prototype.

### Added

- Rust translation router (crates `onplay-translation`, `onplay-translation-cli`;
  `build/onplay-translation`, started by `scripts/dev/translation.sh` and
  `scripts/dev/server.sh`) replaces the Python router on `127.0.0.1:5002` with the
  same HTTP contract, routes, limits, cache and metrics. OPUS/NLLB run on CTranslate2
  through `ct2rs`, Qwen3 context translation on candle (Metal). On the recorded corpus
  all 803 outputs match the Python router; startup fell from 3.5 s to 1.4 s, latency
  and memory are unchanged (`docs/translation-benchmark.md`). Running the server no
  longer needs Python; converting NLLB to CTranslate2 still does.
  `scripts/setup/cmake.sh` pins the CMake that compiles CTranslate2 and SentencePiece.
- `onplay-translation-bench` (`scripts/tools/translation-bench.sh`): black-box
  translation router benchmark on recorded sessions that starts the router fresh per
  run and reports startup, memory, latency and output differences between routers.
- Rust integration tests replace the Python test clients: `onplay-stt-cli/tests/cli.rs`
  (formerly `tests/smoke.py` and the CLI cases of `tests/speakers.py`) and
  `onplay-server-cli/tests/socket.rs` (formerly `tests/live_stream.py` and
  `tests/speakers.py`), which starts its own `build/onplay-server`.
  `scripts/dev/test.sh` now only builds and runs `cargo test`; the `websockets`
  Python dependency is gone.
- `scripts/setup/translation.sh` replaces `setup/translation.py` and
  `setup/fast-translation.py` without Python, pinning the SHA-256 of every
  Hugging Face file and of the Argos OPUS packages (previously only the extracted
  weights were checked).
- Rust `onplay-server` (crates `onplay-server`, `onplay-server-cli`), started by
  `scripts/dev/server.sh`, replaces the Python WebSocket server on `127.0.0.1:8766`
  with the same protocol. STT and KorvaTTS run in process instead of through
  `onplay-stt` child processes and Python KorvaTTS. Idle connections are pinged and dropped after 20 s without a pong.
  Recognition models stay loaded between sessions on one worker thread, and
  end-of-input diarization skips helper recognizers below 120 s of audio.
- Rust `onplay-stt`, `onplay-recognize` and `onplay-speakers` (crates
  `onplay-sherpa-sys`, `onplay-sherpa`, `onplay-stt`, `onplay-stt-cli`) replace the
  C++ tools in `scripts/dev/build.sh`, with the same command lines and output; their
  stdout matched the C++ binaries byte for byte on the checked inputs.
- `scripts/tools/dub-video.sh` translates a video file or link offline into an MP4
  with dubbed Vietnamese/English speech, a soft subtitle track and SRT files. It
  runs the Rust `onplay-dub` pipeline, which overlaps recognition, translation and
  parallel in-process speech, and sentence-cases all-capital STT text before
  translation.
- `rust/` Cargo workspace with a Rust KorvaTTS inference library and
  `scripts/tools/tts.sh` CLI (`--batch`, `max_seconds` duration fitting,
  `--device` CoreML options), checked against the Python package for text
  preparation and waveform parity.
- `scripts/prod/package.sh` builds a relocatable macOS ARM64 archive of the
  native binaries and runtime dylibs with a SHA-256 file. It is not signed or
  notarized.
- Provisional WebSocket translations of the newest partial, replaced by the fast
  final translation and, in context mode, by a Qwen refinement under the same
  segment ID. The router runs fast and context requests in separate lanes.
- Opt-in WebSocket segment speech with a bounded independent TTS worker,
  ordered audio events and text-preserving speech failures.
- WebSocket `audio` events carry the spoken `text` so clients can show
  subtitles in sync with segment playback.
- Opt-in `"audio_encoding":"binary"` delivery of segment speech as one binary
  frame (JSON header plus WAV), avoiding base64 overhead.
- `"speakers":"live"` WebSocket mode: live speaker labels and per-speaker
  voices without recording audio or running end-of-input diarization.
- WebSocket speech keeps up with the source: faster KorvaTTS speed while the
  estimated client playback queue is behind, and `audio_skipped` for segments
  that would play more than 4 seconds late. A full speech queue now drops the
  oldest segment instead of disabling speech.

### Changed

- `ONPLAY_CAPTURE_AUDIO` takes `0` (nothing), `1` (transcript and audio, as before),
  `2` (transcript only) or `3` (audio only); other values stop the server instead of
  being ignored.
- Native binaries copy their runtime dylibs into `build/lib` and resolve them
  through `@loader_path/lib` instead of an absolute `.deps` path.
- Live translation uses warmed native INT8 OPUS/NLLB; Qwen is kept for explicit
  context translation.
- Provisional speaker labels are shown during streaming, including long recordings,
  instead of waiting for the end of input; offline refinement is kept for recordings
  up to 10 minutes.

### Fixed

- `onplay-server` binds `127.0.0.1:8766` before loading models: with the port busy it
  printed the stream address, then crashed with a segmentation fault while exiting
  during the background STT model load. It now reports the busy port and exits 1.
- Live speech no longer reads invented sentences for short English text. KorvaTTS
  (Python too) predicts 4.5-7 s for text such as "Yes." or "Hello." and fills it
  with made-up speech; `onplay-server` now enables the new
  `SynthesisOptions::cap_runaway_duration`, which falls back to `0.6 s + 0.1 s` per
  character when a prediction exceeds twice that. On a 60 s live clip to English,
  final-to-audio latency fell from 591 to about 480 ms mean and no segment was
  skipped (one before); a 5-character sentence took 186 ms instead of 701 ms.
  Ordinary and Vietnamese predictions are unchanged; the option is off by default.
- `onplay-server` writes queued events within 5 ms instead of 20 ms while the client
  is quiet, and sends `done` within 10 ms of the lanes going idle instead of 50 ms.
- Offline diarization with helper recognizers could deadlock: a helper waited for
  jobs while holding the queue lock that the calling thread needed. Helpers now
  start after every turn is queued and never block on the queue.

### Removed

- The Python translation router (`ui/translation_engine.py`) and its fake-provider
  test (`tests/translation_engine.py`), replaced by `onplay-translation`, with its `lingua`
  and `PyYAML` requirements.
- The Python KorvaTTS waveform comparison script (`tests/tts_rust_parity.py`; its
  recorded result stays in the README), the KorvaTTS text fixture generator
  (`onplay-tts/tests/fixtures/text_parity.py`; the JSON fixture stays) and
  `ui/requirements.txt`/`ui/runtime-requirements.txt`. Python is now only used to
  convert NLLB to CTranslate2 (`ui/translation-requirements.txt`).
- The web UI (`ui/index.html`, `live.mjs`, `spectrum.mjs`, `pcm-worklet.js`,
  `metrics.html`), the HTTP server on port 8765 with `/api/transcribe`,
  `/api/translate`, `/api/translate-audio`, `/api/tts` and `/api/sessions/<id>`, the
  resource monitor on port 8767, the Python server modules (`server.py`,
  `ws_stream.py`, `recognition.py`, `sessions.py`, `tts.py`, `voices.py`,
  `translation.py`, `metrics.py`), `scripts/ui.sh`, their tests, the mobile HTTP API
  guides (`docs/mobile-api.md`, `docs/translation-api.md`) and the flow diagram.
  Stored sessions can no longer be read over the network.
- The LibreTranslate Docker service (`compose.translation.yaml`, port 5001) and the
  router's `libretranslate` backend. No route used it; `opus-fast` runs the same
  Argos OPUS 1.9 models natively.
- The C++ sources (`src/`, `include/onplay/`, `CMakeLists.txt`,
  `tests/session.cpp`, `tests/wave_reader.cpp`) and `tests/wave_reader.py`; the
  Rust crates and their tests replace them.
