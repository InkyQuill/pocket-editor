# UX Bug Fixes and Design Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the nine defects and polish items catalogued in
`docs/superpowers/specs/2026-07-21-ux-bugfixes-and-polish-design.md` — a
broken local `.env` client-ID path, a mispositioned Review FAB, an
unreliable panel-dismiss gate, overlapping selection UI, an unresponsive
appearance preview, and several layout/whitespace issues — without
reopening any decision already settled in
`2026-07-20-review-mobile-gestures-design.md` or
`2026-07-20-import-feedback-and-inline-annotation-design.md`.

**Architecture:** Each task is scoped to the exact files/lines identified
during spec research, is independently testable, and follows the priority
order the spec itself lays out: build fix first, then the coupled
FAB/dismiss-invariant work, then the shared-file selection-UI fixes, then
independent polish. Tasks that touch files already covered by existing
Compose instrumented tests (`app/src/androidTest/.../ui/*.kt`) extend those
files rather than creating parallel ones, matching the codebase's existing
per-feature test-file convention.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Gradle Kotlin DSL,
JUnit4 Compose instrumented tests (`androidx.compose.ui.test.junit4`), Lucide
icons for Compose (new dependency, added in Task 5).

---

## File Structure

| File | Responsibility | Touched by |
|---|---|---|
| `app/build.gradle.kts` | `.env` loading, `YANDEX_CLIENT_ID` resolution | Task 1 |
| `app/src/main/java/net/inkyquill/pocketeditor/ui/review/EditorialReviewController.kt` | Chapter-note autosave, dismiss-blocking state | Tasks 3, 4 |
| `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt` | Top bar, review toggle, selection flyout/composer positioning, Contents fallback shell | Tasks 4, 6, 9, 11 |
| `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/AdaptiveReaderScaffold.kt` | Phone/tablet-portrait/tablet-landscape layout, Review FAB | Task 7, 8 |
| `gradle/libs.versions.toml`, `app/build.gradle.kts` (deps block) | Lucide dependency | Task 5 |
| `app/src/main/java/net/inkyquill/pocketeditor/ui/review/SelectionFlyout.kt` | Selection action icons | Task 10 |
| `app/src/main/java/net/inkyquill/pocketeditor/ui/review/SignalComposer.kt`, `EditComposer.kt`, `InlineAnnotationComposer.kt` | Composer card padding/color | Task 11 |
| `app/src/main/java/net/inkyquill/pocketeditor/ui/settings/AppearanceScreen.kt` | Text-size preview, screen sizing | Tasks 12, 16 |
| `app/src/main/java/net/inkyquill/pocketeditor/ui/contents/ContentsPanel.kt` | Chapter list rows, dividers, sizing | Tasks 13, 17 |
| `app/src/main/java/net/inkyquill/pocketeditor/ui/books/BooksScreen.kt` | Empty-state spacing | Task 15 |
| `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/AdaptiveReaderTest.kt` | Existing FAB/EdgeControl tests needing migration | Task 7 |
| `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt` | Dismiss-invariant, chapter-note tests | Tasks 3, 4 |
| `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ContentsSearchClickTest.kt` (new) | Search-result click regression | Task 2 |

---

## Task list (execute in this order)

1. `.env` loader and loud release-build failure (spec item 1)
2. Search-result click regression test (spec item 3)
3. Stop autosaving an unchanged empty chapter note (spec item 6, general fix)
4. Dismiss invariant: X button and FAB-close always work absent a dirty draft (spec item 6 core, item 5f)
5. Add the Lucide icon dependency (spec item 5e, library only)
6. Re-skin the review-visibility toggle as an icon button (spec items 5a, 5b)
7. Replace the broken FAB with one unified `ReviewFab` (spec items 5c, 5d guard) + migrate existing tests
8. Reader bottom padding so the FAB never blocks the last paragraph (spec item 5d)
9. Selection flyout collision avoidance (spec item 2)
10. Swap flyout/toggle/FAB icons to Lucide (finish spec item 5e)
11. Composer card padding and explicit surface color (spec item 4a, 4b)
12. Composer horizontal edge margin (spec item 4, clamp)
13. Text-size live preview (spec item 7)
14. Contents drawer chapter list: dividers, unselected-row treatment (spec item 8)
15. Home screen empty-state spacing (spec item 9, `BooksScreen.kt`)
16. Appearance screen sizing (spec item 9, `AppearanceScreen.kt`)
17. Contents drawer chapter list intrinsic height (spec item 9, `ContentsPanel.kt`)

---

### Task 1: `.env` loader and loud release-build failure

**Files:**
- Modify: `app/build.gradle.kts:1-33`

There is no Gradle TestKit harness in this repo (verified: no
`gradleTestKit()` or `org.gradle.testkit` dependency anywhere), so this
task is verified by direct `./gradlew` invocations rather than a unit test
— matching how the spec's own Verification section for this item already
specifies manual rebuilds.

- [ ] **Step 1: Confirm current broken behavior**

Run: `cd /home/inky/Development/pocket-editor && env -u YANDEX_CLIENT_ID ./gradlew :app:assembleDebug --console=plain -q && /home/inky/Android/Sdk/build-tools/36.0.0/aapt2 dump xmltree app/build/outputs/apk/debug/app-debug.apk --file AndroidManifest.xml | grep -A1 "com.yandex.auth.CLIENT_ID"`
Expected: `android:value(...)="unset"` — reproduces the bug before any fix.

- [ ] **Step 2: Add the `.env` loader and replace the resolution chain**

Replace the top of `app/build.gradle.kts` (currently lines 1-33) with:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Naive KEY=VALUE reader for the local-dev `.env` file. Not a general-purpose
// dotenv implementation: no quoting, no escaping, no multiline values. CI
// never relies on this — it supplies YANDEX_CLIENT_ID purely as an OS env
// var from a GitHub secret (.github/workflows/android.yml), so this loader
// only has to cover the flat "YANDEX_CLIENT_ID=..." line developers keep in
// their local, gitignored .env.
val dotEnv: Map<String, String> = rootProject.file(".env")
    .takeIf { it.exists() }
    ?.readLines()
    ?.mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) return@mapNotNull null
        val key = trimmed.substringBefore("=").trim()
        val value = trimmed.substringAfter("=").trim()
        key to value
    }
    ?.toMap()
    ?: emptyMap()

fun envOrProperty(key: String): Provider<String> =
    providers.gradleProperty(key)
        .orElse(providers.environmentVariable(key))
        .orElse(providers.provider { dotEnv[key] })

