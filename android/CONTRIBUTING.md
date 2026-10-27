# Contributing to VDubbing

Thanks for taking an interest. This is a single-module Android app: Kotlin +
Jetpack Compose, one Gradle module `:app`, no DI framework.

## Getting Started

```bash
git clone https://github.com/nguynninh/v-dubbing.git
cd v-dubbing
```

Requirements:

- A JDK — Android Studio's bundled one works
  (`/Applications/Android Studio.app/Contents/jbr/Contents/Home`).
- An Android SDK with platform 36 installed, pointed at from `local.properties`
  (`sdk.dir=...`, not committed).
- A device or emulator running Android 11 (API 30) or newer — that is `minSdk`,
  because `AccessibilityService.takeScreenshot()` starts there.

The Gradle wrapper (9.5.0) and AGP (9.3.3) come with the repository; dependency
versions live only in `gradle/libs.versions.toml`.

## Commands

| Command | What it proves | Notes |
|---|---|---|
| `./gradlew assembleDebug` | the app compiles and packages | APK at `app/build/outputs/apk/debug/` |
| `./gradlew testDebugUnitTest` | the JVM unit tests pass | `app/src/test` only — 23 tests |
| `./gradlew :app:lintDebug` | Android Lint is clean | `MissingTranslation` and `MissingQuantity` are errors by design |
| `adb install -r app/build/outputs/apk/debug/app-debug.apk` | the APK installs | Reinstalling disconnects the accessibility service; toggle it again in Settings |

**Nothing in this repository tests the overlay, the accessibility service or the
AIDL path.** Those need a device with the accessibility permission granted by
hand, and no automated test covers them. A passing build says the code compiles —
never that the feature works. So never report "tests pass" as the whole story:
state exactly which commands you ran and what they returned.

Inside an agent sandbox `./gradlew` fails before it starts — the Gradle lock
under `~/.gradle` (`gradle-9.5.0-bin.zip.lck: Operation not permitted`), and later
a local socket. That is an environment limit, not a code defect. Say which
command still has to be run by hand rather than reporting a result.

## How the Code Is Organised

```
app/src/main/
  aidl/vn/onplay/vdubbing/    ITranslationService, ITranslationCallback
  java/vn/onplay/vdubbing/
    domain/                   models, repository contracts, TranslateScreenUseCase
    data/                     ML Kit OCR + translation, Gemini, settings, capture store
    framework/
      accessibility/          capture, screenshot, foreground tracking, bridge
      overlay/                bubble, panel, menu, translated text
      service/                TranslateService (foreground + AIDL), JSON shaping
      locale/                 per-app language controller
    presentation/             ViewModel, UI state, SettingsScreen (Compose)
    di/                       AppContainer — the hand-rolled composition root
  res/                        strings in vi (default), en, ja, ko, zh-rCN
```

Rules that keep this shape:

- **The pipeline policy belongs to `TranslateScreenUseCase` and nowhere else.**
  Local OCR when it read enough of the screen, cloud when it did not, local
  result preserved when the cloud fails transiently. Only a bad key or exhausted
  quota aborts. Don't move that decision into a repository or a ViewModel.
- **Platform and network details stay behind the repository interfaces** in
  `domain/repository/Repositories.kt`. That is what makes the use case testable
  without a device; a new dependency on `Context`, a window or an HTTP client in
  `domain/` breaks the unit tests.
- **No Hilt, no new frameworks.** `di/AppContainer` is the composition root by
  design. Before adding a dependency, check whether one already in
  `gradle/libs.versions.toml` does the job.
- **Threading is not optional.** Work arrives on the main thread, from the
  bubble's touch handlers, from the realtime timer, and from Binder callers. UI
  and `WindowManager` calls need `Dispatchers.Main.immediate`; every
  `ITranslationService.Stub` method runs on a Binder thread, not the main one.
- **Release `Bitmap`s as soon as the pixels are no longer needed.** Screenshots
  are large and held in memory for exactly one request.

## Language and Comments

- **Comment sparingly.** A comment earns its place only when the code cannot say
  it: a non-obvious reason, a measured number, a platform/API workaround, or a
  trap that cost real debugging time. Don't restate the next line, narrate
  history, or annotate self-explanatory names. One short line beats a paragraph;
  nothing beats a line that just repeats the code.
