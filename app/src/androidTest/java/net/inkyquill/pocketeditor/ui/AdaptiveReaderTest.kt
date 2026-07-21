package net.inkyquill.pocketeditor.ui

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
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
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import net.inkyquill.pocketeditor.markdown.BlockKind
import net.inkyquill.pocketeditor.markdown.RawRange
import net.inkyquill.pocketeditor.markdown.RenderKind
import net.inkyquill.pocketeditor.reader.ReaderBlock
import net.inkyquill.pocketeditor.reader.ReaderChapter
import net.inkyquill.pocketeditor.reader.ReaderDocument
import net.inkyquill.pocketeditor.reader.ReaderRun
import net.inkyquill.pocketeditor.reader.ReaderRunKind
import net.inkyquill.pocketeditor.reader.ReaderState
import net.inkyquill.pocketeditor.reader.ReaderPosition
import net.inkyquill.pocketeditor.reader.ReaderSyncState
import net.inkyquill.pocketeditor.ui.reader.ReaderCallbacks
import net.inkyquill.pocketeditor.ui.reader.ReaderScreen
import net.inkyquill.pocketeditor.ui.reader.annotationPlacement
import net.inkyquill.pocketeditor.ui.reader.anchoredHorizontalOffsetInRoot
import net.inkyquill.pocketeditor.ui.review.AnnotationComposerPlacement
import net.inkyquill.pocketeditor.ui.reader.ReaderRoute
import net.inkyquill.pocketeditor.ui.reader.ReaderViewModel
import net.inkyquill.pocketeditor.ui.reader.ReaderSearchTarget
import net.inkyquill.pocketeditor.ui.navigation.PocketEditorRoot
import net.inkyquill.pocketeditor.ui.theme.PocketEditorTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.flow.MutableStateFlow

class AdaptiveReaderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun inAppTextScaleChangesReaderTypographyButNotTopBarChrome() {
        val state = sampleState(false).copy(
            title = "Reader chrome title",
            document = ReaderDocument(
                listOf(
                    block(0, BlockKind.HEADING, "Heading level one").copy(headingLevel = 1),
                    block(1, BlockKind.HEADING, "Heading level four").copy(headingLevel = 4),
                    block(2, BlockKind.PARAGRAPH, "Scaled paragraph prose."),
                ),
            ),
        )
        compose.setContent {
            PocketEditorTheme(darkTheme = true, textScale = 1.3f) {
                ReaderScreen(state, ReaderCallbacks(), windowSize = DpSize(360.dp, 800.dp))
            }
        }

