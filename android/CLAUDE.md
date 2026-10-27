# Project Engineering Guidelines

## 1. Core Principle

Correctness is more important than speed.

Do not guess.

When information can be verified from:
- source code
- project configuration
- logs
- tests
- documentation
- dependency definitions
- git history

you MUST verify it before making a conclusion.

Never present an assumption as a fact.

---

## 2. Investigation First

For any non-trivial task, do not immediately modify code.

First:

1. Understand the user's actual objective.
2. Identify the relevant modules/files.
3. Inspect the existing implementation.
4. Search for callers and usages.
5. Trace the relevant execution/data flow.
6. Inspect related configuration and dependencies.
7. Check lifecycle/threading/state management.
8. Identify edge cases.
9. Determine whether enough evidence exists.

Investigation depth must match the work (see §14): a one-line bug needs the code
path it touches, not the whole repository. Expand when evidence points elsewhere.

If information is missing, investigate further when possible.

If it cannot be determined from the available information, say explicitly what is missing.

Do not invent behavior.

---

## 3. Evidence-Based Reasoning

When analyzing a technical problem, distinguish clearly:

### FACT
Directly verified from:
- source code
- logs
- documentation
- test results
- tool output

### INFERENCE
A conclusion strongly supported by verified facts.

### HYPOTHESIS
A possible explanation not yet proven.

Never present a HYPOTHESIS as a FACT.

When debugging, follow:

Evidence → Analysis → Root cause

rather than:

Possible cause A / B / C / D

---

## 4. Before Modifying Code

Before changing code:

1. Read the relevant implementation.
2. Find important callers.
3. Understand the lifecycle.
4. Understand state transitions.
5. Check threading/concurrency.
6. Check memory ownership.
7. Check API compatibility.
8. Check related tests.
9. Consider backward compatibility.
10. Anticipate regressions.

Do not modify unrelated code.

Prefer the smallest safe change that solves the actual problem.

---

## 5. Architecture

Respect the existing project architecture.

Do not introduce:
- new frameworks
- new dependencies
- new architectural patterns
- unnecessary abstractions

unless the task genuinely requires them.

Before adding a dependency, check whether an existing one already solves the problem.

Prefer existing project conventions over generic best practices.

The concrete conventions for this repository are in §20.

---

## 6. Kotlin / Android

For Kotlin code, pay particular attention to:

- coroutine lifetime — `viewModelScope`, `lifecycleScope`, and the service's own
  `CoroutineScope`; a `launch` that outlives what it touches is a leak or a crash
- dispatchers — `Dispatchers.Main.immediate` for UI and `WindowManager`,
  `Dispatchers.IO` for network, `Dispatchers.Default` for ML Kit
- Binder threads — every `ITranslationService.Stub` method runs on one, not the
  main thread
- main-thread-only APIs: `WindowManager.addView/removeView/updateViewLayout`,
  `View` mutation, `Toast`, `startActivity`
- service lifecycle — `onCreate`/`onStartCommand`/`onBind`/`onDestroy`, the
  foreground-service notification, and an accessibility service that connects and
  disconnects at any moment
- `Handler`/`Looper`, `Timer`, broadcasts, notifications
- `Bitmap` ownership — screenshots are large; release them as soon as the pixels
  are no longer needed
- Compose: `remember`/`LaunchedEffect` keys, state hoisting, recomposition cost
- resource qualification — a key added to `values/` is missing from four locales
  until they are all updated (lint fails the build, see §20)

Never assume UI code is running on the main thread.

Verify threading requirements from the actual implementation.

This repository: an accessibility service owns five overlay windows of type
`TYPE_ACCESSIBILITY_OVERLAY`, and a foreground service is also the AIDL endpoint.
Work arrives from the main thread, from the bubble's touch handlers, from a
3-second auto loop, and from Binder callers. Determine which of those a change
runs on.

---

## 7. Dependencies

Before changing dependency-related code, check:

- `gradle/libs.versions.toml` — the version catalog, the only place a dependency
  version is written
- `app/build.gradle.kts` — which artifacts are actually on the classpath
- `gradle/wrapper/gradle-wrapper.properties` — Gradle itself
- `local.properties` — `sdk.dir` (not committed)

Do not assume a dependency version.

