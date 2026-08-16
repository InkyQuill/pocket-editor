# Task 5 implementation report

Status: COMPLETE_REVIEW_CLEAN

## Scope

- Modified only `ReviewInteractionTest.kt`, `ReaderScreenshotTest.kt`, and this report.
- Did not modify production, CI, release/version configuration, or screenshot artifacts/goldens.

## Instrumentation coverage

- Added a forward end-handle gesture from a one-character long-press selection into the adjacent paragraph. The test drives the real `EditorialReviewController.readerCallbacks`, chooses the Note action, enters a comment, saves through the composer, and asserts the recorded production `Signal` contains the literal `x\n\nBeta` source slice and comment.
- Added a reverse start-handle gesture across the one-character seam in the following paragraph. The fixture includes a list marker, projected signal dot, existing comment card, and an opened footnote popup. The saved source is asserted as the literal `x[^1]\n\nReviewed ` and explicitly excludes the bullet glyph, signal label text, comment-card text, and popup body.
- Added a continuous end-handle drag held at the viewport edge to trigger LazyColumn auto-scroll through three tall blocks. The saved signal must start at the selected `x`, reach the third block, contain exactly both `\n\n` separators, and equal the canonical UTF-8 byte slice identified by the saved anchor.
- Existing same-block start-handle and distinct one-character start/end cursor-edge tests remain in place, so the file now covers forward and reverse handle ownership plus one-character seams.
- Added an opt-in `multiBlockSelection=true` screenshot scene. It renders the real projected two-paragraph document and performs the cross-block handle gesture before capture. No screenshot was generated or accepted because runtime review was unavailable.

## Verification

- `./gradlew compileDebugAndroidTestKotlin`: PASS.
- `./gradlew testDebugUnitTest lintDebug compileDebugAndroidTestKotlin`: PASS, 44 tasks, zero failures.
- `adb devices -l`: no attached devices.
- `emulator -list-avds`: no configured AVDs.
- `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.ReviewInteractionTest`: NOT RUN; Gradle assembled both APKs and then failed only with `DeviceException: No connected devices!`.

## TDD/runtime concern

These tests protect already-implemented Task 3/4 behavior and their instrumentation sources compile. A genuine gesture RED/GREEN cycle and coordinate tuning require Android runtime execution; they could not be claimed without a device or AVD. The connected run, including screenshot scene review, remains mandatory before accepting runtime behavior or any golden image.

## Fix round 1/5

Status: DONE_WITH_CONCERNS

- The footnote exclusion fixture now keeps the popup and its `popup secret` body visibly open throughout the reverse cross-block gesture, signal composition, and save. It uses an 800dp fixture and asserts every gesture point lies geometrically to the left of the popup before touching the underlying reader. The exact saved source remains `x[^1]\n\nReviewed `, excluding the list bullet, signal chrome, comment card, popup body, and hidden footnote definition.
- The auto-scroll fixture now contains Cyrillic plus emoji and converges the scrolled end handle on a fixed third-block cursor. It asserts one fixed expected source string, exactly two separators, independently calculated `startByte = 0` and UTF-8 `endByte`, the exact emoji endpoint, and absence of the following tail marker.
- Every new reader gesture now receives the same explicit `Density(renderDensityFor(viewport), 1f)` installed in the fixture. Handle margins convert through that density, and long presses use `ViewConfiguration.getLongPressTimeout() + 100ms`.
- The opt-in screenshot scene captures its actual `LocalDensity` and blocks until the production selection callback reports exactly `x\n\nBeta`; a one-character selection now fails before any image can be written.
- No production source or golden image was changed.

Verification after the fixes:

- `./gradlew compileDebugAndroidTestKotlin`: PASS. The only warning remains the pre-existing `SHOW_IMPLICIT` deprecation.
- Connected runtime remains `NOT RUN`: no device and no configured AVD are available. Full unit/lint/instrumentation-source verification is rerun immediately before the fix commit.

## Final review

- Reviewer verdict for `bb52bd3`: APPROVED; all 3 Important findings and the Minor finding are closed after fix round 1/5.
- Final available gate: `./gradlew testDebugUnitTest lintDebug compileDebugAndroidTestKotlin` — PASS, 44 tasks, zero failures.
- Instrumentation runtime remains pending: `connectedDebugAndroidTest` cannot run because no device is attached and `emulator -list-avds` reports no configured AVD.
- The opt-in screenshot scene remains source-only and unaccepted; no PNG or golden was generated without runtime review.

## Whole-plan final-review fix

- Closed the final Important finding where `ReaderPane` rendered with its optimistic local review-mode value while its selection generation was still keyed to the stale parent `ReaderState.reviewEnabled` value.
- `selectionGeneration` now uses the same effective local `reviewEnabled` value passed to every `ReaderDocumentBlock`. A local mode change therefore changes the generation immediately, clears the shared Compose selection, removes the flyout, and reports `onTextSelected(null)` even before the parent publishes a refreshed `ReaderState`.
- Added a JVM regression for the effective-mode generation boundary and an Android Compose regression that keeps the parent state deliberately stale while toggling review mode with an active selection/flyout.

TDD and verification:

- RED: focused JVM compilation failed at the new regression with `Unresolved reference 'readerSelectionGeneration'` before the production boundary existed.
- GREEN: `./gradlew testDebugUnitTest --tests net.inkyquill.pocketeditor.ui.reader.ReaderSelectionAdapterTest compileDebugAndroidTestKotlin` passed.
- Full available gate: `./gradlew testDebugUnitTest lintDebug compileDebugAndroidTestKotlin` passed, 44 tasks, zero failures.
- The Compose regression was compiled but not executed here because this scoped fix explicitly excluded emulator/runtime actions; it is ready for the plan-level emulator pass.
