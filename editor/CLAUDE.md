# Project Engineering Guidelines

Glotalk Editor: a Tauri 2 desktop app. React 19 + TypeScript + Zustand in
`src/`, Rust in `src-tauri/src/`, `ffmpeg`/`ffprobe` as external executables.
Read [ARCHITECTURE.md](ARCHITECTURE.md) before changing how the parts talk.

## 1. Core principle

Correctness is more important than speed. Do not guess.

When something can be verified from source, configuration, tests, logs,
dependency definitions or tool output, verify it before concluding. Never present
an assumption as a fact. Separate:

- **Fact** — directly seen in code, output or docs.
- **Inference** — strongly supported by facts.
- **Hypothesis** — possible, not yet shown.

When debugging, go Evidence → Analysis → Root cause, not a list of possible
causes.

## 2. Before changing code

Match investigation depth to the task. A one-line fix needs the code path it
touches; a change to a command or the store needs its callers too.

1. Read the implementation and find its callers (`grep` the command name in both
   `src/` and `src-tauri/src/`).
2. Identify which side the change belongs to (see §4).
3. Check the threading and lifetime rules in §5.
4. Check the related tests.
5. Prefer the smallest change that solves the actual problem. Do not modify
   unrelated code, and do not reformat files you are not otherwise changing.

If you notice an unrelated bug, report it instead of fixing it silently.

## 3. Dependencies

- Frontend versions: `package.json` and `package-lock.json`. Rust versions:
  `src-tauri/Cargo.toml` and `src-tauri/Cargo.lock`. Read the lock file for the
  real version; do not assume one.
- Do not add a dependency, framework or pattern when an existing one solves the
  problem. The store is Zustand; there is no router, UI kit, CSS framework or
  test runner on the frontend.
- Tauri plugins need three things kept in step: the npm package, the Rust crate
  in `Cargo.toml` with `.plugin(...)` in `lib.rs`, and a permission in
  `src-tauri/capabilities/default.json`.
- `oar-ocr` uses the `auto-download` feature. The app bundles the Small model
  (`resources/models`) and sets `OAR_HOME` to its data directory; Tiny/Medium,
  and `cargo test` (which uses `~/.oar` unless `OAR_HOME` is set), download on
  first use from ModelScope, which can take many minutes.

## 4. Where code belongs

- **Filesystem, processes, heavy computation → Rust.** The webview never spawns
  `ffmpeg` or reads files itself except media through `convertFileSrc`.
- **Every `invoke()` goes through `src/lib/api.ts`.** Add the Rust command in
  `commands.rs`, register it in `generate_handler!` in `lib.rs`, and add the
  typed wrapper and interface in `api.ts` in the same change.
- **Editor state lives in `src/store/editor.ts`.** Synchronous mutations go in
  the store; async workflows with dialogs and `api` calls go in
  `src/store/actions.ts`. Language and theme have their own small stores in
  `src/i18n/index.ts` and `src/lib/theme.ts`.
- **Every user-visible string goes through i18n.** Add the key to the `vi`
  dictionary (`src/i18n/core.ts` for app shell/settings, `panels.ts` for panels
  and store), then `en` in the same file, then `zh.ts` and `ja.ts`. TypeScript
  fails if a language is missing a key. Use `useT()` in components and `t()`
  outside React.
- **New Tauri window APIs need a capability permission** in
  `src-tauri/capabilities/default.json` (as `core:window:allow-set-theme` did).
- **Shared components in `src/components/` do not import the store.**
- **`src-tauri/src/ocr` and `project.rs` stay free of Tauri types** so they can be
  moved into a crate shared with the Glotalk server.
- `ffmpeg` logic stays in `media/ffmpeg.rs`; resolve executables with
  `find_tool`, never by bare name.

## 5. Threading, lifetimes and the IPC boundary

- A command that runs `ffmpeg` or does more than trivial work must be `async` and
  use `tauri::async_runtime::spawn_blocking`, as the existing ones do. A
  synchronous command blocks that invocation's thread.
