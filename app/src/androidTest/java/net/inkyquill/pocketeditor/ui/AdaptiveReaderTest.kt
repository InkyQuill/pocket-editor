package net.inkyquill.pocketeditor.ui

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
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
    val compose = createAndroidComposeRule<ComponentActivity>()

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
        val heading = hasText("The City of Brass") and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        val beforeScroll = compose.onNode(heading).fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("reader-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
            scroll(0f, 120f)
        }
        compose.waitForIdle()
        val afterScroll = compose.onNode(heading).fetchSemanticsNode().boundsInRoot.top
        assertTrue("A real scroll action must move prose", afterScroll < beforeScroll)
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
    fun rotatedPhoneKeepsModalFullWidthReader() {
        setReader(DpSize(800.dp, 360.dp), dark = true, fontScale = 1f)

        compose.onAllNodesWithTag("contents-sidebar").assertCountEquals(0)
        compose.onAllNodesWithTag("review-sidebar").assertCountEquals(0)
        compose.onNodeWithContentDescription("Open contents").performClick()
        compose.onNodeWithTag("contents-sheet").assertIsDisplayed()
    }

    @Test
    fun portraitTabletUsesContentsMenuAndNonNarrowingReviewOverlay() {
        setReader(DpSize(800.dp, 1280.dp), dark = true, fontScale = 1.5f)

        val before = compose.onNodeWithTag("reader-column", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
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
        val after = compose.onNodeWithTag("reader-column", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Review overlay must not narrow prose", before.width == after.width)
    }

    @Test
    fun portraitPanelsAreAccessibleModalsWithBackScrimAndReopen() {
        setReader(DpSize(800.dp, 1280.dp), dark = true, fontScale = 1f)

        compose.onNodeWithContentDescription("Open contents").performClick()
        compose.onNodeWithTag("contents-drawer")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Contents"))
        compose.onAllNodes(hasContentDescription("Review mode off")).assertCountEquals(0)
        compose.onNodeWithTag("contents-scrim").assertHasClickAction().performClick()
        compose.onAllNodesWithTag("contents-drawer").assertCountEquals(0)
        compose.onNodeWithContentDescription("Review mode off").assertIsDisplayed()

        compose.onNodeWithContentDescription("Open contents").performClick()
        compose.onNodeWithTag("contents-drawer").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onAllNodesWithTag("contents-drawer").assertCountEquals(0)

        compose.onNodeWithContentDescription("Review mode off").performClick()
        compose.onNodeWithTag("review-overlay")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Review"))
        compose.onAllNodes(hasContentDescription("Open contents")).assertCountEquals(0)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onAllNodesWithTag("review-overlay").assertCountEquals(0)
        compose.onNodeWithContentDescription("Expand review panel").assertIsDisplayed().performClick()
        compose.onNodeWithTag("review-overlay").assertIsDisplayed()
    }

    @Test
    fun portraitContentsModalHidesEveryReviewAndReaderAffordance() {
        setReader(DpSize(800.dp, 1280.dp), dark = true, fontScale = 1f, reviewEnabled = true)

        compose.onNodeWithContentDescription("Open contents").performClick()

        compose.onNodeWithTag("contents-drawer")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Contents"))
        compose.onNodeWithTag("contents-scrim").assertHasClickAction()
        compose.onAllNodes(hasContentDescription("Expand review panel")).assertCountEquals(0)
        compose.onAllNodes(hasContentDescription("Review mode on")).assertCountEquals(0)
        compose.onAllNodes(hasContentDescription("Open contents")).assertCountEquals(0)
        compose.onAllNodes(hasText("The City of Brass") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertCountEquals(0)
    }

    @Test
    fun portraitBackFallsThroughWithoutModalAndDismissesOnlyVisibleModal() {
        var fallthroughCount = 0
        compose.activity.runOnUiThread {
            compose.activity.onBackPressedDispatcher.addCallback(
                compose.activity,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        fallthroughCount += 1
                    }
                },
            )
        }
        setReader(DpSize(800.dp, 1280.dp), dark = true, fontScale = 1f)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertTrue("Back without a modal must fall through", fallthroughCount == 1) }

        compose.onNodeWithContentDescription("Open contents").performClick()
        compose.onNodeWithTag("contents-drawer").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        compose.onAllNodesWithTag("contents-drawer").assertCountEquals(0)
        compose.runOnIdle { assertTrue("Visible modal must consume Back", fallthroughCount == 1) }
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
    fun adaptiveThemeAndFontMatrixKeepsPanelsReadableAndControlsVisible() {
        val darkState = mutableStateOf(false)
        val fontScaleState = mutableStateOf(1f)
        val sizeState = mutableStateOf(DpSize(360.dp, 800.dp))
        val revision = mutableStateOf(0)
        val metrics = compose.activity.resources.displayMetrics
        val viewportWidthPx = metrics.widthPixels.toFloat()
        val viewportHeightPx = metrics.heightPixels.toFloat()
        compose.setContent {
            val size = sizeState.value
            val renderDensity = minOf(
                viewportWidthPx / size.width.value,
                viewportHeightPx / size.height.value,
            )
            CompositionLocalProvider(
                LocalDensity provides Density(renderDensity, fontScaleState.value),
            ) {
                PocketEditorTheme(darkTheme = darkState.value) {
                    key(revision.value) {
                        Box(Modifier.requiredSize(size)) {
                            ReaderScreen(sampleState(true), ReaderCallbacks(), windowSize = size)
                        }
                    }
                }
            }
        }

        listOf(DpSize(800.dp, 1280.dp), DpSize(1280.dp, 800.dp)).forEach { size ->
            listOf(false, true).forEach { dark ->
                listOf(1f, 1.5f, 2f).forEach { fontScale ->
                    compose.runOnIdle {
                        sizeState.value = size
                        darkState.value = dark
                        fontScaleState.value = fontScale
                        revision.value += 1
                    }
                    assertTextControlInsideRoot("Previous", size, dark, fontScale)
                    assertTextControlInsideRoot("Next", size, dark, fontScale)
                    assertTextNodeInsideRoot("Saved", size, dark, fontScale)
                    assertInsideRoot("Review mode on")
                    val width = compose.onNodeWithTag("reader-column").fetchSemanticsNode().boundsInRoot.width
                    val logicalDensity = minOf(
                        viewportWidthPx / size.width.value,
                        viewportHeightPx / size.height.value,
                    )
                    val logicalWidth = width / logicalDensity
                    assertTrue(
                        "Reader measure must remain bounded at $size, dark=$dark, fontScale=$fontScale: ${logicalWidth}dp",
                        logicalWidth <= 720f + (1f / logicalDensity),
                    )
                    if (size.width < size.height) {
                        assertInsideRoot("Open contents")
                        assertInsideRoot("Expand review panel")
                    } else {
                        compose.onNodeWithTag("contents-sidebar").assertIsDisplayed()
                        compose.onNodeWithTag("review-sidebar").assertIsDisplayed()
                        assertInsideRoot("Collapse contents")
                        assertInsideRoot("Collapse review panel")
                    }
                }
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

    @Test
    fun landscapeTabletControlsStayInsideRealLogicalRootAtTwoHundredPercentFontScale() {
        setReaderInLogicalRoot(
            size = DpSize(1280.dp, 800.dp),
            dark = true,
            fontScale = 2f,
            reviewEnabled = true,
        )

        compose.onNodeWithTag("contents-sidebar").assertIsDisplayed()
        compose.onNodeWithTag("review-sidebar").assertIsDisplayed()
        listOf("Collapse contents", "Review mode on", "Collapse review panel").forEach(::assertInsideRoot)
        assertTextNodeInsideRoot("Saved", DpSize(1280.dp, 800.dp), dark = true, fontScale = 2f)
    }

    @Test
    fun portraitPanelsStayInsideRealLogicalRootAtTwoHundredPercentFontScale() {
        setReaderInLogicalRoot(
            size = DpSize(800.dp, 1280.dp),
            dark = true,
            fontScale = 2f,
            reviewEnabled = true,
        )

        compose.onNodeWithContentDescription("Open contents").performClick()
        assertTaggedNodeInsideRoot("contents-drawer")
        assertDescriptionInsideTaggedPanel("Close contents", "contents-drawer")
        assertTextInsideTaggedPanel("Chapters", "contents-drawer")
        compose.onNodeWithContentDescription("Close contents").performClick()

        compose.onNodeWithContentDescription("Expand review panel").performClick()
        assertTaggedNodeInsideRoot("review-overlay")
        assertDescriptionInsideTaggedPanel("Close review panel", "review-overlay")
        assertTextInsideTaggedPanel("Complete editorial overlay", "review-overlay")
        assertTextInsideTaggedPanel("Keep the quiet pressure through the final paragraph.", "review-overlay")
    }

    private fun setReader(
        size: DpSize,
        dark: Boolean,
        fontScale: Float,
        reviewEnabled: Boolean = false,
    ) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
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

    private fun setReaderInLogicalRoot(
        size: DpSize,
        dark: Boolean,
        fontScale: Float,
        reviewEnabled: Boolean,
    ) {
        val metrics = compose.activity.resources.displayMetrics
        val renderDensity = minOf(
            metrics.widthPixels / size.width.value,
            metrics.heightPixels / size.height.value,
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(renderDensity, fontScale)) {
                PocketEditorTheme(darkTheme = dark) {
                    Box(Modifier.requiredSize(size)) {
                        ReaderScreen(
                            state = sampleState(reviewEnabled),
                            callbacks = ReaderCallbacks(),
                            windowSize = size,
                        )
                    }
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
                block(4, BlockKind.PARAGRAPH, "Beyond the blue awnings, lamps appeared one by one along the market road."),
                block(5, BlockKind.PARAGRAPH, "Their light gathered on brass trays and bowls of dark fruit."),
                block(6, BlockKind.PARAGRAPH, "She had crossed three provinces to reach the city before the gates closed."),
                block(7, BlockKind.PARAGRAPH, "Now the road behind her felt easier than the answer waiting ahead."),
                block(8, BlockKind.PARAGRAPH, "The tower bell sounded once and every merchant looked toward the river."),
                block(9, BlockKind.PARAGRAPH, "Nadia folded the letter and followed the narrow street into the evening."),
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

    private fun assertInsideRoot(label: String) {
        val root = compose.onNodeWithTag("reader-root").fetchSemanticsNode().boundsInRoot
        val bounds = compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$label must remain inside the window",
            bounds.left >= root.left && bounds.top >= root.top && bounds.right <= root.right && bounds.bottom <= root.bottom,
        )
    }

    private fun assertTextControlInsideRoot(text: String, size: DpSize, dark: Boolean, fontScale: Float) {
        val root = compose.onNodeWithTag("reader-root").fetchSemanticsNode().boundsInRoot
        val node = compose.onNodeWithText(text).assertHasClickAction().fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$text must stay inside root at $size, dark=$dark, fontScale=$fontScale; node=$node root=$root",
            node.left >= root.left && node.top >= root.top && node.right <= root.right && node.bottom <= root.bottom,
        )
    }

    private fun assertTextNodeInsideRoot(text: String, size: DpSize, dark: Boolean, fontScale: Float) {
        val root = compose.onNodeWithTag("reader-root").fetchSemanticsNode().boundsInRoot
        val node = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$text must stay inside root at $size, dark=$dark, fontScale=$fontScale; node=$node root=$root",
            node.left >= root.left && node.top >= root.top && node.right <= root.right && node.bottom <= root.bottom,
        )
    }

    private fun assertTaggedNodeInsideRoot(tag: String) {
        val root = compose.onNodeWithTag("reader-root").fetchSemanticsNode().boundsInRoot
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$tag must stay inside the reader root; node=$node root=$root",
            node.left >= root.left && node.top >= root.top && node.right <= root.right && node.bottom <= root.bottom,
        )
    }

    private fun assertDescriptionInsideTaggedPanel(description: String, panelTag: String) {
        val panel = compose.onNodeWithTag(panelTag).fetchSemanticsNode().boundsInRoot
        val node = compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$description must stay inside $panelTag; node=$node panel=$panel",
            node.left >= panel.left && node.top >= panel.top && node.right <= panel.right && node.bottom <= panel.bottom,
        )
    }

    private fun assertTextInsideTaggedPanel(text: String, panelTag: String) {
        val panel = compose.onNodeWithTag(panelTag).fetchSemanticsNode().boundsInRoot
        val node = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$text must stay inside $panelTag; node=$node panel=$panel",
            node.left >= panel.left && node.top >= panel.top && node.right <= panel.right && node.bottom <= panel.bottom,
        )
    }
}
