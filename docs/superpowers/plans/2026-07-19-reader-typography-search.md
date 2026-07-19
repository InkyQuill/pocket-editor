# Reader Typography and Search Highlight Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bundle Literata and Manrope, render Markdown prose with the approved hierarchy, keep the app chrome compact, and highlight the exact match inside every search excerpt.

**Architecture:** Material `Typography` becomes a fixed Manrope UI system, while a `CompositionLocal` carries separately scalable Literata reader styles. Markdown block level and inline run kind survive parsing and review projection so the reader can choose a style without reparsing source; search hits carry display-relative match offsets so Compose can decorate the excerpt without guessing from the normalized query.

**Tech Stack:** Kotlin 2.x, Jetpack Compose Material 3, CommonMark, Room FTS4, JUnit 5, Android Compose UI tests, Gradle.

## Global Constraints

- Use bundled static Literata for rendered book content and bundled static Manrope for all app chrome; do not download fonts at runtime.
- Include both upstream OFL license texts in the application source tree.
- The in-app text-size setting scales only rendered book prose; Android accessibility font scale continues to affect both book text and UI chrome.
- H1: 28sp/35sp SemiBold, 24dp before and 10dp after.
- H2: 23sp/30sp SemiBold, 22dp before and 8dp after.
- H3: 19sp/26sp SemiBold, 18dp before and 6dp after.
- H4: 17sp/24sp SemiBold, 16dp before and 4dp after.
- H5: 16sp/23sp Bold, 14dp before and 4dp after.
- H6: 14sp/21sp Bold, 12dp before and 4dp after.
- Paragraph, list item, and blockquote: 16sp/25sp Regular with 12dp after.
- Emphasis is Italic; strong is Bold; links use the theme link color and an underline.
- Blockquotes use an inset and left marker without forced italic; list items use a hanging indent; thematic breaks stay subdued.
- Tables and fenced-code presentation are outside this change; retain their current safe fallback rendering.
- Reader top-bar chapter title is Manrope 18sp SemiBold; sync and error text is Manrope 13sp Regular.
- Search result chapter title is Manrope SemiBold; excerpt is Literata 14sp/21sp; the matched substring is Bold with an accessible light/dark background.
- Preserve H1 through H6 and exact UTF-8 source mappings used by selection, review anchors, and search navigation.
- Do not modify canonical Markdown files or the JSON review storage format.
- Preserve the current uncommitted Yandex, folder-browser, NoActionBar, and edge-to-edge fixes; stage only files named by each task.

---

## File Map

- `app/src/main/res/font/literata_*.ttf`: static Regular, Italic, SemiBold, and Bold faces used by book prose.
- `app/src/main/res/font/manrope_*.ttf`: static Regular and SemiBold faces used by UI chrome.
- `app/src/main/res/raw/ofl_literata.txt`, `app/src/main/res/raw/ofl_manrope.txt`: bundled upstream license notices.
- `app/src/main/java/net/inkyquill/pocketeditor/ui/theme/Type.kt`: font families, fixed Manrope Material typography, scalable reader prose token model, and `LocalReaderTypography`.
- `app/src/main/java/net/inkyquill/pocketeditor/ui/theme/Theme.kt`: provides reader-only scale while leaving Material chrome unscaled.
- `app/src/main/java/net/inkyquill/pocketeditor/markdown/RenderedDocument.kt`: carries `headingLevel` and inline `RenderKind` metadata.
- `app/src/main/java/net/inkyquill/pocketeditor/markdown/MarkdownParser.kt`: records CommonMark heading levels.
- `app/src/main/java/net/inkyquill/pocketeditor/reader/ReviewProjector.kt`: preserves block level and inline kind through clean/review projections.
- `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderDocument.kt`: maps reader tokens and Markdown semantics to Compose text, spacing, quote, list, link, and thematic-break presentation.
- `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt`: removes the global 20dp block gap and applies the approved top-bar hierarchy.
- `app/src/main/java/net/inkyquill/pocketeditor/search/SearchEntity.kt`: adds excerpt-relative match offsets to `SearchHit`.
- `app/src/main/java/net/inkyquill/pocketeditor/search/SourceSearch.kt`: returns excerpt text and exact visible match range after Unicode normalization.
- `app/src/main/java/net/inkyquill/pocketeditor/ui/search/SearchExcerpt.kt`: pure construction of the highlighted `AnnotatedString`.
- `app/src/main/java/net/inkyquill/pocketeditor/ui/search/SearchScreen.kt`: applies Literata excerpt and highlighted-match presentation.
- Existing JVM and instrumentation tests named below cover token values, parser/projection propagation, offsets, search decoration, reader semantics, and screenshots.

### Task 1: Bundle Fonts and Separate Reader Typography from UI Typography

