package net.inkyquill.pocketeditor.ui

import android.view.KeyEvent
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.inkyquill.pocketeditor.database.DraftEntity
import net.inkyquill.pocketeditor.markdown.BlockKind
import net.inkyquill.pocketeditor.markdown.MarkdownParser
import net.inkyquill.pocketeditor.markdown.RawRange
import net.inkyquill.pocketeditor.reader.PendingDeletion
import net.inkyquill.pocketeditor.reader.ReaderBlock
import net.inkyquill.pocketeditor.reader.ReaderComment
import net.inkyquill.pocketeditor.reader.ReaderDocument
import net.inkyquill.pocketeditor.reader.ReaderRun
import net.inkyquill.pocketeditor.reader.ReaderRunKind
import net.inkyquill.pocketeditor.reader.ReaderSourceSelection
import net.inkyquill.pocketeditor.reader.ReaderState
import net.inkyquill.pocketeditor.reader.ReaderSyncState
import net.inkyquill.pocketeditor.review.Anchor
import net.inkyquill.pocketeditor.review.Edit
import net.inkyquill.pocketeditor.review.Signal
import net.inkyquill.pocketeditor.review.SignalType
import net.inkyquill.pocketeditor.sync.ConflictChoice
import net.inkyquill.pocketeditor.ui.reader.ReaderCallbacks
import net.inkyquill.pocketeditor.ui.reader.ReaderScreen
import net.inkyquill.pocketeditor.ui.review.ConflictCard
import net.inkyquill.pocketeditor.ui.review.EditorialReviewActions
import net.inkyquill.pocketeditor.ui.review.EditorialReviewController
import net.inkyquill.pocketeditor.ui.review.NoteSaveStatus
import net.inkyquill.pocketeditor.ui.review.ReviewDraft
import net.inkyquill.pocketeditor.ui.review.ReviewDraftSession
import net.inkyquill.pocketeditor.ui.review.ReviewSelection
import net.inkyquill.pocketeditor.ui.review.ReviewUiState
import net.inkyquill.pocketeditor.ui.review.ReviewUiError
import net.inkyquill.pocketeditor.ui.review.ReviewDraftPersistence
import net.inkyquill.pocketeditor.ui.review.ReviewDraftStore
import net.inkyquill.pocketeditor.ui.review.readerCallbacks
import net.inkyquill.pocketeditor.ui.theme.PocketEditorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReviewInteractionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun reviewOverlayRendersDiffSignalsAndOrderedCommentBlocksWhileCleanModeDoesNot() {
        setReader(reviewEnabled = true)

        compose.onNodeWithText("removed", substring = true).assertIsDisplayed()
        compose.onNodeWithText("added", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Сигнал: Предупреждение").assertIsDisplayed()
        compose.onNodeWithContentDescription("Удалённый исходный текст: removed", substring = true)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetSelection))
        compose.onNodeWithContentDescription("Добавленный текст замены: added", substring = true).assertIsDisplayed()
        compose.onNodeWithText("First comment").assertIsDisplayed()
        compose.onNodeWithText("Second comment").assertIsDisplayed()
        // Signal type is already conveyed by the highlight color in the text and the composer's
        // own type picker; it must not also be spelled out as a visible text label in the block.
        compose.onAllNodes(hasText("Предупреждение") and hasAnyAncestor(hasTestTag("reader-block-0")))
            .assertCountEquals(0)
        compose.onAllNodes(hasText("Перепроверить") and hasAnyAncestor(hasTestTag("reader-block-0")))
            .assertCountEquals(0)

        compose.onNodeWithContentDescription("Режим рецензирования включён").performClick()
        compose.onAllNodesWithText("First comment").assertCountEquals(0)
        compose.onAllNodesWithText("added").assertCountEquals(0)
        compose.onNodeWithText("Canonical sentence.").assertIsDisplayed()
    }

    @Test
    fun selectionActionsFollowAnActiveReaderTextSelectionWithoutOpeningTheReviewPanel() {
        var editSelections = 0
        var saves = 0
        var observedSelection: ReaderSourceSelection? = null
        val reviewUi = mutableStateOf(ReviewUiState())
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(
                    sampleState(false).copy(reviewEnabled = true),
                    ReaderCallbacks(
                        onTextSelected = { selected ->
                            observedSelection = selected
                            reviewUi.value = ReviewUiState(
                                draftSession = selected?.let {
                                    ReviewDraftSession(
                                        pendingSelection = ReviewSelection(
                                            0,
                                            0,
                                            it.selectedText.length,
                                            it.rawRange,
                                            it.selectedText,
                                        ),
                                    )
                                } ?: ReviewDraftSession(),
                            )
                        },
                        onEditChosen = { editSelections++ },
                        onSaveDraft = {
                            saves++
                            reviewUi.value = ReviewUiState()
                        },
                    ),
                    reviewUi.value,
                    windowSize = DpSize(360.dp, 800.dp),
                )
            }
        }
        compose.onAllNodesWithText("Complete editorial overlay").assertCountEquals(0)
        val selectedText = compose.onNodeWithTag("reader-text-0", useUnmergedTree = true)
        selectedText.performClick()
        selectedText.performSemanticsAction(SemanticsActions.SetSelection) { setSelection ->
            setSelection(0, 10, false)
        }
        compose.runOnIdle {
            assertTrue("BasicTextField selection reaches the reader callback", observedSelection != null)
        }

        val selectedBlockBounds = compose.onNodeWithTag("reader-block-0", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        listOf("Добавить заметку", "Предупреждение", "Нужно изменить", "Рецензия", "Изменить").forEach { label ->
            val action = compose.onNodeWithContentDescription(label)
            action.assertIsDisplayed()
            val bounds = action.fetchSemanticsNode().boundsInRoot
            assertTrue("$label action is adjacent to the selected reader block", bounds.top >= selectedBlockBounds.top)
            assertTrue("$label action keeps a 44dp touch target", bounds.width / density >= 44f)
            assertTrue("$label action keeps a 44dp touch target", bounds.height / density >= 44f)
        }
        compose.onNodeWithTag("selection-flyout", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("Изменить").assertCountEquals(0)
        compose.onNodeWithContentDescription("Изменить").performClick()
        assertEquals(1, editSelections)

        assertEquals(0, saves)
    }

    @Test
    fun selectedTextComposerStaysInlineAndReviewOverviewHasNoActiveComposer() {
        var saves = 0
        val reviewUi = mutableStateOf(ReviewUiState())
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(
                    sampleState(false).copy(reviewEnabled = true),
                    ReaderCallbacks(
                        onTextSelected = { selected ->
                            if (selected == null) return@ReaderCallbacks
                            reviewUi.value = ReviewUiState(
                                draftSession = ReviewDraftSession(
                                    pendingSelection = ReviewSelection(
                                        0, 0, selected.selectedText.length, selected.rawRange, selected.selectedText,
                                    ),
                                ),
                            )
                        },
                        onSignalChosen = { type ->
                            val selection = reviewUi.value.draftSession.pendingSelection ?: return@ReaderCallbacks
                            reviewUi.value = ReviewUiState(
                                draftSession = ReviewDraftSession(
                                    ReviewDraft.Signal(null, selection, type, ""),
                                ),
                            )
                        },
                        onSaveDraft = {
                            saves++
                            reviewUi.value = ReviewUiState()
                        },
                    ),
                    reviewUi.value,
                    windowSize = DpSize(360.dp, 800.dp),
                )
            }
        }

        compose.onNodeWithTag("reader-text-0", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetSelection) { it(0, 5, false) }
        compose.onNodeWithContentDescription("Добавить заметку").performClick()
        compose.onNodeWithTag("inline-annotation-composer").assertIsDisplayed()
        compose.onAllNodesWithTag("review-sheet").assertCountEquals(0)
        compose.onNodeWithTag("inline-annotation-input").assertIsFocused()
        val composerBounds = compose.onNodeWithTag("inline-annotation-composer").fetchSemanticsNode().boundsInRoot
        val readerBounds = compose.onNodeWithTag("reader-root").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            assertTrue(
                "inline composer stays within reader root",
                composerBounds.left >= readerBounds.left && composerBounds.right <= readerBounds.right &&
                    composerBounds.top >= readerBounds.top && composerBounds.bottom <= readerBounds.bottom,
            )
        }
        compose.onNodeWithText("Сохранить").performClick()
        assertEquals(1, saves)

        compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()
        compose.onAllNodesWithTag("inline-annotation-composer").assertCountEquals(0)
        compose.onAllNodesWithTag("signal-composer").assertCountEquals(0)
        compose.onNodeWithTag("chapter-note").assertIsDisplayed()
    }

    @Test
    fun signalComposerKeepsSixteenDpPaddingAroundItsContentOnEveryEdge() {
        val reviewUi = mutableStateOf(ReviewUiState())
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(
                    sampleState(false).copy(reviewEnabled = true),
                    ReaderCallbacks(
                        onTextSelected = { selected ->
                            if (selected == null) return@ReaderCallbacks
                            reviewUi.value = ReviewUiState(
                                draftSession = ReviewDraftSession(
                                    pendingSelection = ReviewSelection(
                                        0, 0, selected.selectedText.length, selected.rawRange, selected.selectedText,
                                    ),
                                ),
                            )
                        },
                        onSignalChosen = { type ->
                            val selection = reviewUi.value.draftSession.pendingSelection ?: return@ReaderCallbacks
                            reviewUi.value = ReviewUiState(
                                draftSession = ReviewDraftSession(
                                    ReviewDraft.Signal(null, selection, type, ""),
                                ),
                            )
                        },
                    ),
                    reviewUi.value,
                    windowSize = DpSize(360.dp, 800.dp),
                )
            }
        }
        compose.onNodeWithTag("reader-text-0", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetSelection) { it(0, 5, false) }
        compose.onNodeWithContentDescription("Добавить заметку").performClick()

        var previousComposerBounds = androidx.compose.ui.geometry.Rect.Zero
        var stableSamples = 0
        compose.waitUntil(timeoutMillis = 5_000) {
            val currentBounds = compose.onNodeWithTag("inline-annotation-composer")
                .fetchSemanticsNode().boundsInRoot
            stableSamples = if (currentBounds == previousComposerBounds) stableSamples + 1 else 0
            previousComposerBounds = currentBounds
            stableSamples >= 25
        }
        val composerCard = compose.onNodeWithTag("inline-annotation-composer").fetchSemanticsNode().boundsInRoot
        val noteChip = compose.onNode(
            hasTestTag("signal-note") and hasAnyAncestor(hasTestTag("signal-composer")),
        ).fetchSemanticsNode().boundsInRoot
        val commentInput = compose.onNodeWithTag("inline-annotation-input").fetchSemanticsNode().boundsInRoot
        val saveButton = compose.onNodeWithTag("save-draft").fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        val minimumPaddingPx = 16f * density - 1f

        assertTrue(
            "the Note chip must sit at least 16dp inside the card's left edge; card=$composerCard chip=$noteChip",
            noteChip.left - composerCard.left >= minimumPaddingPx,
        )
        assertTrue(
            "the Note chip must sit at least 16dp inside the card's top edge; card=$composerCard chip=$noteChip",
            noteChip.top - composerCard.top >= minimumPaddingPx,
        )
        assertTrue(
            "the comment input must sit at least 16dp inside the card's right edge; card=$composerCard input=$commentInput",
            composerCard.right - commentInput.right >= minimumPaddingPx,
        )
        assertTrue(
            "the Save button must sit at least 16dp inside the card's bottom edge; card=$composerCard save=$saveButton",
            composerCard.bottom - saveButton.bottom >= minimumPaddingPx,
        )
    }

    @Test
    fun restoredSavedRecordDraftWithoutAnchorUsesIndependentComposerFallback() {
        val draft = ReviewDraft.Signal(
            recordId = "signal-1",
            selection = ReviewSelection(0, 0, 9, RawRange(0, 9), "Canonical"),
            type = SignalType.WARNING,
            comment = "Restored draft",
            savedType = SignalType.WARNING,
            savedComment = "Original comment",
        )

        setReader(
            reviewEnabled = false,
            reviewUi = ReviewUiState(draftSession = ReviewDraftSession(draft)),
            size = DpSize(800.dp, 1_280.dp),
        )

        compose.onNodeWithTag("inline-annotation-modal").assertIsDisplayed()
        compose.onNodeWithTag("inline-annotation-composer").assertIsDisplayed()
        compose.onNodeWithContentDescription("Комментарий к сигналу, необязательно").assertTextContains("Restored draft")
        compose.onAllNodesWithTag("review-sheet").assertCountEquals(0)
    }

    @Test
    fun savedInlineDraftDoesNotAnchorLaterIndependentDraftToTheOldSelection() {
        val reviewUi = mutableStateOf(ReviewUiState())
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(
                    sampleState(false).copy(reviewEnabled = true),
                    ReaderCallbacks(
                        onTextSelected = { selected ->
                            if (selected != null) {
                                reviewUi.value = ReviewUiState(
                                    ReviewDraftSession(pendingSelection = ReviewSelection(0, 0, selected.selectedText.length, selected.rawRange, selected.selectedText)),
                                )
                            }
                        },
                        onSignalChosen = { type ->
                            val selection = reviewUi.value.draftSession.pendingSelection ?: return@ReaderCallbacks
                            reviewUi.value = ReviewUiState(ReviewDraftSession(ReviewDraft.Signal(null, selection, type, "")))
                        },
                        onSaveDraft = {
                            reviewUi.value = ReviewUiState(
                                draftSession = ReviewDraftSession(
                                    ReviewDraft.Signal(
                                        recordId = "restored-signal",
                                        selection = ReviewSelection(0, 0, 9, RawRange(0, 9), "Canonical"),
                                        type = SignalType.WARNING,
                                        comment = "Restored independently",
                                        savedType = SignalType.WARNING,
                                        savedComment = "Original",
                                    ),
                                ),
                            )
                        },
                    ),
                    reviewUi.value,
                    windowSize = DpSize(360.dp, 800.dp),
                )
            }
        }

        compose.onNodeWithTag("reader-text-0", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetSelection) { it(0, 5, false) }
        compose.onNodeWithContentDescription("Добавить заметку").performClick()
        compose.onNodeWithTag("inline-annotation-composer").assertIsDisplayed()
        compose.onNodeWithText("Сохранить").performClick()

        compose.onNodeWithTag("inline-annotation-phone-sheet").assertIsDisplayed()
        compose.onNodeWithContentDescription("Комментарий к сигналу, необязательно").assertTextContains("Restored independently")
    }

    @Test
    fun longEditComposerFallsBackBeforeItEscapesTheReaderViewport() {
        val longEdit = ReviewDraft.Edit(
            null,
            ReviewSelection(0, 0, 9, RawRange(0, 9), "Canonical"),
            (1..24).joinToString("\n") { "A deliberately long replacement line $it" },
        )
        val reviewUi = mutableStateOf(ReviewUiState())
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                Box(Modifier.requiredSize(800.dp, 620.dp)) {
                    ReaderScreen(
                        sampleState(false),
                        ReaderCallbacks(
                            onTextSelected = { selected ->
                                reviewUi.value = selected?.let {
                                    ReviewUiState(ReviewDraftSession(pendingSelection = ReviewSelection(0, 0, it.selectedText.length, it.rawRange, it.selectedText)))
                                } ?: ReviewUiState()
                            },
                            onEditChosen = { reviewUi.value = ReviewUiState(ReviewDraftSession(longEdit)) },
                        ),
                        reviewUi.value,
                        windowSize = DpSize(800.dp, 620.dp),
                    )
                }
            }
        }

        compose.onNodeWithTag("reader-text-0", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetSelection) { it(0, 5, false) }
        compose.onNodeWithContentDescription("Изменить").performClick()

        compose.waitUntil(5_000) { compose.onAllNodesWithTag("inline-annotation-modal").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("inline-annotation-modal").assertIsDisplayed()
    }

    @Test
    fun nearRightSelectionClampsEveryInlineActionToTheReaderViewport() {
        val reviewUi = mutableStateOf(ReviewUiState())
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(
                    sampleState(false),
                    selectionCallbacks(reviewUi),
                    reviewUi.value,
                    windowSize = DpSize(360.dp, 800.dp),
                )
            }
        }

        compose.onNodeWithTag("reader-text-0", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetSelection) { it(15, 19, false) }

        val viewport = compose.onNodeWithTag("reader-column", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        listOf("Добавить заметку", "Предупреждение", "Нужно изменить", "Рецензия", "Изменить").forEach { label ->
            val bounds = compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
            assertTrue("$label must stay inside the reader viewport", bounds.left >= viewport.left && bounds.right <= viewport.right)
        }
    }

    @Test
    fun landscapeContentsSidebarClampsRenderedSelectionFlyoutAndBelowComposerInRootSpace() {
        val reviewUi = mutableStateOf(ReviewUiState())
        val wideText = "W".repeat(80)
        var signalSelections = 0
        val callbacks = selectionCallbacks(reviewUi).copy(
            onTextSelected = { selected ->
                if (selected != null) {
                    reviewUi.value = ReviewUiState(
                        draftSession = ReviewDraftSession(
                            pendingSelection = ReviewSelection(0, 0, selected.selectedText.length, selected.rawRange, selected.selectedText),
                        ),
                    )
                }
            },
            onSignalChosen = { type ->
                signalSelections++
                val selection = reviewUi.value.draftSession.pendingSelection ?: return@copy
                reviewUi.value = ReviewUiState(ReviewDraftSession(ReviewDraft.Signal(null, selection, type, "")))
            },
        )
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                Box(Modifier.requiredSize(1_280.dp, 800.dp)) {
                    ReaderScreen(
                        sampleState(false).copy(
                            reviewEnabled = true,
                            document = ReaderDocument(
                                listOf(
                                    ReaderBlock(
                                        0,
                                        BlockKind.PARAGRAPH,
                                        wideText,
                                        RawRange(0, wideText.length),
                                        listOf(ReaderRun(wideText, ReaderRunKind.CANONICAL, sourceByteBoundaries = (0..wideText.length).toList())),
                                    ),
                                ),
                            ),
                        ),
                        callbacks,
                        reviewUi.value,
                        windowSize = DpSize(1_280.dp, 800.dp),
                    )
                }
            }
        }

        compose.onNodeWithTag("reader-text-0", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetSelection) { it(30, 34, false) }

        assertTaggedNodeClampsToReaderColumnRightEdgeInRoot("selection-flyout")
        compose.onNodeWithContentDescription("Добавить заметку").performClick()
        compose.runOnIdle {
            assertEquals(1, signalSelections)
            assertTrue("Добавить заметку creates a draft", reviewUi.value.draftSession.draft is ReviewDraft.Signal)
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("inline-annotation-composer").fetchSemanticsNodes().isNotEmpty()
        }
        assertTaggedNodeClampsToReaderColumnRightEdgeInRoot(
            "inline-annotation-composer",
            marginPx = with(compose.density) { 12.dp.toPx() },
        )
    }

    @Test
    fun landscapeContentsSidebarClampsRenderedAboveComposerInRootSpace() {
        val reviewUi = mutableStateOf(ReviewUiState())
        val wideText = "W".repeat(80)
        val blocks = List(12) { index ->
            ReaderBlock(
                index,
                BlockKind.PARAGRAPH,
                wideText,
                RawRange(index * wideText.length, (index + 1) * wideText.length),
                listOf(ReaderRun(wideText, ReaderRunKind.CANONICAL, sourceByteBoundaries = (0..wideText.length).toList())),
            )
        }
        val callbacks = selectionCallbacks(reviewUi).copy(
            onTextSelected = { selected ->
                if (selected != null) {
                    reviewUi.value = ReviewUiState(
                        draftSession = ReviewDraftSession(
                            pendingSelection = ReviewSelection(0, 0, selected.selectedText.length, selected.rawRange, selected.selectedText),
                        ),
                    )
                }
            },
            onSignalChosen = { type ->
                val selection = reviewUi.value.draftSession.pendingSelection ?: return@copy
                reviewUi.value = ReviewUiState(ReviewDraftSession(ReviewDraft.Signal(null, selection, type, "")))
            },
        )
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                Box(Modifier.requiredSize(1_280.dp, 800.dp)) {
                    ReaderScreen(
                        sampleState(false).copy(reviewEnabled = true, document = ReaderDocument(blocks)),
                        callbacks,
                        reviewUi.value,
                        windowSize = DpSize(1_280.dp, 800.dp),
                    )
                }
            }
        }

        repeat(4) {
            compose.onNodeWithTag("reader-scroll", useUnmergedTree = true).performTouchInput { swipeUp() }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("reader-text-11", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("reader-text-11", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetSelection) { it(34, 38, false) }
        val selection = compose.onNodeWithTag("reader-text-11", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("Добавить заметку").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("inline-annotation-composer").fetchSemanticsNodes().isNotEmpty()
        }

        val composer = compose.onNodeWithTag("inline-annotation-composer").fetchSemanticsNode().boundsInRoot
        assertTrue("composer must render above the scrolled selection", composer.bottom <= selection.top)
        assertTaggedNodeClampsToReaderColumnRightEdgeInRoot(
            "inline-annotation-composer",
            marginPx = with(compose.density) { 12.dp.toPx() },
        )
    }

    @Test
    fun landscapeSelectionUsesModalComposerWithoutOpeningReviewSidebar() {
        val reviewUi = mutableStateOf(ReviewUiState())
        val metrics = compose.activity.resources.displayMetrics
        val size = DpSize(1_280.dp, 800.dp)
        val renderDensity = minOf(
            metrics.widthPixels / size.width.value,
            metrics.heightPixels / size.height.value,
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(renderDensity, 1f)) {
                PocketEditorTheme(darkTheme = true) {
                    Box(Modifier.requiredSize(size.width, 400.dp)) {
                        ReaderScreen(
                            sampleState(false).copy(reviewEnabled = true),
                            selectionCallbacks(reviewUi),
                            reviewUi.value,
                            windowSize = size,
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("contents-sidebar").assertIsDisplayed()
        compose.onNodeWithTag("reader-text-0", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetSelection) { it(0, 5, false) }
        compose.onNodeWithContentDescription("Добавить заметку").performClick()

        // Dialog-window visibility is not observable through this density-scaled logical root.
        // The unscaled cramped-tablet regression verifies visual dialog display; this fixture
        // verifies the landscape fallback is selected and its accessible composer is usable.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("inline-annotation-modal").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithTag("inline-annotation-modal").assertCountEquals(1)
        compose.onNodeWithTag("inline-annotation-composer").assertIsDisplayed()
        compose.onAllNodesWithTag("review-sidebar").assertCountEquals(0)
    }

    @Test
    fun fullLandscapeFixtureHasContentsSidebarAndNonzeroOverlayHost() {
        val metrics = compose.activity.resources.displayMetrics
        val size = DpSize(1_280.dp, 800.dp)
        val renderDensity = minOf(
            metrics.widthPixels / size.width.value,
            metrics.heightPixels / size.height.value,
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(renderDensity, 1f)) {
                PocketEditorTheme(darkTheme = true) {
                    Box(Modifier.requiredSize(size)) {
                        ReaderScreen(sampleState(false).copy(reviewEnabled = true), ReaderCallbacks(), windowSize = size)
                    }
                }
            }
        }

        compose.onNodeWithTag("contents-sidebar").assertIsDisplayed()
        val overlayHost = compose.onNodeWithTag("reader-overlay-host", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("full landscape overlay host must have a real viewport", overlayHost.width > 0f && overlayHost.height > 0f)
    }

    @Test
    fun crampedPhoneSelectionUsesModalBottomSheetComposer() {
        val reviewUi = mutableStateOf(ReviewUiState())
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                Box(Modifier.requiredSize(360.dp)) {
                    ReaderScreen(
                        sampleState(false),
                        selectionCallbacks(reviewUi),
                        reviewUi.value,
                        windowSize = DpSize(360.dp, 360.dp),
                    )
                }
            }
        }

        compose.onNodeWithTag("reader-text-0", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetSelection) { it(0, 5, false) }
        compose.onNodeWithContentDescription("Добавить заметку").performClick()

        compose.onNodeWithTag("inline-annotation-phone-sheet").assertIsDisplayed()
        compose.onNodeWithTag("inline-annotation-composer").assertIsDisplayed()
    }

    @Test
    fun crampedTabletSelectionUsesAnAccessibleModalComposer() {
        val reviewUi = mutableStateOf(ReviewUiState())
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                Box(Modifier.requiredSize(360.dp)) {
                    ReaderScreen(
                        sampleState(false).copy(reviewEnabled = true),
                        selectionCallbacks(reviewUi),
                        reviewUi.value,
                        windowSize = DpSize(800.dp, 1_280.dp),
                    )
                }
            }
        }

        compose.onNodeWithTag("reader-text-0", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetSelection) { it(0, 5, false) }
        compose.onNodeWithContentDescription("Добавить заметку").performClick()

        compose.onNodeWithTag("inline-annotation-modal").assertIsDisplayed()
        compose.onNodeWithTag("inline-annotation-composer").assertIsDisplayed()
    }

    @Test
    fun disposingSelectedBlockDoesNotDiscardDirtySignalDraft() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = EditorialReviewController(
            bookId = "book",
            chapterId = "chapter",
            renderedDocument = { MarkdownParser.parse("Block 0 has enough text to select.") },
            occupiedEditRanges = { emptyList() },
            actions = NoOpReviewActions(),
            drafts = ReviewDraftStore(MemoryDraftPersistence()),
            scope = scope,
        )
        val callbacks = controller.readerCallbacks(scope)
        val document = mutableStateOf(multiBlockState().document)
        compose.setContent {
            val reviewUi by controller.state.collectAsState()
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(
                    multiBlockState().copy(document = document.value),
                    callbacks,
                    reviewUi,
                    windowSize = DpSize(360.dp, 360.dp),
                )
            }
        }
        val firstText = compose.onNodeWithTag("reader-text-0", useUnmergedTree = true)
        firstText.performClick()
        firstText.performSemanticsAction(SemanticsActions.SetSelection) { setSelection ->
            setSelection(0, 10, false)
        }
        compose.onNodeWithTag("selection-flyout", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Добавить заметку").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            controller.state.value.draftSession.draft is ReviewDraft.Signal
        }
        compose.onNodeWithContentDescription("Комментарий к сигналу, необязательно").performTextInput("Keep this draft")
        compose.waitUntil(timeoutMillis = 5_000) {
            (controller.state.value.draftSession.draft as? ReviewDraft.Signal)?.comment == "Keep this draft"
        }

        compose.runOnIdle {
            document.value = ReaderDocument(document.value.blocks.filterNot { it.sourceIndex == 0 })
        }
        compose.onNodeWithTag("reader-text-1", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(
                "Keep this draft",
                (controller.state.value.draftSession.draft as? ReviewDraft.Signal)?.comment,
            )
        }
        compose.onAllNodesWithTag("selection-flyout", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("Комментарий к сигналу, необязательно").assertTextContains("Keep this draft")
        compose.runOnIdle {
            assertEquals(
                "Keep this draft",
                (controller.state.value.draftSession.draft as? ReviewDraft.Signal)?.comment,
            )
        }

        compose.runOnIdle { document.value = multiBlockState().document }
        compose.onNodeWithTag("reader-text-0", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Комментарий к сигналу, необязательно").assertTextContains("Keep this draft")
    }

    @Test
    fun disposingSelectedBlockDoesNotDiscardDirtyEditDraft() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = EditorialReviewController(
            bookId = "book",
            chapterId = "chapter",
            renderedDocument = { MarkdownParser.parse("Block 0 has enough text to select.") },
            occupiedEditRanges = { emptyList() },
            actions = NoOpReviewActions(),
            drafts = ReviewDraftStore(MemoryDraftPersistence()),
            scope = scope,
        )
        val document = mutableStateOf(multiBlockState().document)
        compose.setContent {
            val reviewUi by controller.state.collectAsState()
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(
                    multiBlockState().copy(document = document.value),
                    controller.readerCallbacks(scope),
                    reviewUi,
                    windowSize = DpSize(360.dp, 360.dp),
                )
            }
        }
        val firstText = compose.onNodeWithTag("reader-text-0", useUnmergedTree = true)
        firstText.performSemanticsAction(SemanticsActions.SetSelection) { it(0, 10, false) }
        compose.onNodeWithContentDescription("Изменить").performClick()
        compose.waitUntil(5_000) { controller.state.value.draftSession.draft is ReviewDraft.Edit }
        compose.onNodeWithContentDescription("Изменённый фрагмент").performTextClearance()
        compose.onNodeWithContentDescription("Изменённый фрагмент").performTextInput("Edited draft")

        compose.runOnIdle { document.value = ReaderDocument(document.value.blocks.filterNot { it.sourceIndex == 0 }) }

        compose.onNodeWithContentDescription("Изменённый фрагмент").assertTextContains("Edited draft")
        compose.runOnIdle {
            assertEquals("Edited draft", (controller.state.value.draftSession.draft as ReviewDraft.Edit).after)
        }
    }

    @Test
    fun dirtyEditSurvivesBackAndOutsideDismissUntilExplicitCancel() {
        val reviewUi = mutableStateOf(ReviewUiState())
        var cancels = 0
        val draft = ReviewDraft.Edit(
            null,
            ReviewSelection(0, 0, 9, RawRange(0, 9), "Canonical"),
            "Replacement",
        )
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(
                    sampleState(true),
                    ReaderCallbacks(onCancelDraft = { cancels++; reviewUi.value = ReviewUiState() }),
                    reviewUi.value,
                    windowSize = DpSize(800.dp, 1_280.dp),
                )
            }
        }
        reviewUi.value = ReviewUiState(draftSession = ReviewDraftSession(draft))
        compose.onNodeWithTag("inline-annotation-modal").assertIsDisplayed()
        compose.onNodeWithTag("edit-composer").assertIsDisplayed()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.onNodeWithTag("inline-annotation-modal").assertIsDisplayed()
        compose.onAllNodes(isRoot())[1].performTouchInput { click(Offset(1f, 1f)) }
        compose.onNodeWithTag("inline-annotation-modal").assertIsDisplayed()
        assertEquals(0, cancels)
        compose.onNodeWithTag("cancel-draft").performClick()
        assertEquals(1, cancels)
    }

    @Test
    fun backCancelsACleanAdjacentComposerInsteadOfFinishingTheActivity() {
        val reviewUi = mutableStateOf(ReviewUiState())
        var cancels = 0
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                Box(Modifier.requiredSize(360.dp, 800.dp)) {
                ReaderScreen(
                    sampleState(false).copy(reviewEnabled = true),
                    ReaderCallbacks(
                        onTextSelected = { selected ->
                            if (selected != null) {
                                reviewUi.value = ReviewUiState(
                                    ReviewDraftSession(
                                        pendingSelection = ReviewSelection(0, 0, selected.selectedText.length, selected.rawRange, selected.selectedText),
                                    ),
                                )
                            }
                        },
                        onSignalChosen = { type ->
                            val selection = reviewUi.value.draftSession.pendingSelection ?: return@ReaderCallbacks
                            reviewUi.value = ReviewUiState(ReviewDraftSession(ReviewDraft.Signal(null, selection, type, "")))
                        },
                        onCancelDraft = { cancels++; reviewUi.value = ReviewUiState() },
                    ),
                    reviewUi.value,
                    windowSize = DpSize(360.dp, 800.dp),
                )
                }
            }
        }

        compose.onNodeWithTag("reader-text-0", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetSelection) { it(0, 5, false) }
        compose.onNodeWithContentDescription("Добавить заметку").performClick()
        compose.runOnIdle {
            val draft = reviewUi.value.draftSession.draft as ReviewDraft.Signal
            reviewUi.value = ReviewUiState(
                ReviewDraftSession(draft.copy(savedType = draft.type, savedComment = draft.comment)),
            )
        }
        compose.onNodeWithTag("inline-annotation-composer").assertIsDisplayed()
        compose.onAllNodesWithTag("inline-annotation-phone-sheet").assertCountEquals(0)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        compose.waitUntil(5_000) { cancels == 1 }
        assertEquals(1, cancels)
        compose.onAllNodesWithTag("inline-annotation-composer").assertCountEquals(0)
    }

    @Test
    fun physicalTabletInNarrowSplitScreenUsesCenteredCompactModal() {
        val reviewUi = mutableStateOf(ReviewUiState())
        val tabletConfiguration = Configuration(compose.activity.resources.configuration).apply {
            smallestScreenWidthDp = 800
        }
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides tabletConfiguration) {
                PocketEditorTheme(darkTheme = true) {
                    Box(Modifier.requiredSize(360.dp)) {
                        ReaderScreen(
                            sampleState(false),
                            selectionCallbacks(reviewUi),
                            reviewUi.value,
                            windowSize = DpSize(360.dp, 360.dp),
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("reader-text-0", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetSelection) { it(0, 5, false) }
        compose.onNodeWithContentDescription("Добавить заметку").performClick()
        compose.onNodeWithTag("inline-annotation-modal").assertIsDisplayed()
        compose.onAllNodesWithTag("inline-annotation-phone-sheet").assertCountEquals(0)

        val modal = compose.onNodeWithTag("inline-annotation-modal").fetchSemanticsNode().boundsInRoot
        val composer = compose.onNodeWithTag("inline-annotation-composer").fetchSemanticsNode().boundsInRoot
        val maxWidthPx = 420f * compose.activity.resources.displayMetrics.density
        assertTrue("tablet modal surface must stay compact", composer.width <= maxWidthPx + 1f)
        assertTrue("tablet modal surface must be centered", kotlin.math.abs(composer.center.x - modal.center.x) <= 1f)
    }

    @Test
    fun dirtyComposerSurvivesAdaptiveRotation() {
        val size = mutableStateOf(DpSize(360.dp, 800.dp))
        val draft = ReviewDraft.Signal(
            null,
            ReviewSelection(0, 0, 9, RawRange(0, 9), "Canonical"),
            SignalType.WARNING,
            "Unfinished",
        )
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(
                    sampleState(true),
                    ReaderCallbacks(),
                    ReviewUiState(draftSession = ReviewDraftSession(draft)),
                    windowSize = size.value,
                )
            }
        }
        compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()
        compose.onNodeWithTag("inline-annotation-phone-sheet").assertIsDisplayed()
        compose.onNodeWithTag("signal-composer").assertIsDisplayed()

        compose.runOnIdle { size.value = DpSize(800.dp, 1280.dp) }

        compose.onNodeWithTag("inline-annotation-modal").assertIsDisplayed()
        compose.onNodeWithTag("signal-composer").assertIsDisplayed()
    }

    @Test
    fun chapterNoteIsPlainTextWithQuietStatusAndDeleteOffersUndo() {
        var note = ""
        var undo = ""
        setReader(
            reviewEnabled = true,
            reviewUi = ReviewUiState(
                chapterNote = "Draft rhythm note",
                noteSaveStatus = NoteSaveStatus.WAITING,
                pendingDeletions = listOf("delete-token"),
            ),
            callbacks = ReaderCallbacks(
                onChapterNoteChanged = { note = it },
                onUndoDeletion = { undo = it },
            ),
        )
        compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()

        compose.onNodeWithTag("chapter-note").performTextClearance()
        compose.onNodeWithTag("chapter-note").performTextInput("New note")
        compose.onAllNodesWithText("Ожидает синхронизации").assertCountEquals(1)
        compose.onNodeWithContentDescription("Заметка к главе: Ожидает синхронизации").assertIsDisplayed()
        compose.onNodeWithText("Отменить").performClick()

        assertEquals("New note", note)
        assertEquals("delete-token", undo)
    }

    @Test
    fun conflictResolverLabelsBothChoicesAndRequiresEveryRecord() {
        val choices = mutableListOf<Pair<String, ConflictChoice>>()
        setReader(
            reviewEnabled = true,
            reviewUi = ReviewUiState(
                conflicts = listOf(
                    ConflictCard(
                        "review:review.json:signal-1", "review.json", "signal-1", "review-v1",
                        "Local wording", "Yandex wording",
                    ),
                ),
            ),
            callbacks = ReaderCallbacks(onConflictChoice = { id, _, choice -> choices += id to choice }),
        )
        compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()

        compose.onNodeWithContentDescription("Оставить мою версию для signal-1, не выбрано").performClick()
        compose.onNodeWithContentDescription("Взять версию с Яндекс Диска для signal-1, не выбрано").performClick()

        assertEquals(
            listOf(
                "review:review.json:signal-1" to ConflictChoice.KEEP_MINE,
                "review:review.json:signal-1" to ConflictChoice.KEEP_YANDEX,
            ),
            choices,
        )
    }

    @Test
    fun conflictResolverExposesVisibleAndAccessibleSelectedChoice() {
        setReader(
            reviewEnabled = true,
            reviewUi = ReviewUiState(
                conflicts = listOf(
                    ConflictCard(
                        key = "review:review.json:signal-1",
                        path = "review.json",
                        recordId = "signal-1",
                        identity = "review-v1",
                        localPreview = "Local wording",
                        yandexPreview = "Yandex wording",
                        selectedChoice = ConflictChoice.KEEP_MINE,
                    ),
                ),
            ),
        )
        compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()

        compose.onNodeWithContentDescription("Оставить мою версию для signal-1, выбрано").assertIsSelected()
        compose.onNodeWithContentDescription("Взять версию с Яндекс Диска для signal-1, не выбрано").assertIsDisplayed()
        compose.onNodeWithText("Выбрано", substring = true).assertIsDisplayed()
    }

    @Test
    fun reviewFailureKeepsActionableRetryInsideReviewPanel() {
        var retries = 0
        setReader(
            reviewEnabled = true,
            reviewUi = ReviewUiState(error = ReviewUiError("Сохранение элемента рецензии:: disk full")),
            callbacks = ReaderCallbacks(onRetryReviewError = { retries++ }),
        )
        compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()

        compose.onNodeWithText("Сохранение элемента рецензии:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Повторить").performClick()

        assertEquals(1, retries)
    }

    @Test
    fun chapterNoteFlushesOnlyAfterARealFocusedToUnfocusedTransition() {
        var focusLosses = 0
        setReader(
            reviewEnabled = true,
            callbacks = ReaderCallbacks(onChapterNoteFocusLost = { focusLosses++ }),
        )
        compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()
        compose.waitForIdle()
        assertEquals(0, focusLosses)

        compose.onNodeWithTag("chapter-note").performClick()
        compose.onNodeWithContentDescription("Закрыть панель рецензии").performClick()
        compose.waitForIdle()

        assertEquals(1, focusLosses)
    }

    @Test
    fun reviewPanelCloseButtonDismissesWhileANoteSaveErrorIsShowingAndNoDraftIsDirty() {
        setReader(
            reviewEnabled = true,
            reviewUi = ReviewUiState(noteSaveStatus = NoteSaveStatus.ERROR),
        )
        compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("review-sheet").assertIsDisplayed()
        compose.onNodeWithContentDescription("Заметка к главе: Не удалось сохранить", substring = true, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()

        compose.onNodeWithContentDescription("Закрыть панель рецензии").performClick()
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
        compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()
        compose.onNodeWithTag("review-sheet").assertIsDisplayed()

        compose.onNodeWithContentDescription("Закрыть панель рецензии").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("review-sheet").assertIsDisplayed()
    }

    private fun setReader(
        reviewEnabled: Boolean,
        reviewUi: ReviewUiState = ReviewUiState(),
        callbacks: ReaderCallbacks = ReaderCallbacks(),
        size: DpSize = DpSize(360.dp, 800.dp),
    ) {
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(sampleState(reviewEnabled), callbacks, reviewUi, windowSize = size)
            }
        }
    }

    private fun assertTaggedNodeInsideReaderColumn(tag: String) {
        val readerColumn = compose.onNodeWithTag("reader-column", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$tag must stay inside the centered reader column; node=$node column=$readerColumn",
            node.left >= readerColumn.left && node.right <= readerColumn.right &&
                node.top >= readerColumn.top && node.bottom <= readerColumn.bottom,
        )
    }

    private fun assertTaggedNodeClampsToReaderColumnRightEdgeInRoot(tag: String, marginPx: Float = 0f) {
        val overlayHost = compose.onNodeWithTag("reader-overlay-host", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val readerColumn = compose.onNodeWithTag("reader-column", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val expectedLeft = readerColumn.right - node.width - marginPx
        assertTrue(
            "$tag must use the reader column's root X when clamped right; node=$node host=$overlayHost column=$readerColumn",
            kotlin.math.abs(node.left - expectedLeft) <= 1f,
        )
    }

    private fun selectionCallbacks(reviewUi: androidx.compose.runtime.MutableState<ReviewUiState>) = ReaderCallbacks(
        onTextSelected = { selected ->
            reviewUi.value = selected?.let {
                ReviewUiState(
                    draftSession = ReviewDraftSession(
                        pendingSelection = ReviewSelection(0, 0, it.selectedText.length, it.rawRange, it.selectedText),
                    ),
                )
            } ?: ReviewUiState()
        },
        onSignalChosen = { type ->
            val selection = reviewUi.value.draftSession.pendingSelection ?: return@ReaderCallbacks
            reviewUi.value = ReviewUiState(ReviewDraftSession(ReviewDraft.Signal(null, selection, type, "")))
        },
        onEditChosen = {
            val selection = reviewUi.value.draftSession.pendingSelection ?: return@ReaderCallbacks
            reviewUi.value = ReviewUiState(ReviewDraftSession(ReviewDraft.Edit(null, selection, selection.selectedText)))
        },
    )

    private fun sampleState(reviewEnabled: Boolean) = ReaderState(
        bookId = "book",
        chapterId = "chapter",
        title = "Night chapter",
        document = if (reviewEnabled) reviewedDocument() else cleanDocument(),
        reviewEnabled = reviewEnabled,
        chapterNote = "Draft rhythm note",
        reviewItems = null,
        previousChapter = null,
        nextChapter = null,
        readingPosition = null,
        syncState = ReaderSyncState.WAITING_TO_SYNC,
    )

    private fun reviewedDocument() = ReaderDocument(
        listOf(
            ReaderBlock(
                0,
                BlockKind.PARAGRAPH,
                "Canonical sentence.",
                RawRange(0, 19),
                listOf(
                    ReaderRun("Canonical ", ReaderRunKind.CANONICAL, setOf("signal-1"), setOf(SignalType.WARNING)),
                    ReaderRun("removed", ReaderRunKind.DELETED),
                    ReaderRun("added", ReaderRunKind.ADDED),
                ),
                comments = listOf(
                    ReaderComment("signal-1", SignalType.WARNING, "First comment", RawRange(0, 9)),
                    ReaderComment("signal-2", SignalType.REVIEW, "Second comment", RawRange(10, 18)),
                ),
            ),
        ),
    )

    private fun cleanDocument() = ReaderDocument(
        listOf(
            ReaderBlock(
                0,
                BlockKind.PARAGRAPH,
                "Canonical sentence.",
                RawRange(0, 19),
                listOf(
                    ReaderRun(
                        "Canonical sentence.",
                        ReaderRunKind.CANONICAL,
                        sourceByteBoundaries = (0..19).toList(),
                    ),
                ),
            ),
        ),
    )

    private fun multiBlockState() = sampleState(reviewEnabled = true).copy(
        document = ReaderDocument(
            (0..100).map { index ->
                val text = "Block $index has enough text to select."
                ReaderBlock(
                    index,
                    BlockKind.PARAGRAPH,
                    text,
                    RawRange(index * 100, index * 100 + text.length),
                    listOf(
                        ReaderRun(
                            text,
                            ReaderRunKind.CANONICAL,
                            sourceByteBoundaries = (0..text.length).map { index * 100 + it },
                        ),
                    ),
                )
            },
        ),
    )

    private class MemoryDraftPersistence : ReviewDraftPersistence {
        private var draft: DraftEntity? = null

        override suspend fun put(draft: DraftEntity) {
            this.draft = draft
        }

        override suspend fun get(bookId: String, chapterId: String, draftType: String, recordKey: String) = draft

        override suspend fun delete(bookId: String, chapterId: String, draftType: String, recordKey: String) {
            draft = null
        }
    }

    private class NoOpReviewActions : EditorialReviewActions {
        override suspend fun saveSignal(signal: Signal) = Unit
        override suspend fun saveEdit(edit: Edit) = Unit
        override suspend fun saveChapterNote(text: String) = Unit
        override suspend fun deleteSignal(id: String) = PendingDeletion("signal", 0)
        override suspend fun deleteEdit(id: String) = PendingDeletion("edit", 0)
        override suspend fun pendingDeletions() = emptyList<PendingDeletion>()
        override suspend fun undoDeletion(token: PendingDeletion) = Unit
        override suspend fun finalizeDeletion(token: PendingDeletion) = Unit
        override suspend fun reanchor(recordId: String, anchor: Anchor) = Unit
        override suspend fun resolveReview(path: String, expectedIdentity: String, choices: Map<String, ConflictChoice>) = Unit
        override suspend fun resolveManifest(expectedIdentity: String, choice: ConflictChoice) = Unit
    }

    private object InstrumentationKeys {
        fun back() {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
    }
}
