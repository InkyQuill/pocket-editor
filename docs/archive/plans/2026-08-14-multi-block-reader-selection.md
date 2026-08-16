# Multi-Block Reader Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the reader select text across several Markdown blocks and create signals or comments from the exact source bytes while keeping source replacement restricted to one block.

**Architecture:** Upgrade to Compose Foundation 1.12 through BOM `2026.08.00`, place all reader text under one stateful `SelectionContainer`, and exclude chrome with `DisableSelection`. Correlate `SelectionState.selectedTexts` with `getSelectableTexts()` and block metadata to build one visual-order `TextRange`, then map its endpoints and intermediate raw separators through `SelectionMapper`.

**Tech Stack:** Kotlin, Jetpack Compose Foundation 1.12, `SelectionState`, lazy lists, JUnit 5, Compose UI instrumentation.

## Global Constraints

- Work on `fix/review-issues-4-5` after the contents and Markdown plan.
- Use Compose BOM `2026.08.00` and the stable Foundation 1.12 selection API.
- Cross-block selections support all signal types and comments.
- Cross-block selections never expose **Suggest edit**.
- Review cards, controls, list markers, and footnote popovers must not enter the selected text.
- Store selected text by slicing original UTF-8 source bytes, including Markdown syntax and paragraph separators.

---

### Task 1: Upgrade Compose and establish the selection-state API

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt`
- Test: `app/src/test/java/net/inkyquill/pocketeditor/ReleaseWorkflowPolicyTest.kt`

**Interfaces:**
- Produces: Compose BOM `2026.08.00`.
- Produces: `rememberSelectionState()` scoped to `(bookId, chapterId)` and passed to `SelectionContainer(state = selectionState)`.

- [ ] **Step 1: Add a dependency policy assertion**

```kotlin
@Test fun `compose bom supports public selection state`() {
    val catalog = listOf(Path.of("..", "gradle", "libs.versions.toml"), Path.of("gradle", "libs.versions.toml"))
        .first(Files::exists)
    assertTrue(Files.readString(catalog).contains("compose-bom = \"2026.08.00\""))
}
```

- [ ] **Step 2: Run the policy test and confirm the old BOM fails**

Run: `./gradlew testDebugUnitTest --tests '*BuildSmokeTest*selection state*'`

Expected: FAIL because the catalog contains `2026.06.00`.

- [ ] **Step 3: Upgrade the BOM and compile a minimal stateful container**

```kotlin
val selectionState = rememberSelectionState()
SelectionContainer(state = selectionState) {
    ReaderBlocks(...)
}
```

Use `key(state.bookId, state.chapterId)` or equivalent chapter scoping so a selection never leaks into another chapter.

- [ ] **Step 4: Compile main and instrumentation sources**

Run: `./gradlew compileDebugKotlin compileDebugAndroidTestKotlin`

Expected: BUILD SUCCESSFUL against Foundation 1.12.

- [ ] **Step 5: Commit the dependency floor**

```bash
git add gradle/libs.versions.toml app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt app/src/test/java/net/inkyquill/pocketeditor/ReleaseWorkflowPolicyTest.kt
git commit -m "build: update compose for selection state"
```

### Task 2: Map visual multi-block selection to exact source bytes

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/markdown/SelectionMapper.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/markdown/RenderedDocument.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/reader/ReviewProjector.kt`
- Test: `app/src/test/java/net/inkyquill/pocketeditor/markdown/SelectionMapperTest.kt`
- Test: `app/src/test/java/net/inkyquill/pocketeditor/reader/ReviewProjectorTest.kt`

**Interfaces:**
- Produces: `SelectionMapper.toRawRange(document: RenderedDocument, range: TextRange): RawRange?` for same-block and cross-block ranges.
- Produces: `SelectionMapper.toSourceSelection(document, range): ReaderSourceSelection?` only if dependency direction remains acceptable; otherwise keep source slicing in the controller.
- Produces: `ReviewProjector.locateSlices(document, rawRange): List<LocalRange>` for signals; edits still require exactly one slice.