Read the actual dependency configuration.

This repository has only Gradle/AGP dependencies: no npm, no CocoaPods, no SPM,
no DI framework (see §20, `AppContainer`).

---

## 8. Testing

After modifying code:

1. Review the diff.
2. Compile the affected target when the environment allows it.
3. Run relevant tests.
4. Check warnings/errors.
5. Verify the original bug/requirement.
6. Check for regressions.

Compilation success does NOT mean the task is complete.

When build or tests cannot be run, state exactly which commands were not run, why,
and what therefore remains unverified — see §17. Do not turn "I read the code" into
"the feature works".

---

## 9. Verification

Before saying "done", verify:

- Does the implementation solve the original problem?
- Does it work in the relevant lifecycle?
- Does it handle edge cases?
- Does it introduce regressions?
- Does it preserve existing behavior?
- Does it compile?
- Do relevant tests pass?

If something could not be verified, state it explicitly.

---

## 10. Git

Do not modify unrelated files.

Before finishing:

- inspect `git diff`
- inspect the list of changed files
- identify unintended changes

Never revert the user's changes unless explicitly requested.

Do not rewrite git history unless explicitly requested.

Before editing a file, check the working tree state (see §16).

---

## 11. Communication

For simple tasks:
- be concise
- answer directly

For complex tasks:

1. Conclusion
2. Evidence
3. Root cause
4. Solution
5. Changes
6. Verification
7. Remaining risks

Do not write at length merely to appear thorough.

Depth must match the complexity of the problem.

---

## 12. Stop Conditions

Stop and ask when:

- requirements conflict
- destructive or hard-to-reverse changes are required
- important information is unavailable **and** cannot be verified safely from
  source, configuration, logs, or a targeted test
- multiple architectural approaches carry significant trade-offs
- the correct behavior cannot be determined from available evidence

Ask only after making a reasonable, safe attempt to establish the facts yourself.
If you can keep investigating without causing harm or wasted work, investigate first.

Do not silently pick a risky assumption.

---

## 13. Definition of Done

A task is complete only when:

- the requirement is understood
- the relevant code was investigated
- the implementation is complete
- the changes were reviewed
- verification was performed as far as the environment allows
- remaining uncertainty is stated clearly

---

## 14. Investigation Efficiency

Investigation depth must match the task's complexity, risk, and scope.

- Start with the smallest set of files and execution paths relevant to the problem.
- Expand the investigation when evidence reveals additional dependencies or risks.
- Do not repeatedly inspect unrelated files or perform redundant searches.
- Do not stop investigating merely to save time when correctness remains uncertain.
- Before making changes, identify the minimum evidence needed to justify the proposed solution.

Optimize for correctness and useful progress, not investigation volume.

---

## 15. Root Cause Verification

Before declaring a root cause:

1. Identify the observed symptom and the conditions under which it occurs.
2. Establish a traceable connection between the evidence and the failure.
3. Distinguish the confirmed cause from contributing factors and unverified hypotheses.
4. Use a reproducer, targeted test, logs, instrumentation, or other relevant evidence when available.
5. If the cause cannot be confirmed, state what is known, what remains uncertain, and what evidence is needed next.

Never claim a fix resolves the root cause solely because the code looks correct or
compilation succeeds.

---

## 16. Existing Changes and Scope Control

Before modifying files:

- Inspect the working tree and relevant diffs (`git status`, `git diff`).
- Identify pre-existing user changes and preserve them.
- Never overwrite, revert, or reformat unrelated changes.
- Keep modifications within the requested scope.

"Do not modify unrelated files" does not mean "touch as few files as possible". A
coherent change may legitimately span Kotlin, resources, the AIDL contract and
docs — for example a new user-facing setting needs the repository interface, its
implementation, `AppUiState`, the Compose screen, a string in all five locales,
and possibly `docs/INTEGRATION.md`. Do everything the change needs, and nothing
more.

If you find an unrelated bug or drift, do not fix it silently: report it, and
explain before expanding scope.

---

## 17. Verification Integrity

Report verification results precisely.

- Distinguish commands that were actually executed from those only inspected or recommended.
- Never claim a build, test, runtime scenario, or on-device behavior passed unless it was actually verified.
- When verification is unavailable, state the exact limitation and the remaining uncertainty.
- Prefer targeted regression tests that reproduce the original failure.
- Review the final diff for unintended changes before reporting completion.

