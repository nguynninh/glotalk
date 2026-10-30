# Changelog

All notable changes to VDubbing are recorded here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.6] - 2026-10-30

### Changed

- Update the Glotalk launcher icon and use a distinct bug-marked icon for
  development builds.

### Fixed

- Center and scale adaptive launcher artwork within the safe zone to prevent
  clipping by launcher masks, and replace the default grid background with white.

## [1.0.5] - 2026-10-29

### Added

- Audio translation from the existing bubble: capture one app's playback,
  recognize speech locally with Whisper Small and Silero VAD, translate transcripts
  locally with ML Kit, and read them using an offline Android text-to-speech voice.
  Download and verify the speech models on first use.
- Cancel capture, recognition, translation and speech when stopping; report
  missing audio, permission failures and processing backlog.
- Audio translation requires no API key and sends neither audio nor transcripts
  to a translation server. Download recognition and translation models on first use.

### Changed

- Replace coroutine delays with cancellable countdowns, frame callbacks and
  translation state notifications for typing, flashcards, accessibility polling,
  screenshot preparation and automatic screen translation.

## [1.0.4] - 2026-10-27

### Fixed

- Keep the floating dismiss bubble above translated text after every overlay
  redraw, preserving its position.
- Show language choices in a vertical, scrollable dropdown inside the translation
  panel. Tap the same language chip to close it, switch chips to change the list,
  and close the dropdown after selecting a language.

### Added

- Android regression tests for language dropdown interactions and bubble window
  order across translation redraws and display mode changes.

## [1.0.3] - 2026-10-27

### Fixed

- Fit translated text inside its OCR bounds and draw it on a single overlay
  canvas. Use the block dimensions when positioning text inside a canvas clip,
  preventing translated glyphs from being clipped off screen.
- Sample replacement backgrounds from the screenshot and choose contrasting
  text, preserving coloured buttons instead of covering them with white plates.
- Refine masks to cover clipped glyph edges, split table columns, and exclude
  uncertain leading icon fragments from Latin OCR text. Leave addresses, numeric
  controls and uncertain short icon fragments untouched by the overlay.
- Materialise ML Kit's lazy line lists before matching OCR results, preventing
  visible text from being discarded as an empty recognition result.
- Restore Gemini bounding boxes to the original screenshot dimensions and hide
  the floating bubble while capturing, so it is not recognised as page text.
- Apply AIDL auto-translation settings on the main thread and persist the
  requested source, target and enabled state.

### Changed

- Replace the repeating three-second auto-translation timer with debounced
  window and scroll triggers, avoiding repeated translations of an unchanged
  screen. Coalesce pending triggers and wait for an active translation to finish.

### Added

- Regression tests for background sampling, glyph coverage, translation
  visibility, cloud coordinates and OCR column grouping, including a real page
  fixture and hardware-accelerated text rendering checks on Android.

## [1.0.2] - 2026-10-27

### Fixed

- **Translated text no longer overlaps its neighbours.** A translation is
  routinely longer than the words it replaces, and nothing limited it: a long
  paragraph covered the heading above it, a long tab label spread across the
  labels beside it, and a bit of both in the bottom navigation bar. Every block
  is now confined to its own band on both axes, ending halfway into the gap to
  each neighbour, and only blocks that actually touch along the other axis count
  as neighbours — two columns of text at the same height no longer constrain each
  other vertically, and one row of text no longer constrains another row
  horizontally.
- **Text left untranslated still holds its place.** A block whose translation
  came back identical to the original is not drawn, because painting it over
  itself would only blur the words. Such a block was also dropped when working
  out its neighbours' limits, so the block beside it grew straight into the
  untouched original words — a tab bar read as "Video" fused to the next label's
  translation. Those blocks now hold their space like any other.
- **A gap between a grown block and its neighbour.** Translated text that had to
  widen stops a small distance short of the edge it shares with the next block,
  so the two never read as one run of text.
- The line count the translation layout is limited to now includes the
  line-spacing multiplier. Measuring the font alone under-counted the line pitch
  by 15%, which let a block lay out taller than the box it was drawn in and spill
  onto its neighbour even when its height was otherwise correct.

## [1.0.1] - 2026-10-27

### Fixed