- [ ] **Step 1: Replace the cross-block rejection test with exact-range cases**

```kotlin
@Test fun `selection across three utf8 blocks includes raw separators`() {
    val source = "Первый 😀 абзац\n\nВторой *абзац*\n\nТретий абзац"
    val document = MarkdownParser.parse(source)
    val range = TextRange(startBlock = 0, start = 7, endBlock = 2, end = 6)
    val raw = requireNotNull(SelectionMapper.toRawRange(document, range))
    assertEquals("😀 абзац\n\nВторой *абзац*\n\nТретий", source.bytes(raw))
}
```

Add reverse-handle normalization, hidden endpoint rejection, synthetic-only endpoint rejection, partial Markdown syntax rejection, and same-block regression cases.

- [ ] **Step 2: Run mapper tests and confirm cross-block failure**

Run: `./gradlew testDebugUnitTest --tests '*SelectionMapperTest'`

Expected: FAIL at the current `startBlock != endBlock` guard.

- [ ] **Step 3: Implement endpoint mapping and raw span composition**

```kotlin
val ordered = range.normalized()
val first = document.blocks.getOrNull(ordered.startBlock) ?: return null
val last = document.blocks.getOrNull(ordered.endBlock) ?: return null
val startByte = boundary(first, ordered.start, endpoint = Start) ?: return null
val endByte = boundary(last, ordered.end, endpoint = End) ?: return null
if (document.blocks.subList(ordered.startBlock, ordered.endBlock + 1).any(RenderedBlock::hidden)) return null
return RawRange(startByte, endByte).takeIf { startByte < endByte }
```

Reuse the existing syntax-span containment checks on both endpoint blocks. Intermediate block bytes and raw separators are included naturally by the single `[startByte, endByte)` span.

Replace `ReviewProjector.locate(...).singleOrNull()` for signals with `locateSlices`: intersect the resolved raw range with every visible block, map each intersection through byte boundaries, highlight all returned slices, and attach a non-empty comment card only to the first slice. Keep edit resolution on the existing single-block path and mark a cross-block edit unresolved if malformed legacy data contains one.

- [ ] **Step 4: Run mapper, anchor, and review-projector tests**

Run: `./gradlew testDebugUnitTest --tests '*SelectionMapperTest' --tests '*AnchorTest' --tests '*ReviewProjectorTest'`

Expected: PASS.

