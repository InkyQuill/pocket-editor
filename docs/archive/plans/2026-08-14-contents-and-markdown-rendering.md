# Contents and Markdown Rendering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Open the contents list at the current chapter, keep it tracking navigation, remove redundant book chips, and render CommonMark soft breaks as spaces while preserving byte-accurate selection mapping.

**Architecture:** Give `ContentsPanel` a keyed `LazyListState` initialized from the current chapter and update it when the selected ID changes. Normalize only the `SoftLineBreak` inline node to a protected display space whose boundary map still spans the raw newline; keep `HardLineBreak` and structural block content unchanged.

**Tech Stack:** Kotlin, Jetpack Compose lazy lists, commonmark-java, JUnit 5, Compose UI instrumentation.

## Global Constraints

- Work on `fix/review-issues-4-5` after the bidirectional-sync plan.
- Do not retain or replace the horizontal book-shortcut row; switching stays under **Manage books**.
- `SoftLineBreak` displays one space.
- Two trailing spaces and a trailing backslash remain hard line breaks.
- Source selection and anchors continue using exact UTF-8 byte ranges.

---

### Task 1: CommonMark soft-break rendering and source mapping

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/markdown/MarkdownParser.kt`
- Test: `app/src/test/java/net/inkyquill/pocketeditor/markdown/MarkdownParserTest.kt`
- Test: `app/src/test/java/net/inkyquill/pocketeditor/markdown/SelectionMapperTest.kt`

**Interfaces:**
- Produces: a displayed space for `SoftLineBreak` with boundaries `[rawNewline.startByte, rawNewline.endByte]`.
- Preserves: displayed newline for `HardLineBreak` and unchanged code-block text.

- [ ] **Step 1: Add failing parser and mapping tests**

```kotlin
@Test fun `soft break displays as space and maps over raw newline`() {
    val source = "Первый фрагмент\nвторой фрагмент"
    val document = MarkdownParser.parse(source)
    val block = document.blocks.single()
    assertEquals("Первый фрагмент второй фрагмент", block.text)
    val display = block.text.indexOf(" ")
    assertEquals(source.rawRangeOf("\n"), SelectionMapper.toRawRange(document, TextRange(block.index, display, display + 1)))
}

@Test fun `both hard break forms display newlines`() {
    assertEquals("a\nb", MarkdownParser.parse("a  \nb").blocks.single().text)
    assertEquals("a\nb", MarkdownParser.parse("a\\\nb").blocks.single().text)
}
```

Add a fenced-code assertion that its embedded newline remains unchanged.

- [ ] **Step 2: Run the focused tests and confirm the soft-break assertion fails**

Run: `./gradlew testDebugUnitTest --tests '*MarkdownParserTest*break*' --tests '*SelectionMapperTest*soft break*'`

Expected: FAIL because `SoftLineBreak` currently appends `\n`.

- [ ] **Step 3: Render soft breaks through protected boundary mapping**

```kotlin
when (node) {
    is SoftLineBreak -> appendProtected(" ", requireNotNull(raw), inheritedKind)
    is HardLineBreak -> appendProtected("\n", requireNotNull(raw), inheritedKind)
}
```

Update plain-text extraction to append a space for `SoftLineBreak` and newline for `HardLineBreak`. Do not collapse whitespace in code, table, HTML, or separate block nodes.

- [ ] **Step 4: Run all Markdown and anchor tests**

Run: `./gradlew testDebugUnitTest --tests 'net.inkyquill.pocketeditor.markdown.*' --tests 'net.inkyquill.pocketeditor.anchor.*' --tests '*SourceSearchTest'`

Expected: PASS.

- [ ] **Step 5: Commit Markdown semantics**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/markdown/MarkdownParser.kt app/src/test/java/net/inkyquill/pocketeditor/markdown app/src/test/java/net/inkyquill/pocketeditor/anchor
git commit -m "fix: render markdown soft breaks as spaces"
```

### Task 2: Current-chapter contents positioning and chip removal

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/contents/ContentsPanel.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/navigation/PocketEditorRoot.kt`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowScreenshotTest.kt`

**Interfaces:**
- Produces: `ContentsPanel` without `onSwitchBook`.
- Produces: keyed list state initialized from `currentChapterId` and updated by `scrollToItem`.

- [ ] **Step 1: Add failing Compose assertions**

```kotlin
@Test fun contentsStartsAtCurrentChapter() {
    renderContents(chapterCount = 80, currentChapter = 55)
    compose.onNodeWithText("Chapter 55").assertIsDisplayed()
    compose.onNodeWithText("Chapter 1").assertDoesNotExist()
}

@Test fun contentsHasNoBookShortcutChips() {
    renderContents(bookCount = 2)
    compose.onNodeWithText("Second Book").assertDoesNotExist()
    compose.onNodeWithTag("manage-books").assertIsDisplayed()
}
```

Add a test that changes `currentChapterId` while keeping the panel composed and expects the new row to become visible.

- [ ] **Step 2: Run the target instrumentation class and confirm failure**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.BookFlowTest`

Expected: current-chapter visibility fails. If no emulator is connected, run `compileDebugAndroidTestKotlin` and keep runtime status pending.

- [ ] **Step 3: Introduce keyed lazy-list state**

```kotlin
val chapters = book?.chapters.orEmpty()
val currentIndex = chapters.indexOfFirst { it.id == currentChapterId }.coerceAtLeast(0)
val chapterListState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex)

LaunchedEffect(currentChapterId, chapters.map(BookChapter::id)) {
    val index = chapters.indexOfFirst { it.id == currentChapterId }
    if (index >= 0) chapterListState.scrollToItem(index)
}
```

Pass `state = chapterListState` to `LazyColumn`. Empty lists do nothing; a removed ID falls back safely when the next valid current chapter arrives.

- [ ] **Step 4: Remove book chips and obsolete callback wiring**

Delete the `books.size > 1` shortcut block, the `onSwitchBook` parameter, and its call site in `PocketEditorRoot`. Keep the **Manage books** action unchanged.

```kotlin
fun ContentsPanel(
    books: List<BookSummary>,
    currentBookId: String,
    currentChapterId: String,
    // no onSwitchBook callback
    onOpenBooks: () -> Unit,
    // remaining callbacks unchanged
)
```

- [ ] **Step 5: Compile and run contents tests**

Run: `./gradlew compileDebugAndroidTestKotlin testDebugUnitTest && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.BookFlowTest`

Expected: compilation and the target instrumentation class pass on an available emulator.

- [ ] **Step 6: Update screenshots only after reviewing rendered output**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.BookFlowScreenshotTest`

Expected: screenshots show the current chapter in view and no book-chip row. Accept fixture updates only for those intended differences.

- [ ] **Step 7: Commit contents behavior**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui/contents/ContentsPanel.kt app/src/main/java/net/inkyquill/pocketeditor/ui/navigation/PocketEditorRoot.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui
git commit -m "fix: keep current chapter visible in contents"
```

### Task 3: Slice verification

**Files:**
- Verify only.

- [ ] **Step 1: Run unit tests, lint, and Android-test compilation**

Run: `./gradlew testDebugUnitTest lintDebug compileDebugAndroidTestKotlin`

Expected: BUILD SUCCESSFUL with zero failures.

- [ ] **Step 2: Inspect a representative wrapped Markdown chapter**

Use a copied test fixture containing ordinary newlines, two-space breaks, backslash breaks, and fenced code. Confirm prose reflows, explicit hard breaks remain, and the original file bytes are unchanged.