val releaseStoreFile = providers.environmentVariable("POCKET_EDITOR_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("POCKET_EDITOR_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("POCKET_EDITOR_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("POCKET_EDITOR_RELEASE_KEY_PASSWORD").orNull
val releaseSigningReady = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

val resolvedYandexClientId: String = envOrProperty("YANDEX_CLIENT_ID").orElse("").get()
val releaseFacingTasks = setOf("assembleRelease", "bundleRelease")
gradle.taskGraph.whenReady {
    val runningReleaseTask = allTasks.any { it.name in releaseFacingTasks }
    if (runningReleaseTask && resolvedYandexClientId.isBlank()) {
        throw GradleException(
            "YANDEX_CLIENT_ID is not set. Add it to .env, pass -PYANDEX_CLIENT_ID=..., " +
                "or set the YANDEX_CLIENT_ID environment variable before running a release build.",
        )
    }
}

android {
    namespace = "net.inkyquill.pocketeditor"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.inkyquill.pocketeditor"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["YANDEX_CLIENT_ID"] = resolvedYandexClientId.ifBlank {
            logger.warn("YANDEX_CLIENT_ID unset — Yandex sign-in will not work in this build")
            "unset"
        }
    }
```

Leave everything from the `signingConfigs { ... }` block onward (currently
starting at line 35) unchanged.

- [ ] **Step 2b: Add the `Provider`/`GradleException` imports if the Kotlin DSL script requires them**

Gradle Kotlin DSL script files (`.gradle.kts`) resolve `org.gradle.api.provider.Provider` and
`org.gradle.api.GradleException` from the implicit Gradle API classpath without an explicit
`import` line — verify this compiles as-is in Step 3; only add explicit `import
org.gradle.api.provider.Provider` at the top of the file if the build fails with an unresolved
reference.

- [ ] **Step 3: Verify the debug build now picks up `.env`**

Run: `./gradlew :app:assembleDebug --console=plain -q && /home/inky/Android/Sdk/build-tools/36.0.0/aapt2 dump xmltree app/build/outputs/apk/debug/app-debug.apk --file AndroidManifest.xml | grep -A1 "com.yandex.auth.CLIENT_ID"`
Expected: `android:value(...)="70afca2605834d0fba5fa6d6b9076cda"` (the value
currently in this repo's `.env`), with no `-P`/env var passed on the command
line — proving the `.env` file alone is now sufficient.

- [ ] **Step 4: Verify debug build still works with no `.env` and no env var (warns, doesn't fail)**

Run: `mv .env .env.bak && env -u YANDEX_CLIENT_ID ./gradlew :app:assembleDebug --console=plain 2>&1 | tee /tmp/debug-build.log; mv .env.bak .env`
Expected: exit code 0 (`BUILD SUCCESSFUL`), and `/tmp/debug-build.log`
contains the line `YANDEX_CLIENT_ID unset — Yandex sign-in will not work in this build`.

- [ ] **Step 5: Verify release-facing tasks fail loudly with no `.env` and no env var**

Run: `mv .env .env.bak && env -u YANDEX_CLIENT_ID ./gradlew :app:assembleRelease --console=plain; echo "exit=$?"; mv .env.bak .env`
Expected: non-zero exit code, with `YANDEX_CLIENT_ID is not set. Add it to
.env, pass -PYANDEX_CLIENT_ID=..., or set the YANDEX_CLIENT_ID environment
variable before running a release build.` in the output.

- [ ] **Step 6: Verify release-facing tasks succeed when `.env` is present**

Run: `./gradlew :app:assembleRelease --console=plain -q; echo "exit=$?"`
Expected: exit code 0 (release build may still be unsigned locally — that's
governed by `releaseSigningReady`, unrelated to this task; only the
`YANDEX_CLIENT_ID` gate is being verified here).

- [ ] **Step 7: Verify CI's own path is untouched**

Run: `grep -n "YANDEX_CLIENT_ID" .github/workflows/android.yml`
Expected: unchanged from before this task — CI still supplies
`YANDEX_CLIENT_ID: ${{ secrets.YANDEX_CLIENT_ID }}` as an env var at the
same two locations (`signed-release` job); this task must not modify that
file.

- [ ] **Step 8: Commit**

```bash
git add app/build.gradle.kts
git commit -m "fix: load YANDEX_CLIENT_ID from .env and fail release builds on a blank value"
```

---

### Task 2: Search-result click — confirm existing coverage, add a touch-input variant

**Files:**
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt:294-329` (existing, read-only in this task)
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt`

**Important finding that changes this task's shape:** an existing test,
`contentsOwnsBookSwitchChapterOrderAndExactSourceSearch`
(`BookFlowTest.kt:294-329`), already renders `ContentsPanel` directly with a
non-empty `searchResults` list and calls
`compose.onNodeWithText("…пахло дождём…").performClick()`, asserting
`onSearchResult` fires with the correct `SearchNavigation`. This is exactly
the regression test the spec called for — it already exists and (per Step
1 below) already passes, which is strong evidence the click-to-navigate
*wiring* is correct and the on-device repro was not a logic bug in
`SearchScreen.kt`/`ContentsPanel.kt`.

- [ ] **Step 1: Run the existing test and confirm it passes today**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.BookFlowTest.contentsOwnsBookSwitchChapterOrderAndExactSourceSearch" --console=plain` (requires a running emulator/device — use the same `PocketEditor_API_35_A` AVD already set up)
Expected: `BUILD SUCCESSFUL`, 1 test passed.

This confirms `Surface(onClick = ...)` in `SearchScreen.kt:61` does fire on
a semantic click. `performClick()` dispatches a semantic click action, not
a full simulated touch-down/up sequence, so it does not rule out a
touch-dispatch or nested-scroll-gesture issue specific to real pointer
events — Step 2 closes that gap.

- [ ] **Step 2: Add a touch-input variant of the same scenario**

Add this test directly below
`contentsOwnsBookSwitchChapterOrderAndExactSourceSearch` in
`BookFlowTest.kt` (same file, so it reuses the existing `BOOKS` companion
fixture and existing imports — `SearchHit`, `SearchNavigation`,
`ContentsPanel`, `PocketEditorTheme` are already imported in this file):

```kotlin
    @Test
    fun searchResultRespondsToARealisticTouchDownAndUpNotJustASemanticClick() {
        var selectedSearch: SearchNavigation? = null
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ContentsPanel(
                    books = BOOKS,
                    currentBookId = BOOKS.first().bookId,
                    currentChapterId = "chapter-a",
                    query = "дождём",
                    searchResults = listOf(SearchHit("chapter-b", "Copper Gate", "…пахло дождём…", 7, 13, 48, 73)),
                    searching = false,
                    closeLabel = "Close contents",
                    onClose = {},
                    onSwitchBook = {},
                    onChapterSelected = {},
                    onQueryChanged = {},
                    onSearchResult = { selectedSearch = it },
                    onOpenBooks = {},
                    onAppearance = {},
                )
            }
        }

        compose.onNodeWithText("…пахло дождём…").performTouchInput { click(center) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(SearchNavigation("chapter-b", 48, 73), selectedSearch)
        }
    }
```

This needs `import androidx.compose.ui.test.performTouchInput` and
`import androidx.compose.ui.test.click` added to `BookFlowTest.kt`'s import
block if not already present — check first with
`grep -n "performTouchInput\|import androidx.compose.ui.test.click" app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt`
before adding, to avoid a duplicate-import compile error.

- [ ] **Step 3: Run both tests together**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.BookFlowTest.contentsOwnsBookSwitchChapterOrderAndExactSourceSearch" --tests "net.inkyquill.pocketeditor.ui.BookFlowTest.searchResultRespondsToARealisticTouchDownAndUpNotJustASemanticClick" --console=plain`
Expected: `BUILD SUCCESSFUL`, 2 tests passed.

- [ ] **Step 4: Record the conclusion**

If both tests pass (expected, given the wiring read correctly in both the
spec's research and here): the search-result click defect observed live is
not reproducible through either a semantic click or a simulated touch
click against `ContentsPanel` in isolation. Do not change
`SearchScreen.kt`/`ContentsPanel.kt` for this item — record in the task's
commit message that this is a closed investigation, not a fix, so a future
session doesn't reopen it without new evidence. If a live repro is needed
again, the next step beyond this plan's scope would be reproducing inside
the full `ModalBottomSheet`-hosted `ReaderScreen` (not `ContentsPanel`
alone) to test for nested-scroll/drag-handle gesture theft, since that
composition is what was actually on screen during the original repro.

- [ ] **Step 5: Commit**

```bash
git add app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt
git commit -m "test: add touch-input coverage confirming search-result click navigation works"
```

---

### Task 3: Stop autosaving an unchanged empty chapter note

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/EditorialReviewController.kt:162-165`
- Test: `app/src/test/java/net/inkyquill/pocketeditor/ui/review/EditorialReviewControllerTest.kt`

An existing JVM unit test file already covers this controller directly
(`EditorialReviewControllerTest.kt`, `FakeActions.saveChapterNote` already
records every save into `actions.notes`) — extend it, don't create a new
file.

- [ ] **Step 1: Write the failing test**

Add this test directly below `` `chapter note debounces and focus loss flushes immediately` `` (after line 138) in `EditorialReviewControllerTest.kt`:

```kotlin
    @Test
    fun `focus loss on an untouched empty chapter note does not attempt a save`() = runBlocking {
        val actions = FakeActions()
        val controller = controller(MarkdownParser.parse("Plain"), actions, MemoryDraftPersistence())

        controller.chapterNoteFocusLost()

        assertEquals(emptyList<String>(), actions.notes)
    }

    @Test
    fun `focus loss still saves when the user typed then cleared the note back to blank`() = runBlocking {
        val actions = FakeActions()
        val controller = controller(MarkdownParser.parse("Plain"), actions, MemoryDraftPersistence())

        controller.changeChapterNote("Draft")
        controller.changeChapterNote("")
        controller.chapterNoteFocusLost()

        assertEquals(listOf(""), actions.notes)
    }
```

- [ ] **Step 2: Run the tests to verify the first one fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.inkyquill.pocketeditor.ui.review.EditorialReviewControllerTest" --console=plain`
Expected: `focus loss on an untouched empty chapter note does not attempt a save` FAILS
with `expected: <[]> but was: <[]>`... actually with `AssertionFailedError`
showing `actions.notes` contains `[""]` (the current unconditional save),
not `[]`. The second new test should already PASS (it exercises the
existing correct behavior for a real edit-to-blank).

- [ ] **Step 3: Implement the fix**

In `EditorialReviewController.kt`, replace lines 162-165:

```kotlin
    suspend fun chapterNoteFocusLost() {
        noteJob?.cancel()
        saveChapterNote(mutableState.value.chapterNote)
    }
```

with:

```kotlin
    suspend fun chapterNoteFocusLost() {
        noteJob?.cancel()
        val current = mutableState.value.chapterNote
        if (current.isBlank() && pendingChapterNote == null) return
        saveChapterNote(current)
    }
```

`pendingChapterNote` (already a private field, set by `changeChapterNote` at
line 152 and cleared to `null` only after a confirmed-saved sync round-trip
in `updateChapterContext`) is `null` exactly when no local edit has been
staged since the note was last loaded/saved — so this only skips the save
when the note is blank *and* untouched, matching
`` `focus loss still saves when the user typed then cleared the note back to blank` ``
above, where `pendingChapterNote` is `""` (non-null) after the two
`changeChapterNote` calls.

- [ ] **Step 4: Run the tests again to verify both pass**

Run: `./gradlew :app:testDebugUnitTest --tests "net.inkyquill.pocketeditor.ui.review.EditorialReviewControllerTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, all tests in the class pass, including both new
ones and the pre-existing `` `chapter note debounces and focus loss flushes immediately` ``.

- [ ] **Step 5: Run the full unit test suite to check for regressions**

Run: `./gradlew :app:testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui/review/EditorialReviewController.kt app/src/test/java/net/inkyquill/pocketeditor/ui/review/EditorialReviewControllerTest.kt
git commit -m "fix: do not autosave a chapter note that is blank and was never edited"
```

---

### Task 4: Dismiss invariant — X button (and later, FAB-close) must always close the panel absent a dirty draft

**Files:**
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt`
- Read-only reference: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt:193` (`onDismissReview`), `app/src/main/java/net/inkyquill/pocketeditor/ui/review/ReviewDraft.kt:42-48` (`blocksDismissal`)

**Important finding that changes this task's shape:** reading
`ReaderScreen.kt:193` directly —

```kotlin
onDismissReview = { if (!reviewUiState.draftSession.blocksDismissal) reviewExpanded = false },
```

— shows the dismiss gate already depends **only** on
`draftSession.blocksDismissal` (`ReviewDraft.kt:42-48`, itself only about an
active Signal/Edit draft's dirtiness), not on `NoteSaveStatus` or
`ReaderSyncState`. This one function is shared by the panel's X button
(`PanelColumn`'s `onClose` in `ReaderShell`/`ReviewShell`) and the
`ModalBottomSheet`'s own `onDismissRequest` (swipe-down, scrim tap, system
Back) — both call the identical `onDismissReview`. During the original live
repro, the system Back gesture *did* successfully close the panel
immediately after the X-button tap appeared to do nothing, through this
exact same function — strong evidence the gating logic itself is already
correct, and the live symptom was most likely a missed tap target on the
physical device/emulator, not a logic gate. This task writes the tests the
spec calls for regardless, both to codify the invariant permanently and to
settle the question with evidence rather than assumption.

- [ ] **Step 1: Write the two tests**

Add these two tests to `ReviewInteractionTest.kt`, directly below
`` `chapterNoteFlushesOnlyAfterARealFocusedToUnfocusedTransition` `` (after
line 973). Both reuse the file's existing `setReader(...)` helper
(`ReviewInteractionTest.kt:975-986`) and existing imports
(`ReviewUiState`, `NoteSaveStatus`, `ReviewDraftSession`, `ReviewDraft`,
`ReviewSelection`, `RawRange`, `SignalType` are all already imported in
this file):

```kotlin
    @Test
    fun reviewPanelCloseButtonDismissesWhileANoteSaveErrorIsShowingAndNoDraftIsDirty() {
        setReader(
            reviewEnabled = true,
            reviewUi = ReviewUiState(noteSaveStatus = NoteSaveStatus.ERROR),
        )
        compose.onNodeWithContentDescription("Open review panel").performClick()
        compose.onNodeWithTag("review-sheet").assertIsDisplayed()
        compose.onNodeWithText("Save failed", substring = true).assertIsDisplayed()

        compose.onNodeWithContentDescription("Close review panel").performClick()
        compose.waitForIdle()

        compose.onAllNodesWithTag("review-sheet").assertCountEquals(0)
    }

    @Test
    fun reviewPanelCloseButtonStaysBlockedWhileASignalDraftIsDirty() {
        val reviewUi = mutableStateOf(
            ReviewUiState(
                draftSession = ReviewDraftSession(
                    ReviewDraft.Signal(
                        null,
                        ReviewSelection(0, 0, 9, RawRange(0, 9), "Canonical"),
                        SignalType.NOTE,
                        "Unsaved comment",
                    ),
                ),
            ),
        )
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(
                    sampleState(true),
                    ReaderCallbacks(),
                    reviewUi.value,
                    windowSize = DpSize(360.dp, 800.dp),
                )
            }
        }
        compose.onNodeWithContentDescription("Open review panel").performClick()
        compose.onNodeWithTag("review-sheet").assertIsDisplayed()

        compose.onNodeWithContentDescription("Close review panel").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("review-sheet").assertIsDisplayed()
    }
```

Note the second test intentionally does **not** assert the panel closes —
it documents that a dirty draft correctly keeps blocking dismissal, so a
future change to `blocksDismissal` semantics doesn't silently start
discarding unsaved drafts on an accidental tap.

- [ ] **Step 2: Run both new tests**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.ReviewInteractionTest.reviewPanelCloseButtonDismissesWhileANoteSaveErrorIsShowingAndNoDraftIsDirty" --tests "net.inkyquill.pocketeditor.ui.ReviewInteractionTest.reviewPanelCloseButtonStaysBlockedWhileASignalDraftIsDirty" --console=plain`
Expected: `BUILD SUCCESSFUL`, 2 tests passed — this is the expected result
given the code already reads as correct.

- [ ] **Step 3: If both pass (expected), record the conclusion; if either fails, fix `onDismissReview` before continuing**

If both pass: no production code change is needed for the dismiss gate
itself. Record in the commit message that this closes the investigation for
this specific mechanism with passing regression coverage — the tests now
permanently guard the invariant stated in the spec (X/close always works
absent a dirty draft; a dirty draft always blocks it), independent of
whether the original live symptom was a coordinate-tap miss.

