# Review Panel Controls Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decouple the Review overlay from panel visibility and replace phone edge controls with an accessible Review FAB and icon-only selection actions.

**Architecture:** `ReaderScreen` owns three independent saved states: overlay, Contents, and Review panel. `AdaptiveReaderScaffold` renders the correct surface for each layout, while `SelectionFlyout` owns the compact semantic action group. Phone uses explicit top-bar controls plus a FAB; tablet retains existing tap-only rail controls.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Compose UI testing, Android instrumentation tests.

## Global Constraints

- Never use edge drag/swipe gestures to open or close panels.
- Review toggle must not assign to Contents or Review panel state in any layout.
- Remove the phone `EdgeControl`; retain tablet rail controls as tap-only controls.
- Every new icon action has a 44dp target, TalkBack label, and tooltip.
- Signal actions use both existing color and a distinct semantic icon.

---

### Task 1: Decouple reader state and phone panel affordances

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/AdaptiveReaderScaffold.kt`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/AdaptiveReaderTest.kt`

**Interfaces:**
- Produces: independent `reviewEnabled`, `reviewExpanded`, and `contentsExpanded` transitions.

- [ ] **Step 1: Add failing interaction tests**

Add phone, tablet portrait, and tablet landscape cases asserting that toggling Review changes the text overlay but leaves both panels closed/open exactly as before. Add a phone assertion that no node with the old `Expand review panel` edge-control label exists.

- [ ] **Step 2: Run the focused tests**

Run: `ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.AdaptiveReaderTest`

Expected: FAIL because `onToggleReview` assigns `reviewExpanded = enabled` and closes Contents in tablet portrait.

- [ ] **Step 3: Implement independent state transitions**

In `ReaderScreen.kt`, change `onToggleReview` to only set `reviewEnabled` and call `callbacks.onReviewModeChanged`. Keep panel mutual exclusion only in explicit `onExpandContents` and `onExpandReview` handlers. Initialise `reviewExpanded` independently from the overlay so enabling Review never opens it.

In `AdaptiveReaderScaffold.kt`, remove the PHONE `EdgeControl` branch. Add a lower-right `FloatingActionButton` when `policy.mode == PHONE && reviewEnabled && !reviewExpanded`; use `onExpandReview`, a 48dp Material target, `contentDescription = "Open review panel"`, and a tooltip.

- [ ] **Step 4: Verify focused tests pass**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit**

`git add app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt app/src/main/java/net/inkyquill/pocketeditor/ui/reader/AdaptiveReaderScaffold.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/AdaptiveReaderTest.kt && git commit -m "fix: decouple review overlay from panel"`

### Task 2: Replace selection text actions with accessible semantic icons

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/SelectionFlyout.kt`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt`

- [ ] **Step 1: Add failing flyout assertions**

Assert the flyout exposes five 44dp actions with labels Note, Warning, Change needed, Review, and Edit; assert the visible Edit text is absent while the Edit icon action remains clickable.

- [ ] **Step 2: Run the focused test**

Run: `ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.ReviewInteractionTest#signalComposerChangesColorAcceptsEmptyCommentAndNeedsExplicitSaveOrCancel`

Expected: FAIL because `AssistChip` renders text labels and Edit is textual.

- [ ] **Step 3: Implement the icon group**

Replace text chips with a grouped row of `IconButton`s: note icon for Note, warning triangle for Warning, error marker for Change needed, question marker for Review, and Pencil for Edit. Use existing `LocalReviewColors`, `minimumInteractiveComponentSize`, semantic content descriptions, and `TooltipBox` for each action.

- [ ] **Step 4: Verify and commit**

Run the Step 2 command, then commit SelectionFlyout and its test with message `feat: compact review selection actions`.

### Task 3: Remove persistent chapter controls and complete verification

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowScreenshotTest.kt`

- [ ] **Step 1: Add failing reader assertions**

Assert Contents remains accessible from the top app bar and the reader contains neither Previous nor Next buttons.

- [ ] **Step 2: Remove `ChapterButton` bottom row**

Delete the previous/next row and its now-unused helper/imports; retain chapter navigation solely through Contents.

- [ ] **Step 3: Run full verification**

Run: `./gradlew lintDebug testDebugUnitTest assembleDebug`

Run: `ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest`

Expected: lint and unit tests pass; instrumentation has no failures apart from intentionally skipped capture tests.

- [ ] **Step 4: Commit**

`git add app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowScreenshotTest.kt && git commit -m "feat: streamline reader navigation"`