- **Code comments are in English.** So are `README.md`, `CHANGELOG.md`,
  `CONTRIBUTING.md` and `CLAUDE.md`.
- **User-visible strings are Vietnamese first.** `values/strings.xml` is the
  source of truth; `values-en`, `values-ja`, `values-ko`, `values-zh-rCN` are
  translations of it. Quote a UI label verbatim when you refer to it
  (`"Bật dịch nền"`, `"Dịch ngay"`); don't translate it.
- **Error messages that cross the AIDL boundary are Vietnamese and stay
  Vietnamese**, whatever the app's UI language is. Callers are told to render
  their own wording from the numeric code instead.
- The one Vietnamese document is the local, untracked `docs/INTEGRATION.md`,
  addressed to external integrators.

A new user-facing string goes into all five locale files, and a plural into
every quantity its language needs — lint fails the build otherwise.

## Public Contracts

These surfaces have consumers outside the repository. Weigh a change here as a
breaking change:

- **The AIDL contract** — `ITranslationService` and `ITranslationCallback` in
  `app/src/main/aidl/`. Method names, parameter order and types, and the
  `oneway` callback direction are the contract; external apps compile against
  them.
- **The service identity** — the action `vn.onplay.vdubbing.TRANSLATE`, the
  `TranslateService` class path, and the `normal` permission
  `vn.onplay.vdubbing.permission.CALL_TRANSLATE` that gates `bindService`.
  Callers declare those by name, so renaming any of them breaks them.
- **The JSON wire format** — `framework/service/TranslationJson.kt` produces
  `engine`, `sourceLang`, `targetLang`, `text`, and `blocks[]` with
  `original`/`translated`/`lang`/`x`/`y`/`w`/`h` in screenshot pixels. The
  `"on-device"` / `"cloud"` engine values are part of it.
- **The error codes** — `TranslationErrorCode` 1–8. A client switches on the
  number and shows its own wording; renumbering one silently mislabels a
  failure. Keep the codes aligned with the table in `docs/INTEGRATION.md`.
- **String resource keys** — the Compose screen and the service resolve them by
  name. Renaming one is a compile error inside the app; changing a *value* is a
  user-visible change.

## Invariants — Do Not Change Without Being Asked

- `applicationId` / `namespace` `vn.onplay.vdubbing`, `minSdk 30`, and the
  `TranslateService` / accessibility-service class names.
- `permission.CALL_TRANSLATE` stays `protectionLevel="normal"`. Raising it
  breaks every existing caller's `bindService`.
- **The four overlay windows stay separate, and the translated-text window stays
  `FLAG_NOT_TOUCHABLE`.** One touchable full-screen window would make the phone
  unusable.
- **The app's own screen is never overlaid** (`ownPackage` in
  `TranslateScreenUseCase`) — the settings controls must stay reachable.
- `lint.error` keeps `MissingTranslation` and `MissingQuantity`.
- `android.injected.testOnly=false` in `gradle.properties` keeps command-line
  APKs installable outside Android Studio.

## Submitting a PR

```bash
git checkout -b feature/your-feature-name
# edit app/src/main/...
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew :app:lintDebug
git commit -m "feat: short description"
git push origin feature/your-feature-name
```

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/),
written **in English** — subject and body. Do not add `Co-Authored-By` or
"Generated with Claude" to commits or pull requests. Don't rewrite history or
reformat unrelated files; keep the diff to what the change needs.

In each PR, state what is included and what is intentionally out of scope.
Record verification as: area, command run, environment, and result or remaining
limitation. If you could not run a build or a device check, say so and why.

**Reinstalling the app disconnects the accessibility service.** Expect to toggle
it on again in Settings during manual verification; that is not a regression.

## Releasing

A release is two things, and they must agree:

1. Bump `versionName` **and** `versionCode` in `app/build.gradle.kts`.
2. Add an entry to `CHANGELOG.md`, following
   [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), in English.

Check that the two versions carry the same number before calling it released.
There is no release script and no publish workflow.