If `reviewPanelCloseButtonDismissesWhileANoteSaveErrorIsShowingAndNoDraftIsDirty`
unexpectedly fails: change `onDismissReview` at `ReaderScreen.kt:193` to
also ignore `reviewUiState.error`/`NoteSaveStatus` explicitly (it already
does — a failure here would point to something else, such as `PanelColumn`'s
`FilledTonalIconButton`'s `onClick` not actually being wired to the `onClose`
parameter passed into `ReviewShell`; re-read that call chain before changing
anything, since the spec's own research already traced it end to end).

- [ ] **Step 4: Run the full `ReviewInteractionTest` class to check for regressions**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.ReviewInteractionTest" --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt
git commit -m "test: codify that the review panel dismiss gate depends only on dirty-draft state"
```

---

### Task 5: Add the Lucide icon dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts` (dependencies block, currently ending at line 123)

Library choice is locked by the spec to `com.composables:icons-lucide` — do
not evaluate alternatives. Only the exact patch version needs confirming
against Maven Central at implementation time.

- [ ] **Step 1: Find the latest stable version**

Run: `curl -s https://repo1.maven.org/maven2/com/composables/icons-lucide/maven-metadata.xml | grep -oE "<release>[^<]+</release>"`
Expected: a version string like `<release>1.x.y</release>`. Use that value
below in place of `<LUCIDE_VERSION>`. If this command fails (offline
environment), use the latest version already known to work with this
project's Compose BOM (`compose-bom = "2026.06.00"`, `gradle/libs.versions.toml:5`)
and note the pin as provisional in the commit message.

- [ ] **Step 2: Add the version and library entries**

In `gradle/libs.versions.toml`, add to `[versions]` (after the existing
`networknt-json-schema-validator` line):

```toml
icons-lucide = "<LUCIDE_VERSION>"
```

Add to `[libraries]` (after the existing `androidx-compose-ui-test-manifest` line):

```toml
icons-lucide = { module = "com.composables:icons-lucide", version.ref = "icons-lucide" }
```

- [ ] **Step 3: Add the dependency to the app module**

In `app/build.gradle.kts`, inside the `dependencies { ... }` block, add
this line next to the other `implementation(libs.androidx.compose.ui)`-style
entries (around line 95):

```kotlin
    implementation(libs.icons.lucide)
```

- [ ] **Step 4: Verify it resolves and compiles**

Run: `./gradlew :app:assembleDebug --console=plain -q`
Expected: `BUILD SUCCESSFUL` — this only proves the dependency resolves;
nothing references it yet (that starts in Task 6).

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build: add the Lucide Compose icon library"
```

---

### Task 6: Re-skin the review-visibility toggle as a small icon button

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt:589-609` (`ReviewToggle`)

**Correction to the spec's plan for this item:** the spec (item 5a) said to
delete `ReviewToggle` and its call site outright. Reading the current code
shows this is unnecessary churn — `ReviewToggle` is a small private
composable that's easy to re-skin in place, and at least six existing
instrumented tests across `AdaptiveReaderTest.kt` and
`ReviewInteractionTest.kt` already assert its exact semantics
(`contentDescription = "Review mode on"` / `"Review mode off"`,
`role = Role.Button`, `toggleableState`). Keeping the function and only
changing its visual body (Material `FilledTonalButton` + icon + text →
small Lucide `IconButton`) preserves every one of those existing tests
unmodified, which is strictly better than deleting and rebuilding
equivalent semantics from scratch.

- [ ] **Step 1: Confirm the existing tests that must keep passing unmodified**

Run: `grep -rln '"Review mode on"\|"Review mode off"' app/src/androidTest`
Expected: at minimum `AdaptiveReaderTest.kt` and `ReviewInteractionTest.kt`.
None of these files are edited in this task — they are the regression
safety net proving the re-skin didn't change behavior.

- [ ] **Step 2: Replace `ReviewToggle`'s body**

Replace the current `ReviewToggle` (`ReaderScreen.kt:589-609`):

```kotlin
@Composable
private fun ReviewToggle(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    FilledTonalButton(
        onClick = { onToggle(!enabled) },
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = if (enabled) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = if (enabled) "Review mode on" else "Review mode off"
                role = Role.Button
                toggleableState = if (enabled) ToggleableState.On else ToggleableState.Off
            },
    ) {
        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("Review", maxLines = 1)
    }
}
```

with:

```kotlin
@Composable
private fun ReviewToggle(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    FilledIconButton(
        onClick = { onToggle(!enabled) },
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier
            .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
            .semantics {
                contentDescription = if (enabled) "Review mode on" else "Review mode off"
                role = Role.Button
                toggleableState = if (enabled) ToggleableState.On else ToggleableState.Off
            },
    ) {
        Icon(
            imageVector = if (enabled) LucideIcons.Eye else LucideIcons.EyeOff,
            contentDescription = null,
        )
    }
}
```

Add `import androidx.compose.material3.FilledIconButton`,
`import androidx.compose.material3.IconButtonDefaults`, and
`import com.composables.icons.lucide.LucideIcons` (confirm the exact import
path matches the artifact resolved in Task 5 — `com.composables.icons.lucide`
is the package for `com.composables:icons-lucide`; adjust if Task 5 pinned a
different artifact) to `ReaderScreen.kt`'s import block. The now-unused
`Icons.AutoMirrored.Filled.List`, `BorderStroke`, and
`ButtonDefaults.filledTonalButtonColors` imports/usages can stay if used
elsewhere in the file — check with
`grep -n "BorderStroke\|ButtonDefaults\|Icons.AutoMirrored.Filled.List" app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt`
before removing any import, since `BorderStroke` in particular is likely
still used by `ChapterRow`/other composables in this same file.

- [ ] **Step 3: Build and run the existing toggle-related tests**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.AdaptiveReaderTest" --tests "net.inkyquill.pocketeditor.ui.ReviewInteractionTest" --console=plain`
Expected: `BUILD SUCCESSFUL` — every existing test referencing
`"Review mode on"`/`"Review mode off"` still passes unmodified, since the
semantics didn't change, only the visual composable body.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt
git commit -m "refactor: re-skin the review-visibility toggle as a small Lucide icon button"
```

---

### Task 7: Replace the broken FAB with one unified `ReviewFab` on phone and tablet-portrait

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/AdaptiveReaderScaffold.kt:1-211`
- Modify (existing tests to migrate): `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/AdaptiveReaderTest.kt` (5 tests currently asserting `"Expand review panel"` on tablet-portrait)
- Test (new): `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/AdaptiveReaderTest.kt`

**Deliberate simplification vs. the spec's draft code for this item:** the
spec (item 5c) sketched a FAB whose icon swaps between "open" and "close"
based on `reviewExpanded`, with `onClick` calling either
`onExpandReview()` or `onDismissReview()`. Building that would reintroduce
exactly the shared-`blocksDismissal` dead-button risk the spec's own item
5f flagged as a follow-on problem. The actual on-device bug was **only**
about position (top-left instead of bottom-right) — the existing broken
code already only renders while `reviewEnabled && !reviewExpanded`
(`AdaptiveReaderScaffold.kt:86`), i.e. it already only ever *opens*, never
closes; closing already works today via the `ModalBottomSheet`'s own swipe
down/scrim/system-Back (`onDismissRequest = onDismissReview`,
`AdaptiveReaderScaffold.kt:114`) and the panel's own X button
(`PanelColumn`, tested in Task 4). Keeping the FAB open-only and dropping
the icon-swap idea fixes the actual reported bug with less surface area,
and makes spec item 5f moot: a FAB that never closes anything cannot
inherit the X button's dead-button risk. If a future session wants the FAB
to also close the panel, that's new scope building on Task 4's now-proven
dismiss invariant, not part of this task.

- [ ] **Step 1: Write a failing test that pins the FAB to the bottom-right**

None of the ~6 existing tests referencing `"Open review panel"` assert
*where* it renders on screen (`.assertHasClickAction()`/`.assertIsDisplayed()`
only) — which is exactly how the top-left misplacement shipped unnoticed.
Add this new test to `AdaptiveReaderTest.kt`, directly below
`phonePreservesExpandedReviewPanelWhileReviewModeChanges` (after line 224),
reusing the file's existing `setReader(size, dark, fontScale, reviewEnabled)`
helper (lines 650-668):

```kotlin
    @Test
    fun reviewFabRendersInTheBottomRightQuadrantOnPhoneAndTabletPortrait() {
        listOf(DpSize(360.dp, 800.dp), DpSize(800.dp, 1_280.dp)).forEach { size ->
            setReader(size, dark = true, fontScale = 1f, reviewEnabled = true)

            val root = compose.onNodeWithTag("reader-root").fetchSemanticsNode().boundsInRoot
            val fab = compose.onNodeWithContentDescription("Open review panel").fetchSemanticsNode().boundsInRoot
            val density = compose.activity.resources.displayMetrics.density

            assertTrue(
                "FAB must be in the right half of the screen at size=$size; fab=$fab root=$root",
                fab.left > root.left + root.width / 2f,
            )
            assertTrue(
                "FAB must be in the bottom half of the screen at size=$size; fab=$fab root=$root",
                fab.top > root.top + root.height / 2f,
            )
            assertTrue(
                "FAB keeps a 44dp minimum touch target at size=$size",
                fab.width / density >= 44f && fab.height / density >= 44f,
            )
        }
    }
```

- [ ] **Step 2: Run it and confirm it fails against the current broken code**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.AdaptiveReaderTest.reviewFabRendersInTheBottomRightQuadrantOnPhoneAndTabletPortrait" --console=plain`
Expected: FAILS at `size=DpSize(360.0.dp, 800.0.dp)` on the "right half"
assertion (the FAB currently renders top-left, so `fab.left` is small, not
`> root.width / 2`).

- [ ] **Step 3: Replace the PHONE-mode FAB block and remove `TooltipBox`/`reviewTooltipState`**

In `AdaptiveReaderScaffold.kt`, remove the now-unused
`val reviewTooltipState = rememberTooltipState()` (line 68) — it will have
no remaining callers after this step. Replace the PHONE branch's inner
`Box` (lines 84-103):

```kotlin
                ReaderLayoutMode.PHONE -> {
                    Box(Modifier.fillMaxSize()) {
                        reader()
                        if (reviewEnabled && !reviewExpanded) {
                            TooltipBox(
                                state = reviewTooltipState,
                                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                                tooltip = { PlainTooltip { Text("Open review panel") } },
                                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                            ) {
                                FloatingActionButton(
                                    onClick = onExpandReview,
                                    modifier = Modifier.size(48.dp).semantics {
                                        contentDescription = "Open review panel"
                                    },
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                                }
                            }
                        }
                    }
```

with:

```kotlin
                ReaderLayoutMode.PHONE -> {
                    Box(Modifier.fillMaxSize()) {
                        reader()
                        if (reviewEnabled && !reviewExpanded) {
                            ReviewFab(onClick = onExpandReview)
                        }
                    }
```

- [ ] **Step 4: Add the `ReviewFab` composable**

Add this private composable near `EdgeControl`/`SideRailControl` (after
line 211, i.e. after the closing brace of `AdaptiveReaderScaffold`):

```kotlin
@Composable
private fun BoxScope.ReviewFab(onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(16.dp)
            .size(56.dp)
            .semantics { contentDescription = "Open review panel" },
    ) {
        Icon(imageVector = LucideIcons.MessageSquareText, contentDescription = null)
    }
}
```

Add `import com.composables.icons.lucide.LucideIcons` to this file's import
block (same package confirmed in Task 6). `56.dp` matches Material's
standard `FloatingActionButton` size and clears the 44dp minimum with
room to spare — this also changes the touch-target size from the previous
`48.dp`, which is a strict improvement, not a regression.

- [ ] **Step 5: Replace the TABLET_PORTRAIT `EdgeControl` for review with the same `ReviewFab`**

Replace (line 185-187 in the original, now shifted — locate by content, not
line number, since Step 3 changed earlier line counts):

```kotlin
                    } else if (reviewEnabled && !contentsExpanded) {
                        EdgeControl("Expand review panel", EdgeSide.RIGHT, onExpandReview)
                    }
```

with:

```kotlin
                    } else if (reviewEnabled && !contentsExpanded) {
                        ReviewFab(onClick = onExpandReview)
                    }
```

This preserves the exact same `reviewEnabled && !contentsExpanded` guard
`EdgeControl` already had — the FAB still hides while Contents is open on
tablet-portrait, matching the old control's behavior exactly (this is the
`showFab`/`!contentsExpanded` distinction the spec's item 5d called out;
implementing it as reusing the existing `else if` branch, rather than a
separate `showFab` boolean, is simpler and equivalent).

