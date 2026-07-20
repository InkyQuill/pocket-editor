# Task 2 report: reader-scoped annotation composer

## RED

Added `selectedTextComposerStaysInlineAndReviewOverviewHasNoActiveComposer` to `ReviewInteractionTest` before production changes. The first executable test attempt exposed a test-only `Rect.contains(Rect)` type mismatch; after correcting that assertion, the required focused command failed as expected because no node had content description `Add note`. This demonstrated that the selected-text action/composer still lived in the previous Review-shell flow.

Command:

```text
ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.ReviewInteractionTest
```

Expected failure: `could not find any node ... ContentDescription = 'Add note'`.

## GREEN

Implemented the reader-scoped `InlineAnnotationComposer`, placement enum and viewport placement calculation. The reader captures the selected anchor, clamps the inline card horizontally, focuses its input on draft identity changes, and provides phone/tablet fallback placements. `ReviewShell` no longer renders either active composer. Existing interaction checks were updated from ReviewShell-composer expectations to the Review-overview behavior.

Fresh verification used the same focused command above. Result XML:

```text
tests=11 failures=0 errors=0 skipped=0
```

## Files

- `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt`
- `app/src/main/java/net/inkyquill/pocketeditor/ui/review/InlineAnnotationComposer.kt`
- `app/src/main/java/net/inkyquill/pocketeditor/ui/review/SignalComposer.kt`
- `app/src/main/java/net/inkyquill/pocketeditor/ui/review/EditComposer.kt`
- `app/src/main/java/net/inkyquill/pocketeditor/ui/review/SelectionFlyout.kt`
- `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt`

## Commit

`cbbbeae feat: compose selected-text annotations inline`

## Follow-up: tablet overlay coordinate boundary

`ReaderPane` mounts the selection flyout and the Below/Above inline composer
inside the centered `reader-column`. Their horizontal offset must therefore be
column-local. The prior use of `anchoredHorizontalOffsetInRoot()` added the
centered column's root X a second time.

### RED

Added `centeredTabletReaderColumnKeepsRenderedSelectionFlyoutAndComposerInsideColumn`
to `ReviewInteractionTest`. Before the coordinate fix, the centered-column
instrumentation scenario placed the inline composer outside its visible reader
column. The regression asserts actual semantics bounds for both the flyout and
the inline composer against `reader-column`; it does not only test the offset
helper.

### GREEN

Switched the flyout plus both `AnnotationComposerPlacement.Below` and
`AnnotationComposerPlacement.Above` branches to `anchoredHorizontalOffset()`,
which is local to the reader-column parent.

Verified on `emulator-5556`:

```text
ANDROID_SERIAL=emulator-5556 ./gradlew --console=plain --quiet :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.ReviewInteractionTest
ANDROID_SERIAL=emulator-5556 ./gradlew --console=plain --quiet :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.AdaptiveReaderTest
```

## Follow-up: anchor identity, measured placement, and dismissal safety

The transient inline anchor is now tied to the exact `ReviewSelection` and draft kind which created it. Save, Cancel, or a non-matching replacement draft clears it, so restored or existing drafts without a current anchor use the independent phone sheet or tablet dialog.

Inline placement begins with a conservative estimate and is re-evaluated from the composer's measured height. A long edit therefore falls back to the modal before it can extend outside the reader viewport.

`ReviewInteractionTest` now covers the saved-inline-then-restored-draft sequence, long-edit fallback, and actual Dialog back/outside dismissal attempts on a dirty draft; the draft remains until the explicit Cancel action.

Verification:

```text
ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.ReviewInteractionTest
tests=18 failures=0 errors=0 skipped=0
```

## Tablet fixture correction

`tabletSelectionUsesAnAccessibleInlineComposer` used a 601×360 dp child while only changing the logical window size. On emulator-5556, that root is physically cramped: a 320 dp composer cannot fit above or below the selection, so the correct tablet behavior is the `TabletModal` fallback. The test is now named `crampedTabletSelectionUsesAnAccessibleModalComposer`; it uses an actual 360×360 dp root with tablet policy dimensions and asserts that both `inline-annotation-modal` and the composer are displayed. This preserves the accessibility check and distinguishes the tablet dialog fallback from the phone bottom-sheet fallback.

Fresh focused verification (emulator-5556 only):

```text
ANDROID_SERIAL=emulator-5556 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.ReviewInteractionTest,net.inkyquill.pocketeditor.ui.AdaptiveReaderTest
tests=41 failures=0 errors=0 skipped=0
```

## Review-finding follow-up

- Reader flyout and adjacent composer now use the reader column as their shared
  viewport coordinate system. Both clamp horizontally; placement reserves the
  required 8 dp clearance and flips Above before fallback.
- Pencil now captures the same draft anchor as every signal action, so a dirty
  Edit remains visible after its selected lazy-list block disposes.
- Phone fallback is a Material `ModalBottomSheet`; tablet fallback is an
  independent Compose `Dialog`. Dirty drafts decline outside/back dismissal.
- Added regressions for near-right action clamping, gap-aware Above placement,
  phone sheet, narrow tablet modal, and dirty Edit disposal. Existing action
  assertions continue to cover 44 dp touch targets and the no-Review-open flow.

Fresh focused verification:

```text
ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.ReviewInteractionTest,net.inkyquill.pocketeditor.ui.AdaptiveReaderTest

OK (40 tests)
```

## Important review follow-up: centered columns and no-anchor drafts

- Corrected reader-scoped overlay coordinates: flyout and inline composer offsets
  are now returned in root coordinates, including the centered reader column's
  left edge. This keeps a clamped Below composer within the tablet reading
  column rather than drifting into the outer scaffold.
- A non-null draft without an ephemeral text-selection anchor now remains
  accessible from the reader through the independent fallback surface: a phone
  sheet on phones or a tablet dialog on larger layouts. This covers restored
  dirty drafts and saved-record edits without rendering an active composer in
  the Review overview.
- Added `centeredTabletBelowComposerClampsInsideReaderColumn` to cover the
  centered-column Below clamp, and
  `restoredSavedRecordDraftWithoutAnchorUsesIndependentComposerFallback` to
  assert the accessible no-Review fallback. Updated draft survival assertions
  to verify the now-intended reader composer across adaptive changes.

Focused verification:

```text
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.ReviewInteractionTest,net.inkyquill.pocketeditor.ui.AdaptiveReaderTest
```
