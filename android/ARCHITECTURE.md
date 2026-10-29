# Architecture

## Modules and dependency direction

```text
:app  ──depends on──>  :domain
```

`:domain` is a Kotlin/JVM library. It owns translation policy, data models and
repository contracts. It depends on Kotlin coroutines, not Android, Compose,
Hilt, ML Kit, SQLite or HTTP clients. Its tests run without the Android SDK.

`:app` owns Android entry points, implementations, UI, resources and dependency
wiring. The production flavor uses `vn.onplay.glotalk` (Glotalk); development uses
`vn.onplay.glotalk.dev` (Glotalk Dev). Each flavor has debug and release build types.
Permissions and intent actions use the
installed application's ID. The source namespace, component class names and AIDL
package remain `vn.onplay.vdubbing`; public AIDL methods, JSON fields and error
numbers are unchanged.

Package names are unchanged for domain declarations. Source locations are:

- `domain/src/main/kotlin/vn/onplay/vdubbing/domain/`
- `domain/src/test/kotlin/vn/onplay/vdubbing/domain/`
- `app/src/main/java/vn/onplay/vdubbing/`
- `app/src/test/java/vn/onplay/vdubbing/`
- `app/src/androidTest/java/vn/onplay/vdubbing/`

## Ownership inside the Android app

| Package | Owns | Must not own |
|---|---|---|
| `data/local` | SQLite history, preferences, connectivity | Screen state or navigation |
| `data/mlkit` | OCR, language detection, local translation | Cloud fallback policy |
| `data/remote` | Gemini requests and response parsing | Overlay windows |
| `data/capture` | Bitmap storage and pixel sampling | Service lifecycle |
| `framework/accessibility` | Screenshots, foreground tracking, service bridge | Translation policy |
| `framework/overlay` | Physical windows, bubble, text rendering, panel/menu Views | Engines or repositories |
| `framework/service` | Foreground lifecycle, AIDL, request progress, notifications | OCR/cloud selection |
| `presentation/feature/<feature>` | Screen state, ViewModel, commands, events, feature UI | Data implementations |
| `presentation/designsystem` | Shared visual components and theme | Feature imports or navigation |
| `presentation/preview` | Sample data and fake preview repositories | Production state |
| `presentation/speech` | Text-to-speech lifecycle in Compose | History writes |
| `presentation/uimodel` | Localizable messages | Platform operations |
| `presentation/mapper` | Domain values to UI resources | Business policy |
| `di` | Construction and bindings | Screen state |

Each feature owns its `components/` directory. `FlashcardCommand` and
`FlashcardEvent` live in `feature/flashcard`; history equivalents live in
`feature/history`. A component moves to the design system when it is shared
visual UI without a dependency on a feature's state or commands.

The home feature composes settings, text translation and history. History opens
flashcard review. These screen relationships belong in features, never in shared
design-system components. Content composables receive state and callbacks so
previews and UI tests can render them without live engines.

## Screen translation paths

### App screen

`MainActivity` hosts `SettingsUI`, supplies permission requests and applies locale
changes. `SettingsViewModel` reads domain settings and calls the shared
`TranslateScreenUseCase`. It passes the app package name so the app's own controls
are never covered by a translation overlay. Results become screen state.

When the activity resumes, the ViewModel reloads settings that may have changed
through the bubble or AIDL. A translation also reads the current repository values
at submission, so it does not use an old UI snapshot.

### AIDL and service

1. `TranslateService` owns the foreground-service scope on
   `Dispatchers.Main.immediate`, system callbacks and lifecycle cleanup.
2. `TranslationBinder` receives external calls. It dispatches mutations to that
   scope, validates source/target against supported choices, and falls back to
   stored choices for invalid inputs. A one-off translation does not overwrite
   saved languages. It converts results to the existing JSON/error contract.
3. `TranslationRequestRunner` admits one service request at a time, updates bubble
   progress, closes transient overlays and waits the calibrated capture delay.
4. The same `TranslateScreenUseCase` used by the app runs capture, OCR, language
   selection, local/cloud policy and optional overlay output.
5. `TranslationNotifications` builds localized notifications, remembers the last
   successful result's metadata and refreshes the channel after locale changes.

The bubble, notification action and debounced accessibility events enter the same
service request path. The AIDL adapter does not mutate Views on Binder threads.

## Concurrency and screenshot lifetime

There are two distinct admission checks. The service runner serializes its own
progress and callbacks on Main. A mutex in the shared use-case instance rejects
overlap across *all* screen callers, including the app and service, with the stable
`BUSY` error. The checks protect different ownership boundaries.

A captured screen is an opaque handle in domain. Only data implementations read
its bitmap. Every admitted request releases its own handle in `finally`, and the
use case unlocks even after capture failure or cancellation. Cancelling local OCR
does not start a cloud fallback.

ML Kit can keep reading an image after the calling coroutine is cancelled. OCR
awaits the image read before allowing capture cleanup. A screenshot delivered
after capture cancellation is recycled by the continuation's cancellation handler.
Stopping the foreground service cancels its requests; it does not clear the shared
bitmap store or recycle another caller's screenshot.

## Background status and dependency injection

`VDubbingApp` owns one `AppContainer` per process. Hilt providers return its shared
settings, history, translation engines and screen use case. Services use the same
container, rather than constructing a second set of engines.

`BackgroundStatusStore` publishes service state through the domain
`BackgroundStatusRepository` interface. ViewModels receive a read-only `StateFlow`.
There is no global mutable `BackgroundState.shared`, so a test or a separate
container cannot accidentally inherit another instance's service status.

Settings remain the persisted source of truth. Service-running status is runtime
state; it must not reset a stored auto-translation preference merely because a
new process starts with no running foreground service.

## Overlay boundaries

`AccessibilityOverlayRenderer` owns attaching, updating and removing the four
windows, their ordering, bubble gestures and translated text placement.
`OverlayPanelContent` owns panel Views and the open language dropdown.
`OverlayMenuContent` owns menu Views. Both report user actions through callbacks;
neither attaches a window. Shared native View builders live in `OverlayUi`.

Keep these Android invariants:

- Four separate accessibility-overlay windows.
- Translated text uses `FLAG_NOT_TOUCHABLE`.
- Screenshot coordinates and translated text use the same edge-to-edge origin.
- Keep capture delays, bubble placement and text calibration until device evidence
  justifies a change.
- Resolve the accessibility service through the bridge at use time; it can
  disconnect independently of the foreground service.

## Verification

```bash
./gradlew :domain:test :app:testDevelopmentDebugUnitTest
./gradlew :app:assembleDevelopmentDebug :app:assembleDevelopmentDebugAndroidTest :app:lintDevelopmentDebug
./gradlew :app:connectedDevelopmentDebugAndroidTest
```

`LayerDependenciesTest` checks forbidden imports, the domain source location and
the design-system boundary. Domain tests check fallback policy, source-language
selection, overlap rejection and cancellation cleanup. App unit tests check
service request admission/cancellation and settings synchronization alongside the
existing rendering calculations and feature ViewModels.

Instrumentation tests cover the AIDL adapter from a worker thread, existing
overlay behaviour, ML Kit, SQLite and Compose UI. Building the test APK proves
compilation only. The AIDL adapter test does not prove cross-process Binder
transport. Accessibility grants, real service restarts, OEM windows and full
end-to-end capture still require a device and manual checks.
