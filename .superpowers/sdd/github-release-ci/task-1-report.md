# Task 1 report — GitHub release CI and version display

## Status

Complete locally. No release was pushed, dispatched, or published. The workflow
will publish only after Release Please creates a release from a push to `main`.

## Commits

- `a425f10 ci: add signed GitHub release workflow`

## Changed files

- `.github/workflows/android.yml`
- `.release-please-manifest.json`, `release-please-config.json`, `version.txt`, and `CHANGELOG.md`
- `app/build.gradle.kts`
- `app/src/main/java/net/inkyquill/pocketeditor/ui/settings/AppearanceScreen.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/settings/AppearanceScreenVersionTest.kt`
- `app/src/test/java/net/inkyquill/pocketeditor/ReleaseWorkflowPolicyTest.kt`
- `docs/runbooks/release.md`

## TDD evidence

1. Extended `ReleaseWorkflowPolicyTest` before implementing the workflow,
   metadata, Gradle version override behavior, and version UI.
2. RED: `./gradlew testDebugUnitTest --tests net.inkyquill.pocketeditor.ReleaseWorkflowPolicyTest`
   compiled and failed 5 of 9 policy tests because Release Please metadata,
   release gating, PR validation, and version UI wiring did not exist.
3. GREEN: the same focused command passed after implementation (9 tests).
4. Negative validation: `POCKET_EDITOR_VERSION_CODE=0 ./gradlew help` failed
   as expected with `POCKET_EDITOR_VERSION_CODE must be a positive Android-safe integer`.

## Verification

| Command | Result |
| --- | --- |
| `jq empty .release-please-manifest.json && jq empty release-please-config.json` | PASS |
| Ruby `YAML.safe_load` for `.github/workflows/android.yml` | PASS |
| `./gradlew testDebugUnitTest --tests net.inkyquill.pocketeditor.ReleaseWorkflowPolicyTest` | PASS |
| `./gradlew testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin` | PASS (62 tasks) |
| `./gradlew test` | PASS |
| `git diff --check` | PASS before commit |

## Release behavior delivered

- PR title validation uses `actions/github-script` and reads the title from the
  event payload rather than interpolating it into shell.
- `main` verification and emulator jobs gate Release Please, which is pinned
  to the required commit and writes root simple-release metadata.
- The signed job checks out the exact release SHA, validates the constrained
  tag and SHA before using Git/GitHub CLI, builds with the release version and
  a bounded GitHub run-number code, verifies signer certificates and SHA-256,
  then idempotently uploads only the signed APK and checksum to that release.
- The temporary keystore is decoded from standard input under the runner temp
  directory, mode-restricted, scoped to secret-bearing steps, and removed in
  an `always` cleanup step.
- Appearance settings now show the localized accessible BuildConfig version.
  `AppearanceScreenVersionTest` provides Compose instrumentation coverage.

## Device and runtime gaps

- Instrumentation source compiled, but no emulator/physical-device test was
  run in this task. The workflow's emulator job remains the CI runtime gate.
- GitHub Actions was not executed; the protected `release` environment secrets
  still need manual entry in GitHub as documented in the runbook.
- No signing keystore, credential, `.env`, or key path contents were read or
  printed.

## Concerns

- Release Please and signed-release behavior is validated structurally and by
  policy tests; the first real release should be observed in GitHub Actions to
  confirm the environment approval and release asset permissions.