**Files:**
- Delete: `app/src/main/res/font/book_serif.ttf`
- Delete: `app/src/main/res/font/book_serif_bold.ttf`
- Delete: `app/src/main/res/font/book_serif_italic.ttf`
- Create: `app/src/main/res/font/literata_regular.ttf`
- Create: `app/src/main/res/font/literata_italic.ttf`
- Create: `app/src/main/res/font/literata_semibold.ttf`
- Create: `app/src/main/res/font/literata_bold.ttf`
- Create: `app/src/main/res/font/manrope_regular.ttf`
- Create: `app/src/main/res/font/manrope_semibold.ttf`
- Create: `app/src/main/res/raw/ofl_literata.txt`
- Create: `app/src/main/res/raw/ofl_manrope.txt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/theme/Type.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/theme/Theme.kt`
- Modify: `app/src/test/java/net/inkyquill/pocketeditor/ui/theme/ThemeTokenTest.kt`

**Interfaces:**
- Consumes: `/home/inky/Загрузки/Literata,Manrope.zip` supplied by the user.
- Produces: `LiterataFamily: FontFamily`, `ManropeFamily: FontFamily`, `ReaderTypography.scaled(scale: Float): ReaderTypography`, and `LocalReaderTypography: ProvidableCompositionLocal<ReaderTypography>`.

- [ ] **Step 1: Replace the prose-size test with failing dual-system token tests**

Add these tests to `ThemeTokenTest` and remove the obsolete assertion that `PocketTypography.bodyLarge` is 18sp/28sp:

```kotlin
@Test
fun `reader typography matches approved prose scale`() {
    assertEquals(28.sp, DefaultReaderTypography.h1.fontSize)
    assertEquals(35.sp, DefaultReaderTypography.h1.lineHeight)
    assertEquals(23.sp, DefaultReaderTypography.h2.fontSize)
    assertEquals(19.sp, DefaultReaderTypography.h3.fontSize)
    assertEquals(17.sp, DefaultReaderTypography.h4.fontSize)
    assertEquals(16.sp, DefaultReaderTypography.h5.fontSize)
    assertEquals(14.sp, DefaultReaderTypography.h6.fontSize)
    assertEquals(16.sp, DefaultReaderTypography.prose.fontSize)
    assertEquals(25.sp, DefaultReaderTypography.prose.lineHeight)
    assertEquals(14.sp, DefaultReaderTypography.searchExcerpt.fontSize)
    assertEquals(21.sp, DefaultReaderTypography.searchExcerpt.lineHeight)
}

@Test
fun `reader scale changes prose but not Manrope chrome`() {
    val scaled = DefaultReaderTypography.scaled(1.3f)

    assertEquals(20.8.sp, scaled.prose.fontSize)
    assertEquals(32.5.sp, scaled.prose.lineHeight)
    assertEquals(18.sp, PocketTypography.titleLarge.fontSize)
    assertEquals(13.sp, PocketTypography.labelMedium.fontSize)
    assertEquals(ManropeFamily, PocketTypography.titleLarge.fontFamily)
    assertEquals(LiterataFamily, scaled.prose.fontFamily)
}
```

- [ ] **Step 2: Run the focused tests and verify they fail**

Run: `./gradlew testDebugUnitTest --tests net.inkyquill.pocketeditor.ui.theme.ThemeTokenTest`

Expected: compilation fails because `DefaultReaderTypography`, `ManropeFamily`, and `LiterataFamily` do not exist.

- [ ] **Step 3: Extract only the approved static faces and exact license files**

Run:

```bash
tmp_fonts=$(mktemp -d)
unzip -q '/home/inky/Загрузки/Literata,Manrope.zip' -d "$tmp_fonts"
mkdir -p app/src/main/res/raw
cp "$tmp_fonts/Literata/static/Literata-Regular.ttf" app/src/main/res/font/literata_regular.ttf
cp "$tmp_fonts/Literata/static/Literata-Italic.ttf" app/src/main/res/font/literata_italic.ttf
cp "$tmp_fonts/Literata/static/Literata-SemiBold.ttf" app/src/main/res/font/literata_semibold.ttf
cp "$tmp_fonts/Literata/static/Literata-Bold.ttf" app/src/main/res/font/literata_bold.ttf
cp "$tmp_fonts/Manrope/static/Manrope-Regular.ttf" app/src/main/res/font/manrope_regular.ttf
cp "$tmp_fonts/Manrope/static/Manrope-SemiBold.ttf" app/src/main/res/font/manrope_semibold.ttf
cp "$tmp_fonts/Literata/OFL.txt" app/src/main/res/raw/ofl_literata.txt
cp "$tmp_fonts/Manrope/OFL.txt" app/src/main/res/raw/ofl_manrope.txt
rm app/src/main/res/font/book_serif.ttf app/src/main/res/font/book_serif_bold.ttf app/src/main/res/font/book_serif_italic.ttf
```

Expected: six TTF files and two OFL files exist; the three DejaVu-backed `book_serif` resources are gone. The temporary directory may be removed after verifying the copied files.

- [ ] **Step 4: Implement fixed Manrope chrome and scalable Literata reader tokens**

