package net.inkyquill.pocketeditor.ui

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.mutableStateOf
import net.inkyquill.pocketeditor.markdown.BlockKind
import net.inkyquill.pocketeditor.markdown.RawRange
import net.inkyquill.pocketeditor.reader.ReaderBlock
import net.inkyquill.pocketeditor.reader.ReaderChapter
import net.inkyquill.pocketeditor.reader.ReaderDocument
import net.inkyquill.pocketeditor.reader.ReaderRun
import net.inkyquill.pocketeditor.reader.ReaderRunKind
import net.inkyquill.pocketeditor.reader.ReaderState
import net.inkyquill.pocketeditor.reader.ReaderSyncState
import net.inkyquill.pocketeditor.ui.reader.ReaderCallbacks
import net.inkyquill.pocketeditor.ui.reader.ReaderScreen
import net.inkyquill.pocketeditor.ui.reader.ReaderRoute
import net.inkyquill.pocketeditor.ui.reader.ReaderViewModel
import net.inkyquill.pocketeditor.ui.navigation.PocketEditorRoot
import net.inkyquill.pocketeditor.ui.theme.PocketEditorTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.flow.MutableStateFlow

class AdaptiveReaderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rootProvidesCoherentLoadingState() {
        compose.setContent { PocketEditorTheme(darkTheme = true) { PocketEditorRoot() } }

