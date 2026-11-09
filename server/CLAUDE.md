# Onplay STT engineering guidelines

## Project context

This is `onplay-stt`, a Rust speech-to-text, translation and speech workspace for
Apple Silicon macOS. It is not the Glotalk Android application. Do not assume
Android services, Gradle modules, Compose or AIDL exist here.

Current layout:

- `rust/` is a Cargo workspace; one version for all crates is set in
  `rust/Cargo.toml` (`workspace.package.version`).
- `onplay-sherpa-sys` binds the Sherpa ONNX C API (bindgen); `onplay-sherpa` is the
  only safe wrapper allowed `unsafe` (the workspace forbids it elsewhere).
- `onplay-stt` exposes the streaming `Engine`/`Session` API, WAV reading and
  diarization; `onplay-stt-cli` builds `onplay-stt`, `onplay-recognize` and
  `onplay-speakers`.
- `onplay-server`/`onplay-server-cli` build `onplay-server`, the streaming
  WebSocket server (`127.0.0.1:8766`, several sessions at once up to
  `ONPLAY_MAX_SESSIONS`). It also runs dub jobs (`build/onplay-dub` child processes)
  and serves their results over HTTP on `127.0.0.1:8767`; it has no web UI.
- `onplay-translation`/`onplay-translation-cli` build `onplay-translation`, the
  loopback HTTP translation router (port 5002; CTranslate2 via `ct2rs`, Qwen via
  candle; routes in `config/translation.yaml`). `onplay-translation-bench`/
  `-bench-cli` benchmark it.
- `onplay-tts`/`onplay-tts-cli` run KorvaTTS on ONNX Runtime; `onplay-dub`/
  `onplay-dub-cli` translate and dub videos.
- `scripts/setup/` downloads hash-pinned assets, `scripts/dev/` builds, runs and
  tests, `scripts/prod/` runs the public server and packages, `scripts/tools/` holds
  the recognition, TTS, dubbing and benchmark wrappers.
- Tests: `onplay-stt/tests/session.rs` (session validation and finalization),
  `onplay-stt-cli/tests/cli.rs` (real-model English/Vietnamese CLI checks) and
  `onplay-server-cli/tests/socket.rs` (starts `build/onplay-server` and checks it).
- `docs/` holds the WebSocket protocol and translation records.
- `.deps/` and `models/` hold local runtime and model downloads; `sessions/`,
  `build/` and `dist/` hold stored sessions, build output and packages. All are
  ignored.

The setup scripts target macOS ARM64, pin Sherpa ONNX v2.0.1 and verify the SHA-256
of every download. Python is used only to convert NLLB to CTranslate2. Check the
current scripts before changing versions or documenting commands. A project license
is not currently present.

The API accepts mono 16 kHz float PCM in chunks of at most 16,000 samples,
with finite values in `[-1, 1]`. It is synchronous and single-worker-thread;
listeners must not re-enter the engine. `finish()` is idempotent and closes input.
Preserve these contracts unless the task explicitly changes them.

## Investigate before editing

1. Read the relevant source, callers, configuration and tests when present.
2. Check `git status` and relevant diffs before editing if Git is initialized.
   Otherwise preserve existing content and review changes against local copies.
3. Distinguish verified facts from intended design and untested assumptions.
4. Keep changes within the requested scope and preserve the user's work.

Prefer the smallest complete solution. Reuse existing project conventions and
avoid new dependencies, wrappers or frameworks without a concrete need.

## Native recognition

When modifying runtime integration:

- Verify library version, architecture, headers and model compatibility from the
  actual assets and build configuration.
- Define ownership for recognizers, streams, buffers and results. Release native
  resources on success, failure and cancellation.
- Validate model paths and required files before starting recognition.
- Make sample rate, channel count and sample representation explicit. Do not
  silently reinterpret audio or assume a model's input requirements.
- Verify the runtime's threading contract before sharing a recognizer or stream.
- Keep downloaded runtimes and models outside source ownership. Record versions,
  origins, checksums and licenses when a reproducible setup is implemented.
- Avoid logging raw audio, recognized text or credentials by default.

Do not document capture, streaming, network, latency or platform guarantees until
implementation and relevant evidence exist.

## Verification

Review the final diff and changed-file list. Run `./scripts/dev/build.sh` and `./scripts/dev/test.sh` when native changes require
them and assets are available. The latter builds, then runs all Rust workspace tests
including the real-model ones (session, CLI and socket checks; port 8766 must be
free); no sanitizer runs. A successful compile proves compilation, not recognition
accuracy, resource cleanup or realtime performance.

For documentation-only work, verify paths, commands, links and implementation
claims against the checkout. Do not add tests that merely restate documentation.

Report exact commands actually run and their results. If verification is not
possible, state what was not run, why and what remains unverified. Never invent
a build command or claim a test passed without running it.

## Documentation and Git

- Keep README, ARCHITECTURE, CHANGELOG and this file in English.
- Separate current behavior from planned implementation.
- Keep the changelog focused on this project; assign releases only when real
  version metadata and release work exist.
- Keep `AGENTS.md` local. Never force-add, commit or push it.
- Do not stage downloaded archives, runtime libraries, model files or build
  output. Review ignore rules before staging; verify current ignore rules cover these paths.
- Never revert unrelated changes or rewrite Git history without authorization.
- Use concise English Conventional Commit messages when commits are requested.

Ask for clarification only when missing information cannot be established safely
from the workspace, requirements conflict, or an irreversible action is needed.