Replace `Type.kt` with font families and a focused token object. Keep all unspecified Material slots on Manrope by copying the stock Material styles through `withManrope()`:

```kotlin
val LiterataFamily = FontFamily(
    Font(R.font.literata_regular, FontWeight.Normal),
    Font(R.font.literata_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.literata_semibold, FontWeight.SemiBold),
    Font(R.font.literata_bold, FontWeight.Bold),
)

val ManropeFamily = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
)

data class ReaderTypography(
    val h1: TextStyle,
    val h2: TextStyle,
    val h3: TextStyle,
    val h4: TextStyle,
    val h5: TextStyle,
    val h6: TextStyle,
    val prose: TextStyle,
    val searchExcerpt: TextStyle,
) {
    fun scaled(scale: Float): ReaderTypography = copy(
        h1 = h1.scaled(scale), h2 = h2.scaled(scale), h3 = h3.scaled(scale),
        h4 = h4.scaled(scale), h5 = h5.scaled(scale), h6 = h6.scaled(scale),
        prose = prose.scaled(scale), searchExcerpt = searchExcerpt.scaled(scale),
    )
}

internal val DefaultReaderTypography = ReaderTypography(
    h1 = readerStyle(28, 35, FontWeight.SemiBold),
    h2 = readerStyle(23, 30, FontWeight.SemiBold),
    h3 = readerStyle(19, 26, FontWeight.SemiBold),
    h4 = readerStyle(17, 24, FontWeight.SemiBold),
    h5 = readerStyle(16, 23, FontWeight.Bold),
    h6 = readerStyle(14, 21, FontWeight.Bold),
    prose = readerStyle(16, 25, FontWeight.Normal),
    searchExcerpt = readerStyle(14, 21, FontWeight.Normal),
)

val LocalReaderTypography = staticCompositionLocalOf { DefaultReaderTypography }

private fun TextStyle.withManrope() = copy(fontFamily = ManropeFamily)

private fun Typography.withManrope() = copy(
    displayLarge = displayLarge.withManrope(),
    displayMedium = displayMedium.withManrope(),
    displaySmall = displaySmall.withManrope(),
    headlineLarge = headlineLarge.withManrope(),
    headlineMedium = headlineMedium.withManrope(),
    headlineSmall = headlineSmall.withManrope(),
    titleLarge = titleLarge.withManrope(),
    titleMedium = titleMedium.withManrope(),
    titleSmall = titleSmall.withManrope(),
    bodyLarge = bodyLarge.withManrope(),
    bodyMedium = bodyMedium.withManrope(),
    bodySmall = bodySmall.withManrope(),
    labelLarge = labelLarge.withManrope(),
    labelMedium = labelMedium.withManrope(),
    labelSmall = labelSmall.withManrope(),
)

internal val PocketTypography = Typography().withManrope().copy(
    titleLarge = TextStyle(ManropeFamily, FontWeight.SemiBold, 18.sp, lineHeight = 24.sp),
    labelMedium = TextStyle(ManropeFamily, FontWeight.Normal, 13.sp, lineHeight = 18.sp),
)

private fun readerStyle(size: Int, lineHeight: Int, weight: FontWeight) =
    TextStyle(LiterataFamily, weight, size.sp, lineHeight = lineHeight.sp)

private fun TextStyle.scaled(scale: Float) = copy(fontSize = fontSize * scale, lineHeight = lineHeight * scale)
```

In `PocketEditorTheme`, add `LocalReaderTypography provides DefaultReaderTypography.scaled(textScale.coerceIn(.8f, 1.3f))` to the existing `CompositionLocalProvider`, and pass unscaled `PocketTypography` to `MaterialTheme`.

- [ ] **Step 5: Run token tests and verify resource packaging**

Run:

```bash
./gradlew testDebugUnitTest --tests net.inkyquill.pocketeditor.ui.theme.ThemeTokenTest assembleDebug
unzip -l app/build/outputs/apk/debug/app-debug.apk | rg 'literata_|manrope_|ofl_(literata|manrope)'
```

Expected: `ThemeTokenTest` passes; APK listing contains all six fonts and both OFL resources and contains no `book_serif` resource.

- [ ] **Step 6: Commit only the typography resource slice**

```bash
git add app/src/main/res/font app/src/main/res/raw app/src/main/java/net/inkyquill/pocketeditor/ui/theme/Type.kt app/src/main/java/net/inkyquill/pocketeditor/ui/theme/Theme.kt app/src/test/java/net/inkyquill/pocketeditor/ui/theme/ThemeTokenTest.kt
git commit -m "feat: bundle reader and chrome typography"
```

