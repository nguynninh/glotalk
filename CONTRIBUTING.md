# Contributing

Repository-wide conventions. Build and test instructions for each app are in its
own `CONTRIBUTING.md` or `README.md`.

## Branches

- `main` is always releasable. Do not push to it directly.
- Work on a short-lived branch named `<type>/<scope>-<topic>`, for example
  `feat/android-offline-tts` or `fix/server-session-leak`, and merge it through a
  pull request.
- One branch, one purpose: do not mix apps or unrelated changes in one branch.

## Commits

[Conventional Commits](https://www.conventionalcommits.org/), in English:

```
<type>(<scope>): <summary in imperative mood>

<optional body: why, not what>
```

- `type`: `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `build`, `ci`, `chore`.
- `scope`: the app — `android`, `editor`, `server` — or `repo` for root-level
  files. A commit touches one scope.
- Keep commits small and self-contained. A file move or reformat goes in its own
  commit, separate from behaviour changes.
- Release bumps do not go in the subject; tag the commit instead (below).

## Releases and tags

Each app is versioned on its own. Tag the release commit with `<app>/v<version>`:

```bash
git tag -a android/v1.0.10 -m "Glotalk Android 1.0.10"
git push origin android/v1.0.10
```

The version in the tag must match the app's version metadata
(`android/app/build.gradle.kts`, `server/rust/Cargo.toml`, `editor/package.json`)
and its `CHANGELOG.md` entry.

## Large files

Only small models the app bundles are committed (the editor's OCR models). Large
downloaded models, build output and test media are not committed; each app's
`.gitignore` covers them.