        compose.onNodeWithText("Opening your library").assertIsDisplayed()
        compose.onNodeWithContentDescription("Loading books").assertIsDisplayed()
    }

    @Test
    fun readerRouteObservesViewModelOwnedState() {
        val state = MutableStateFlow<ReaderState?>(sampleState(false))
        val viewModel = ReaderViewModel(state, ReaderCallbacks())
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderRoute(viewModel, windowSize = DpSize(360.dp, 800.dp))
            }
        }

        compose.onNodeWithText("Saved").assertIsDisplayed()
        compose.runOnIdle { state.value = sampleState(false).copy(title = "The Glass Orchard") }
        compose.onNodeWithText("The Glass Orchard").assertIsDisplayed()
    }

    @Test
    fun phoneUsesScrollableReaderAndModalPanels() {
        setReader(DpSize(360.dp, 800.dp), dark = true, fontScale = 1f)

        compose.onNodeWithTag("reader-scroll").assert(hasScrollAction())
        compose.onNodeWithContentDescription("Open contents").performClick()
        compose.onNodeWithTag("contents-sheet").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close contents").assertHasClickAction()
        compose.onNodeWithContentDescription("Close contents").performClick()
        compose.onNodeWithTag("contents-sheet").assertIsNotDisplayed()

        compose.onNodeWithContentDescription("Review mode off").assert(role(Role.Button)).assertIsOff().performClick()
        compose.onNodeWithContentDescription("Review mode on").assertIsOn()
        compose.onNodeWithTag("review-sheet").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close review panel").assertHasClickAction()
    }

    @Test
    fun portraitTabletUsesContentsMenuAndNonNarrowingReviewOverlay() {
        setReader(DpSize(800.dp, 1280.dp), dark = true, fontScale = 1.5f)

        val before = compose.onNodeWithTag("reader-column").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("Open contents").performClick()
        compose.onNodeWithTag("contents-drawer").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close contents").assertHasClickAction()
        compose.onNodeWithContentDescription("Close contents").performClick()

        compose.onNodeWithContentDescription("Review mode off").performClick()
        compose.onNodeWithTag("review-overlay").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close review panel").performClick()
        val edgeWidth = compose.onNodeWithContentDescription("Expand review panel").fetchSemanticsNode().boundsInRoot.width
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertTrue("Edge controls keep a 48dp touch target", edgeWidth / density >= 48f)
        compose.onNodeWithContentDescription("Expand review panel").performClick()
        compose.onNodeWithTag("review-overlay").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close review panel").assertHasClickAction()
        val after = compose.onNodeWithTag("reader-column").fetchSemanticsNode().boundsInRoot
        assertTrue("Review overlay must not narrow prose", before.width == after.width)
    }

    @Test
    fun landscapeTabletOwnsSidebarControlsAndLeavesEdgeControls() {
        setReader(DpSize(1280.dp, 800.dp), dark = true, fontScale = 1f, reviewEnabled = true)

        compose.onNodeWithTag("contents-sidebar").assertIsDisplayed()
        compose.onNodeWithTag("review-sidebar").assertIsDisplayed()
        compose.onNodeWithContentDescription("Collapse contents").assertHasClickAction().performClick()
        compose.onNodeWithTag("contents-sidebar").assertIsNotDisplayed()
        compose.onNodeWithContentDescription("Expand contents").assertIsDisplayed().performClick()
        compose.onNodeWithTag("contents-sidebar").assertIsDisplayed()
        compose.onNodeWithContentDescription("Collapse review panel").assertHasClickAction().performClick()
        compose.onNodeWithTag("review-sidebar").assertIsNotDisplayed()
        compose.onNodeWithContentDescription("Expand review panel").assertIsDisplayed()
    }

    @Test
    fun themesAndLargeFontKeepNavigationAndControlsVisible() {
        val darkState = mutableStateOf(false)
        val fontScaleState = mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current.density
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDensity provides Density(density, fontScaleState.value),
            ) {
                PocketEditorTheme(darkTheme = darkState.value) {
                    ReaderScreen(sampleState(false), ReaderCallbacks(), windowSize = DpSize(360.dp, 800.dp))
                }
            }
        }
        listOf(false, true).forEach { dark ->
            listOf(1f, 1.5f, 2f).forEach { fontScale ->
                compose.runOnIdle {
                    darkState.value = dark
                    fontScaleState.value = fontScale
                }
                compose.onNodeWithText("Previous").assertIsDisplayed().assertHasClickAction()
                compose.onNodeWithText("Next").assertIsDisplayed().assertHasClickAction()
                compose.onNodeWithText("Saved").assertIsDisplayed()
                compose.onNodeWithText("Review").assert(hasContentDescription("Review mode off"))
                val width = compose.onNodeWithTag("reader-column").fetchSemanticsNode().boundsInRoot.width
                val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
                assertTrue("Reader measure must remain bounded", width / density <= 720f)
            }
        }
    }

    @Test
    fun controlsStayInsideWindowAtTwoHundredPercentFontScale() {
        setReader(DpSize(800.dp, 1280.dp), dark = false, fontScale = 2f, reviewEnabled = true)

        val root = compose.onNodeWithTag("reader-root").fetchSemanticsNode().boundsInRoot
        listOf("Open contents", "Review mode on").forEach { label ->
            val bounds = compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
            assertTrue(
                "$label must remain inside the window",
                bounds.left >= root.left && bounds.top >= root.top && bounds.right <= root.right && bounds.bottom <= root.bottom,
            )
        }
        compose.onNodeWithTag("reader-scroll").assert(hasScrollAction())
    }

    private fun setReader(
        size: DpSize,
        dark: Boolean,
        fontScale: Float,
        reviewEnabled: Boolean = false,
    ) {
        compose.setContent {
            val density = LocalDensity.current.density
            androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                PocketEditorTheme(darkTheme = dark) {
                    ReaderScreen(
                        state = sampleState(reviewEnabled),
                        callbacks = ReaderCallbacks(),
                        windowSize = size,
                    )
                }
            }
        }
    }

    private fun sampleState(reviewEnabled: Boolean) = ReaderState(
        bookId = "alchemy",
        chapterId = "chapter-02",
        title = "The City of Brass",
        document = ReaderDocument(
            blocks = listOf(
                block(0, BlockKind.HEADING, "The City of Brass"),
                block(1, BlockKind.PARAGRAPH, "At dusk, the sandstone walls kept the last warmth of the sun."),
                block(2, BlockKind.PARAGRAPH, "Nadia listened to the market settle into whispers, then opened the letter again."),
                block(3, BlockKind.QUOTE, "Every map is a promise made by someone who has already left."),
            ),
        ),
        reviewEnabled = reviewEnabled,
        chapterNote = "Keep the quiet pressure through the final paragraph.",
        reviewItems = null,
        previousChapter = ReaderChapter("chapter-01", "The Salt Road"),
        nextChapter = ReaderChapter("chapter-03", "A Name in Smoke"),
        readingPosition = null,
        syncState = ReaderSyncState.SAVED,
    )

    private fun block(index: Int, kind: BlockKind, text: String) = ReaderBlock(
        sourceIndex = index,
        kind = kind,
        canonicalText = text,
        rawRange = RawRange(index * 100, index * 100 + text.encodeToByteArray().size),
        runs = listOf(ReaderRun(text, ReaderRunKind.CANONICAL)),
    )

    private fun role(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)
}
