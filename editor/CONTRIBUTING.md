# Contributing to Glotalk Editor

Thanks for taking an interest. Glotalk Editor is a Tauri 2 desktop app: a React
19 + TypeScript + Zustand frontend built by Vite, and a Rust core that drives
`ffmpeg` and on-device OCR.

## Getting started

Requirements:

- **Node.js** and npm. Developed with Node 25.
- **Rust** stable via rustup. The crate uses `edition = "2024"`, so Rust 1.85 or
  newer is required; developed with 1.99.
- **Tauri 2 system prerequisites** for your OS — on macOS, the Xcode Command
  Line Tools. See the [Tauri prerequisites](https://tauri.app/start/prerequisites/).
- **`ffmpeg` and `ffprobe`** installed on the machine (for example
  `brew install ffmpeg`). They are not bundled. The app looks in `PATH`, then
  `/opt/homebrew/bin` and `/usr/local/bin`. **Cài đặt** shows whether they were
  found and which GPU encoders are available.
- Network access on first build (npm and crates). The app bundles the PP-OCRv6
  Small model from `src-tauri/resources/models/`; the Tiny and Medium models, and
  the models used by `cargo test` (cached in `~/.oar`), are downloaded from
  ModelScope on first use, which can take over ten minutes.

```bash
npm install
```

```bash
npm run tauri dev
```

The first Rust build compiles ONNX Runtime bindings and takes several minutes.
The dev profile optimises dependencies (`opt-level = 2`) so OCR is usable in
debug builds.

## Commands

| Command | What it proves | Notes |
|---|---|---|
| `npm run tauri dev` | the app starts | Vite on port 1420 (`strictPort`), Rust rebuilt on change |
| `npx tsc --noEmit` | the frontend type-checks | Fast; run on every frontend change |
| `npm run build` | the frontend type-checks and bundles into `dist/` | Does not build Rust |
| `cd src-tauri && cargo test --lib` | Rust unit tests pass | Integration tests are skipped unless their variables are set |
| `npm run tauri build` | a release bundle builds | LTO, `panic = "abort"`, stripped; slow |

### Integration tests with real video

`probe_media_kinds` builds its own image and audio files with `ffmpeg` and runs
whenever `ffmpeg` is installed. Two other Rust tests need a video file and return
early — reporting `ok` — when their variable is unset. Put test videos in `.dev/` (git-ignored). Paths are relative
to `src-tauri/`, where `cargo test` runs:

```bash
cd src-tauri && GLOTALK_SAMPLE=../.dev/sample.mp4 cargo test --lib media -- --nocapture
```

Checks probe, 16 kHz extraction, waveform peaks and thumbnails. The sample must
have an audio track and be about 10 seconds long (the test expects ~500 peaks at
50 per second).

```bash
cd src-tauri && GLOTALK_HARDSUB=../.dev/hardsub.mp4 cargo test --lib ocr::tests -- --nocapture
```

Runs the full OCR pipeline. It expects a specific clip with four burned-in
Chinese lines at fixed times in the bottom band of a 720p frame; it is not a
general-purpose test.

## How the code is organised

See [ARCHITECTURE.md](ARCHITECTURE.md). The rules that keep this shape:

- **The webview never touches the filesystem or `ffmpeg` directly.** Add a Rust
  command instead.
- **A new command is one change in three places:** the function in
  `commands.rs`, its entry in `generate_handler!` in `lib.rs`, and a typed
  wrapper in `src/lib/api.ts`. Components call `api`, never `invoke`.
- **Blocking work runs in `spawn_blocking`.** Child processes are killed and
  waited for on every path, including errors and cancellation.
- **One store.** State lives in `useEditor`; async workflows live in
  `store/actions.ts`; components in `src/components/` take props, not the store.
- **Async results check they are still current** (compare the video path) before
  writing into the store.
- **Times are milliseconds, regions are 0..1 fractions** on both sides.
- **Keep `ocr/` and `project.rs` free of Tauri types** so they can move into a
  crate shared with the Glotalk server.
- **A new Tauri plugin** needs the npm package, the Rust crate and `.plugin()`
  call, and a permission in `src-tauri/capabilities/default.json`. New window
  APIs need a permission there too.
- **Long jobs report progress through events** and the frontend unlistens in
  `finally`; one OCR job runs at a time and reuses the cached engine.

The multi-track timeline and `.glotalk` project files are being built. Until
they land, `npx tsc --noEmit` can fail in files you did not touch; say so rather
than patching half-finished code.

## Language and comments

- **The UI supports Vietnamese, English, Simplified Chinese and Japanese.** Write
  every user-visible string as a key in `src/i18n/`: Vietnamese first (`core.ts`
  for the app shell and settings, `panels.ts` for panels and store messages),
  then English in the same file, then `zh.ts` and `ja.ts`. TypeScript fails if a
  language is missing a key. Use `useT()` in components and `t()` elsewhere,
  with `{name}` placeholders.
- **Code comments are Vietnamese**, like the existing code. Rust error messages
  are shown to users unchanged, so write them in Vietnamese too.
- **Comment sparingly.** A comment earns its place when the code cannot say it:
  a non-obvious reason, a measured constant, a platform workaround. Do not restate
  the next line.
- `ARCHITECTURE.md`, `CHANGELOG.md`, `CONTRIBUTING.md` and `CLAUDE.md` are in
  English. Quote UI labels verbatim (`"Nhập SRT"`, `"Vừa khung"`).

## Style

- TypeScript is `strict` with `noUnusedLocals` and `noUnusedParameters`; keep
  `npx tsc --noEmit` clean.
- Format Rust with `cargo fmt` and check `cargo clippy` for new code. Neither has
  been run across the whole codebase, so keep formatting changes out of unrelated
  files.
- Prefer small functions next to their single caller over new shared modules.

## Submitting changes

1. Keep each change focused on one problem.
2. Run the checks from the table that apply to what you touched, plus the
   integration tests if you changed `media/` or `ocr/`.
3. Add an entry under `[Unreleased]` in [CHANGELOG.md](CHANGELOG.md) for anything a
   user would notice.
4. Update ARCHITECTURE.md when you change how the parts communicate, add a
   command or move ownership.
5. In the description, list what you ran and its result, and what you could not
   verify (for example, Windows or Linux behaviour, GPU encoders you don't have).

Use [Conventional Commits](https://www.conventionalcommits.org/):
`feat(ocr): …`, `fix(timeline): …`, `docs: …`.

## Platform notes

- Development and testing so far have been on macOS (Apple Silicon). Windows
  and Linux builds are configured by Tauri but untested.
- `find_tool` adds Homebrew paths because a macOS app launched from Finder does
  not see the shell `PATH`. If you add other tool locations, keep that list in
  `media/ffmpeg.rs`.
- Thumbnails and other derived files are cached in
  `$TMPDIR/glotalk-editor/` and are not cleaned up; delete that folder to reset.
