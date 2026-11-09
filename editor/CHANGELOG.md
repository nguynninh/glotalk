# Changelog

All notable changes to Glotalk Editor are recorded here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Version `0.1.0` is set in `package.json`, `Cargo.toml` and `tauri.conf.json`;
no build has been released yet.

### Added

- Subtitle translation in the Detect tab: the source column is translated into a
  target language through the Glotalk server's `POST /translate` (signed-in
  accounts only), in order and 10 lines at a time with the 5 previous lines as
  context, with progress and Cancel. By default only lines without a translation
  are sent; overwriting existing translations asks first.
- Tauri 2 desktop shell with a React 19 + Zustand editor: Project, Monitor and
  Inspector panels above a timeline, with draggable splitters. Panel sizes are
  remembered between sessions.
- Interface in Vietnamese, English, Simplified Chinese and Japanese, chosen from
  the title bar and remembered; the first run follows the OS language.
- Dark, light and system themes (dark by default). On macOS the app draws its
  own title bar and keeps the native window chrome in the same theme.
- Video import with `ffprobe` metadata, background thumbnail strip and audio
  waveform. Thumbnails are cached per file under the system temp directory.
- Program monitor with frame-accurate seeking, fit/25/50/100% zoom, fullscreen,
  master volume and mute, and a live subtitle preview using the current style.
- Timeline with a timecode ruler, scrubbing, Ctrl/Cmd+wheel zoom, playhead
  follow, and caption clips that can be moved or trimmed without overlapping
  their neighbours and snap to the playhead.
- Subtitle editing: add at playhead, split, delete, edit timing frame by frame,
  speaker, source text and translation; searchable caption list.
- NLE-style shortcuts: Space, ←/→ (Shift for one second), Home/End, ↑/↓ to jump
  between subtitles, N, S, Delete, +/−, Esc.
- Subtitle style editor (font, size, colour, scale, outline, shadow, glow,
  background, vertical position), designed to map to an ASS style.
- Colour tab with temperature, tint, exposure, contrast and saturation, applied
  to the preview only.
- SRT import and export. A two-line block is read as source + translation;
  export can write the translation, the source or both.
- Hard-subtitle OCR from the "Nhận diện" tab: choose the model size, samples
  per second, every-frame or full-frame scanning, see progress and cancel. Uses
  the drawn subtitle region, or the bottom band of the frame when none is drawn,
  and asks before replacing existing captions. Recognised text becomes the source
  line of each caption.
- The PP-OCRv6 Small model is bundled, so OCR works offline out of the box;
  Tiny and Medium are downloaded on first use.
- Region selection on the monitor for the subtitle area and an optional wider
  OCR scan area.
- Audio extraction to mono 16 kHz WAV for speech recognition.
- Probing of audio files and still images as well as video.
- Settings tab for the Glotalk server URL and licence token, and a check for
  `ffmpeg`, `ffprobe` and GPU encoders (NVENC, QSV, AMF, VideoToolbox).
- Unit tests for crop geometry, the text mask, merging and media probing;
  opt-in integration tests against real videos (`GLOTALK_SAMPLE`,
  `GLOTALK_HARDSUB`).

### Fixed

- OCR merging no longer drops subtitles seen in only two samples at 10 samples
  per second. The minimum subtitle duration is now a fixed 200 ms instead of
  `interval.max(250)`; one-sample flickers at high scan rates are still dropped.
- OCR no longer mixes background text into subtitles or drops a line because of
  it. Boxes under 0.5 confidence or under 0.6 × the tallest box are discarded
  before lines are built.
- OCR no longer creates fake subtitles from background patterns between lines:
  the periodic re-check runs only while the text mask sees text.

### In progress

- Multi-track timeline: imported media as assets, any number of video, audio
  and text tracks, linked video/audio clips, per-track hide, lock, mute, solo and
  gain, and a dedicated dubbing track.
- Saving and opening projects as `.glotalk` files.

The frontend does not type-check while this work is unfinished.

- Speech recognition through the Glotalk server (Inspector › Detect › "STT giọng
  nói"): choose the spoken and target language, see progress, cancel. The
  transcript fills the source column and the server's translation the Vietnamese
  one. Settings defaults to `https://stt.qiverse.top` and has a
  "Kiểm tra kết nối" button.

- OCR shows download progress (MB done/total and a bar) while fetching a model
  that is not on disk yet, instead of a single "loading model" line.

### Known limitations

- The Small OCR model reads simplified "别" as traditional "別".
- Speech-recognition timings are estimated (the server sends none) and need
  checking on the timeline. Server TTS and dubbing are not connected.
  "Chạy pipeline" and the Export tab's run button only write a log line.
- Colour correction is preview-only and may differ slightly once mapped to
  ffmpeg filters at export.
- Derived files in the temp directory are never cleaned up.
- The webview has no CSP and the asset protocol can read any path; the licence
  token is stored unencrypted in `localStorage`.