        assertEquals(36.4f, compose.onNodeWithText("Heading level one").fontSize(), 0.01f)
        assertEquals(22.1f, compose.onNodeWithText("Heading level four").fontSize(), 0.01f)
        assertEquals(20.8f, compose.onNodeWithText("Scaled paragraph prose.").fontSize(), 0.01f)
        assertEquals(18f, compose.onNodeWithTag("reader-topbar-title").fontSize(), 0.01f)
        assertEquals(13f, compose.onNodeWithTag("reader-topbar-sync").fontSize(), 0.01f)
    }

    @Test
    fun proseRendersInlineStylesQuoteRuleAndHangingListMarker() {
        val inlineText = "Тихий вечер и ссылка"
        val inlineBlock = ReaderBlock(
            sourceIndex = 0,
            kind = BlockKind.PARAGRAPH,
            canonicalText = inlineText,
            rawRange = RawRange(0, inlineText.encodeToByteArray().size),
            runs = listOf(
                ReaderRun("Тихий ", ReaderRunKind.CANONICAL),
                ReaderRun("вечер", ReaderRunKind.CANONICAL, renderKind = RenderKind.EMPHASIS),
                ReaderRun(" и ", ReaderRunKind.CANONICAL),
                ReaderRun("ссылка", ReaderRunKind.CANONICAL, renderKind = RenderKind.LINK),
            ),
        )
        val state = sampleState(false).copy(
            document = ReaderDocument(
                listOf(
                    inlineBlock,
                    block(1, BlockKind.QUOTE, "Цитата без обязательного курсива"),
                    block(2, BlockKind.LIST_ITEM, "Пункт списка с висячим отступом"),
                ),
            ),
        )
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(state, ReaderCallbacks(), windowSize = DpSize(360.dp, 800.dp))
            }
        }

        val spans = compose.onNodeWithText(inlineText).textLayout().layoutInput.text.spanStyles
        assertTrue(spans.any { it.start == 6 && it.end == 11 && it.item.fontStyle == FontStyle.Italic })
        assertTrue(spans.any { it.start == 14 && it.end == 20 && it.item.textDecoration == TextDecoration.Underline })
        assertTrue(spans.none { it.item.fontWeight == FontWeight.Bold && it.start == 6 && it.end == 11 })
        compose.onNodeWithTag("reader-quote-marker-1").assertIsDisplayed()
        compose.onNodeWithTag("reader-list-marker-2").assertTextContains("•")
    }

    @Test
    fun rootOpensBooksWhenNoUsableRootExists() {
        compose.setContent { PocketEditorRoot() }

        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Your offline story shelf")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Your offline story shelf").assertIsDisplayed()
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
        val state = sampleState(false).copy(
            document = ReaderDocument(
                sampleState(false).document.blocks + (10..24).map { index ->
                    block(index, BlockKind.PARAGRAPH, "The road carried another quiet detail into the evening.")
                },
            ),
        )
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(state, ReaderCallbacks(), windowSize = DpSize(360.dp, 800.dp))
            }
        }

        compose.onNodeWithTag("reader-scroll").assert(hasScrollAction())
        val heading = hasText("The City of Brass") and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        val beforeScroll = compose.onNode(heading).fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("reader-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
            scroll(0f, 24f)
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
        compose.onAllNodesWithTag("review-sheet").assertCountEquals(0)
        compose.onNodeWithContentDescription("Open review panel").assertHasClickAction()
    }

    @Test
    fun phoneReviewToggleChangesTextWithoutOpeningPanelsOrShowingTheOldEdgeControl() {
        setReviewOverlayReader(DpSize(360.dp, 800.dp))

        compose.onNodeWithContentDescription("Review mode off").performClick()

        compose.onNodeWithContentDescription("Base text. Added replacement text: review overlay").fetchSemanticsNode()
        compose.onAllNodesWithTag("contents-sheet").assertCountEquals(0)
        compose.onAllNodesWithTag("review-sheet").assertCountEquals(0)
        compose.onNodeWithContentDescription("Open review panel").assertHasClickAction()
        compose.onAllNodes(hasContentDescription("Expand review panel")).assertCountEquals(0)
    }

    @Test
    fun phonePreservesExpandedReviewPanelWhileReviewModeChanges() {
        val reviewEnabled = setReviewPanelPreservationReader(DpSize(360.dp, 800.dp))

        compose.onNodeWithContentDescription("Open review panel").performClick()
        compose.onNodeWithTag("review-sheet").assertIsDisplayed()
        compose.runOnIdle { reviewEnabled.value = false }
        compose.onAllNodesWithTag("review-sheet").assertCountEquals(0)
        compose.runOnIdle { reviewEnabled.value = true }
        compose.onNodeWithTag("review-sheet").assertIsDisplayed()
    }

    @Test
    fun reviewFabRendersInTheBottomRightQuadrantOnPhoneAndTabletPortrait() {
        val sizeState = mutableStateOf(DpSize(360.dp, 800.dp))
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                key(sizeState.value) {
                    ReaderScreen(
                        state = sampleState(true),
                        callbacks = ReaderCallbacks(),
                        windowSize = sizeState.value,
                    )
                }
            }
        }

        listOf(DpSize(360.dp, 800.dp), DpSize(800.dp, 1_280.dp)).forEach { size ->
            compose.runOnIdle { sizeState.value = size }

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

    @Test
    fun actualScrollPersistsBlockAndRawByteAndRecreationRestoresIt() {
        val saved = mutableListOf<ReaderPosition>()
        val state = mutableStateOf(
            sampleState(false).copy(
                document = ReaderDocument(
                    sampleState(false).document.blocks + (10..24).map { index ->
                        block(index, BlockKind.PARAGRAPH, "The road carried another quiet detail into the evening.")
                    },
                ),
            ),
        )
        val visible = mutableStateOf(true)
        compose.setContent {
            if (visible.value) PocketEditorTheme(darkTheme = true) {
                key(state.value.readingPosition) {
                    ReaderScreen(
                        state.value,
                        ReaderCallbacks(onReadingPositionChanged = saved::add),
                        windowSize = DpSize(360.dp, 800.dp),
                    )
                }
            }
        }

        compose.onNodeWithTag("reader-scroll").performSemanticsAction(SemanticsActions.ScrollToIndex) { scrollToIndex ->
            scrollToIndex(8)
        }
        compose.runOnIdle { visible.value = false }
        compose.waitUntil(3_000) { (saved.lastOrNull()?.blockIndex ?: 0) > 0 }
        val position = saved.last()
        assertTrue(position.byteOffset == position.blockIndex * 100)

        compose.runOnIdle {
            state.value = state.value.copy(readingPosition = position)
            visible.value = true
        }
        compose.onNodeWithTag("reader-block-${position.blockIndex}").assertIsDisplayed()
    }

    @Test
    fun exactSearchTargetScrollsToDeepLineInsideSingleLongParagraph() {
        val positioned = mutableListOf<Int>()
        val text = (0 until 90).joinToString("\n") { "line $it ordinary words" }
        val selected = "line 78 ordinary"
        val start = text.indexOf(selected)
        val longBlock = ReaderBlock(
            sourceIndex = 0,
            kind = BlockKind.PARAGRAPH,
            canonicalText = text,
            rawRange = RawRange(0, text.length),
            runs = listOf(
                ReaderRun(
                    text,
                    ReaderRunKind.CANONICAL,
                    sourceByteBoundaries = (0..text.length).toList(),
                ),
            ),
        )
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(
                    sampleState(false).copy(document = ReaderDocument(listOf(longBlock))),
                    ReaderCallbacks(onSearchTargetPositioned = positioned::add),
                    windowSize = DpSize(360.dp, 800.dp),
                    searchTarget = ReaderSearchTarget(start, start + selected.length),
                )
            }
        }

        compose.waitUntil(3_000) { (positioned.lastOrNull() ?: 0) > 500 }
        compose.onNodeWithContentDescription("Search result: line 78 ordinary", substring = true).fetchSemanticsNode()
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
        compose.onAllNodesWithTag("review-overlay").assertCountEquals(0)
        val fabWidth = compose.onNodeWithContentDescription("Open review panel").fetchSemanticsNode().boundsInRoot.width
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertTrue("The review FAB keeps a 44dp touch target", fabWidth / density >= 44f)
        compose.onNodeWithContentDescription("Open review panel").performClick()
        compose.onNodeWithTag("review-overlay").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close review panel").assertHasClickAction()
        val after = compose.onNodeWithTag("reader-column", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Review overlay must not narrow prose", before.width == after.width)
    }

    @Test
    fun portraitTabletReviewToggleChangesTextWithoutOpeningPanels() {
        setReviewOverlayReader(DpSize(800.dp, 1280.dp))

        compose.onNodeWithContentDescription("Review mode off").performClick()

        compose.onNodeWithContentDescription("Base text. Added replacement text: review overlay").fetchSemanticsNode()
        compose.onAllNodesWithTag("contents-drawer").assertCountEquals(0)
        compose.onAllNodesWithTag("review-overlay").assertCountEquals(0)
        compose.onNodeWithContentDescription("Open review panel").assertHasClickAction()
    }

    @Test
    fun portraitTabletPreservesExpandedReviewPanelWhileReviewModeChanges() {
        val reviewEnabled = setReviewPanelPreservationReader(DpSize(800.dp, 1280.dp))

        compose.onNodeWithContentDescription("Open review panel").performClick()
        compose.onNodeWithTag("review-overlay").assertIsDisplayed()
        compose.runOnIdle { reviewEnabled.value = false }
        compose.onAllNodesWithTag("review-overlay").assertCountEquals(0)
        compose.runOnIdle { reviewEnabled.value = true }
        compose.onNodeWithTag("review-overlay").assertIsDisplayed()
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
        compose.onAllNodesWithTag("review-overlay").assertCountEquals(0)
        compose.onNodeWithContentDescription("Open review panel").performClick()
        compose.onNodeWithTag("review-overlay")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Review"))
        compose.onAllNodes(hasContentDescription("Open contents")).assertCountEquals(0)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onAllNodesWithTag("review-overlay").assertCountEquals(0)
        compose.onNodeWithContentDescription("Open review panel").assertIsDisplayed().performClick()
        compose.onNodeWithTag("review-overlay").assertIsDisplayed()
    }

    @Test
    fun portraitContentsModalHidesEveryReviewAndReaderAffordance() {
        setReader(DpSize(800.dp, 1280.dp), dark = true, fontScale = 1f, reviewEnabled = true)

        compose.onNodeWithContentDescription("Open contents").performClick()

        compose.onNodeWithTag("contents-drawer")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Contents"))
        compose.onNodeWithTag("contents-scrim").assertHasClickAction()
        compose.onAllNodes(hasContentDescription("Open review panel")).assertCountEquals(0)
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
    fun landscapeTabletReviewToggleChangesTextWithoutChangingPanelExpansion() {
        setReviewOverlayReader(DpSize(1280.dp, 800.dp))

        compose.onNodeWithContentDescription("Review mode off").performClick()

        compose.onNodeWithContentDescription("Base text. Added replacement text: review overlay").fetchSemanticsNode()
        compose.onNodeWithTag("contents-sidebar").assertIsDisplayed()
        compose.onAllNodesWithTag("review-sidebar").assertCountEquals(0)
        compose.onNodeWithContentDescription("Expand review panel").assertHasClickAction()
    }

    @Test
    fun landscapeTabletPreservesExpandedReviewPanelWhileReviewModeChanges() {
        val reviewEnabled = setReviewPanelPreservationReader(DpSize(1280.dp, 800.dp))

        compose.onNodeWithTag("review-sidebar").assertIsDisplayed()
        compose.runOnIdle { reviewEnabled.value = false }
        compose.onAllNodesWithTag("review-sidebar").assertCountEquals(0)
        compose.runOnIdle { reviewEnabled.value = true }
        compose.onNodeWithTag("review-sidebar").assertIsDisplayed()
    }

    @Test
    fun liveLandscapeToPortraitToPhoneTransitionRetainsOnlyReviewThenDismissesOnce() {
        val size = mutableStateOf(DpSize(1280.dp, 800.dp))
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(sampleState(reviewEnabled = true), ReaderCallbacks(), windowSize = size.value)
            }
        }

        compose.onNodeWithTag("contents-sidebar").assertIsDisplayed()
        compose.onNodeWithTag("review-sidebar").assertIsDisplayed()

        compose.runOnIdle { size.value = DpSize(800.dp, 1280.dp) }
        compose.onAllNodesWithTag("contents-drawer").assertCountEquals(0)
        compose.onAllNodesWithTag("contents-scrim").assertCountEquals(0)
        compose.onNodeWithTag("review-overlay").assertIsDisplayed()
        compose.onNodeWithTag("review-scrim").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)).assertCountEquals(1)

        compose.runOnIdle { size.value = DpSize(360.dp, 800.dp) }
        compose.onAllNodesWithTag("contents-sheet").assertCountEquals(0)
        compose.onNodeWithTag("review-sheet").assertIsDisplayed()
        compose.onAllNodesWithTag("contents-drawer").assertCountEquals(0)
        compose.onAllNodesWithTag("review-overlay").assertCountEquals(0)

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithTag("review-sheet").assertIsNotDisplayed()
        compose.onNodeWithContentDescription("Open review panel").assertIsDisplayed()
    }

    @Test
    fun liveTransitionWithReviewDisabledRetainsOnlyContentsAcrossPortraitAndPhone() {
        val size = mutableStateOf(DpSize(1280.dp, 800.dp))
        val reviewEnabled = mutableStateOf(true)
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(
                    sampleState(reviewEnabled = reviewEnabled.value),
                    ReaderCallbacks(),
                    windowSize = size.value,
                )
            }
        }

        compose.onNodeWithTag("contents-sidebar").assertIsDisplayed()
        compose.onNodeWithTag("review-sidebar").assertIsDisplayed()

        compose.runOnIdle {
            reviewEnabled.value = false
            size.value = DpSize(800.dp, 1280.dp)
        }
        compose.onNodeWithTag("contents-drawer").assertIsDisplayed()
        compose.onNodeWithTag("contents-scrim").assertIsDisplayed()
        compose.onAllNodesWithTag("review-overlay").assertCountEquals(0)
        compose.onAllNodesWithTag("review-scrim").assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)).assertCountEquals(1)

        compose.runOnIdle { size.value = DpSize(360.dp, 800.dp) }
        compose.onNodeWithTag("contents-sheet").assertIsDisplayed()
        compose.onAllNodesWithTag("review-sheet").assertCountEquals(0)

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithTag("contents-sheet").assertIsNotDisplayed()
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
                        assertInsideRoot("Open review panel")
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

        compose.onNodeWithContentDescription("Open review panel").performClick()
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

    private fun setReviewOverlayReader(size: DpSize) {
        val state = sampleState(false).copy(
            document = ReaderDocument(
                listOf(
                    block(0, BlockKind.PARAGRAPH, "Base text").copy(
                        runs = listOf(
                            ReaderRun("Base text", ReaderRunKind.CANONICAL),
                            ReaderRun("review overlay", ReaderRunKind.ADDED),
                        ),
                    ),
                ),
            ),
        )
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(state, ReaderCallbacks(), windowSize = size)
            }
        }
    }

    private fun setReviewPanelPreservationReader(size: DpSize): MutableState<Boolean> {
        val reviewEnabled = mutableStateOf(true)
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(sampleState(reviewEnabled.value), ReaderCallbacks(), windowSize = size)
            }
        }
        return reviewEnabled
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

    private fun assertInsideRoot(label: String) {
        val root = compose.onNodeWithTag("reader-root").fetchSemanticsNode().boundsInRoot
        val bounds = compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$label must remain inside the window",
            bounds.left >= root.left && bounds.top >= root.top && bounds.right <= root.right && bounds.bottom <= root.bottom,
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
    @Test
    fun annotationPlacementReservesGapAndFlipsAboveBeforeDeviceFallback() {
        val viewport = Rect(0f, 0f, 600f, 1_000f)

        assertEquals(
            AnnotationComposerPlacement.Above,
            annotationPlacement(Rect(200f, 650f, 300f, 700f), viewport, composerHeightPx = 300f, composerWidthPx = 300f, gapPx = 8f, tablet = false),
        )
        assertEquals(
            AnnotationComposerPlacement.PhoneSheet,
            annotationPlacement(Rect(200f, 100f, 300f, 200f), Rect(0f, 0f, 600f, 500f), 300f, 300f, 8f, tablet = false),
        )
        assertEquals(
            AnnotationComposerPlacement.TabletModal,
            annotationPlacement(Rect(200f, 100f, 300f, 200f), Rect(0f, 0f, 600f, 500f), 300f, 300f, 8f, tablet = true),
        )
    }

    @Test
    fun centeredTabletBelowAndAboveComposersClampInReaderRootSpace() {
        val readerColumn = Rect(280f, 0f, 1_000f, 1_000f)
        val composerWidth = 320f

        assertEquals(
            AnnotationComposerPlacement.Below,
            annotationPlacement(Rect(950f, 100f, 980f, 150f), readerColumn, composerHeightPx = 320f, composerWidthPx = composerWidth, gapPx = 8f, tablet = true),
        )
        assertEquals(
            AnnotationComposerPlacement.Above,
            annotationPlacement(Rect(950f, 650f, 980f, 700f), readerColumn, composerHeightPx = 320f, composerWidthPx = composerWidth, gapPx = 8f, tablet = true),
        )
        listOf(
            Rect(950f, 100f, 980f, 150f),
            Rect(950f, 650f, 980f, 700f),
        ).forEach { selection ->
            val composerLeft = anchoredHorizontalOffsetInRoot(selection, readerColumn, composerWidth).toFloat()
            assertEquals(readerColumn.right - composerWidth, composerLeft)
            assertTrue(composerLeft >= readerColumn.left)
            assertTrue(composerLeft + composerWidth <= readerColumn.right)
        }
    }
}