- [ ] **Step 5: Commit raw-range mapping**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/markdown app/src/test/java/net/inkyquill/pocketeditor/markdown app/src/test/java/net/inkyquill/pocketeditor/anchor app/src/test/java/net/inkyquill/pocketeditor/reader/ReviewProjectorTest.kt
git commit -m "feat: map selections across markdown blocks"
```

### Task 3: Replace per-paragraph text fields with one selectable reader surface

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderDocument.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderSelectionAdapter.kt`
- Create test: `app/src/test/java/net/inkyquill/pocketeditor/ui/reader/ReaderSelectionAdapterTest.kt`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt`

**Interfaces:**
- Produces: `ReaderSelectionAdapter.range(selected: List<AnnotatedString>, all: List<AnnotatedString>): TextRange?`.
- Produces: one `ReaderSourceSelection?` callback for the entire reader selection.
- Produces: visible-endpoint `Rect?` for flyout placement.

- [ ] **Step 1: Add failing adapter tests with duplicate paragraph text**

```kotlin
@Test fun `adapter uses annotated block identity when texts repeat`() {
    val all = listOf(tagged(3, "same"), tagged(4, "same"), tagged(5, "last"))
    val selected = listOf(taggedSelection(4, 2, 4, "me"), taggedSelection(5, 0, 2, "la"))
    assertEquals(TextRange(4, 2, 5, 2), adapter.range(selected, all, blocks))
}
```

Tag every selectable character range with a private string annotation carrying `blockIndex`, original display start, and original display end. This preserves exact endpoints after Compose slices the `AnnotatedString`; never correlate by visible text alone. `all` is used only to validate visual block order and reject selected text that did not originate in the current reader document.

```kotlin
for (offset in text.indices) {
    addStringAnnotation(
        tag = "reader-selection-provenance",
        annotation = "${block.sourceIndex}:$offset:${offset + 1}",
        start = offset,
        end = offset + 1,
    )
}
```

- [ ] **Step 2: Run adapter tests and confirm the adapter is absent**

Run: `./gradlew testDebugUnitTest --tests '*ReaderSelectionAdapterTest'`

Expected: FAIL because the adapter does not exist.

- [ ] **Step 3: Render selectable text without read-only `BasicTextField`**

Replace `ReviewableText` with `BasicText` or Material `Text` under the shared `SelectionContainer`. Preserve annotated styling, semantics, footnote pointer handling, layout callbacks, and test tags. Wrap list bullets, quote markers, signal labels, comments, reader controls, composer UI, and footnote popovers in `DisableSelection`.

```kotlin
SelectionContainer(state = selectionState) {
    LazyColumn(state = listState) {
        items(state.document.blocks, key = ReaderBlock::sourceIndex) { block ->
            ReaderDocumentBlock(block = block.annotatedWithIdentity(), ...)
        }
    }
}
```

- [ ] **Step 4: Observe selection state after layout**

```kotlin
LaunchedEffect(selectionState, state.chapterId) {
    snapshotFlow { selectionState.selectedTexts }
        .collectLatest { selected ->
            val range = adapter.range(selected, selectionState.getSelectableTexts())
            callbacks.onTextSelected(range?.let { document.sourceSelection(it) })
        }
}
```

Do not call `getSelectableTexts()` during composition. Clear selection and flyout state on chapter changes. Keep selected lazy items pinned by the framework while handles move; do not clear merely because the first endpoint scrolls off-screen.

- [ ] **Step 5: Place the flyout from the visible active endpoint**

Collect the endpoint text layout coordinates and translate the final visible glyph bounds into root coordinates. Recompute on selection, scroll, and layout changes. If neither endpoint is visible, hide the flyout without clearing the selection.

```kotlin
val endpointBounds = adapter.visibleEndpointBounds(
    selected = selectionState.selectedTexts,
    layouts = visibleBlockLayouts,
    preferEnd = true,
)
selectionBoundsInRoot = endpointBounds
```

- [ ] **Step 6: Run adapter tests and compile instrumentation**

Run: `./gradlew testDebugUnitTest --tests '*ReaderSelectionAdapterTest' --tests '*ReaderLayoutPolicyTest' && ./gradlew compileDebugAndroidTestKotlin`

Expected: PASS.

- [ ] **Step 7: Commit the shared selection surface**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui/reader app/src/test/java/net/inkyquill/pocketeditor/ui/reader app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt
git commit -m "feat: select text across reader blocks"
```

### Task 4: Allow signals and comments but forbid cross-block edits

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/reader/ReaderState.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/ReviewDraft.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/ReviewUiState.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/SelectionFlyout.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/EditorialReviewController.kt`
- Test: `app/src/test/java/net/inkyquill/pocketeditor/ui/review/EditorialReviewControllerTest.kt`
- Test: `app/src/test/java/net/inkyquill/pocketeditor/ui/review/ReviewDraftStateMachineTest.kt`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt`

**Interfaces:**
- Produces: `ReaderSourceSelection(val rawRange: RawRange, val selectedText: String, val spansMultipleBlocks: Boolean)`.
- Produces: `ReviewDraftSession.canSuggestEdit: Boolean`.

- [ ] **Step 1: Add failing controller and state-machine tests**

