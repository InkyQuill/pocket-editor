# Import Feedback and Inline Annotation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give folder import immediate, informative feedback and create selected-text signals/edits in a local composer rather than the chapter Review panel.

**Architecture:** Keep import progress as a local `FolderBrowserScreen` state while `BookLibraryController.openFolder(path)` suspends. Keep Review as a chapter overview and introduce a reader-scoped composer host that consumes the existing `ReviewDraftSession` and callbacks; a placement model chooses below, above, then device-specific fallback.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Compose instrumentation tests, existing `EditorialReviewController` and Room-backed drafts.

## Global Constraints

- Preserve the platform Copy/Select all toolbar and all existing dirty-draft protection.
- Every new touch action has a 44 dp target and content description.
- Do not create a `BookDestination` for import progress.
- On tablet fallback use an independent modal, never Review or a bottom sheet.
- Review remains chapter overview: it never renders `SignalComposer` or `EditComposer` for a draft.

---

### Task 1: Folder preview and immediate import feedback

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/BookLibraryController.kt:35-40`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/FolderBrowserScreen.kt:37-120`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/navigation/PocketEditorRoot.kt:108-123`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowScreenshotTest.kt`

**Interfaces:**
- Produces: `FolderListing.otherFiles: Int` populated by browse mapping.
- Produces: `FolderBrowserScreen(..., choosingFolder: Boolean, onChooseThisFolder: () -> Unit)`.
- Consumes: existing suspend `BookLibraryController.openFolder(path)`.

- [ ] **Step 1: Write failing UI tests for preview and progress.**

```kotlin
compose.onNodeWithText("Markdown chapters").assertIsDisplayed()
compose.onNodeWithText("chapter-01.md").assertIsDisplayed()
compose.onNodeWithText("+2 more").assertIsDisplayed()
compose.onNodeWithText("Other files · 3").assertIsDisplayed()
compose.onNodeWithText("Use this folder").performClick()
compose.onNodeWithText("Reading files…").assertIsDisplayed()
compose.onNodeWithContentDescription("Reading selected folder").assertIsDisplayed()
```

- [ ] **Step 2: Run the focused test to verify it fails because preview groups and local progress do not exist.**

Run: `ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.BookFlowTest`

- [ ] **Step 3: Implement the minimal listing classification and local UI state.**

```kotlin
data class FolderListing(
    val path: String,
    val folders: List<RemoteFolder>,
    val markdown: List<String> = emptyList(),
    val otherFiles: Int = 0,
    val fromCache: Boolean = false,
)

var choosingFolder by rememberSaveable(listing?.path) { mutableStateOf(false) }
Button(
    enabled = listing != null && listing.markdown.isNotEmpty() && !choosingFolder,
    onClick = { choosingFolder = true; onChooseThisFolder(listing.path) },
) {
    if (choosingFolder) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text("Reading files…")
    } else Text("Use this folder")
}
```

Populate `otherFiles` from non-folder, non-Markdown remote entries. Render Folders, Markdown chapters (first eight plus overflow), and Other files count. Render **No Markdown files in this folder** when `markdown` is empty. Reset `choosingFolder` only when path changes or an error returns.

- [ ] **Step 4: Run focused behavior and screenshot tests.**

Run: `ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.BookFlowTest,net.inkyquill.pocketeditor.ui.BookFlowScreenshotTest`

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui/books app/src/main/java/net/inkyquill/pocketeditor/ui/navigation/PocketEditorRoot.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowScreenshotTest.kt
git commit -m "feat: show folder import progress and preview"
```

### Task 2: Reader-scoped annotation composer and Review overview

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt:249-610`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/SignalComposer.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/EditComposer.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/InlineAnnotationComposer.kt`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt`

**Interfaces:**
- Produces: `InlineAnnotationComposer(session, callbacks, placement, modalFallback)`.
- Consumes: `ReviewDraftSession`, `ReaderCallbacks`, active selection bounds, `ReaderLayoutPolicy`.
- Produces: `AnnotationComposerPlacement.Below | Above | PhoneSheet | TabletModal`.