### Task 2: Preserve Markdown Heading Levels and Inline Semantics

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/markdown/RenderedDocument.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/markdown/MarkdownParser.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/reader/ReviewProjector.kt`
- Modify: `app/src/test/java/net/inkyquill/pocketeditor/markdown/MarkdownParserTest.kt`
- Modify: `app/src/test/java/net/inkyquill/pocketeditor/reader/ReviewProjectorTest.kt`

**Interfaces:**
- Consumes: CommonMark `Heading.level` and existing `RenderedBlock.runs: List<RenderRun>`.
- Produces: `RenderedBlock.headingLevel: Int?`, `ReaderBlock.headingLevel: Int?`, and `ReaderRun.renderKind: RenderKind` with defaults that keep test fixtures source-compatible.

- [ ] **Step 1: Add failing parser tests for H1-H6 and inline kinds**

Add to `MarkdownParserTest`:

```kotlin
@Test
fun `preserves all heading levels and prose inline kinds`() {
    val source = (1..6).joinToString("\n\n") { level -> "${"#".repeat(level)} H$level" } +
        "\n\nОбычный *курсив*, **жирный** и [ссылка](https://example.com)."

    val document = MarkdownParser.parse(source)

    assertEquals((1..6).toList(), document.blocks.filter { it.kind == BlockKind.HEADING }.map { it.headingLevel })
    val paragraph = document.blocks.single { it.kind == BlockKind.PARAGRAPH }
    assertTrue(paragraph.runs.any { it.text == "курсив" && it.kind == RenderKind.EMPHASIS })
    assertTrue(paragraph.runs.any { it.text == "жирный" && it.kind == RenderKind.STRONG })
    assertTrue(paragraph.runs.any { it.text == "ссылка" && it.kind == RenderKind.LINK })
}
```

Add to `ReviewProjectorTest`:

```kotlin
@Test
fun `clean and reviewed projections retain heading and inline presentation metadata`() {
    val rendered = MarkdownParser.parse("### Подзаголовок\n\nТихий *вечер* и **свет**.")

    listOf(false, true).forEach { reviewMode ->
        val projected = ReviewProjector.project(rendered, review(), reviewMode)
        assertEquals(3, projected.blocks.first().headingLevel)
        val paragraphRuns = projected.blocks.last().runs
        assertTrue(paragraphRuns.any { "вечер" in it.text && it.renderKind == RenderKind.EMPHASIS })
        assertTrue(paragraphRuns.any { "свет" in it.text && it.renderKind == RenderKind.STRONG })
    }
}
```

Use the existing `review()` fixture helper from `ReviewProjectorTest`.

- [ ] **Step 2: Run the focused tests and verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests net.inkyquill.pocketeditor.markdown.MarkdownParserTest --tests net.inkyquill.pocketeditor.reader.ReviewProjectorTest
```

Expected: compilation fails on missing `headingLevel` and `renderKind` properties.

- [ ] **Step 3: Carry heading level through parser drafts**

Add `headingLevel: Int? = null` as the final constructor field of `RenderedBlock`, so positional fixtures remain source-compatible. Add the same final field to `BlockDraft`; make `finish` pass it by named arguments. Change heading collection to:

```kotlin
is Heading -> output += renderInlineBlock(
    node = child,
    kind = BlockKind.HEADING,
    source = source,
    index = index,
    headingLevel = child.level,
)
```

Give `renderInlineBlock` a final `headingLevel: Int? = null` parameter and pass it into `InlineBuilder.build`. Give `build` the same optional parameter. Hidden and protected drafts keep `null`. This preserves `BlockKind.HEADING` for existing callers while making level explicit.

- [ ] **Step 4: Carry inline kinds through review projection without breaking anchors**

Add these defaulted final fields so positional fixtures remain source-compatible:

```kotlin
data class ReaderRun(
    // existing fields unchanged
    val renderKind: RenderKind = RenderKind.TEXT,
)

data class ReaderBlock(
    // existing fields unchanged
    val headingLevel: Int? = null,
) { /* existing mapping methods */ }
```

Pass `headingLevel = block.headingLevel` in both `ReaderBlock` construction paths. Replace the clean projection's single canonical run with source-run segments created from `block.runs`; each segment must keep its `RenderRun.kind` and `block.byteBoundaries.slice(run.start..run.end)`.

In `appendSourceBacked`, include every `block.runs` start/end inside the requested interval in the boundary set. For each piece, resolve its inline kind with:

```kotlin
val renderKind = block.runs.firstOrNull { it.start <= pieceStart && pieceEnd <= it.end }?.kind
    ?: RenderKind.TEXT
```

Pass `renderKind` into `ReaderRun`. Update `addMerged` so two adjacent runs merge only when `renderKind` also matches. Added diff chunks remain `RenderKind.TEXT`; canonical and deleted chunks retain source presentation and byte provenance.

- [ ] **Step 5: Run parser, projection, anchor, and selection regressions**

Run:

```bash
./gradlew testDebugUnitTest --tests net.inkyquill.pocketeditor.markdown.MarkdownParserTest --tests net.inkyquill.pocketeditor.reader.ReviewProjectorTest --tests net.inkyquill.pocketeditor.markdown.SelectionMapperTest --tests net.inkyquill.pocketeditor.anchor.AnchorResolverTest
```

Expected: all selected suites pass; no UTF-8 range or review-selection test changes are needed.

- [ ] **Step 6: Commit the Markdown metadata slice**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/markdown/RenderedDocument.kt app/src/main/java/net/inkyquill/pocketeditor/markdown/MarkdownParser.kt app/src/main/java/net/inkyquill/pocketeditor/reader/ReviewProjector.kt app/src/test/java/net/inkyquill/pocketeditor/markdown/MarkdownParserTest.kt app/src/test/java/net/inkyquill/pocketeditor/reader/ReviewProjectorTest.kt
git commit -m "feat: preserve markdown presentation metadata"
```

### Task 3: Render the Approved Prose System

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderDocument.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt`
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReaderScreenshotTest.kt`
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewScreenshotTest.kt`
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/AdaptiveReaderTest.kt`

**Interfaces:**
- Consumes: `LocalReaderTypography.current`, `ReaderBlock.headingLevel`, and `ReaderRun.renderKind` from Tasks 1-2.
- Produces: `ReaderBlockPresentation` and `readerBlockPresentation(block: ReaderBlock): ReaderBlockPresentation`, used by `ReaderDocumentBlock` to apply exact style and spacing.

- [ ] **Step 1: Add a failing UI test for semantic hierarchy and reader-only scale**

In `AdaptiveReaderTest`, render an H1, H4, paragraph, quote, and list block under `PocketEditorTheme(textScale = 1.3f)`. Give the title/sync nodes stable tags `reader-topbar-title` and `reader-topbar-sync`. Capture each text layout with `performSemanticsAction(SemanticsActions.GetTextLayoutResult) { results -> layout = results.single() }`. Assert:

```kotlin
assertEquals(36.4f, h1Layout.layoutInput.style.fontSize.value, 0.01f)
assertEquals(22.1f, h4Layout.layoutInput.style.fontSize.value, 0.01f)
assertEquals(20.8f, paragraphLayout.layoutInput.style.fontSize.value, 0.01f)
assertEquals(18f, titleLayout.layoutInput.style.fontSize.value, 0.01f)
assertEquals(13f, syncLayout.layoutInput.style.fontSize.value, 0.01f)
compose.onNodeWithTag("reader-block-0").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
```

Expected meaning: in-app 130% scaling changes Literata reader sizes but not Manrope chrome sizes. Compose density/font-scale conversion may require comparing the `TextUnit` from `layoutInput.style` rather than pixel output; do not assert raster pixels.

- [ ] **Step 2: Run the focused instrumentation test and verify it fails**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.AdaptiveReaderTest`

Expected: assertions fail because all headings use the old display style, prose is 18sp, and the whole Material typography currently scales.

- [ ] **Step 3: Introduce one exhaustive presentation mapper**

In `ReaderDocument.kt`, define:

```kotlin
private data class ReaderBlockPresentation(
    val style: TextStyle,
    val before: Dp,
    val after: Dp,
    val start: Dp = 0.dp,
    val hanging: Dp = 0.dp,
    val quote: Boolean = false,
)

@Composable
private fun readerBlockPresentation(block: ReaderBlock): ReaderBlockPresentation {
    val type = LocalReaderTypography.current
    return when (block.kind) {
        BlockKind.HEADING -> when (block.headingLevel ?: 1) {
            1 -> ReaderBlockPresentation(type.h1, 24.dp, 10.dp)
            2 -> ReaderBlockPresentation(type.h2, 22.dp, 8.dp)
            3 -> ReaderBlockPresentation(type.h3, 18.dp, 6.dp)
            4 -> ReaderBlockPresentation(type.h4, 16.dp, 4.dp)
            5 -> ReaderBlockPresentation(type.h5, 14.dp, 4.dp)
            else -> ReaderBlockPresentation(type.h6, 12.dp, 4.dp)
        }
        BlockKind.QUOTE -> ReaderBlockPresentation(type.prose, 0.dp, 12.dp, start = 16.dp, quote = true)
        BlockKind.LIST_ITEM -> ReaderBlockPresentation(type.prose, 0.dp, 12.dp, start = 20.dp, hanging = 12.dp)
        BlockKind.CODE_BLOCK, BlockKind.TABLE_ROW -> ReaderBlockPresentation(type.prose, 0.dp, 12.dp)
        BlockKind.PARAGRAPH, BlockKind.HTML_BLOCK -> ReaderBlockPresentation(type.prose, 0.dp, 12.dp)
        BlockKind.HIDDEN_SOURCE, BlockKind.THEMATIC_BREAK -> ReaderBlockPresentation(type.prose, 0.dp, 0.dp)
    }
}
```

Use a zero `LazyColumn` item gap in `ReaderScreen`; presentation padding becomes the single source of vertical rhythm. Apply `before`, `after`, and `start` to the block container. For a list item, prefix a bullet in a fixed-width leading box and keep wrapped lines aligned with the text column. For a quote, draw a 2dp `outlineVariant` left rule and inset the text; do not apply italic globally. Keep thematic break as a 1dp `outlineVariant` divider with 12dp vertical padding.

- [ ] **Step 4: Layer Markdown inline styles with review and search overlays**

While constructing the existing `AnnotatedString`, apply the inline span before the review-state span:

```kotlin
val markdownStyle = when (run.renderKind) {
    RenderKind.EMPHASIS -> SpanStyle(fontStyle = FontStyle.Italic)
    RenderKind.STRONG -> SpanStyle(fontWeight = FontWeight.Bold)
    RenderKind.LINK -> SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
    )
    RenderKind.TEXT, RenderKind.CODE, RenderKind.INERT_HTML -> SpanStyle()
}
addStyle(markdownStyle, start, length)
```

Then retain current signal background, red strikethrough deletion, green addition, and search-target background spans. This order lets editorial overlays remain visible without erasing italic, bold, or link semantics. Tables/fenced code keep their current safe prose fallback.

- [ ] **Step 5: Update fixtures and visual baselines for all six heading levels**

Expand `ReaderScreenshotTest` to include Cyrillic H1-H6, paragraph, emphasis, strong, link, quote, list, and thematic break. Update direct `ReaderBlock` fixtures with named `headingLevel`/`renderKind` arguments where needed. Capture both light and dark screenshots; update `ReviewScreenshotTest` only for expected font/spacing changes, preserving all editorial colors.

Run:

```bash
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.AdaptiveReaderTest,net.inkyquill.pocketeditor.ui.ReaderScreenshotTest,net.inkyquill.pocketeditor.ui.ReviewScreenshotTest
```

Expected: all three instrumentation classes pass; screenshots show readable Cyrillic Literata and unchanged review-layer meanings in light and dark themes.

- [ ] **Step 6: Commit the reader presentation slice**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderDocument.kt app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReaderScreenshotTest.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewScreenshotTest.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/AdaptiveReaderTest.kt
git commit -m "feat: apply markdown prose typography"
```

### Task 4: Highlight Exact Search Matches and Fix Top-Bar Hierarchy

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/search/SearchEntity.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/search/SourceSearch.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/ui/search/SearchExcerpt.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/search/SearchScreen.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt`
- Modify: `app/src/test/java/net/inkyquill/pocketeditor/search/SourceSearchTest.kt`
- Create: `app/src/test/java/net/inkyquill/pocketeditor/ui/search/SearchExcerptTest.kt`
- Modify: `app/src/test/java/net/inkyquill/pocketeditor/ui/search/SearchNavigationTest.kt`
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt`
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowScreenshotTest.kt`

**Interfaces:**
- Consumes: normalized match boundaries and original UTF-16 offsets already computed by `SourceSearch`.
- Produces: `SearchHit.excerptMatchStart: Int`, `SearchHit.excerptMatchEnd: Int`, and `highlightSearchExcerpt(hit, background): AnnotatedString`.

- [ ] **Step 1: Add failing source-offset tests including Cyrillic normalization and ellipsis**

Extend the first `SourceSearchTest` assertion:

```kotlin
assertEquals("золотой ключ", hit.excerpt.substring(hit.excerptMatchStart, hit.excerptMatchEnd))
```

Add:

```kotlin
@Test
fun `excerpt match offsets survive leading ellipsis and normalized Cyrillic case`() = runBlocking {
    val search = SourceSearch(FakeSearchDao())
    val prefix = "Очень длинное начало. ".repeat(8)
    search.replaceChapter(BOOK_ID, CHAPTER_ID, "Глава", "$prefixЁЖИК идёт дальше.".encodeToByteArray())

    val hit = search.query(BOOK_ID, "ежик").first().single()

    assertTrue(hit.excerpt.startsWith("…"))
    assertEquals("ЁЖИК", hit.excerpt.substring(hit.excerptMatchStart, hit.excerptMatchEnd))
}
```

- [ ] **Step 2: Add a failing pure decoration test**

Create `SearchExcerptTest`:

```kotlin
@Test
fun `decorates only the exact visible match`() {
    val background = Color(0xFFFFD54F)
    val hit = SearchHit("chapter", "Глава", "…тихий дождь ночью…", 7, 12, 48, 60)

    val annotated = highlightSearchExcerpt(hit, background)

    val spans = annotated.spanStyles.filter { it.start == 7 && it.end == 12 }
    assertTrue(spans.any { it.item.fontWeight == FontWeight.Bold })
    assertTrue(spans.any { it.item.background == background })
    assertEquals("дождь", annotated.substring(7, 12).text)
}
```

- [ ] **Step 3: Run search tests and verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests net.inkyquill.pocketeditor.search.SourceSearchTest --tests net.inkyquill.pocketeditor.ui.search.SearchExcerptTest
```

Expected: compilation fails on the two new `SearchHit` fields and missing `highlightSearchExcerpt`.

- [ ] **Step 4: Return excerpt text and visible offsets as one value**

Change `SearchHit` to this order so all call sites must make offsets explicit:

```kotlin
data class SearchHit(
    val chapterId: String,
    val title: String,
    val excerpt: String,
    val excerptMatchStart: Int,
    val excerptMatchEnd: Int,
    val rawStartByte: Int,
    val rawEndByte: Int,
)
```

In `SourceSearch`, replace the string-only helper with:

```kotlin
private data class Excerpt(val text: String, val matchStart: Int, val matchEnd: Int)

private fun excerpt(content: String, start: Int, end: Int): Excerpt {
    val beforeCount = content.codePointCount(0, start).coerceAtMost(EXCERPT_CONTEXT)
    val afterCount = content.codePointCount(end, content.length).coerceAtMost(EXCERPT_CONTEXT)
    val excerptStart = content.offsetByCodePoints(start, -beforeCount)
    val excerptEnd = content.offsetByCodePoints(end, afterCount)
    val leadingEllipsis = excerptStart > 0
    val text = buildString {
        if (leadingEllipsis) append('…')
        append(content.substring(excerptStart, excerptEnd))
        if (excerptEnd < content.length) append('…')
    }
    val visibleStart = (if (leadingEllipsis) 1 else 0) + start - excerptStart
    return Excerpt(text, visibleStart, visibleStart + end - start)
}
```

Create one `Excerpt` in `toHits` and pass all three values to `SearchHit`. Keep raw byte offsets unchanged. Update fixture constructors in navigation and screenshot tests with correct excerpt-relative values.

- [ ] **Step 5: Build and use the highlighted annotated excerpt**

Create `SearchExcerpt.kt`:

```kotlin
internal fun highlightSearchExcerpt(hit: SearchHit, background: Color): AnnotatedString =
    buildAnnotatedString {
        append(hit.excerpt)
        if (hit.excerptMatchStart in 0 until hit.excerptMatchEnd && hit.excerptMatchEnd <= hit.excerpt.length) {
            addStyle(
                SpanStyle(fontWeight = FontWeight.Bold, background = background),
                hit.excerptMatchStart,
                hit.excerptMatchEnd,
            )
        }
    }
```

In `SearchScreen`, use `LocalReaderTypography.current.searchExcerpt`, and `MaterialTheme.colorScheme.tertiaryContainer` as the accessible light/dark match background. Keep the title on `MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)`. Add the semantic description `Search match: <matched text>` to the excerpt node without duplicating the whole excerpt.

- [ ] **Step 6: Apply explicit Manrope top-bar styles**

In `ReaderTopBar`, remove the compact-title downgrade to `labelLarge`. Use:

```kotlin
Text(
    text = if (compactTitle) title.substringBefore(" · ") else title,
    style = MaterialTheme.typography.titleLarge,
    modifier = Modifier.testTag("reader-topbar-title"),
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
)
Text(
    text = syncState.label,
    style = MaterialTheme.typography.labelMedium,
    modifier = Modifier.testTag("reader-topbar-sync"),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = 1,
)
```

Render `syncReason` with the same 13sp `labelMedium` style and error color. This makes chapter title and sync metadata visibly different on both phone and tablet.

- [ ] **Step 7: Run unit and focused UI tests**

Run:

```bash
./gradlew testDebugUnitTest --tests net.inkyquill.pocketeditor.search.SourceSearchTest --tests net.inkyquill.pocketeditor.ui.search.SearchExcerptTest --tests net.inkyquill.pocketeditor.ui.search.SearchNavigationTest
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.BookFlowTest,net.inkyquill.pocketeditor.ui.BookFlowScreenshotTest
```

Expected: unit tests pass; BookFlow can select the same raw search target; light/dark screenshots show Literata excerpts with only the matching substring highlighted and a smaller sync label beneath the chapter title.

- [ ] **Step 8: Commit the search and top-bar slice**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/search/SearchEntity.kt app/src/main/java/net/inkyquill/pocketeditor/search/SourceSearch.kt app/src/main/java/net/inkyquill/pocketeditor/ui/search/SearchExcerpt.kt app/src/main/java/net/inkyquill/pocketeditor/ui/search/SearchScreen.kt app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt app/src/test/java/net/inkyquill/pocketeditor/search/SourceSearchTest.kt app/src/test/java/net/inkyquill/pocketeditor/ui/search/SearchExcerptTest.kt app/src/test/java/net/inkyquill/pocketeditor/ui/search/SearchNavigationTest.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowScreenshotTest.kt
git commit -m "feat: highlight search matches and refine reader chrome"
```

