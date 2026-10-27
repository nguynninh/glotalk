# Contributing to VDubbing

Thanks for taking an interest. The project uses Kotlin + Jetpack Compose, an Android `:app` module,
and a pure Kotlin/JVM `:domain` module. Hilt injects ViewModels from the shared
engines owned by `AppContainer`.

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
| `./gradlew :domain:test :app:testDebugUnitTest` | domain and app JVM tests pass | No device required |
| `./gradlew :app:assembleDebugAndroidTest` | instrumentation tests compile and package | Does not run them |
| `./gradlew :app:connectedDebugAndroidTest` | instrumentation tests pass | Needs a connected device or emulator |
| `./gradlew :app:lintDebug` | Android Lint is clean | `MissingTranslation` and `MissingQuantity` are errors by design |
| `adb install -r app/build/outputs/apk/debug/app-debug.apk` | the APK installs | Reinstalling disconnects the accessibility service; toggle it again in Settings |

Instrumentation tests cover overlay window order, language dropdowns, rendering,
ML Kit and Compose screens. `TranslationBinderTest` calls the AIDL adapter from a
worker thread and checks Main-thread dispatch, languages and response codes.
These adapter tests do not exercise cross-process Binder transport, real
accessibility grants, service restarts or OEM window behaviour. Those still need
manual device checks. A passing build proves compilation, not runtime behaviour.

A first Gradle run needs a JDK and network access for dependency resolution. If a
command fails due to Gradle locks, SDK setup or other environment limits, report
the actual error and which checks remain unverified.

## How the Code Is Organised

See [ARCHITECTURE.md](ARCHITECTURE.md) for module and package ownership. The rules
that keep this shape are:

- **The pipeline policy belongs to `:domain`'s `TranslateScreenUseCase`.** Local
  OCR when sufficient, cloud otherwise, local results preserved on transient
  cloud failure. Only invalid keys and exhausted quota abort the fallback.
- **`app` depends on `domain`, never the reverse.** Domain contracts live in
  individual files under `domain/repository/`. No Android, Compose, HTTP or ML Kit
  types belong in their signatures.
- **Each feature owns its content, commands and events.** Shared visual primitives
  belong in `presentation/designsystem`; this package must not import features.
- **Reuse the existing Hilt bindings and shared engines.** Do not create a second
  `AppContainer` or a separate OCR/translation engine for each screen or service.
- **AIDL dispatches mutations onto Main.** `TranslationBinder` accepts Binder
  calls; `TranslationRequestRunner` handles admission and progress. Service
  lifecycle and overlay events remain in `TranslateService`.
- **All screen translation callers share one use-case instance.** Its mutex rejects
  overlapping requests, including calls from the app while the service is busy.
- **Captures belong to their requests.** Release them in `finally`; never clear the
  shared store when only one consumer stops. Await ML Kit's pixel reads before
  releasing a cancelled request's bitmap.
- **Keep physical window ownership in `AccessibilityOverlayRenderer`.** Panel and
  menu content builders create Views; they do not attach windows or own engines.

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

## External App Integration

Other Android apps can request screen translation through AIDL:

1. Copy `ITranslationService.aidl` and `ITranslationCallback.aidl` from
   `app/src/main/aidl/vn/onplay/vdubbing/`, preserving their package names.
2. Declare `vn.onplay.vdubbing.permission.CALL_TRANSLATE` in the caller's manifest.
3. Bind to VDubbing's `TranslateService` with the action
   `vn.onplay.vdubbing.TRANSLATE` and package `vn.onplay.vdubbing`.
4. Call `translateNow(source, target, callback)` with language codes such as
   `"ja"` and `"vi"`; the source also accepts `"auto"`.

The user must enable VDubbing's accessibility service before capture can work.
Results arrive as JSON through `onResult`; failures arrive through `onError`
with a stable numeric code. Remote callbacks arrive on Binder threads, so the
caller must dispatch UI updates to its main thread.

The AIDL files define the methods. `TranslationJson.kt` defines the JSON fields,
and `domain/model/TranslationErrorCode.kt` defines error numbers. The contract
details below identify the surfaces callers rely on.

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
./gradlew :domain:test :app:testDebugUnitTest
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
