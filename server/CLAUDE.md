# Onplay STT engineering guidelines

## Project context

This is `onplay-stt`, an initial speech-to-text workspace. It is not the Glotalk
Android application. Do not assume Android services, Gradle modules, Compose,
AIDL, translation engines or app release versions exist here.

Current layout:

- `include/onplay/stt.hpp` exposes the streaming C++20 API.
- `src/stt.cpp` integrates Sherpa ONNX; `src/main.cpp` reads WAV files.
- `scripts/` contains setup, build, run and test entry points.
- `tests/session.cpp` checks session validation and finalization;
  `tests/smoke.py` runs real-model English/Vietnamese checks.
- `CMakeLists.txt` builds a static library and CLI with an external runtime SDK.
- `.deps/` and `models/` hold local runtime and model downloads.

The shell bootstrap targets macOS ARM64, pins Sherpa ONNX v2.0.1 and verifies
runtime/model archive hashes. Check the current scripts before changing versions
or documenting commands. A project license is not currently present.

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

Review the final diff and changed-file list. Run `./scripts/build.sh` and `./scripts/test.sh` when native changes require
them and assets are available. The latter runs sanitized session checks and real
model smoke checks. A successful compile proves compilation, not recognition
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