### Task 5: Full Verification and Resume the Interrupted Offline E2E

**Files:**
- Modify only if a real regression is found: files already owned by Tasks 1-4.
- Do not modify: canonical fixture Markdown under `/home/inky/Yandex.Disk/PocketEditor-E2E-2026-07-19`.

**Interfaces:**
- Consumes: completed feature commits plus the existing uncommitted Yandex/NoActionBar E2E fixes.
- Produces: a debug APK verified on `emulator-5554`, preserved canonical Markdown hashes, a durable line note waiting to sync offline, and a final online sync check.

- [ ] **Step 1: Verify the complete automated suite and APK**

Run:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
```

Expected: all tasks succeed. If an unrelated pre-existing instrumentation failure occurs, record its exact test and evidence; do not weaken an assertion to make the suite green.

- [ ] **Step 2: Verify font identity inside the built APK**

Run:

```bash
unzip -l app/build/outputs/apk/debug/app-debug.apk | rg 'literata_(regular|italic|semibold|bold)|manrope_(regular|semibold)|ofl_(literata|manrope)'
```

Expected: all eight bundled resources are listed. Inspect the reader on the emulator in light and dark mode to confirm Cyrillic glyphs use Literata and chrome uses Manrope; there must be no fallback-looking mixed Cyrillic within a run.

- [ ] **Step 3: Build with the registered release key and install without clearing the interrupted offline state**

Run:

```bash
set -a
source /home/inky/Development/pocket-editor/.env
set +a
export POCKET_EDITOR_RELEASE_STORE_FILE=/home/inky/.keys/pocket-editor-release.jks
export POCKET_EDITOR_RELEASE_STORE_PASSWORD="$KEYSTORE_PASSWORD"
export POCKET_EDITOR_RELEASE_KEY_ALIAS=pocket-editor
export POCKET_EDITOR_RELEASE_KEY_PASSWORD="$KEYSTORE_PASSWORD"
./gradlew assembleRelease
adb -s emulator-5554 install -r app/build/outputs/apk/release/app-release.apk
adb -s emulator-5554 shell cmd connectivity airplane-mode enable
adb -s emulator-5554 shell am force-stop net.inkyquill.pocketeditor
adb -s emulator-5554 shell monkey -p net.inkyquill.pocketeditor 1
```

Expected: the release APK is signed by alias `pocket-editor`, `install -r` succeeds without a signature mismatch, application data remains, `PocketEditor-E2E-2026-07-19` opens from cache, and chapter note `Offline chapter note draft` still reads `Waiting to sync`. If the transient text selection was lost on reinstall, select the same passage again—do not recreate or overwrite the durable chapter note. Do not echo or print any environment value.

- [ ] **Step 4: Resume exactly at the interrupted line-note workflow**

With Review on and the passage selected:

1. Tap `Note` (blue).
2. Enter `Offline line note draft`.
3. Change the color to `Warning` (yellow), then back to `Note` (blue) to verify color correction in the editor.
4. Tap outside while the comment field is active and verify the editor stays open.
5. Tap Save.
6. Force-stop and relaunch while airplane mode remains enabled.

Expected: the selected original text is blue-highlighted, the comment block appears below its line, the note survives restart, and status remains `Waiting to sync`. Review off shows only untouched canonical prose; Review on restores every note/edit overlay.

- [ ] **Step 5: Recheck search highlighting and typography on the real fixture**

Still offline, search for `quiet`.

Expected: only `quiet` is bold and background-highlighted inside the Literata excerpt; opening it scrolls to and highlights the exact reader destination. Check H1-H4 present in the two fixture chapters, paragraph size, top-bar 18sp title/13sp status hierarchy, quote/list treatment if available, and both themes.

- [ ] **Step 6: Re-enable networking and verify synchronization**

Run: `adb -s emulator-5554 shell cmd connectivity airplane-mode disable`

Trigger Sync now in the app.

Expected: chapter note and line note change from `Waiting to sync` to `Saved`; remote `.review.json` contains both notes; `.pocket-editor.sync.lock` is removed after sync. Do not print OAuth tokens or secrets.

- [ ] **Step 7: Prove canonical Markdown was never changed**

Run:

```bash
sha256sum /home/inky/Yandex.Disk/PocketEditor-E2E-2026-07-19/01-arrival.md /home/inky/Yandex.Disk/PocketEditor-E2E-2026-07-19/02-return.md
```

Expected:

```text
a2666354aeafdbd2582ee134332fe784a0cbff5d4e2abef188712629b4e8c5c2  /home/inky/Yandex.Disk/PocketEditor-E2E-2026-07-19/01-arrival.md
1cea330b9447847e2ea13f5c3e80192033fcfdac651d4b94de9364e8853f9d6c  /home/inky/Yandex.Disk/PocketEditor-E2E-2026-07-19/02-return.md
```

- [ ] **Step 8: Review worktree scope before any final commit**

Run:

```bash
git status --short
git diff --check
git log --oneline -6
```

Expected: no whitespace errors; typography/search work is committed in focused slices. The previously uncommitted Yandex, browser, theme-manifest, and gateway fixes remain visible and must be reviewed/committed as their own E2E repair slice rather than folded into typography commits.
