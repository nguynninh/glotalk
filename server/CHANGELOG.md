# Changelog

All notable changes to Onplay STT will be recorded here.

Entries are grouped by Added, Changed, Fixed and Removed as applicable. Keep
unreleased work here until an actual version is assigned; do not invent release
versions or import another project's release history.

## [Unreleased]

### Added

- Opt-in WebSocket segment speech with a bounded independent TTS worker,
  ordered audio events and text-preserving speech failures.

- Configurable local translation router with NLLB-200 research baseline, explicit
  Qwen context mode, OPUS fallback, pair routes, output validation, bounded cache
  and aggregate metrics. Preserve the original POST STT contract.

- Optional transcript translation through a configured LibreTranslate instance,
  with target-language selection, a separate result, retry after editing, and
  translation copy/download actions.

- Experimental offline speaker labels and timestamps in the local UI, with an
  original-transcript comparison, checksum-pinned model setup and real-inference
  lifecycle checks. Streaming text remains available during playback; speaker
  processing runs afterward for recordings up to 10 minutes.

- C++20 streaming recognition API using the Sherpa ONNX C API, with partial and
  final callbacks, endpoint reset and input validation.
- WAV CLI with optional realtime input pacing and processing metrics.
- macOS ARM64 setup, build, run and test scripts, plus CMake build targets.
- Session validation tests and real-model English/Vietnamese smoke checks.
- Project documentation describing the Sherpa ONNX speech-to-text setup, API
  contracts and verification limits.

### Changed

- Use warmed native INT8 OPUS/NLLB for live translation, keeping Qwen for explicit
  manual context translation. Add a 500 ms latency check and HTTPS mobile guide.

- Default translation to local LibreTranslate on loopback port 5001, with a
  digest-pinned Docker Compose service and persistent English/Vietnamese/Chinese models.

- Send original and translated segments over the same WebSocket during playback,
  with revision/segment IDs, a bounded background translation queue, and translation
  errors that preserve recognition. Keep the existing POST APIs unchanged.

- Show provisional speaker labels during streaming, including long recordings,
  instead of waiting for end-of-file. Keep offline refinement for short clips.

- Replace inherited Glotalk Android documentation with Onplay STT documentation.