- **Overlapping translated text.** A translation longer than the line it replaced
  grew past its own box and covered the lines above and below it — most visibly
  a paragraph swallowing the tail of the heading before it. Every block is now
  confined to its own vertical band, ending halfway into the gap to each
  neighbour, and only blocks that overlap horizontally count as neighbours, so
  two columns of text at the same height no longer constrain each other.
- The line count the translation layout is limited to now includes the
  line-spacing multiplier. Measuring the font alone under-counted the line pitch
  by 15%, which let a block lay out taller than the box it was drawn in and spill
  onto its neighbour even when its height was otherwise correct.

### Removed

- The result banner ("Bản dịch (…)" with its ✕). It duplicated what the bubble
  already shows: while a translation is on screen the bubble is the red ✕ that
  clears it. The overlay is now four windows, not five.

## [1.0.0] - 2026-10-27

First release.

### Added

- **On-screen translation.** An accessibility service captures the current
  screen, ML Kit recognises the text and translates it on the device, and the
  result is drawn back over the original lines — no copy-paste, no screenshots
  leaving the phone.
- **Two display modes.** *Replace* paints a plate matching the page colour over
  the original and writes the translation there; *Parallel* keeps the original
  and places the translation beneath it.
- **On-device recognition per script.** Dedicated ML Kit recognisers for Latin,
  Chinese, Japanese and Korean; translation models download on first use per
  language pair and then work offline.
- **Source and target language pickers.** Target: Vietnamese, English,
  Japanese, Korean, Chinese. Source: any of those, plus automatic detection.
- **Optional Gemini fallback.** When the on-device OCR reads too little of the
  screen, one Gemini request performs OCR and translation together, given a key
  the user supplies. Off until enabled; the key is stored in private app
  preferences.
- **Realtime mode.** Re-translates on window changes (1.5 s debounce), on scroll
  (0.8 s debounce) and on a 3 s timer while it is on.
- **Floating bubble.** Tap opens the translation panel, long-press opens the
  quick menu (pause, language, settings, hide, exit), drag moves it. While a
  translation is showing, the bubble becomes a red ✕.
- **Foreground service.** Keeps the bubble and the AIDL endpoint alive, with a
  notification offering *Dịch ngay* and *Tắt*.
- **AIDL integration.** `ITranslationService` / `ITranslationCallback`, exported
  under the `normal` permission `vn.onplay.vdubbing.permission.CALL_TRANSLATE`
  and bound with the action `vn.onplay.vdubbing.TRANSLATE`. Methods:
  `translateNow`, `setAutoTranslate`, `getLastResult`, `isAccessibilityReady`,
  `hideOverlay`. Results arrive as JSON (`engine`, `sourceLang`, `targetLang`,
  `text`, `blocks[]` with `original`/`translated`/`lang`/`x`/`y`/`w`/`h` in
  screenshot pixels); failures arrive as eight stable numeric error codes.
- **Settings screen.** Service control, translate-now and clear-translation
  actions, bubble visibility, auto-translate on app switch, source and target
  language, app language, theme, Gemini configuration with key verification, the
  most recent result, and an accessibility diagnostics section.
- **Five UI languages.** Vietnamese (default), English, Japanese, Korean and
  Simplified Chinese, switchable in the app or from Android 13's per-app
  language screen.
- **Light and dark themes**, following the system or pinned by the user.
- **JVM unit tests** for the translation pipeline and language tag mapping.

### Security

- Screenshots are held in memory for the duration of a request and recycled
  immediately afterwards.
- On-device mode sends nothing off the device. The Gemini fallback is off until
  the user enables it with their own key, and screenshots go to Google only then.

[Unreleased]: https://github.com/nguynninh/v-dubbing/compare/v1.0.6...HEAD
[1.0.6]: https://github.com/nguynninh/v-dubbing/compare/v1.0.5...v1.0.6
[1.0.5]: https://github.com/nguynninh/v-dubbing/compare/v1.0.4...v1.0.5
[1.0.4]: https://github.com/nguynninh/v-dubbing/compare/v1.0.3...v1.0.4
[1.0.3]: https://github.com/nguynninh/v-dubbing/compare/v1.0.2...v1.0.3
[1.0.2]: https://github.com/nguynninh/v-dubbing/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/nguynninh/v-dubbing/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/nguynninh/v-dubbing/releases/tag/v1.0.0