```kotlin
@Test fun `cross block selection allows signal but not edit`() = runTest {
    controller.select(selection(spansMultipleBlocks = true))
    assertTrue(controller.state.value.draftSession.canChooseSignal)
    assertFalse(controller.state.value.draftSession.canSuggestEdit)
    controller.chooseSignal(SignalType.NOTE)
    controller.changeDraftText("Комментарий к двум абзацам")
    controller.saveDraft()
    assertEquals(expectedRawSlice, actions.savedSignal.selectedText)
}
```

- [ ] **Step 2: Run review tests and confirm edit remains enabled**

Run: `./gradlew testDebugUnitTest --tests '*EditorialReviewControllerTest*cross block*' --tests '*ReviewDraftStateMachineTest*cross block*'`

Expected: FAIL because the selection carries no block-span capability.

- [ ] **Step 3: Carry selection capability into flyout state**

Set `spansMultipleBlocks` when `TextRange.startBlock != TextRange.endBlock`. Derive `canSuggestEdit = validSelection && !selection.spansMultipleBlocks`. `SelectionFlyout` renders every signal choice but omits the edit action when false.

```kotlin
if (session.canSuggestEdit) {
    TextButton(onClick = onEdit) { Text(stringResource(R.string.suggest_edit)) }
}
```

Guard `chooseEdit()` in the state machine as well, so no accessibility action or stale UI callback can submit a cross-block edit.

- [ ] **Step 4: Run review unit tests**

Run: `./gradlew testDebugUnitTest --tests 'net.inkyquill.pocketeditor.ui.review.*' --tests 'net.inkyquill.pocketeditor.review.*'`

Expected: PASS.

- [ ] **Step 5: Commit review capability rules**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/reader app/src/main/java/net/inkyquill/pocketeditor/ui/review app/src/test/java/net/inkyquill/pocketeditor/ui/review app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt
git commit -m "feat: comment on multi-block selections"
```

### Task 5: Instrumented selection verification

**Files:**
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt`
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReaderScreenshotTest.kt`

- [ ] **Step 1: Add adjacent- and three-block interaction cases**

Use Compose touch input to long-press in paragraph one, drag the end handle into paragraph two, select a signal, enter a comment, and save it. Repeat by dragging into an auto-scrolled third paragraph. Assert the saved anchor bytes include both `\n\n` separators.

```kotlin
compose.onNodeWithTag("reader-scroll").performTouchInput {
    down(Offset(width * 0.35f, height * 0.20f))
    advanceEventTime(650)
    moveTo(Offset(width * 0.70f, height * 0.72f), 700)
    up()
}
compose.onNodeWithText("Заметка").performClick()
compose.onNodeWithTag("review-comment").performTextInput("Комментарий к нескольким абзацам")
compose.onNodeWithText("Сохранить").performClick()
assertEquals("конец первого\n\nВторой\n\nначало третьего", savedSignal.selectedText)
```

- [ ] **Step 2: Assert selection exclusions**

Select across a list item followed by a reviewed paragraph. Assert the selected source text excludes the bullet glyph, signal dot, comment card, and footnote popup content. Assert **Suggest edit** does not exist while signal actions remain visible.

```kotlin
compose.onNodeWithText("Предложить правку").assertDoesNotExist()
compose.onNodeWithText("Заметка").assertIsDisplayed()
assertFalse(savedSelection.contains("•"))
assertFalse(savedSelection.contains(existingCommentText))
```

- [ ] **Step 3: Run instrumentation on an available emulator**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.ReviewInteractionTest`

Expected: PASS for adjacent blocks, auto-scrolled third block, exclusions, and edit suppression. If no emulator is connected, record runtime verification as pending.

- [ ] **Step 4: Run full verification**

Run: `./gradlew testDebugUnitTest lintDebug compileDebugAndroidTestKotlin`

Expected: BUILD SUCCESSFUL with zero failures.

- [ ] **Step 5: Commit final instrumentation coverage**

```bash
git add app/src/androidTest/java/net/inkyquill/pocketeditor/ui
git commit -m "test: cover multi-block reader selection"
```