- [ ] **Step 6: Remove now-unused imports, build**

Run: `./gradlew :app:compileDebugKotlin --console=plain 2>&1 | grep -i "unused\|warning: parameter"`
Remove any of `PlainTooltip`, `TooltipBox`, `TooltipAnchorPosition`,
`TooltipDefaults`, `rememberTooltipState`, `Icons.AutoMirrored.Filled.KeyboardArrowRight`
(if `Icons.AutoMirrored.Filled.KeyboardArrowLeft` is still used by
`EdgeControl`'s `EdgeSide.RIGHT` branch, keep the `Icons`/`Icons.AutoMirrored.Filled`
imports — only remove the specific unused member imports) that the compiler
flags as unused. Then run:

Run: `./gradlew :app:assembleDebug --console=plain -q`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Run the new bounds test and confirm it now passes**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.AdaptiveReaderTest.reviewFabRendersInTheBottomRightQuadrantOnPhoneAndTabletPortrait" --console=plain`
Expected: `BUILD SUCCESSFUL`, 1 test passed, at both sizes.

- [ ] **Step 8: Migrate the 5 existing tablet-portrait tests from `"Expand review panel"` to `"Open review panel"`**

These five tests in `AdaptiveReaderTest.kt` currently reference
`"Expand review panel"` (the old `EdgeControl`'s content description) and
must be updated since the control that opens review on tablet-portrait is
now the same `ReviewFab` used on phone, with content description
`"Open review panel"`. Do **not** touch
`landscapeTabletOwnsSidebarControlsAndLeavesEdgeControls` or
`landscapeTabletReviewToggleChangesTextWithoutChangingPanelExpansion` — the
`TABLET_LANDSCAPE` branch's `SideRailControl`/`EdgeControl` for review is
unchanged by this task, and those two tests correctly keep asserting
`"Expand review panel"`/`"Collapse review panel"` for that mode.

In `portraitTabletUsesContentsMenuAndNonNarrowingReviewOverlay` (around the
original line 322-325), change:

```kotlin
        val edgeWidth = compose.onNodeWithContentDescription("Expand review panel").fetchSemanticsNode().boundsInRoot.width
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertTrue("Edge controls keep a 48dp touch target", edgeWidth / density >= 48f)
        compose.onNodeWithContentDescription("Expand review panel").performClick()
```

to:

```kotlin
        val fabWidth = compose.onNodeWithContentDescription("Open review panel").fetchSemanticsNode().boundsInRoot.width
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertTrue("The review FAB keeps a 44dp touch target", fabWidth / density >= 44f)
        compose.onNodeWithContentDescription("Open review panel").performClick()
```

(The touch-target assertion drops from `48f` to `44f` because that's the
spec's actual minimum requirement, and the real `FloatingActionButton` size
is `56.dp` regardless — `44f` is what's being guaranteed, not what's
expected to render.)

In `portraitTabletReviewToggleChangesTextWithoutOpeningPanels` (around the
original line 341), change:

```kotlin
        compose.onNodeWithContentDescription("Expand review panel").assertHasClickAction()
```

to:

```kotlin
        compose.onNodeWithContentDescription("Open review panel").assertHasClickAction()
```

In `portraitTabletPreservesExpandedReviewPanelWhileReviewModeChanges`
(around the original line 348), change:

```kotlin
        compose.onNodeWithContentDescription("Expand review panel").performClick()
```

to:

```kotlin
        compose.onNodeWithContentDescription("Open review panel").performClick()
```

In `portraitPanelsAreAccessibleModalsWithBackScrimAndReopen` (around the
original lines 376 and 383), change both occurrences of:

```kotlin
        compose.onNodeWithContentDescription("Expand review panel").performClick()
```

and

```kotlin
        compose.onNodeWithContentDescription("Expand review panel").assertIsDisplayed().performClick()
```

to `"Open review panel"` in place of `"Expand review panel"` (keep
`.performClick()`/`.assertIsDisplayed()` chains exactly as they are).

In `portraitContentsModalHidesEveryReviewAndReaderAffordance` (around the
original line 396), change:

```kotlin
        compose.onAllNodes(hasContentDescription("Expand review panel")).assertCountEquals(0)
```

to:

```kotlin
        compose.onAllNodes(hasContentDescription("Open review panel")).assertCountEquals(0)
```

- [ ] **Step 9: Run the full `AdaptiveReaderTest` class**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.AdaptiveReaderTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, every test in the class passes, including all
5 migrated tests and the new bounds test.

- [ ] **Step 10: Run the full instrumented suite to catch anything else referencing the old content description**

Run: `grep -rln '"Expand review panel"' app/src/androidTest` then run each
matching test class with `./gradlew :app:connectedDebugAndroidTest --tests "<FQCN>" --console=plain`
for any file Step 8 didn't already cover, fixing the same way.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui/reader/AdaptiveReaderScaffold.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/AdaptiveReaderTest.kt
git commit -m "fix: render the review FAB bottom-right on phone and tablet-portrait instead of top-left"
```

---

### Task 8: Reader bottom padding so the FAB never blocks the last paragraph

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt:384-393` (`LazyColumn` inside `ReaderPane`)
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/AdaptiveReaderTest.kt`

`ReaderPane` already receives both `reviewEnabled: Boolean`
(`ReaderScreen.kt:271`) and `policy: ReaderLayoutPolicy`
(`ReaderScreen.kt:269`) as parameters — enough to compute, without any new
parameter, whether the `ReviewFab` from Task 7 is showing for the current
layout mode: it shows on `PHONE` and `TABLET_PORTRAIT` whenever
`reviewEnabled` is true (Task 7's `ReviewFab` call sites), never on
`TABLET_LANDSCAPE` (permanent side rail instead).

- [ ] **Step 1: Write the failing test**

Add this test to `AdaptiveReaderTest.kt`, directly below the new
`reviewFabRendersInTheBottomRightQuadrantOnPhoneAndTabletPortrait` test from
Task 7:

```kotlin
    @Test
    fun lastParagraphScrollsFullyClearOfTheReviewFabWhenReviewIsEnabled() {
        setReader(DpSize(360.dp, 800.dp), dark = true, fontScale = 1f, reviewEnabled = true)

        compose.onNodeWithTag("reader-scroll").performScrollToIndex(9)
        compose.waitForIdle()

        val lastBlock = compose.onNodeWithTag("reader-block-9", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val fab = compose.onNodeWithContentDescription("Open review panel").fetchSemanticsNode().boundsInRoot

        assertTrue(
            "the last paragraph must be fully above the FAB once scrolled to the end; lastBlock=$lastBlock fab=$fab",
            lastBlock.bottom <= fab.top,
        )
    }
```

This needs `import androidx.compose.ui.test.performScrollToIndex` added to
`AdaptiveReaderTest.kt`'s import block if not already present — check with
`grep -n "performScrollToIndex" app/src/androidTest/java/net/inkyquill/pocketeditor/ui/AdaptiveReaderTest.kt`
first.

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.AdaptiveReaderTest.lastParagraphScrollsFullyClearOfTheReviewFabWhenReviewIsEnabled" --console=plain`
Expected: FAILS — with the current `bottom = 48.dp` fixed content padding
(`ReaderScreen.kt:390`), the last paragraph's bottom can land at or below
the FAB's top edge once scrolled fully to the end, since there's no space
reserved for the FAB.

- [ ] **Step 3: Add the conditional bottom padding**

In `ReaderScreen.kt`, inside `ReaderPane`, replace the `LazyColumn`'s
`contentPadding` (currently lines 386-391):

```kotlin
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = policy.readerHorizontalPaddingDp.dp,
                        end = policy.readerHorizontalPaddingDp.dp,
                        top = 32.dp,
                        bottom = 48.dp,
                    ),
```

with:

```kotlin
                val fabShowsForThisPane = reviewEnabled && policy.mode != ReaderLayoutMode.TABLET_LANDSCAPE
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = policy.readerHorizontalPaddingDp.dp,
                        end = policy.readerHorizontalPaddingDp.dp,
                        top = 32.dp,
                        bottom = if (fabShowsForThisPane) 96.dp else 48.dp,
                    ),
```

96dp covers the 56dp FAB (Task 7) plus its 16dp margin plus a 24dp buffer
above it, so the last paragraph can scroll fully clear of it, not just
adjacent to it. Place the `val fabShowsForThisPane = ...` line immediately
before the `LazyColumn(` call so it's scoped inside the same `Box` (it
needs no state not already available as `ReaderPane` parameters).

- [ ] **Step 4: Run the test again to confirm it passes**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.AdaptiveReaderTest.lastParagraphScrollsFullyClearOfTheReviewFabWhenReviewIsEnabled" --console=plain`
Expected: `BUILD SUCCESSFUL`, 1 test passed.

- [ ] **Step 5: Confirm the non-review-enabled case is unaffected**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.AdaptiveReaderTest.phoneUsesScrollableReaderAndModalPanels" --console=plain`
Expected: `BUILD SUCCESSFUL` — this test starts with `reviewEnabled = false`
via `sampleState(false)`, so it exercises the unchanged `48.dp` bottom
padding path and must still pass unmodified.

- [ ] **Step 6: Run the full `AdaptiveReaderTest` class**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.AdaptiveReaderTest" --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/AdaptiveReaderTest.kt
git commit -m "fix: reserve scroll space so the last paragraph clears the review FAB"
```

---

### Task 9: Selection flyout collision avoidance (flip above when below doesn't fit)

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt` (flyout state/positioning inside `ReaderPane`, and a new pure function next to `annotationPlacement`)
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/AdaptiveReaderTest.kt`

The composer already has a proven, directly-unit-tested pure placement
function, `annotationPlacement` (`ReaderScreen.kt:747-760`, tested by
`annotationPlacementReservesGapAndFlipsAboveBeforeDeviceFallback` at
`AdaptiveReaderTest.kt:820`). The flyout has no equivalent — it always
renders below the selection (`ReaderScreen.kt`, the `SelectionFlyout` call's
`.offset { ... }`), which is the root cause of the on-device overlap. This
task adds a matching pure function for the flyout and wires it in the same
style, rather than reusing `annotationPlacement` itself (which returns a
4-way `AnnotationComposerPlacement` enum including sheet/modal fallbacks
that don't apply to the flyout — the flyout only ever renders inline, never
as a sheet).

- [ ] **Step 1: Write the failing pure-function test**

Add this test to `AdaptiveReaderTest.kt`, directly below
`annotationPlacementReservesGapAndFlipsAboveBeforeDeviceFallback` (after
line 835):

```kotlin
    @Test
    fun flyoutPrefersBelowButFlipsAboveWithExtraReservedRoomForTheSystemSelectionMenu() {
        val viewport = Rect(0f, 0f, 600f, 1_000f)

        // Plenty of room below: stays below.
        assertTrue(
            flyoutPlacementIsBelow(
                selection = Rect(200f, 100f, 300f, 150f),
                viewport = viewport,
                flyoutHeightPx = 120f,
                gapPx = 16f,
                reservedAbovePx = 56f,
            ),
        )

        // No room below, but more than enough above even with the reserved
        // system-menu buffer: flips above.
        assertFalse(
            flyoutPlacementIsBelow(
                selection = Rect(200f, 900f, 300f, 950f),
                viewport = viewport,
                flyoutHeightPx = 120f,
                gapPx = 16f,
                reservedAbovePx = 56f,
            ),
        )

        // No room below, and the room above is enough for the flyout itself
        // but not enough once the reserved system-menu buffer is added: the
        // reserved buffer must be the deciding factor, not just raw space.
        assertTrue(
            flyoutPlacementIsBelow(
                selection = Rect(200f, 180f, 300f, 230f),
                viewport = viewport,
                flyoutHeightPx = 120f,
                gapPx = 16f,
                reservedAbovePx = 56f,
            ),
        )
    }
```

The third case: selection top is at `180f`, viewport top is `0f`, so raw
room above is `180f` — more than `flyoutHeightPx + gapPx` (`136f`) but less
than `flyoutHeightPx + gapPx + reservedAbovePx` (`192f`). This must resolve
to `true` (stays below, even though it technically doesn't fit below either
— see Step 3's fallback rule) precisely because the reserved buffer rules
out flipping above.

- [ ] **Step 2: Run it and confirm it fails to compile (the function doesn't exist yet)**

Run: `./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -20`
Expected: compile error, `unresolved reference: flyoutPlacementIsBelow`.

- [ ] **Step 3: Add `flyoutPlacementIsBelow` next to `annotationPlacement`**

In `ReaderScreen.kt`, add this function immediately after the closing brace
of `annotationPlacement` (currently ending at line 760):

```kotlin
internal fun flyoutPlacementIsBelow(
    selection: Rect,
    viewport: Rect,
    flyoutHeightPx: Float,
    gapPx: Float,
    reservedAbovePx: Float,
): Boolean = when {
    viewport.bottom - selection.bottom >= flyoutHeightPx + gapPx -> true
    selection.top - viewport.top >= flyoutHeightPx + gapPx + reservedAbovePx -> false
    else -> true
}
```

The `reservedAbovePx` buffer only guards the *above* branch: flipping above
only happens when there's enough room to clear both the flyout itself and
Android's own floating selection toolbar, which also wants space directly
above the selection and would otherwise collide with the flyout there. The
`else -> true` fallback keeps the flyout below (inside the reader column,
even if visually snug against the next paragraph) rather than flipping
above into a spot that's provably too cramped for both UIs — below is
always at least inside the viewport, unlike an under-sized "above".

- [ ] **Step 4: Run the pure-function test again**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.AdaptiveReaderTest.flyoutPrefersBelowButFlipsAboveWithExtraReservedRoomForTheSystemSelectionMenu" --console=plain`
Expected: `BUILD SUCCESSFUL`, 1 test passed.

- [ ] **Step 5: Wire the function into the flyout's positioning inside `ReaderPane`**

In `ReaderScreen.kt`, inside `ReaderPane`, find the existing flyout-related
state declarations (originally around lines 317-322, now shifted slightly
by Task 8's edit — locate by content):

```kotlin
    val estimatedFlyoutWidthPx = with(LocalDensity.current) { 220.dp.toPx() }
    var flyoutWidthPx by remember(state.chapterId) { mutableStateOf(estimatedFlyoutWidthPx) }
    val estimatedComposerHeightPx = with(LocalDensity.current) { 320.dp.toPx() }
    var composerHeightPx by remember(state.chapterId) { mutableStateOf(estimatedComposerHeightPx) }
    val composerWidthPx = with(LocalDensity.current) { 320.dp.toPx() }
    val annotationGapPx = with(LocalDensity.current) { 8.dp.toPx() }
```

Replace with:

```kotlin
    val estimatedFlyoutWidthPx = with(LocalDensity.current) { 220.dp.toPx() }
    var flyoutWidthPx by remember(state.chapterId) { mutableStateOf(estimatedFlyoutWidthPx) }
    val estimatedFlyoutHeightPx = with(LocalDensity.current) { 64.dp.toPx() }
    var flyoutHeightPx by remember(state.chapterId) { mutableStateOf(estimatedFlyoutHeightPx) }
    val estimatedComposerHeightPx = with(LocalDensity.current) { 320.dp.toPx() }
    var composerHeightPx by remember(state.chapterId) { mutableStateOf(estimatedComposerHeightPx) }
    val composerWidthPx = with(LocalDensity.current) { 320.dp.toPx() }
    val annotationGapPx = with(LocalDensity.current) { 16.dp.toPx() }
    val flyoutReservedAbovePx = with(LocalDensity.current) { 56.dp.toPx() }
```

(`annotationGapPx` changes from `8.dp` to `16.dp` per the spec — this is a
shared value also used by the composer's `Below`/`Above` offset math, which
is an intentional, spec-directed increase, not scope creep.)

Then find the `SelectionFlyout(...)` call's `modifier` (originally around
lines 447-457, now shifted — locate by the `.testTag("selection-flyout")`
suffix):

```kotlin
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .onGloballyPositioned { flyoutWidthPx = it.size.width.toFloat() }
                        .offset {
                            IntOffset(
                                (anchoredHorizontalOffsetInRoot(selectionBounds, readerColumnBounds, flyoutWidthPx) - overlayHostBounds.left).toInt(),
                                (selectionBounds.bottom - readerColumnBounds.top + annotationGapPx).toInt(),
                            )
                        }
                        .testTag("selection-flyout"),
```

Replace with:

```kotlin
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .onGloballyPositioned {
                            flyoutWidthPx = it.size.width.toFloat()
                            flyoutHeightPx = it.size.height.toFloat()
                        }
                        .offset {
                            val below = flyoutPlacementIsBelow(
                                selection = selectionBounds,
                                viewport = readerColumnBounds,
                                flyoutHeightPx = flyoutHeightPx,
                                gapPx = annotationGapPx,
                                reservedAbovePx = flyoutReservedAbovePx,
                            )
                            val desiredTop = if (below) {
                                selectionBounds.bottom - readerColumnBounds.top + annotationGapPx
                            } else {
                                selectionBounds.top - readerColumnBounds.top - flyoutHeightPx - annotationGapPx
                            }
                            IntOffset(
                                (anchoredHorizontalOffsetInRoot(selectionBounds, readerColumnBounds, flyoutWidthPx) - overlayHostBounds.left).toInt(),
                                desiredTop.coerceIn(0f, (readerColumnBounds.height - flyoutHeightPx).coerceAtLeast(0f)).toInt(),
                            )
                        }
                        .testTag("selection-flyout"),
```

- [ ] **Step 6: Build**

Run: `./gradlew :app:assembleDebug --console=plain -q`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Add a Compose-level regression test proving the flyout flips above near the bottom of a chapter**

Add this test to `AdaptiveReaderTest.kt`, directly below
`lastParagraphScrollsFullyClearOfTheReviewFabWhenReviewIsEnabled` (Task 8):

```kotlin
    @Test
    fun selectionFlyoutFlipsAboveWhenTheSelectionIsNearTheBottomOfTheViewport() {
        val state = sampleState(true)
        val reviewUi = mutableStateOf(ReviewUiState())
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(
                    state,
                    ReaderCallbacks(
                        onTextSelected = { selected ->
                            reviewUi.value = selected?.let {
                                ReviewUiState(
                                    draftSession = ReviewDraftSession(
                                        pendingSelection = ReviewSelection(0, 0, it.selectedText.length, it.rawRange, it.selectedText),
                                    ),
                                )
                            } ?: ReviewUiState()
                        },
                    ),
                    reviewUi.value,
                    windowSize = DpSize(360.dp, 640.dp),
                )
            }
        }

        compose.onNodeWithTag("reader-scroll").performScrollToIndex(9)
        compose.onNodeWithTag("reader-text-9", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetSelection) { it(0, 5, false) }
        compose.waitForIdle()

        val selectionBlockBounds = compose.onNodeWithTag("reader-block-9", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val flyoutBounds = compose.onNodeWithTag("selection-flyout", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

        assertTrue(
            "the flyout must render above the selection when there is no room below; selection=$selectionBlockBounds flyout=$flyoutBounds",
            flyoutBounds.bottom <= selectionBlockBounds.top + 1f,
        )
    }
```

This needs `import net.inkyquill.pocketeditor.ui.review.ReviewDraftSession`
and `import net.inkyquill.pocketeditor.ui.review.ReviewSelection` added to
`AdaptiveReaderTest.kt` if not already present — check first with
`grep -n "ReviewDraftSession\|ReviewSelection" app/src/androidTest/java/net/inkyquill/pocketeditor/ui/AdaptiveReaderTest.kt`.

- [ ] **Step 8: Run the new Compose test**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.AdaptiveReaderTest.selectionFlyoutFlipsAboveWhenTheSelectionIsNearTheBottomOfTheViewport" --console=plain`
Expected: `BUILD SUCCESSFUL`, 1 test passed.

- [ ] **Step 9: Run the full `ReviewInteractionTest` and `AdaptiveReaderTest` classes to check for regressions from the shared `annotationGapPx` change**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.ReviewInteractionTest" --tests "net.inkyquill.pocketeditor.ui.AdaptiveReaderTest" --console=plain`
Expected: `BUILD SUCCESSFUL`. Pay particular attention to any existing test
with hardcoded gap-dependent pixel expectations near composer placement
(e.g. `longEditComposerFallsBackBeforeItEscapesTheReaderViewport`,
`nearRightSelectionClampsEveryInlineActionToTheReaderViewport`) — if any
fail because they asserted exact positions computed with the old `8.dp` gap,
update their expected values to use `16.dp` consistently rather than
loosening the assertion.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/AdaptiveReaderTest.kt
git commit -m "fix: flip the selection flyout above the selection when it would otherwise overlap the next paragraph"
```

---

### Task 10: Swap the five selection-flyout icons to Lucide

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/SelectionFlyout.kt`

Sequenced after Task 9 on purpose (both touch this general area of the
reader-selection code; Task 9's positioning change is the higher-risk one,
so it lands first with its own passing tests before this purely cosmetic
icon swap, which cannot conflict with Task 9 since Task 9 does not modify
`SelectionFlyout.kt` itself, only `ReaderScreen.kt`).

- [ ] **Step 1: Replace the icon mapping**

In `SelectionFlyout.kt`, replace the private `SignalType.icon` extension
(currently):

```kotlin
private val SignalType.icon: ImageVector
    get() = when (this) {
        SignalType.NOTE -> Icons.AutoMirrored.Filled.Note
        SignalType.WARNING -> Icons.Filled.Warning
        SignalType.CHANGE_REQUIRED -> Icons.Filled.Error
        SignalType.REVIEW -> Icons.AutoMirrored.Filled.Help
    }
```

with:

```kotlin
private val SignalType.icon: ImageVector
    get() = when (this) {
        SignalType.NOTE -> LucideIcons.NotebookPen
        SignalType.WARNING -> LucideIcons.TriangleAlert
        SignalType.CHANGE_REQUIRED -> LucideIcons.CircleAlert
        SignalType.REVIEW -> LucideIcons.CircleHelp
    }
```

And the Edit action's icon in `SelectionFlyout` (currently
`icon = Icons.Filled.Edit`) to `icon = LucideIcons.Pencil`.

