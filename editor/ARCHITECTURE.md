# Architecture

Glotalk Editor is a desktop subtitle and dubbing editor built with Tauri 2. A
React webview owns the editing UI and all project state; a Rust process owns
everything that touches the filesystem, `ffmpeg`/`ffprobe` and the OCR model.

> **In progress (2026-11-09):** the timeline is being reworked from four fixed
> tracks into a multi-track model (assets, tracks, linked clips) together with
> `.glotalk` project files. Those parts are described only at the level of intent
> below and do not type-check yet. Everything else reflects the current code.

## Processes and dependency direction

```text
React webview (src/)  ──invoke()──>  Rust core (src-tauri/src/)  ──spawn──>  ffmpeg / ffprobe
        │      ▲                             │
        │      └── events (ocr-progress) ────┤
        └── asset protocol (convertFileSrc) ─┘  reads media files and cached thumbnails
```

- The webview never runs `ffmpeg` and never reads files directly. File content
  comes back through a command (`read_text`) or, for media, through Tauri's asset
  protocol (`convertFileSrc`).
- The Rust core holds no editor state. Commands are request/response; the only
  long-lived Rust state is the cached OCR engine (`OcrState`).
- `ffmpeg` and `ffprobe` are external executables found at runtime, not bundled.
  `find_tool` searches `PATH` and then `/opt/homebrew/bin` and `/usr/local/bin`,
  because a macOS GUI app does not inherit the shell's `PATH`.