State the exact command and its result, or say "not run".

---

## 18. Public API Compatibility

For anything with external consumers, evaluate the impact on them. The public
surfaces of this project, all of which need this check:

- **The AIDL contract** — `ITranslationService` and `ITranslationCallback` in
  `app/src/main/aidl/`. Method names, parameter order and types, and the
  `oneway` callback direction are the contract. Apps outside this repository are
  compiled against them; renaming or retyping a method is a build break for them.
- **The service identity** — `TranslateService`'s intent action
  `vn.onplay.vdubbing.TRANSLATE`, its `<service android:name>` path, and the
  `normal` permission `vn.onplay.vdubbing.permission.CALL_TRANSLATE` that gates
  `bindService`. Callers declare that permission by name.
- **The JSON wire format** — `TranslationJson.kt` produces `engine`,
  `sourceLang`, `targetLang`, `text`, `blocks[]` with
  `original`/`translated`/`lang`/`x`/`y`/`w`/`h`. Key names, coordinate units
  (screenshot pixels) and the `"on-device"`/`"cloud"` engine values are public.
- **The error codes** — `TranslationErrorCode` 1–8. A client switches on the
  number and shows its own wording; renumbering one silently mislabels a failure.
  Keep the codes aligned with the table in `docs/INTEGRATION.md`.
- **String resource keys** — the Compose screen and service resolve them by
  name; removing or renaming one is a compile error inside the app and breaks
  nothing outside it, but translating the *values* changes user-visible UI.

Also check when relevant:

- Minimum Android version (`minSdk 30`) and the API levels the app targets
  (`compileSdk`/`targetSdk` 36) — a new API silently raises the floor.
- The manifest: exported flags, permissions, intent filters, and the
  `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` text that Play reviews.
- Existing client behavior and migration requirements.

Do not introduce a breaking API or behavioral change without explicit authorization
or a clearly justified requirement.

---

## 19. Multi-Agent Coordination

When multiple agents work on the same task:

- Assign explicit responsibilities and file ownership where practical.
- Avoid concurrent edits to the same files or overlapping implementation areas.
- Define integration points and expected deliverables before delegating.
- Independently inspect important findings and verify submitted changes.
- Resolve conflicts using repository evidence and project conventions, not agent consensus alone.
- Perform a final integrated diff review and relevant verification before completion.

Delegation does not transfer responsibility for correctness.

---

## 20. This Repository

Verified facts about this project. Re-verify before relying on a command that may
have changed.

### Shape

- A single-module Android app: Kotlin + Jetpack Compose, one Gradle module `:app`
  (root project `VDubbing`). No npm, no Swift, no second module.
- Build configuration lives in `app/build.gradle.kts` + `gradle/libs.versions.toml`
  (AGP 9.3.3, Kotlin 2.2.10, Compose BOM 2026.02.01, Gradle wrapper 9.5.0).
  `applicationId` and `namespace` are both `vn.onplay.vdubbing`; `compileSdk` and
  `targetSdk` are 36; `minSdk` is 30 because
  `AccessibilityService.takeScreenshot()` starts there.
- `app/src/main/java/vn/onplay/vdubbing/` is layered:
  `domain/` (models, repository interfaces, `TranslateScreenUseCase`),
  `data/` (ML Kit OCR + translation, Gemini, settings, capture store),
  `framework/` (accessibility capture and bridge, the five overlay windows,
  `TranslateService` — the foreground service **and** the AIDL endpoint),
  `presentation/` (ViewModel, UI state, `SettingsScreen`), `di/AppContainer`
  (the hand-rolled composition root — no Hilt and no plan to add one).
- The whole pipeline policy is in `TranslateScreenUseCase` and nowhere else:
  local OCR when it read enough of the screen, cloud OCR+translation when it did
  not, local result preserved when the cloud fails for a transient reason. Only
  a bad key or exhausted quota aborts.
- `app/src/main/aidl/` holds `ITranslationService` / `ITranslationCallback`;
  `framework/service/TranslationJson.kt` shapes the JSON handed to callers.