- Any spawned child process must be killed and waited for on every exit path
  (`FrameReader`'s `Drop` is the pattern). A leaked `ffmpeg` keeps running after
  the user moves on.
- Long jobs must be cancellable (`AtomicBool`, checked per unit of work) and
  report progress throttled, as `ocr::run` does.
- Argument names: JavaScript sends camelCase, Tauri maps them to snake_case Rust
  parameters. Return values keep whatever field names the Rust struct
  serialises — `VideoInfo`/`MediaInfo` are snake_case, the OCR types are
  camelCase. Check the `serde` attributes before writing the TypeScript interface.
- Background results must check they still apply. Thumbnails, peaks and OCR
  results are discarded if a different video is loaded by the time they arrive;
  keep that guard for any new async result tied to the current media.
- Long jobs report progress with events (`app.emit`), not polling. The frontend
  must `unlisten` in `finally`, as `runOcr` does.
- Only one OCR job runs at a time (`OcrState.running`); the engine is cached and
  reused. Do not build a second `OcrEngine` per call.
- Colours drawn on a `<canvas>` from CSS variables must be redrawn on theme
  change: include `useTheme().resolved` in the effect's dependencies.
- React handlers that outlive a render (keyboard listeners, pointer drags, rAF
  loops) read `useEditor.getState()`, not values captured from the render.
- Times are integer milliseconds everywhere. `seek` snaps to frames; keep new
  time edits frame-aligned.
- Regions are fractions 0..1 of the source frame on both sides; crop pixels are
  computed only in Rust (`CropSpec`).

## 6. Data model drift

There are three segment shapes: `src/lib/srt.ts` `Segment`
(`start`/`end`/`src`/`vi`) — the canonical one in the frontend — plus
`project.rs` `Segment` (`start_ms`/`end_ms`/`text_src`/`text_vi`/`audio_path`)
and `ocr::merge` `OcrSegment` (`start`/`end`/`text`/`conf`). Convert explicitly
at the boundary, as `runOcr` does (`text` → `src`, empty `vi`). Do not silently
rename fields on one side.

## 6a. Work in progress

The multi-track timeline (`src/lib/timeline.ts`, tracks/clips/assets in
`editor.ts`) and `.glotalk` project files are being built. While that lands,
`npx tsc --noEmit` may fail in files you did not touch. Check whether the errors
come from your change before reporting, and do not "fix" half-finished code in
those files unless asked.

## 7. Language

- **Code comments are Vietnamese**, matching the existing code.
- **UI strings are written in Vietnamese first** in the i18n dictionaries, then
  translated into English, Chinese and Japanese. Never hard-code UI text in a
  component.
- **`AppError` messages are Vietnamese only** and are shown in the log as-is,
  whatever the UI language.
- **Comment sparingly.** Explain a non-obvious reason, a measured constant or a
  platform workaround; do not restate the code.
- `ARCHITECTURE.md`, `CHANGELOG.md`, `CONTRIBUTING.md` and this file are in
  English. Quote UI labels verbatim (`"Nhập video"`, `"Chạy pipeline"`).

## 8. Verification

After a change, run what applies and report the exact command and result:

| Change touches | Run |
|---|---|
| `src/` | `npx tsc --noEmit` (and `npm run build` for bundling) |
| `src-tauri/src/` | `cd src-tauri && cargo test --lib` |
| `ffmpeg` code | the media test with `GLOTALK_SAMPLE` set (see CONTRIBUTING) |
| OCR code | the OCR test with `GLOTALK_HARDSUB` set |
| UI behaviour | `npm run tauri dev` and exercise it |

- The media and OCR integration tests return early and report `ok` when their
  environment variable is unset. An `ok` without the variable proves nothing.
  `probe_media_kinds` generates its own files with `ffmpeg` and is skipped only
  when `ffmpeg` is missing.
- Type-checking and compiling do not prove the UI works. If you could not run the
  app, say so and list what remains unverified.

## 9. Scope and git

- Inspect `git status` and `git diff` before and after changing files (once the
  project is under git). Preserve the user's existing changes.
- Never commit `dist/`, `node_modules/`, `src-tauri/target/` or `.dev/` (test
  videos). They are already ignored.
- Do not change `identifier` (`com.glotalk.editor`), the security settings in
  `tauri.conf.json` or capability permissions without saying so explicitly.

## 10. Communication

Be concise for simple tasks. For complex ones: conclusion, evidence, root cause,
change, verification, remaining risks. Stop and ask when requirements conflict,
when a change is destructive or hard to reverse, or when several designs carry
real trade-offs — after first establishing what you can yourself.