- The OCR models (PP-OCRv6 Small) are bundled as resources; see
  [OCR models](#ocr-models).

## Source layout

| Path | Owns | Must not own |
|---|---|---|
| `src-tauri/src/lib.rs` | Tauri builder, plugins, managed state, startup model preparation, the command list | Business logic |
| `src-tauri/src/commands.rs` | `#[tauri::command]` adapters; moves blocking work to `spawn_blocking`; `OcrState` | Parsing or ffmpeg arguments |
| `src-tauri/src/error.rs` | `AppError`, serialised to the frontend as a plain string | UI wording beyond the message |
| `src-tauri/src/media/ffmpeg.rs` | Tool discovery, probing, audio extraction, waveform peaks, thumbnails, cache directories | Editor state |
| `src-tauri/src/ocr/` | Hard-subtitle extraction: frame decode, change filter, PaddleOCR, merging into timed segments | Tauri types — it is a plain library module |
| `src-tauri/src/stt.rs` | WebSocket client for the Glotalk server's speech recognition (`server/docs/streaming-audio.md`): PCM decode, session protocol, timing alignment | UI state |
| `src-tauri/src/project.rs` | Shared data model (`Segment`, `Region`) intended for reuse by a Rust server | Tauri or UI concerns |
| `src/lib/api.ts` | Typed wrappers around every `invoke()`, IPC types, timecode formatting, `localStorage` settings | React components |
| `src/lib/srt.ts` | SRT parse/serialise and the frontend `Segment` type (the canonical caption shape) | Store access |
| `src/lib/subStyle.ts` | Subtitle style model and its CSS preview | Store access |
| `src/lib/colorGrade.ts` | Basic colour correction model and its CSS preview | Store access |
| `src/lib/timeline.ts` | Track/clip/asset model and pure placement helpers (in progress) | Store access |
| `src/lib/theme.ts` | Theme preference store and native window theme sync | Editor state |
| `src/i18n/` | UI strings in four languages, `useT()` and `t()` | Logic |
| `src/store/editor.ts` | The editor Zustand store and its synchronous mutations | Dialogs, `invoke()` |
| `src/store/actions.ts` | Async workflows: dialogs, calls into `api`, OCR runs, project files, logging | Rendering |
| `src/panels/` | Project, Monitor, Inspector and Timeline panels | `invoke()` other than through `api` |
| `src/components/` | Shared presentational pieces (`Panel`, `Splitter`, property rows, `TitlebarPrefs`) | Store imports |
| `src/tabs/SettingsTab.tsx` | Server URL / token settings and tool status | Editor state |

## Rust commands

Registered in `lib.rs`, wrapped in `src/lib/api.ts`:

| Command | Purpose | Runs on |
|---|---|---|
| `check_tools` | `ffmpeg`/`ffprobe` version lines and available GPU encoders | Command thread (sync) |
| `probe_video` | Duration, size, fps, audio presence of a video | `spawn_blocking` |
| `probe_media` | Same for video, audio or still image (`kind`); cover art in audio files is not treated as video | `spawn_blocking` |
| `extract_audio` | Mono 16 kHz WAV for speech recognition | `spawn_blocking` |
| `audio_peaks` | Peak amplitude per bucket from 8 kHz mono `s16le` streamed from `ffmpeg` stdout | `spawn_blocking` |
| `thumbnails` | `count` evenly spaced JPEGs, cached on disk | `spawn_blocking` |
| `ocr_extract` | Runs hard-subtitle OCR on a job; emits `ocr-progress` events | `spawn_blocking` |
| `ocr_cancel` | Sets the cancellation flag of the running OCR job | Command thread (sync) |
| `read_text` / `write_text` | Plain file I/O for SRT and project files | Command thread (sync) |
| `overlay_begin` / `overlay_write` | Fresh temp folder for an export's text/caption PNGs; `overlay_write` takes raw bytes with the file name in the `x-name` header | Command thread (sync) |
| `stt_check` | Opens a recognition-only session on the server, waits for `ready`, closes; returns the round trip | async (tokio) |
| `stt_transcribe` / `stt_cancel` | Streams the file's audio to the server and returns timed transcript + translation; progress as `stt-progress` events | async (tokio); ffmpeg decode in `spawn_blocking` |
| `render_video` / `render_cancel` | Export the timeline with one ffmpeg run (`media/render.rs`); progress as `render-progress` events | `spawn_blocking` |

Tauri maps camelCase JavaScript argument names (`perSecond`, `durationMs`) to the
snake_case Rust parameters. Return types are serialised as declared:
`VideoInfo`, `MediaInfo` and `ToolStatus` keep snake_case fields
(`duration_ms`, `has_audio`), which is why the TypeScript interfaces use them
too. The OCR types use `#[serde(rename_all = "camelCase")]`.

Errors cross the boundary as a single Vietnamese string (`AppError` implements
`Serialize` via `Display`). The frontend logs them; there are no error codes.

## Frontend state

`useEditor` is the editor store. Panels subscribe to the slices they render;
event handlers read the latest state through `useEditor.getState()` so long-lived
listeners (keyboard shortcuts, pointer drags) do not capture stale values.
Language (`useLang`) and theme (`useTheme`) are separate small stores.

- **Main video.** `video` is the sequence's reference: its fps drives frame
  snapping and it is the source for preview and OCR.
- **Time and seeking.** `time` is the playhead in milliseconds. `seek(ms)` snaps
  to a whole frame and clamps to the sequence end (`timelineEnd`: the main
  video's duration or the last clip/caption, whichever is later), then bumps
  `seekTo = { ms, n }`. `MonitorPanel` watches `seekTo` and moves the `<video>`;
  the counter `n` lets a repeated seek to the same time still fire. While playing,
  a `requestAnimationFrame` loop copies `video.currentTime` into `time`, because
  `timeupdate` fires only about four times per second.
- **Captions.** `segments` (shape from `srt.ts`) is kept sorted by `start`.
  `addSegmentAt` refuses to overlap an existing caption or create one shorter
  than 200 ms; `splitAt` requires 100 ms on each side; timeline drags clamp a
  caption between its neighbours and snap to the playhead within 6 px. Captions
  live in their own caption track, not in `clips`.
- **Tracks, clips and assets (in progress).** Imported media become `assets`.
  Placing a video with sound creates a video clip and a linked audio clip that
  move, split and delete together. Tracks can be hidden, locked, muted or soloed
  and have a gain. One audio track is marked `dub`, the target of the future TTS
  step. Times are integer milliseconds; `in` is the offset into the source file.
- **Regions.** `regions.dialog` and `regions.ocr` are rectangles in 0..1 of the
  frame, drawn on the monitor. With `linkOcr` on, OCR scans the dialog region.
  `Region` matches `project::Region` in Rust.
- **Style and colour.** `subStyle` drives the monitor's caption preview via
  `subStyleCss`; sizes are in source-video pixels, scaled by the displayed size.
  `grade` (temperature, tint, exposure, contrast, saturation) is preview-only: a
  CSS filter plus a `mix-blend-mode: multiply` overlay for white balance, because
  WKWebView ignores SVG filters on `<video>`. Both are meant to map to ASS styles
  and ffmpeg `eq`/`colorbalance` at export, which does not exist yet.
- **Persistence.** In `localStorage`: panel sizes (`glotalk.layout.projectW`,
  `inspectorW`, `topH`), master volume (`glotalk.audioMix`), theme
  (`glotalk.theme`), language (`glotalk.lang`) and server settings
  (`glotalk.serverUrl`, `glotalk.token`). The project itself is saved to a
  `.glotalk` file through `write_text` (in progress); SRT export also keeps the
  captions.

## Internationalisation and theming

- Vietnamese (`vi`) is the source dictionary. `en` lives next to it in
  `core.ts`/`panels.ts`; `zh.ts` and `ja.ts` are typed `Record<TKey, string>`, so
  TypeScript rejects a missing key. A lookup falls back to `vi`, then to the key.
- Components use `useT()` so they re-render on language change; store and
  actions use `t()`. Placeholders are written `{name}`. Log lines are translated
  at the moment they are written.
- The first language is the stored one, else the OS language if supported, else
  English.
- Theme is dark by default, or light, or following the system. It sets
  `data-theme` on `<html>` and the native window theme (permission
  `core:window:allow-set-theme`), so macOS window chrome matches. Canvas drawing
  that reads CSS colours lists `useTheme().resolved` in its effect dependencies
  so it redraws on change.
- On macOS the window uses `titleBarStyle: "Overlay"`; the app draws its own
  title bar with a drag region (`core:window:allow-start-dragging`) holding the
  theme, language and settings controls. Other platforms keep the native title
  bar and put those controls at the end of the toolbar.

## Media pipeline

Importing probes the file and starts thumbnails and waveform peaks in the
background. Background results are applied only if the same media is still
loaded, so a slow job for a previous file cannot overwrite the current one.

Thumbnails and other derived files go to
`$TMPDIR/glotalk-editor/<kind>/<hash>/`. The hash covers the path, size and
modification time, so an edited file gets a new directory. Old directories are
never removed.

The timeline draws only the visible window: the ruler and waveform are canvases
sized to the viewport, and clips and thumbnails outside the scroll range plus a
50 px margin are not rendered. This keeps multi-hour videos responsive.

## Timeline and program playback

- **Model** (`src/lib/timeline.ts`): `assets` (probed with `probe_media`: video,
  audio or still image), `tracks` (video, audio, text, plus the single caption
  track whose content is `segments`) and `clips` (`start`/`dur` on the timeline,
  `in` into the source, optional `linkId`, optional `fx`). Clips on one track
  never overlap. A video with sound is placed as a linked video + audio pair.
  Video tracks stack V1 at the bottom; text tracks draw above video, captions
  on top.
- **Tools** (`tool` in the store, palette in `TimelinePanel`): selection, track
  select forward (also shifts later captions), ripple trim, razor, slip, pen
  (vertical drag sets clip volume or opacity), hand, type. `snap` and
  `linkedSelection` toggle snapping and linked behaviour.
- **Playback** (`src/panels/useProgram.ts`): the clock is the timeline, not a
  media element. While playing, a `requestAnimationFrame` loop advances `time`
  from `performance.now()`. Each clip near the playhead gets its own
  `<video>`/`<audio>`/`<img>` (created with `crossOrigin="anonymous"`), which is
  played or seeked to `time - start + in` and re-seeked if it drifts by more than
  200 ms. Video-track elements are muted; sound comes from audio clips at
  `master × track gain × clip volume`, honouring mute/solo.
- **Compositing** (`src/lib/compositor.ts`): WebGL draws the active video/image
  layers with fit, scale, offset, opacity and a YUV chroma key. If a frame
  cannot be uploaded to a texture (no WebGL, or the source is treated as
  cross-origin), it falls back to Canvas 2D without keying and logs why. Text
  clips and captions are HTML overlays. The colour grade is a CSS filter over
  the whole program.
- **Project files** (`.glotalk`, JSON): assets without thumbnails/peaks, tracks,
  clips, segments, style, grade and regions. Thumbnails and peaks are rebuilt on
  open from the disk cache.

## Export (`src-tauri/src/media/render.rs`)

`exportVideo` (actions.ts) turns the timeline into a `RenderJob`, mirroring what
the preview draws: video/image layers bottom to top with fit, scale, offset,
opacity and chroma key; audio clips of audible tracks at `track gain × clip
volume` (the monitor's master volume is not exported); the colour grade as
channel gains plus `eq`. The installed ffmpeg may lack `drawtext`/`libass`, so
text clips and captions are drawn in the webview on a canvas (`lib/graphics.ts`,
following `subStyleCss`) into one transparent PNG per interval where the visible
text does not change, listed with durations in an ffconcat file and overlaid
last. Rust builds a single `filter_complex`: a black `color` base, each layer
`-ss in -t dur` → `fps,scale,chromakey,despill,colorchannelmixer` → `setpts`
shifted to its start → `overlay`, then grade, graphics and `format=yuv420p`;
audio is `adelay`ed and `amix`ed over a silent bed. Progress comes from
`-progress pipe:1`; cancel kills ffmpeg and deletes the partial file.

The preview's chroma key works in UV units of 0..1; the frontend divides
similarity/smoothness by 4√2 for ffmpeg's `chromakey`. ffmpeg's curve is linear
where the shader's is `pow(…, 1.5)`, so soft edges can differ slightly.
`media/render.rs` holds the ffmpeg graph for export; other ffmpeg calls stay in
`media/ffmpeg.rs`.

## Hard-subtitle OCR (`src-tauri/src/ocr`)

```text
FrameReader (ffmpeg: fps → crop → scale ≤1280 px, rgb24 on stdout)
  → TextMask change filter (+ periodic re-check while text is visible)
  → OcrEngine (PP-OCRv6 via oar-ocr / ONNX Runtime), one frame per call
  → merge(): group similar readings into timed OcrSegment
```

1. `CropSpec` converts the 0..1 region to even pixel coordinates and rejects
   regions under 16 px. `null` scans the full frame.
2. `TextMask` marks bright pixels that sit within 2 px of a very dark pixel —
   outlined subtitle text. A frame with too few such pixels is treated as empty
   and is not sent to OCR. A frame whose mask differs from the last OCR'd frame
   by under 35% reuses its text. While the mask sees text, a frame is still
   re-OCR'd every `REFRESH_SECS` (1 s); if the text changed unnoticed, a binary
   search over the skipped frames finds where it changed. `every_frame` disables
   the filter.
3. `OcrEngine` drops background text before building lines: boxes with
   confidence under 0.5 (PaddleOCR's `drop_score`) and boxes shorter than 0.6 ×
   the tallest box. On the test clip, subtitle boxes are 55–67 px with
   confidence ≥ 0.98, while background boxes are ≤ 25 px with confidence up to
   0.91, so confidence alone is not enough. Remaining boxes are sorted into lines
   by vertical centre and joined left to right; confidence is their mean.
4. `merge` normalises whitespace, drops samples below `min_conf` (0.6), joins
   consecutive readings whose Levenshtein similarity is at least 0.7, tolerates
   one empty sample, picks the most frequent reading per group, widens boundaries
   by half a sample interval, drops results shorter than 200 ms and splits
   overlaps at the midpoint.

`run` checks an `AtomicBool` for cancellation per frame and reports progress at
most every 200 ms. `FrameReader` kills and reaps `ffmpeg` on drop.

### Running OCR from the UI

The "Nhận diện" tab collects the model size, samples per second, every-frame
and full-frame options. `runOcr` in `actions.ts`:

1. Picks the region: none for full frame; otherwise the dialog region (or the
   separate OCR region when unlinked). With no region drawn it uses
   `DEFAULT_SUB_REGION` (the bottom band) and logs that.
2. Asks before replacing existing captions.
3. Listens to `ocr-progress` and calls `ocr_extract`. Only one job runs at a
   time; a second call fails with "OCR đang chạy.".
4. Discards the result if another video was opened meanwhile; otherwise maps
   each `OcrSegment` to a caption with the OCR text as `src` and an empty `vi`.

### OCR models

At startup `prepare_models` copies the bundled models from
`resources/models` (declared in `tauri.conf.json` `bundle.resources`) into
`<app data>/models`, skipping files of identical size, and sets `OAR_HOME` there.
Tiny and Medium are not bundled; `oar-ocr`'s `auto-download` fetches them from
ModelScope into the same directory on first use. The engine is cached in
`OcrState` and rebuilt only when the model size changes. `cargo test` does not
run `prepare_models`, so tests use `~/.oar` unless `OAR_HOME` is set.

The Small model reads simplified "别" as traditional "別" even on clean frames;
both are in its dictionary. The integration test therefore compares text by
similarity, not exact equality.

## Planned stages that do not exist yet

The Export tab lists five steps — extraction, translation, analysis, dubbing and
video export. Extraction (hard-subtitle OCR, audio extraction for STT) and the
"Xuất video" button (timeline export, see above) exist; "Chạy pipeline" and the
pipeline's run button only write a log line. Speech recognition and its
live translation call the Glotalk server (see below); TTS and server-side dubbing
are not called. Resolution and
encoder apply to "Xuất video"; the checkpoint option is UI only.

## Glotalk server

`stt.rs` speaks the server's streaming WebSocket protocol from Rust, because the
server checks the `Origin` header and the webview cannot set it. An `https://`
(or bare) address becomes `wss://` with `Origin: https://<host>`; `http://`/`ws://`
uses the server's built-in local origin `http://127.0.0.1:8765`. The token in
Settings is not sent: live sessions have no authentication (the token is for dub
jobs only).

The whole file is decoded to 16 kHz PCM16 in memory and sent in 100 ms messages;
the server applies backpressure. While translating, the sender pauses when more
than 24 finished lines lack a `live` translation, because the server stops
translating at 64 queued lines.

`final` events carry no timestamps. Each line takes the last `progress` position
(reported about once per second of audio) before its first `partial` and before its
`final`, and `align` then moves both edges to voiced 20 ms frames of the local audio.
Times are therefore estimates; expect to adjust them on the timeline.

## Security configuration

`tauri.conf.json` sets `csp: null` and an asset protocol scope of `"**"`, so the
webview can load any file on disk by path. The capability file grants
`core:default`, window dragging and theme, `opener:default` and
`dialog:default`. The licence token is stored unencrypted in `localStorage`.
`prepare_models` sets `OAR_HOME` with `unsafe { set_var }` during setup, before
any OCR command can read it. Revisit the first three before distributing a build.
