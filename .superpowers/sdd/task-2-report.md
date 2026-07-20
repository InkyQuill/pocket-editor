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
