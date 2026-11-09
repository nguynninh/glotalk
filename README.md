# Glotalk

Monorepo for the Glotalk translation and dubbing apps.

| Directory | What it is | Stack |
|---|---|---|
| [`android/`](android/) | Glotalk Android app: on-screen, text and audio translation | Kotlin, Jetpack Compose |
| [`editor/`](editor/) | Glotalk Editor: desktop subtitle/dubbing editor with OCR | Tauri 2, React, Rust |
| [`server/`](server/) | Onplay STT: offline speech recognition, translation, TTS and dubbing server | Rust |

Each directory is self-contained: build instructions, architecture notes and the
changelog live in its own `README.md`, `ARCHITECTURE.md`, `CONTRIBUTING.md` and
`CHANGELOG.md`.

See [CONTRIBUTING.md](CONTRIBUTING.md) for branch, commit and release conventions.
