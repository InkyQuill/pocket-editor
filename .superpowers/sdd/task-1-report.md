# Task 1 report: decouple reader state and phone panel affordances

## Scope and result

The assigned worktree already contained the complete Task 1 implementation. The
functional change is commit `a7bfa62e169c4b376f2dbabe80b22474fa9b9375`
(`fix: decouple review overlay from panel`), which is an ancestor of the task
branch's starting `HEAD`.

It changes only the three files named in the brief:

- `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/AdaptiveReaderTest.kt`
- `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/AdaptiveReaderScaffold.kt`
- `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt`

The implementation keeps `reviewEnabled` independent from panel expansion,
retains panel mutual exclusion in explicit expansion handlers, and replaces the
phone edge control with a 48dp lower-right FAB labelled `Open review panel` and
a tooltip.

## RED evidence

No new RED run was possible in this task execution: the task worktree began
after the functional commit above and its interaction tests already passed.
`git show a7bfa62` shows that the commit added the phone, tablet portrait, and
tablet landscape interaction assertions at the same time as the minimal
implementation. This is therefore inherited test-first evidence rather than a
fresh observed RED result; no production code was changed in this task run.

## Verification

Prescribed command attempted:

```sh
ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.AdaptiveReaderTest
```

Result: exit 0 after compilation, but Android Gradle Plugin reported `No
installables found in test fixture. Nothing to install.` for the connected task,
so it did not execute the tests.

Fresh emulator verification used the APKs built by that command:

```sh
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w -r \
  -e class net.inkyquill.pocketeditor.ui.AdaptiveReaderTest \
  net.inkyquill.pocketeditor.test/androidx.test.runner.AndroidJUnitRunner
```

Result: `OK (25 tests)` in 27.8 seconds. This includes the three task-specific
toggle tests: phone, tablet portrait, and tablet landscape.

## Self-review

- The test suite asserts the review overlay text changes while panels retain
  their expansion state for phone, portrait tablet, and landscape tablet.
- Phone asserts the old `Expand review panel` edge-control label is absent and
  the new `Open review panel` control is clickable.
- `ReaderScreen` toggle mutates only `reviewEnabled` and calls the callback.
- No reader/review UI changes beyond the task brief were made in this run.

## Commit

Functional implementation: `a7bfa62e169c4b376f2dbabe80b22474fa9b9375`.
This report is committed separately as the task handoff record.