Remove the now-unused imports
(`androidx.compose.material.icons.automirrored.filled.Help`,
`androidx.compose.material.icons.automirrored.filled.Note`,
`androidx.compose.material.icons.filled.Edit`,
`androidx.compose.material.icons.filled.Error`,
`androidx.compose.material.icons.filled.Warning`) and add
`import com.composables.icons.lucide.LucideIcons`.

- [ ] **Step 2: Build**

Run: `./gradlew :app:assembleDebug --console=plain -q`
Expected: `BUILD SUCCESSFUL`. If any of the specific Lucide icon names above
don't exist in the resolved version of `com.composables:icons-lucide`,
check the library's actual icon list (it ships one object member per
icon under `com.composables.icons.lucide.LucideIcons`) and substitute the
closest equivalent name, keeping the same semantic meaning per icon
(note-taking, triangular warning, circular alert/error, circular
question-mark help, pencil edit).

- [ ] **Step 3: Run the existing flyout interaction test to confirm nothing about behavior changed**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.ReviewInteractionTest.selectionActionsFollowAnActiveReaderTextSelectionWithoutOpeningTheReviewPanel" --console=plain`
Expected: `BUILD SUCCESSFUL` — this test (`ReviewInteractionTest.kt:117-181`)
asserts on `contentDescription` labels ("Add note", "Warning", "Change
needed", "Review", "Edit") and touch-target sizes, none of which depend on
which icon glyph is drawn, so it should pass unmodified.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui/review/SelectionFlyout.kt
git commit -m "refactor: use Lucide icons for the selection flyout actions"
```

---

### Task 11: Composer card padding and explicit surface color

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/SignalComposer.kt:33-36`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/EditComposer.kt:29-31`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/InlineAnnotationComposer.kt:44-50`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt`

- [ ] **Step 1: Write the failing test**

Add this test to `ReviewInteractionTest.kt`, directly below
`selectedTextComposerStaysInlineAndReviewOverviewHasNoActiveComposer`
(after line 243), reusing the file's `selectionCallbacks` helper
(`ReviewInteractionTest.kt:1012-1030`):

```kotlin
    @Test
    fun signalComposerKeepsSixteenDpPaddingAroundItsContentOnEveryEdge() {
        val reviewUi = mutableStateOf(ReviewUiState())
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(
                    sampleState(false).copy(reviewEnabled = true),
                    selectionCallbacks(reviewUi),
                    reviewUi.value,
                    windowSize = DpSize(360.dp, 800.dp),
                )
            }
        }
        compose.onNodeWithTag("reader-text-0", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetSelection) { it(0, 5, false) }
        compose.onNodeWithContentDescription("Add note").performClick()

        val composerCard = compose.onNodeWithTag("inline-annotation-composer").fetchSemanticsNode().boundsInRoot
        val typePicker = compose.onNodeWithText("Note").fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density

        assertTrue(
            "the Note chip must sit at least 16dp inside the card's left edge; card=$composerCard chip=$typePicker",
            (typePicker.left - composerCard.left) / density >= 16f,
        )
        assertTrue(
            "the Note chip must sit at least 16dp inside the card's top edge; card=$composerCard chip=$typePicker",
            (typePicker.top - composerCard.top) / density >= 16f,
        )
    }
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.ReviewInteractionTest.signalComposerKeepsSixteenDpPaddingAroundItsContentOnEveryEdge" --console=plain`
Expected: FAILS on the left-edge assertion — `SignalComposer.kt`'s current
`padding(vertical = 4.dp)` has zero horizontal padding, so the "Note" title
text (which renders above the chips, at the same left edge) sits flush
against the card's left edge.