- [ ] **Step 1: Write failing interaction tests.**

```kotlin
compose.onNodeWithTag("reader-text-0")
    .performSemanticsAction(SemanticsActions.SetSelection) { it(0, 5, false) }
compose.onNodeWithContentDescription("Add note").performClick()
compose.onNodeWithTag("inline-annotation-composer").assertIsDisplayed()
compose.onAllNodesWithTag("review-sheet").assertCountEquals(0)
compose.onNodeWithTag("inline-annotation-input").assertIsFocused()
compose.onNodeWithText("Save").performClick()
```

Also assert a near-right selection yields action bounds inside `reader-root`, and Review opened separately renders the chapter note/record list but no active composer.

- [ ] **Step 2: Run the focused test to verify it fails because the composer is in `ReviewShell`.**

Run: `ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.ReviewInteractionTest`

- [ ] **Step 3: Introduce the inline composer host and placement model.**

```kotlin
private fun annotationPlacement(
    selection: Rect,
    viewport: Rect,
    composerHeightPx: Float,
    tablet: Boolean,
): AnnotationComposerPlacement = when {
    viewport.bottom - selection.bottom >= composerHeightPx -> Below
    selection.top - viewport.top >= composerHeightPx -> Above
    tablet -> TabletModal
    else -> PhoneSheet
}
```

Place the flyout and card with a horizontally clamped offset. `InlineAnnotationComposer` renders `SignalComposer` or `EditComposer`, owns a `FocusRequester`, and requests focus in `LaunchedEffect(draft.recordId, draft::class)`. Move both composer branches out of `ReviewShell`; leave ReviewShell with `ChapterNote`, saved records/navigation, conflict handling, and close affordance only.

- [ ] **Step 4: Run focused interaction tests.**

Run: `ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.ReviewInteractionTest`

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt app/src/main/java/net/inkyquill/pocketeditor/ui/review app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt
git commit -m "feat: compose selected-text annotations inline"
```

### Task 3: Cross-layout regression coverage and release verification

**Files:**
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/AdaptiveReaderTest.kt`
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt`
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt`

**Interfaces:**
- Consumes: Task 1 folder preview/loading semantics and Task 2 placement tags.
- Produces: coverage for phone/tablet fallback, Review separation, error recovery, and empty folders.

- [ ] **Step 1: Add failing cross-layout tests.**

```kotlin
setReader(DpSize(1280.dp, 800.dp), reviewEnabled = true)
selectText("reader-text-0", 0, 5)
compose.onNodeWithContentDescription("Add note").performClick()
compose.onNodeWithTag("inline-annotation-modal").assertIsDisplayed()
compose.onAllNodesWithTag("review-sidebar").assertCountEquals(0)
```

Add equivalent phone fallback test for a selection with no space above or below, and a folder error test which verifies the button becomes enabled again after error recovery.

- [ ] **Step 2: Run focused tests to verify pre-change failure.**

Run: `ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.AdaptiveReaderTest,net.inkyquill.pocketeditor.ui.ReviewInteractionTest,net.inkyquill.pocketeditor.ui.BookFlowTest`

- [ ] **Step 3: Make only test-support or layout corrections required by the assertions.**

Keep production behavior from Tasks 1–2 unchanged except exact viewport insets or semantic tags needed for the specified behavior.

- [ ] **Step 4: Run full verification.**

```bash
./gradlew lintDebug testDebugUnitTest assembleDebug
ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest
```

Expected: build succeeds; all instrumentation tests pass with only documented screenshot/minified skips.

- [ ] **Step 5: Commit.**

```bash
git add app/src/androidTest/java/net/inkyquill/pocketeditor/ui
git commit -m "test: cover import and inline annotation fallbacks"
```

## Plan self-review

- Folder preview, local loading, empty/race error handling map to Task 1.
- Inline creation, clamping, focus, phone/tablet fallback, Review separation map to Task 2.
- Cross-layout and end-to-end regression requirements map to Task 3.
- No new destination, no tablet sheet, platform selection preservation, 44 dp targets, and dirty-draft safety are global constraints.

