# Architecture

## Components

| Path | Responsibility |
|---|---|
| `include/onplay/stt.hpp` | Public C++20 model, transcript and streaming session API |
| `src/stt.cpp` | Sherpa ONNX C API integration and session ownership |
| `src/main.cpp` | WAV CLI, input pacing and timing reports |
| `src/speakers.cpp` | Optional offline speaker segmentation, clustering and per-turn STT |
| `src/recognize.cpp` | Resident STT model and fresh per-request streams for audio translation |
| `ui/recognition.py` | Serial PCM pipe client with bounded I/O, timeout and worker cleanup |
| `scripts/setup-speakers.sh` | Checksum-pinned speaker models |
| `ui/translation.py` | Bounded HTTP client for the local router |
| `ui/translation_engine.py` | Normalize, resolve pair, route NLLB/Qwen/OPUS, validate output, cache and metrics |
| `config/translation.yaml` | Registered models, per-pair routes, fallback and context limits |
| `ui/ws_stream.py` | Loopback PCM bridge, optional speaker processing and background segment translation |
| `scripts/setup.sh` | macOS ARM64 runtime and model bootstrap |
| `scripts/build.sh` | clang++ CLI build with a local runtime search path |
| `scripts/run.sh` | Baseline model paths and default English WAV |
| `scripts/test.sh` | Sanitized session tests and Python integration checks |
| `tests/session.cpp` | Input validation, silence and finalization checks |
| `tests/smoke.py` | Real-model English, Vietnamese and endpoint checks |
| `CMakeLists.txt` | Static library and CLI targets using an external SDK |
| `.deps/`, `models/` | Local downloaded dependencies and recognition assets |

## Recognition flow

The CLI loads a WAV through Sherpa ONNX, creates `StreamingSTT`, and feeds 100 ms
chunks. Optional realtime pacing waits for the corresponding input timestamp.
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

A session owns its recognizer and stream through RAII. Temporary recognition
results are also released through RAII. The stream is destroyed before its
recognizer. Model configuration validates file existence and thread count (1–16,
default 2); runtime creation failures raise exceptions.

The public contract requires a single worker thread. Callbacks run synchronously
and must not re-enter the engine. There is no asynchronous queue, cancellation
API or internal locking. `finish()` is idempotent; further audio is rejected.
Each accepted chunk is at most one second, with finite samples in `[-1, 1]`.

## Build and deployment

The shell path targets Apple Silicon macOS with Sherpa ONNX v2.0.1 shared
libraries. CMake requires C++20, CMake 3.20+ and a supplied SDK. It produces a
static Onplay library and a CLI, while retaining the shared runtime dependency.
Do not assume portability or deployable library paths without a platform check.

Setup verifies hashes for runtime and model archives. The separately fetched C
header and Vietnamese WAV are not checksum-pinned in the script. Keep downloaded
assets outside source ownership and review their licenses before redistribution.

## Verification

```sh
./scripts/build.sh
./scripts/test.sh
```

The test script uses real model assets, ASan/UBSan for the session test, and a
Python smoke test. The checks do not establish broad recognition accuracy,
live microphone behavior, cross-platform compatibility or sustained realtime
performance. CMake has no registered CTest targets. Report actual execution
results separately from the coverage described here.

## Optional speaker pass

The UI sends a boolean `speakers` setting with its WebSocket configuration. With
it enabled, the bridge saves arriving PCM to a temporary mono 16 kHz WAV while
streaming STT continues. At EOF it closes the WAV, waits for STT and starts the
separate `onplay-speakers` process. This uses the same pinned runtime and its
Pyannote/CAM++ diarization API. Sorted time ranges are transcribed independently;
labels are numbered by first appearance. The streaming API adds optional `ModelConfig.speaker_model` and a default-zero
`Transcript.speaker` field. Existing callers keep ordinary STT behavior.

The offline preview is limited to 600 seconds before allocating its full float
buffer. The bridge enforces the matching PCM byte limit and falls back to ordinary
STT for longer audio. Original finals remain available in the UI. Speaker results
replace the displayed transcript only after the extra process succeeds; failure
leaves the original intact. Connection closure kills the active process, joins the
output thread, closes temporary files and releases the session lock. Temporary
PCM is removed after the process exits. No speaker embeddings persist across runs.

### Live provisional labels

With speaker mode enabled, the bridge also passes the pinned CAM++ model to the
streaming CLI. The session retains at most four seconds of PCM and uses a fresh
embedding stream to compare recent speech with its session-local speaker manager.
Labels refresh after approximately two seconds when recognition text changes and
at finalization. An ASR endpoint clears the audio window and pending label while
keeping speaker identities for subsequent utterances. Embeddings, extraction
streams and manager search results use RAII. CLI speaker events carry labels
through the bridge to both partial and final UI text before EOF. The 10-minute
limit applies only to offline refinement; live labeling continues for long files.
A label describes the recent audio window, not precise per-word attribution.

## Optional WebSocket speech

With `speech: true` and a translation target, live translations feed a separate
bounded TTS worker (four waiting segments). It returns complete segment WAVs as
base64 JSON events keyed by segment ID, without blocking recognition or
translation. Offline refined translations do not produce speech. Speech errors
disable speech for the connection but preserve text output. `done` waits for
both workers. See [the protocol](docs/streaming-audio.md) for cancellation limits.
