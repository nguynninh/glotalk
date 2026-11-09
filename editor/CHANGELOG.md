# Changelog

All notable changes to Glotalk Editor are recorded here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.3.1] - 2026-11-09

### Fixed

- Speaker detection split one character into many on long videos with music
  and short lines: a 2-hour short drama (4,080 captions) came out with 311
  speakers. Line-by-line clustering is now only the first step. Clusters holding
  less than 0.5% of the long lines (or fewer than 2) no longer become speakers,
  speakers whose average voices are at least 0.7 alike are merged, and every
  caption then joins the nearest speaker or stays unassigned below 0.3. The same
  video now gives 19 speakers with 190 lines unassigned; the four-speaker sample
  is unchanged. "Số nhân vật" now merges whole speakers down to that number.

## [0.3.0] - 2026-11-09

Speakers and Vietnamese pronouns, ahead of the dubbing step. Everything runs on
this computer or the Glotalk server; no external AI service or API key is used.

### Added

- "Nhân vật" tab in the Inspector: speakers are detected from the timing of the
  existing captions (OCR, speech recognition or an imported SRT) and the video's
  original audio, on this computer. Each caption's audio is turned into a CAM++
  voice embedding (the model the server uses, downloaded once, about 28 MB) and
  all captions are clustered together, so a character keeps the same label for
  the whole video. Options: a fixed number of speakers, or a merge similarity
  (default 0.5). Captions shorter than 1.2 s join the nearest speaker; very short
  or unmatched ones stay unassigned.
- Speaker list: name, colour, gender (suggested from median voice pitch), the
  KorvaTTS voice for the future dubbing step (suggested by gender), "Gộp vào…" to
  merge two speakers, delete, jump to a speaker's first line or the next
  unassigned line. Speakers are saved in the `.glotalk` file.
- Captions on the timeline carry their speaker's colour; the Caption tab picks a
  speaker from the list instead of free text. Keys `1`–`9` assign the speaker at
  that position to the selected caption, `0` clears it.
- "Phân biệt người nói (server)" option for speech recognition: asks the server
  for `speakers: true` and labels each line with the speaker turn it overlaps
  most.
- "Xưng hô (tiếng Việt)" in the "Nhân vật" tab: rules per speaker pair (or one
  speaker to anyone) give the words that replace "tôi" and "bạn" after
  translating into Vietnamese, since the server's Qwen3 1.7B model ignores
  speaker names and pronoun instructions in its context (tested). Plural and
  "friend" uses ("chúng tôi", "các bạn", "bạn bè", "bạn của tôi"…) are left
  alone. The listener defaults to the nearest other speaker in the previous,
  then next, line within 15 s, and can be set per caption ("Nói với"). Applied to
  new translations automatically and to existing ones with "Áp cho các câu đã
  dịch". Rules are saved in the `.glotalk` file. No external AI service is used.

## [0.2.0] - 2026-11-09

First release. Earlier `0.1.0` builds were never published.

### Added

#### App and projects

- Tauri 2 desktop shell with a React 19 + Zustand editor: Project, Monitor and
  Inspector panels above a timeline, with draggable splitters. Panel sizes are
  remembered between sessions.
- Sign-in screen against the Glotalk server (`/auth/login`), with "Ghi nhớ đăng
  nhập" (name and token only, never the password), sign-out from the start
  screen, and "Dùng ngay" to work without an account.
- Start screen with "Dự án mới", "Mở dự án…" and a list of recent projects;
  missing files are marked and can be removed from the list.
- Projects saved as `.glotalk` files in a project folder with `export/` and
  `work/`. Every change is saved automatically within a second.
- Interface in Vietnamese, English, Simplified Chinese and Japanese, chosen from
  the title bar and remembered; the first run follows the OS language.
- Dark, light and system themes (dark by default). On macOS the app draws its
  own title bar and keeps the native window chrome in the same theme.
- Settings tab for the Glotalk server URL (default `https://stt.qiverse.top`)
  with "Kiểm tra kết nối", the licence token, and a check for `ffmpeg`,
  `ffprobe` and GPU encoders (NVENC, QSV, AMF, VideoToolbox).

#### Media and timeline

- Import of video, audio and still images (button or drag and drop) with
  `ffprobe` metadata, a background thumbnail strip and an audio waveform.
  Thumbnails are cached per file under the system temp directory.
- Multi-track timeline: any number of video, audio and text tracks, a subtitle
  track and a dedicated dubbing track. Video and audio from the same file are
  linked clips that move, trim and delete together, and can be unlinked.