- Localization: `values/` is Vietnamese and the default; `values-en`, `values-ja`,
  `values-ko`, `values-zh-rCN` mirror it. `res/xml/locales_config.xml` decides
  what Android 13's per-app language screen offers.
- `docs/` is listed in `.gitignore`, so `docs/INTEGRATION.md` exists only in a
  local checkout and is not part of the repository.

### Commands and what they actually prove

| Command | Proves | Notes |
|---|---|---|
| `./gradlew assembleDebug` | the app compiles and packages | APK at `app/build/outputs/apk/debug/`; needs `sdk.dir` in `local.properties` |
| `./gradlew testDebugUnitTest` | the JVM unit tests pass | 18 tests in `app/src/test` only |
| `./gradlew :app:lintDebug` | Android Lint is clean | `MissingTranslation` and `MissingQuantity` are errors, by design |

Nothing here covers the overlay, the accessibility service or the AIDL path: those
need a device (or emulator) with the accessibility permission granted by hand.
A passing build says the code compiles — never that the feature works.

A first Gradle run needs a JDK (Android Studio's bundled one is at
`/Applications/Android Studio.app/Contents/jbr/Contents/Home`) and network access
for dependency resolution. Inside the agent sandbox `./gradlew` fails before it
starts — `gradle-9.5.0-bin.zip.lck: Operation not permitted` under `~/.gradle`.
When that happens, say so and name the command that still has to be run rather
than reporting a result.

Reinstalling the app disconnects the accessibility service; the user must toggle
it in Settings again. Expect that during manual verification instead of reading
it as a regression.

### Invariants — do not change unless asked

- `applicationId` / `namespace` `vn.onplay.vdubbing`, `minSdk 30`, and the
  `TranslateService` / accessibility-service class names: the permission, the
  intent action and the system's accessibility binding all point at them.
- `permission.CALL_TRANSLATE` stays `protectionLevel="normal"`. Raising it breaks
  every existing caller's `bindService`.
- The five overlay windows stay separate, and the translated-text window stays
  `FLAG_NOT_TOUCHABLE` — one touchable full-screen window would make the phone
  unusable.
- The app's own screen is never overlaid (`ownPackage` in
  `TranslateScreenUseCase`); the settings controls must stay reachable.
- `lint.error` keeps `MissingTranslation` and `MissingQuantity`: a locale file
  lagging behind is a bug, not a warning.
- `android.injected.testOnly=false` in `gradle.properties` keeps command-line
  APKs installable outside Android Studio.
- A new user-facing string goes into all five locale files, and its plural into
  every quantity each language needs.
- Repository interfaces in `domain/repository/Repositories.kt` are what the use
  case depends on; platform and network details stay behind them so the pipeline
  stays unit-testable without a device.

### Conventions

- **Comments: as few as possible.** Write one only when the code cannot say it
  itself — a non-obvious *why*, a measured number behind a constant, a workaround
  for a specific platform/API behaviour, or a trap that costs real debugging time.
  Never restate what the next line does, never narrate history, never add a
  comment to a name that already explains itself. Prefer one short line over a
  paragraph; prefer no comment over a short line that repeats the code.
  Do not generate explanatory comments as part of a translation, refactor, or
  "documentation pass" — that is not a reason to add words.
- Code comments are in English.
- User-visible strings are Vietnamese first: the default `values/strings.xml` is
  the source of truth, and every other locale is a translation of it. Doc quotes
  of a UI label keep the Vietnamese verbatim (e.g. `"Bật dịch nền"`, `"Dịch ngay"`).
- Error messages that cross the AIDL boundary (`TranslationException.message`) are
  Vietnamese and stay Vietnamese regardless of the app's UI language — callers are
  told to display from the code, per `docs/INTEGRATION.md`.
- README, CHANGELOG and CLAUDE.md are in English. The one Vietnamese document is
  the local, untracked `docs/INTEGRATION.md`, which is addressed to external
  (Vietnamese) integrators.
- CHANGELOG follows Keep a Changelog. There is no release script: a release means
  bumping `versionName` **and** `versionCode` in `app/build.gradle.kts` and
  adding the CHANGELOG entry — check the two agree before claiming a release.
- Commit messages: Conventional Commits, with a Vietnamese subject.
- No `Co-Authored-By` or "Generated with Claude" in commits or pull requests.