- [ ] **Step 3: Fix the padding in both composers**

In `SignalComposer.kt`, replace (lines 33-36):

```kotlin
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("signal-composer"),
    ) {
```

with:

```kotlin
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().padding(16.dp).testTag("signal-composer"),
    ) {
```

In `EditComposer.kt`, apply the identical change — replace (lines 29-31):

```kotlin
    Column(
        ...
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("edit-composer"),
```

with:

```kotlin
    Column(
        ...
        modifier = modifier.fillMaxWidth().padding(16.dp).testTag("edit-composer"),
```

(Preserve whatever the `...` verticalArrangement/other params already are
in `EditComposer.kt` — only the `.padding(vertical = 4.dp)` →
`.padding(16.dp)` change matters here.)

- [ ] **Step 4: Set an explicit card background color**

In `InlineAnnotationComposer.kt`, replace the shared `Surface` (lines
44-50):

```kotlin
    val content: @Composable (Modifier) -> Unit = { surfaceModifier ->
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
            modifier = surfaceModifier.testTag("inline-annotation-composer"),
        ) {
```

with:

```kotlin
    val content: @Composable (Modifier) -> Unit = { surfaceModifier ->
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
            modifier = surfaceModifier.testTag("inline-annotation-composer"),
        ) {
```

- [ ] **Step 5: Run the new test again to confirm it passes**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.ReviewInteractionTest.signalComposerKeepsSixteenDpPaddingAroundItsContentOnEveryEdge" --console=plain`
Expected: `BUILD SUCCESSFUL`, 1 test passed.

- [ ] **Step 6: Run the full `ReviewInteractionTest` class — this padding change is the one flagged as needing manual chip-wrap verification**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.ReviewInteractionTest" --console=plain`
Expected: `BUILD SUCCESSFUL`. Pay particular attention to
`nearRightSelectionClampsEveryInlineActionToTheReaderViewport` (line 355)
and `longEditComposerFallsBackBeforeItEscapesTheReaderViewport` (line 319):
these exercise the composer at its clamped/narrowest widths, exactly where
16dp of padding on each side (32dp total) eating into the available width
for `SignalComposer`'s `FlowRow` of signal-type chips is most likely to
cause a wrap or overflow. `FlowRow` reflows to a new line rather than
overflowing horizontally, so a wrap is an acceptable outcome; the test only
needs to keep passing, since it doesn't assert the chips render on a single
line — if the test does start failing at this step, that indicates the
narrowest supported width can no longer fit the chips at all, which would
mean revisiting `composerWidthPx` (`ReaderScreen.kt`, currently 320dp) or
the `320.dp` `widthIn(max = ...)` at the composer's call site, not reverting
this padding.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui/review/SignalComposer.kt app/src/main/java/net/inkyquill/pocketeditor/ui/review/EditComposer.kt app/src/main/java/net/inkyquill/pocketeditor/ui/review/InlineAnnotationComposer.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt
git commit -m "fix: give the inline annotation composer real padding and a distinct background"
```

---

### Task 12: Composer horizontal edge margin (never touch the screen edge)

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt` (`anchoredHorizontalOffset`, `anchoredHorizontalOffsetInRoot`, and the composer's two offset call sites)
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/AdaptiveReaderTest.kt:838-859` (`centeredTabletBelowAndAboveComposersClampInReaderRootSpace`, existing test whose assertion must change to match the new margin)

The existing clamp already keeps the composer fully inside the viewport
(`anchoredHorizontalOffset`, `ReaderScreen.kt:762-765`:
`.coerceIn(0f, (viewport.width - contentWidthPx).coerceAtLeast(0f))`) — it
just clamps flush to the edge (0 margin) rather than leaving a gap. This
task adds a margin parameter used only by the composer's `Below`/`Above`
call sites; the flyout's own call (Task 9) intentionally keeps the default
0 margin, since the spec only asked for an edge margin on the composer
card, not the flyout.

- [ ] **Step 1: Write the failing test**

This is a pure-function change, so extend the existing pure-function test
style. Add this test to `AdaptiveReaderTest.kt`, directly below
`centeredTabletBelowAndAboveComposersClampInReaderRootSpace` (after line
859):

```kotlin
    @Test
    fun anchoredHorizontalOffsetKeepsAMarginFromTheViewportEdgeWhenRequested() {
        val viewport = Rect(0f, 0f, 600f, 1_000f)
        val contentWidth = 320f
        val marginPx = 12f

        // Selection far to the right: clamp must stop `marginPx` short of the
        // right edge, not flush against it.
        val rightClamped = anchoredHorizontalOffsetInRoot(
            anchor = Rect(590f, 100f, 600f, 150f),
            viewport = viewport,
            contentWidthPx = contentWidth,
            marginPx = marginPx,
        )
        assertEquals((viewport.right - contentWidth - marginPx).toInt(), rightClamped)

        // Selection far to the left: clamp must stop `marginPx` past the left
        // edge, not flush against it.
        val leftClamped = anchoredHorizontalOffsetInRoot(
            anchor = Rect(0f, 100f, 10f, 150f),
            viewport = viewport,
            contentWidthPx = contentWidth,
            marginPx = marginPx,
        )
        assertEquals((viewport.left + marginPx).toInt(), leftClamped)

        // Default margin (0f) preserves the exact previous flush-to-edge
        // behavior for any caller that doesn't pass one (the flyout, Task 9).
        val flushClamped = anchoredHorizontalOffsetInRoot(
            anchor = Rect(590f, 100f, 600f, 150f),
            viewport = viewport,
            contentWidthPx = contentWidth,
        )
        assertEquals((viewport.right - contentWidth).toInt(), flushClamped)
    }
```

- [ ] **Step 2: Run it and confirm it fails to compile**

Run: `./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -20`
Expected: compile error — `anchoredHorizontalOffsetInRoot` has no
`marginPx` parameter yet.

- [ ] **Step 3: Add the `marginPx` parameter**

In `ReaderScreen.kt`, replace (lines 762-768):

```kotlin
private fun anchoredHorizontalOffset(anchor: Rect, viewport: Rect, contentWidthPx: Float): Int =
    (anchor.left - viewport.left)
        .coerceIn(0f, (viewport.width - contentWidthPx).coerceAtLeast(0f))
        .toInt()

internal fun anchoredHorizontalOffsetInRoot(anchor: Rect, viewport: Rect, contentWidthPx: Float): Int =
    viewport.left.toInt() + anchoredHorizontalOffset(anchor, viewport, contentWidthPx)
```

with:

```kotlin
private fun anchoredHorizontalOffset(anchor: Rect, viewport: Rect, contentWidthPx: Float, marginPx: Float): Int =
    (anchor.left - viewport.left)
        .coerceIn(marginPx, (viewport.width - contentWidthPx - marginPx).coerceAtLeast(marginPx))
        .toInt()

internal fun anchoredHorizontalOffsetInRoot(anchor: Rect, viewport: Rect, contentWidthPx: Float, marginPx: Float = 0f): Int =
    viewport.left.toInt() + anchoredHorizontalOffset(anchor, viewport, contentWidthPx, marginPx)
```

Every existing call site that doesn't pass `marginPx` (the flyout's call
from Task 9, and the existing
`centeredTabletBelowAndAboveComposersClampInReaderRootSpace` test's calls
at `AdaptiveReaderTest.kt:854`) keeps compiling unchanged, defaulting to
`0f` — exact previous behavior.

- [ ] **Step 4: Pass a 12dp margin from the composer's two offset call sites**

In `ReaderScreen.kt`, inside `ReaderPane`, add a margin constant next to
the other flyout/composer pixel constants added in Task 9
(`flyoutReservedAbovePx`):

```kotlin
    val composerEdgeMarginPx = with(LocalDensity.current) { 12.dp.toPx() }
```

Then in the composer's `Below` branch offset (originally lines 478-489,
content-locate by `AnnotationComposerPlacement.Below -> Modifier`):

```kotlin
                    AnnotationComposerPlacement.Below -> Modifier
                        .align(Alignment.TopStart)
                        .offset {
                            val anchor = requireNotNull(draftAnchorBounds)
                            val maxTop = readerColumnBounds.bottom - readerColumnBounds.top - composerHeightPx
                            val desiredTop = anchor.bottom - readerColumnBounds.top + annotationGapPx
                            IntOffset(
                                (anchoredHorizontalOffsetInRoot(anchor, readerColumnBounds, composerWidthPx) - overlayHostBounds.left).toInt(),
                                desiredTop.coerceAtMost(maxTop).toInt(),
                            )
                        }
```

change the offset's horizontal calculation to pass the margin:

```kotlin
                                (anchoredHorizontalOffsetInRoot(anchor, readerColumnBounds, composerWidthPx, composerEdgeMarginPx) - overlayHostBounds.left).toInt(),
```

Apply the identical one-line change to the `Above` branch's matching call
(originally at line 496):

```kotlin
                                (anchoredHorizontalOffsetInRoot(anchor, readerColumnBounds, composerWidthPx, composerEdgeMarginPx) - overlayHostBounds.left).toInt(),
```

Leave the flyout's own `anchoredHorizontalOffsetInRoot(selectionBounds, readerColumnBounds, flyoutWidthPx)`
call (Task 9) with no fourth argument, keeping its 0 default margin.

- [ ] **Step 5: Update the existing test whose expected value assumed 0 margin**

`centeredTabletBelowAndAboveComposersClampInReaderRootSpace`
(`AdaptiveReaderTest.kt:838-859`) currently asserts the composer clamps
flush to `readerColumn.right - composerWidth`. That assertion is still
correct **as a test of the pure function's default-margin behavior**, but
no longer represents what the real composer now renders (which passes
`composerEdgeMarginPx = 12.dp`). Leave the pure-function calls in this test
exactly as they are (they correctly document the 0-margin default), but add
one more assertion block directly after the existing `forEach` (after line
858) that exercises the *actual* 12dp-margin call your production code now
makes:

```kotlin
        val marginPx = with(compose.density) { 12.dp.toPx() }
        listOf(
            Rect(950f, 100f, 980f, 150f),
            Rect(950f, 650f, 980f, 700f),
        ).forEach { selection ->
            val marginedLeft = anchoredHorizontalOffsetInRoot(selection, readerColumn, composerWidth, marginPx)
            assertEquals(readerColumn.right - composerWidth - marginPx, marginedLeft.toFloat())
            assertTrue(marginedLeft >= readerColumn.left + marginPx)
        }
```

This needs `compose.density` — if `ComposeContentTestRule` doesn't expose a
`density` property directly in this test's rule type, use
`Density(InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density)`
instead (check which pattern the file already uses elsewhere with
`grep -n "LocalDensity\|Density(" app/src/androidTest/java/net/inkyquill/pocketeditor/ui/AdaptiveReaderTest.kt`
before writing this block).

- [ ] **Step 6: Run both tests**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.AdaptiveReaderTest.anchoredHorizontalOffsetKeepsAMarginFromTheViewportEdgeWhenRequested" --tests "net.inkyquill.pocketeditor.ui.AdaptiveReaderTest.centeredTabletBelowAndAboveComposersClampInReaderRootSpace" --console=plain`
Expected: `BUILD SUCCESSFUL`, 2 tests passed.

- [ ] **Step 7: Run the full `AdaptiveReaderTest` and `ReviewInteractionTest` classes**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.AdaptiveReaderTest" --tests "net.inkyquill.pocketeditor.ui.ReviewInteractionTest" --console=plain`
Expected: `BUILD SUCCESSFUL`. As in Task 9's Step 9, check any test with a
hardcoded exact-pixel composer-left expectation for whether it needs the
new `composerEdgeMarginPx` folded in — in particular re-check
`nearRightSelectionClampsEveryInlineActionToTheReaderViewport` (this one
clamps the *flyout*, not the composer, so it should be unaffected by this
task) and `landscapeContentsSidebarClampsRenderedSelectionFlyoutAndBelowComposerInRootSpace`/
`landscapeContentsSidebarClampsRenderedAboveComposerInRootSpace` (these do
involve the composer's clamped position and may need the same
`composerEdgeMarginPx` folded into their expected values).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/AdaptiveReaderTest.kt
git commit -m "fix: keep a 12dp margin between the inline annotation composer and the screen edge"
```

---

### Task 13: Text-size live preview

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/settings/AppearanceScreen.kt`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt`

Confirmed the wiring: `PocketEditorRoot.kt:73` already passes
`PocketEditorTheme(darkTheme = library.appearance.dark, textScale = library.appearance.textScale)`
at the app root, so `LocalReaderTypography.current` anywhere underneath —
including inside `AppearanceScreen` — already recomposes live as
`appearance.textScale` changes on every +/-/reset tap. The only thing
wrong is that the sample sentence reads from
`MaterialTheme.typography.bodyLarge` (a fixed Material style) instead of
`LocalReaderTypography.current` (the scaled reader style), so no code
outside this one `Text` call needs to change.

- [ ] **Step 1: Write the failing test**

Add these two private helpers and one test to `BookFlowTest.kt`, directly
below `appearanceProvidesOneThemeSwitchAndBoundedTextControls` (after line
361):

```kotlin
    @Test
    fun appearanceSampleTextScalesWithTheLiveTextScalePreference() {
        var fontSizeAt100Percent = 0f
        compose.setContent {
            PocketEditorTheme(darkTheme = false, textScale = 1f) {
                AppearanceScreen(
                    AppearancePreference(dark = false, textScale = 1f),
                    onBack = {},
                    onDarkChanged = {},
                    onDecrease = {},
                    onReset = {},
                    onIncrease = {},
                )
            }
        }
        fontSizeAt100Percent = compose.onNodeWithText("The quick brown fox crossed the moonlit courtyard.").fontSize()

        var fontSizeAt130Percent = 0f
        compose.setContent {
            PocketEditorTheme(darkTheme = false, textScale = 1.3f) {
                AppearanceScreen(
                    AppearancePreference(dark = false, textScale = 1.3f),
                    onBack = {},
                    onDarkChanged = {},
                    onDecrease = {},
                    onReset = {},
                    onIncrease = {},
                )
            }
        }
        fontSizeAt130Percent = compose.onNodeWithText("The quick brown fox crossed the moonlit courtyard.").fontSize()

        assertTrue(
            "the sample sentence must visibly grow between 100% and 130%; 100%=$fontSizeAt100Percent 130%=$fontSizeAt130Percent",
            fontSizeAt130Percent > fontSizeAt100Percent,
        )
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        var results: List<TextLayoutResult> = emptyList()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            val captured = mutableListOf<TextLayoutResult>()
            check(action(captured))
            results = captured
        }
        return results.single()
    }

    private fun SemanticsNodeInteraction.fontSize(): Float = textLayout().layoutInput.style.fontSize.value