- Per-track hide, lock, rename and delete; audio tracks have mute, solo and
  volume.
- Audio tracks can be made taller by dragging their bottom edge; once tall
  enough they show separate left and right channel volumes, applied in playback
  and export. Double-click resets the height or a channel.
- One subtitle track for both lines of each subtitle, with separate eyes for the
  source text and the translation. On screen the source sits above the
  translation; either, both or neither can be shown. Projects with the earlier
  C1 (source) + C2 (translation) tracks are merged on open.
- Video clips show a continuous filmstrip across their whole length.
- NLE-style tools: select, track select forward, ripple, razor, slip, pen
  (volume/opacity), hand and type, plus snapping to the playhead and clip edges
  and linked selection.
- Timecode ruler, scrubbing, Ctrl/Cmd+wheel zoom, zoom slider, fit to window and
  playhead follow.
- Shortcuts: Space, ←/→ (Shift for one second), Home/End, ↑/↓ to jump between
  subtitles, N, S, Delete, +/−, `\`, Esc.

#### Monitor and effects

- Program monitor that composites every visible layer with WebGL, with
  frame-accurate seeking, fit/25/50/100% zoom, fullscreen, master volume and
  mute, and a live subtitle preview using the current style.
- Clip effects: opacity, scale, X/Y position, clip volume, and chroma key
  (key colour picked from the frame, similarity, smoothness, spill).
- Text clips with their own content, size and colour.
- Colour tab with temperature, tint, exposure, contrast and saturation.
- Subtitle style editor (font, size, colour, scale, outline, shadow, glow,
  background, vertical position).

#### Subtitles

- Subtitle editing: add at playhead, split, delete, frame-by-frame timing,
  speaker, source text and translation; searchable subtitle list.
- SRT import and export. A two-line block is read as source + translation;
  export can write the translation, the source or both.
- Hard-subtitle OCR from the "Nhận diện" tab: model size (Tiny, Small, Medium),
  samples per second, every-frame or full-frame scanning, progress and Cancel.
  Uses the drawn subtitle region, or the bottom band of the frame when none is
  drawn, and asks before replacing existing subtitles. A cancelled scan keeps
  its results and can be resumed ("Tiếp tục quét") or restarted.
- The PP-OCRv6 Small model is bundled, so OCR works offline out of the box;
  Tiny and Medium are downloaded on first use with MB done/total and a bar.
- Region selection on the monitor for the subtitle area and an optional wider
  OCR scan area.
- Speech recognition through the Glotalk server ("STT giọng nói"): choose the
  spoken and target language, see progress, cancel. The transcript fills the
  source text and the server's translation the translation.
- Subtitle translation through the server's `POST /translate` (signed-in
  accounts only), in order, 10 lines at a time with the 5 previous lines as
  context, with progress and Cancel. By default only lines without a translation
  are sent; overwriting existing translations asks first.

#### Export

- Video export to MP4 at the source size or 1080×1920 portrait, with libx264,
  libx265, VideoToolbox, NVENC, QSV or AMF, progress and Cancel. Includes every
  visible video layer with its effects and chroma key, the audio mix, text
  clips, subtitles in the current style and the colour correction.

#### Development

- Unit tests for crop geometry, the text mask, merging, media probing and
  rendering; opt-in integration tests against real videos (`GLOTALK_SAMPLE`,
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

### Known limitations

- The Small OCR model reads simplified "别" as traditional "別".
- Speech-recognition timings are estimated (the server sends none) and need
  checking on the timeline. Server TTS and dubbing are not connected, and
  "Chạy pipeline" only writes a log line.
- Exported colour correction uses ffmpeg filters and may differ slightly from
  the preview.
- Zoomed far in, neighbouring filmstrip frames can repeat: a video has at most
  240 thumbnails.
- Derived files in the temp directory are never cleaned up.
- The webview has no CSP and the asset protocol can read any path; the licence
  token and the remembered sign-in token are stored unencrypted in
  `localStorage`.

[Unreleased]: https://github.com/nguynninh/glotalk/compare/editor/v0.3.1...HEAD
[0.3.1]: https://github.com/nguynninh/glotalk/compare/editor/v0.3.0...editor/v0.3.1
[0.3.0]: https://github.com/nguynninh/glotalk/compare/editor/v0.2.0...editor/v0.3.0
[0.2.0]: https://github.com/nguynninh/glotalk/releases/tag/editor/v0.2.0
