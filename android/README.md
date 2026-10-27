# VDubbing

Reads the text on the screen and translates it in place — no copy-paste, no
screenshots to an external app. An accessibility service captures the display,
ML Kit recognises and translates the text on the device, and the result is drawn
back over the original words. Other apps can ask it to translate over AIDL.

## What it does

- **Translate whatever is on screen.** Capture → OCR → translate → overlay, in
  one request, from the floating bubble or from another app.
- **Two ways to draw the result.** *Replace* paints a plate the colour of the
  page over the original line and writes the translation there; *Parallel* keeps
  the original and hangs the translation beneath it.
- **On-device by default.** ML Kit text recognition — a dedicated recognizer for
  each of Latin, Chinese, Japanese and Korean — and ML Kit translation, so
  nothing leaves the phone.
- **Optional Gemini fallback.** When OCR reads too little of the screen, and the
  user has enabled it with their own API key, one Gemini request does OCR and
  translation together and returns bounding boxes.
- **Realtime mode.** Re-translates when the window changes (debounced) and on a
  timer while it is on.
- **Floating bubble.** Tap for the translation panel (languages, mode, start),
  long-press for the quick menu (pause, language, settings, hide, exit), drag to
  move. While a translation is showing the bubble turns into a red ✕.
- **Background service.** A foreground service keeps the bubble and the AIDL
  endpoint alive, with a notification that offers *Dịch ngay* and *Tắt*.
- **Five UI languages.** Vietnamese, English, Japanese, Korean, Simplified
  Chinese — plus following the system setting. Switchable in the app or from
  Android 13's per-app language screen.

## Requirements

- Android 11 (API 30) or newer — `AccessibilityService.takeScreenshot()` starts
  there.
- The accessibility permission, which only the user can grant.
- The first translation per language pair downloads an ML Kit model (a few MB);
  after that translation works offline.

## Build

```bash
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. A JDK is required — Android
Studio's bundled one works, and `local.properties` must point `sdk.dir` at an
installed Android SDK (compile SDK 36). The Gradle wrapper (9.5) and the Android
Gradle plugin (9.3.3) come with the project.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Using it

1. Open VDubbing and tap **Bật dịch nền**. The ongoing notification appears and
   the bubble is drawn over the current screen.
2. Grant the accessibility permission from the status card, or from
   *Settings → Accessibility → VDubbing*. Android disconnects the service when
   the app is reinstalled; the diagnostics section under the status card says so
   and tells you to toggle it once.
3. Open any app, tap the bubble, pick the source and target language and the
   display mode, then **Bắt đầu dịch**. The translation is drawn over the screen
   and the bubble becomes a ✕.
4. Long-press the bubble to pause, change the language, hide the bubble, or stop
   the service.

The app's own screen is never overlaid: a translation started from the settings
screen is shown inside the app instead, so the controls stay reachable.

## Calling it from another app

`TranslateService` is exported under the `normal` permission
`vn.onplay.vdubbing.permission.CALL_TRANSLATE`, and speaks AIDL
(`ITranslationService`, `ITranslationCallback`). A caller binds with the action
`vn.onplay.vdubbing.TRANSLATE`, calls `translateNow(source, target, callback)` and
receives the result as JSON — the translated text, per-line blocks with pixel
bounding boxes, and the engine that produced them. Failures come back as stable
numeric codes (no accessibility, capture failed, no text, translation failed,
bad API key, cloud quota, offline, busy) so the caller can show its own wording.

Both callbacks arrive on a Binder thread, never the main thread. The full walk
through — permission, binding, JSON schema, error table and limits such as
`FLAG_SECURE` — is in `docs/INTEGRATION.md`. Note that `.gitignore` excludes
`/docs`, so that file exists only in a local checkout.

## How it is put together

```
app/src/main/
  aidl/vn/onplay/vdubbing/    ITranslationService, ITranslationCallback
  java/vn/onplay/vdubbing/
    domain/                   models, repository contracts, TranslateScreenUseCase
    data/                     ML Kit OCR + translation, Gemini, settings, capture store
    framework/
      accessibility/          capture, screenshot, foreground tracking, bridge
      overlay/                bubble, banner, panel, menu, translated text
      service/                TranslateService (foreground + AIDL), JSON shaping
      locale/                 per-app language controller
    presentation/             ViewModel, UI state, SettingsScreen (Compose)
    di/                       AppContainer — the hand-rolled composition root
  res/                        strings in vi (default), en, ja, ko, zh-rCN
```

The pipeline lives in `TranslateScreenUseCase` and nowhere else: local OCR is
used when it read enough of the screen, the cloud is tried when it did not, and
a cloud failure that is not a key or quota problem still returns the local
result. Everything below it — pixels, Binder, ML Kit, windows — is behind
interfaces, so the use case is unit-tested without an emulator.

The overlay is five separate windows rather than one. The translated text window
is `FLAG_NOT_TOUCHABLE`, so taps pass through to the app underneath; the bubble,
banner, panel and menu each get their own small window, because a touchable
window covering the screen would make the phone unusable.

## Tests

```bash
./gradlew testDebugUnitTest
```

18 JVM unit tests cover `TranslateScreenUseCase` (engine selection, cloud
fallback, self-overlay suppression, error paths) and `AppLanguage` tag mapping.
Nothing else is automated: the overlay, the service and the accessibility
behaviour need a device, and none of it is covered by tests.

## Verification state

The 18 unit tests were not run while this document was written: `./gradlew` needs
to write under `~/.gradle` and open a local socket, both of which this sandbox
blocks. Run them yourself with the command above. Nothing in this README was
verified on a device — the overlay behaviour, the service and the AIDL path are
described from the source, not from a run.

## Limitations

- **`FLAG_SECURE` apps cannot be read.** Netflix, most banking apps and some
  video players return a black screenshot. There is no legitimate way around it.
- **Screenshot rate.** Android throttles `takeScreenshot()`. Realtime mode spaces
  its captures (3 s timer, 1.5 s debounce on window changes) for that reason.
- **The accessibility service is the user's to enable**; no code can grant it.
- **On-device translation quality varies** with the language pair and with how
  much text the OCR managed to read.

## Privacy

Screenshots are held in memory for the duration of one request and recycled
immediately afterwards. On-device mode sends nothing anywhere. The Gemini
fallback is off until the user turns it on, and when it is on, screenshots are
sent to Google; the API key is stored in private app preferences and is never
logged or shared with a calling app.

## License

No license file yet — treat the code as all rights reserved until one is added.