```

Add the imports `androidx.compose.ui.test.SemanticsNodeInteraction`,
`androidx.compose.ui.semantics.SemanticsActions`, and
`androidx.compose.ui.text.TextLayoutResult` to `BookFlowTest.kt` if not
already present — check first with
`grep -n "SemanticsNodeInteraction\|SemanticsActions\|TextLayoutResult" app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt`.

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.BookFlowTest.appearanceSampleTextScalesWithTheLiveTextScalePreference" --console=plain`
Expected: FAILS — `fontSizeAt130Percent` equals `fontSizeAt100Percent`
today, since the sample text is hardcoded to `MaterialTheme.typography.bodyLarge`.

- [ ] **Step 3: Fix the sample text's style**

In `AppearanceScreen.kt`, replace:

```kotlin
                    Text("The quick brown fox crossed the moonlit courtyard.", style = MaterialTheme.typography.bodyLarge)
```

with:

```kotlin
                    Text("The quick brown fox crossed the moonlit courtyard.", style = LocalReaderTypography.current.prose)
```

Add `import net.inkyquill.pocketeditor.ui.theme.LocalReaderTypography` to
`AppearanceScreen.kt`'s import block.

- [ ] **Step 4: Run the test again to confirm it passes**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.BookFlowTest.appearanceSampleTextScalesWithTheLiveTextScalePreference" --console=plain`
Expected: `BUILD SUCCESSFUL`, 1 test passed.

- [ ] **Step 5: Run the full `BookFlowTest` class**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.BookFlowTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, including the pre-existing
`appearanceProvidesOneThemeSwitchAndBoundedTextControls`, which doesn't
assert on the sample text's size and so is unaffected by this change.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui/settings/AppearanceScreen.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt
git commit -m "fix: make the Appearance screen's sample text scale with the live text-size preference"
```

---

### Task 14: Contents drawer chapter list — dividers instead of per-row boxes

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/contents/ContentsPanel.kt:118-134`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt`

**Correction to the spec's plan for this item:** the spec (item 8) said to
apply the same treatment to `ChapterRow` in `ReaderScreen.kt:627-668`.
Reading that composable directly shows it already uses
`color = if (current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent`
for unselected rows — it's a separate, rarely-used fallback shell
(`ContentsShell`, only rendered when `contentsContent == null`, i.e. never
in the actual running app, which always supplies the real `ContentsPanel`)
that already matches the target look. No change needed there; this task
only touches `ContentsPanel.kt`.

- [ ] **Step 1: Write the failing test**

Add this test to `BookFlowTest.kt`, directly below
`contentsOwnsBookSwitchChapterOrderAndExactSourceSearch` (after line 329),
reusing the existing `BOOKS` fixture:

```kotlin
    @Test
    fun chapterListSeparatesRowsWithDividersInsteadOfIndividualRowBackgrounds() {
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ContentsPanel(
                    books = BOOKS,
                    currentBookId = "book-a",
                    currentChapterId = "chapter-a",
                    query = "",
                    searchResults = emptyList(),
                    searching = false,
                    closeLabel = "Close contents",
                    onClose = {},
                    onSwitchBook = {},
                    onChapterSelected = {},
                    onQueryChanged = {},
                    onSearchResult = {},
                    onOpenBooks = {},
                    onAppearance = {},
                )
            }
        }

        // BOOKS.first() ("Alchemy of Rain") has 2 chapters -> exactly 1
        // divider between them, none before the first or after the last.
        compose.onAllNodesWithTag("chapter-divider").assertCountEquals(1)
    }
```

- [ ] **Step 2: Run it and confirm it fails to compile (the tag doesn't exist yet)**

Run: `./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -20`
Expected: this actually compiles fine (`onAllNodesWithTag` with a tag that
matches nothing is valid Kotlin) — instead run the test directly:
Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.BookFlowTest.chapterListSeparatesRowsWithDividersInsteadOfIndividualRowBackgrounds" --console=plain`
Expected: FAILS — `assertCountEquals(1)` finds 0 nodes tagged
`"chapter-divider"`, since no divider exists in the chapter list today.

- [ ] **Step 3: Implement the row treatment and dividers**

In `ContentsPanel.kt`, replace the chapter `LazyColumn` (lines 118-134):

```kotlin
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) {
            items(book?.chapters.orEmpty(), key = BookChapter::id) { chapter ->
                val current = chapter.id == currentChapterId
                Surface(
                    onClick = { onChapterSelected(chapter) },
                    color = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(
                        chapter.title,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }
            }
        }
```

with:

```kotlin
        val chapters = book?.chapters.orEmpty()
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) {
            itemsIndexed(chapters, key = { _, chapter -> chapter.id }) { index, chapter ->
                val current = chapter.id == currentChapterId
                Surface(
                    onClick = { onChapterSelected(chapter) },
                    color = if (current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(
                        chapter.title,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
                if (index != chapters.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.testTag("chapter-divider"),
                    )
                }
            }
        }
```

Add `import androidx.compose.foundation.lazy.itemsIndexed`,
`import androidx.compose.ui.graphics.Color`, and
`import androidx.compose.ui.platform.testTag` to `ContentsPanel.kt`'s
import block (check first which are already present — `HorizontalDivider`
and `MaterialTheme` are already imported per the existing divider before
the "Chapters" label).

- [ ] **Step 4: Run the test again to confirm it passes**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.BookFlowTest.chapterListSeparatesRowsWithDividersInsteadOfIndividualRowBackgrounds" --console=plain`
Expected: `BUILD SUCCESSFUL`, 1 test passed.

- [ ] **Step 5: Run the full `BookFlowTest` class**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.BookFlowTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, including
`contentsOwnsBookSwitchChapterOrderAndExactSourceSearch` and
`contentsShowsQuietDiscoveryActionsWithExplicitNonRemoteSemantics`, neither
of which asserts on row background color or divider presence, so both
should be unaffected.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui/contents/ContentsPanel.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt
git commit -m "fix: separate Contents chapter rows with dividers instead of individual row backgrounds"
```

---

### Task 15: Home screen empty-state spacing

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/BooksScreen.kt`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt`

- [ ] **Step 1: Write the failing test**

Add this test to `BookFlowTest.kt`, directly below
`signInFailureIsVisibleAndRetryable`'s test body (find its closing brace
and add after it), reusing the same empty-books, signed-out rendering
pattern already used there:

```kotlin
    @Test
    fun emptyStateSitsNearTheSignInCardInsteadOfCenteredInAllLeftoverSpace() {
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                BooksScreen(
                    books = emptyList(), signedIn = false, signingIn = false, forgetBookId = null,
                    onSignIn = {}, onAddBook = {}, onOpenBook = {}, onRequestForget = {},
                    onConfirmForget = {}, onCancelForget = {}, onAppearance = {},
                )
            }
        }

        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val signInCard = compose.onNodeWithText("Connect Yandex Disk").fetchSemanticsNode().boundsInRoot
        val emptyState = compose.onNodeWithTag("empty-books").fetchSemanticsNode().boundsInRoot

        assertTrue(
            "the empty state must start close under the sign-in card, not drift toward mid-screen; card=$signInCard empty=$emptyState",
            emptyState.top - signInCard.bottom < root.height / 4f,
        )
    }
```

- [ ] **Step 2: Run it and confirm it fails to compile (the tag doesn't exist yet)**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.BookFlowTest.emptyStateSitsNearTheSignInCardInsteadOfCenteredInAllLeftoverSpace" --console=plain`
Expected: FAILS — no node is tagged `"empty-books"` yet, so
`onNodeWithTag("empty-books")` finds nothing (assertion failure /
`AssertionError: Failed: assertExists`).

- [ ] **Step 3: Add the tag and fix the spacing**

In `BooksScreen.kt`, replace the call site (currently):

```kotlin
                if (books.isEmpty()) {
                    EmptyBooks(signedIn, onAddBook, Modifier.weight(1f))
                } else {
```

with:

```kotlin
                if (books.isEmpty()) {
                    Spacer(Modifier.height(48.dp))
                    EmptyBooks(signedIn, onAddBook, Modifier.weight(1f, fill = false).testTag("empty-books"))
                } else {
```

And replace `EmptyBooks` itself (currently):

```kotlin
@Composable
private fun EmptyBooks(signedIn: Boolean, onAddBook: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxWidth().padding(28.dp),
    ) {
```

with:

```kotlin
@Composable
private fun EmptyBooks(signedIn: Boolean, onAddBook: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
        modifier = modifier.fillMaxWidth().padding(28.dp),
    ) {
```

Add `import androidx.compose.ui.platform.testTag` to `BooksScreen.kt` if
not already present.

- [ ] **Step 4: Run the test again to confirm it passes**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.BookFlowTest.emptyStateSitsNearTheSignInCardInsteadOfCenteredInAllLeftoverSpace" --console=plain`
Expected: `BUILD SUCCESSFUL`, 1 test passed.

- [ ] **Step 5: Run the full `BookFlowTest` class**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.BookFlowTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, including
`signedOutBooksKeepsOfflineRootReadableAndExplainsSignInBoundary` (has
books, so it exercises the `else` branch, not `EmptyBooks`, and stays
unaffected) and `signInFailureIsVisibleAndRetryable` (empty books, signed
out — now renders through the changed code path; it only asserts an error
message is visible, not layout, so it should keep passing).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui/books/BooksScreen.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt
git commit -m "fix: stop centering the empty book-shelf state in all leftover vertical space"
```

---

### Task 16: Appearance screen — stop forcing the content column to fill the viewport

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/settings/AppearanceScreen.kt`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt`

- [ ] **Step 1: Write the failing test**

Add this test to `BookFlowTest.kt`, directly below
`appearanceProvidesOneThemeSwitchAndBoundedTextControls` (or directly below
Task 13's `appearanceSampleTextScalesWithTheLiveTextScalePreference` if
Task 13 already ran):

```kotlin
    @Test
    fun appearanceContentDoesNotForceItselfToFillTheWholeViewport() {
        compose.setContent {
            PocketEditorTheme(darkTheme = false, textScale = 1f) {
                AppearanceScreen(
                    AppearancePreference(dark = false, textScale = 1f),
                    onBack = {},
                    onDarkChanged = {},
                    onDecrease = {},
                    onReset = {},
                    onIncrease = {},
                )
            }
        }

        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag("appearance-content").fetchSemanticsNode().boundsInRoot

        assertTrue(
            "the content column must size to its own content, not the full viewport; root=$root content=$content",
            content.height < root.height * 0.9f,
        )
    }
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.BookFlowTest.appearanceContentDoesNotForceItselfToFillTheWholeViewport" --console=plain`
Expected: FAILS to find a node tagged `"appearance-content"` (doesn't exist
yet).

- [ ] **Step 3: Add the tag and change `fillMaxSize()` to `fillMaxWidth()` plus `wrapContentHeight`**

**Correction found during implementation:** swapping `fillMaxSize()` for
`fillMaxWidth()` alone was verified NOT to pass the Step 1 test on a real
run — the outer `Surface(Modifier.fillMaxSize())` still hands the inner
`Column` a tight (min == max) height constraint, and `verticalScroll()`
coerces its own reported size back into whatever constraint it's given
rather than loosening it, so the Column still stretched to full viewport
height even with only `fillMaxWidth()`. An explicit
`wrapContentHeight(Alignment.Top)` is also required, placed *before*
`verticalScroll()` in the modifier chain (after it would have no effect,
since it would wrap an already-sized placeable). With `wrapContentHeight`'s
default (non-`unbounded`) behavior this doesn't defeat scrolling for
legitimately tall content (e.g. large accessibility font scale): the
wrapped size is still coerced back into the original incoming constraints,
so content taller than the viewport still scrolls normally.

In `AppearanceScreen.kt`, replace the root `Column`:

```kotlin
        Column(
            Modifier.fillMaxSize().widthIn(max = 760.dp).verticalScroll(rememberScrollState())
                .navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
```

with:

```kotlin
        Column(
            Modifier.fillMaxWidth().widthIn(max = 760.dp).wrapContentHeight(Alignment.Top)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp)
                .testTag("appearance-content"),
        ) {
```

Add `import androidx.compose.ui.platform.testTag`,
`import androidx.compose.foundation.layout.fillMaxWidth`, and
`import androidx.compose.foundation.layout.wrapContentHeight` to
`AppearanceScreen.kt` if not already present (the file currently imports
`fillMaxSize` for the outer `Surface`, which is unaffected by this change
and must stay).

- [ ] **Step 4: Run the test again to confirm it passes**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.BookFlowTest.appearanceContentDoesNotForceItselfToFillTheWholeViewport" --console=plain`
Expected: `BUILD SUCCESSFUL`, 1 test passed.

- [ ] **Step 5: Run the full `BookFlowTest` class**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.BookFlowTest" --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui/settings/AppearanceScreen.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt
git commit -m "fix: size the Appearance screen's content column to its content instead of the full viewport"
```

---

### Task 17: Contents drawer chapter list — stop reserving full height for a short list

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/contents/ContentsPanel.kt`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt`

**Correction to the spec's plan for this item:** the spec (item 9, third
bullet) said to drop `weight(1f)` entirely, leaving just
`Modifier.fillMaxWidth()`. That would break the layout: this `LazyColumn`
sits inside `ContentsPanel`'s outer `Column`
(`ContentsPanel.kt:62`, `Column(Modifier.padding(...))`, no
`verticalScroll`, no fixed height), and a `LazyColumn` with no `weight`
inside an unbounded-height `Column` throws at measure time (`LazyColumn`
requires a bounded height constraint unless it's the one flexible child of
a `Column` via `weight`). The actual fix is `Modifier.weight(1f, fill = false)`
— this still gives the `LazyColumn` a bounded *maximum* height (the
remaining space in the drawer), which it needs to not crash, but no longer
*forces* it to consume all of that space when the chapter list is short.

- [ ] **Step 1: Write the failing test**

Add this test to `BookFlowTest.kt`, directly below Task 14's
`chapterListSeparatesRowsWithDividersInsteadOfIndividualRowBackgrounds`:

```kotlin
    @Test
    fun manageBooksSitsDirectlyBelowAShortChapterListInsteadOfAtTheDrawerBottom() {
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ContentsPanel(
                    books = BOOKS,
                    currentBookId = "book-b",
                    currentChapterId = "chapter-c",
                    query = "",
                    searchResults = emptyList(),
                    searching = false,
                    closeLabel = "Close contents",
                    onClose = {},
                    onSwitchBook = {},
                    onChapterSelected = {},
                    onQueryChanged = {},
                    onSearchResult = {},
                    onOpenBooks = {},
                    onAppearance = {},
                )
            }
        }

        // BOOKS[1] ("Other Story") has exactly 1 chapter, so the list is as
        // short as it gets.
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val lastChapter = compose.onNodeWithText("First Light").fetchSemanticsNode().boundsInRoot
        val manageBooks = compose.onNodeWithText("Manage books").fetchSemanticsNode().boundsInRoot

        assertTrue(
            "Manage books must sit close under the last chapter row, not near the drawer bottom; " +
                "lastChapter=$lastChapter manageBooks=$manageBooks root=$root",
            manageBooks.top - lastChapter.bottom < root.height / 4f,
        )
    }
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.BookFlowTest.manageBooksSitsDirectlyBelowAShortChapterListInsteadOfAtTheDrawerBottom" --console=plain`
Expected: FAILS — today's `Modifier.weight(1f)` (fill defaults to `true`)
forces the `LazyColumn` to consume all remaining drawer height even for a
single-chapter book, pushing "Manage books" to the very bottom.

- [ ] **Step 3: Change `weight(1f)` to `weight(1f, fill = false)`**

In `ContentsPanel.kt` (after Task 14's edit, the line reads):

```kotlin
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) {
```

Change to:

```kotlin
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).padding(top = 6.dp)) {
```

- [ ] **Step 4: Run the test again to confirm it passes**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.BookFlowTest.manageBooksSitsDirectlyBelowAShortChapterListInsteadOfAtTheDrawerBottom" --console=plain`
Expected: `BUILD SUCCESSFUL`, 1 test passed.

- [ ] **Step 5: Run the full `BookFlowTest` class, and confirm a long chapter list still scrolls correctly**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.inkyquill.pocketeditor.ui.BookFlowTest" --console=plain`
Expected: `BUILD SUCCESSFUL`. `fill = false` only changes behavior when
content is shorter than the available space — a book with many chapters
still has its `LazyColumn` expand up to (and scroll within) the same
bounded maximum height as before, so no existing scroll-related assertion
should change.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui/contents/ContentsPanel.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt
git commit -m "fix: stop the Contents chapter list from reserving full drawer height for a short book"
```

---

## Self-review

**Spec coverage:** items 1-9 of
`docs/superpowers/specs/2026-07-21-ux-bugfixes-and-polish-design.md`,
including the follow-on corrections from the code-review pass on that spec,
each map to at least one task above: item 1 → Task 1; item 2 → Task 9; item
3 → Task 2; item 4 → Tasks 11-12; item 5 (a-f) → Tasks 5-8, 10; item 6 →
Tasks 3-4; item 7 → Task 13; item 8 → Task 14; item 9 → Tasks 15-17. The
spec's "Cross-cutting notes" priority order (1, 3, 5+6, 2+5e, 4, 7/8/9) is
preserved as this plan's task order, with 5 split into 5-8 and 10 to keep
each task reviewable on its own, and 4 (composer padding) split into 11-12
to isolate the padding/color fix from the edge-margin fix.

**Placeholder scan:** every step that changes code shows the exact
before/after code; every test step shows complete, runnable test code, not
a description of a test. Three tasks (2, 4) conclude that no production
code change may be needed and say so explicitly with a fallback plan if the
assumption turns out wrong, rather than leaving a "TBD."

**Type/name consistency:** `flyoutPlacementIsBelow`, `ReviewFab`,
`fabShowsForThisPane`, `composerEdgeMarginPx`, `flyoutReservedAbovePx`, and
`marginPx` are each introduced once (Tasks 7-9, 12) and referenced with the
same name in every later task that touches the same code. Content
descriptions (`"Open review panel"`, `"Close review panel"`,
`"Review mode on"`/`"Review mode off"`) are preserved exactly from the
existing codebase throughout, since Tasks 6-7 deliberately re-skin existing
composables rather than introducing new semantics.
