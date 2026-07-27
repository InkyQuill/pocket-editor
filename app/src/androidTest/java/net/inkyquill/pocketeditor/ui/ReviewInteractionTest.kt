package net.inkyquill.pocketeditor.ui

import android.content.res.Configuration
import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
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
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowInsetsCompat
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
import net.inkyquill.pocketeditor.reader.ReaderEditItem
import net.inkyquill.pocketeditor.reader.ReaderReviewItems
import net.inkyquill.pocketeditor.reader.ReaderRun
import net.inkyquill.pocketeditor.reader.ReaderRunKind
import net.inkyquill.pocketeditor.reader.ReaderSignalItem
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
import net.inkyquill.pocketeditor.ui.review.AnnotationComposerPlacement
import net.inkyquill.pocketeditor.ui.review.EditorialReviewActions
import net.inkyquill.pocketeditor.ui.review.EditorialReviewController
import net.inkyquill.pocketeditor.ui.review.InlineAnnotationComposer
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
    fun phoneSelectionAlwaysUsesBottomSheetEvenWhenAnchorHasRoom() {
        val reviewUi = mutableStateOf(ReviewUiState())
        val phone = Configuration(compose.activity.resources.configuration).apply {
            smallestScreenWidthDp = 360
        }
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides phone) {
                PocketEditorTheme(darkTheme = true) {
                    Box(Modifier.requiredSize(360.dp, 800.dp)) {
                        ReaderScreen(
                            sampleState(false).copy(reviewEnabled = true),
                            selectionCallbacks(reviewUi),
                            reviewUi.value,
                            windowSize = DpSize(360.dp, 800.dp),
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("reader-text-0", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetSelection) { it(0, 5, false) }
        compose.onNodeWithContentDescription("Добавить заметку").performClick()

        compose.onNodeWithTag("inline-annotation-phone-sheet").assertIsDisplayed()
        compose.onAllNodesWithTag("inline-annotation-modal").assertCountEquals(0)
    }

    @Test
    fun physicalTabletAlwaysUsesModalEvenWhenAnchorHasRoom() {
        val reviewUi = mutableStateOf(ReviewUiState())
        val tablet = Configuration(compose.activity.resources.configuration).apply {
            smallestScreenWidthDp = 800
        }
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides tablet) {
                PocketEditorTheme(darkTheme = true) {
                    Box(Modifier.requiredSize(800.dp, 1_280.dp)) {
                        ReaderScreen(
                            sampleState(false).copy(reviewEnabled = true),
                            selectionCallbacks(reviewUi),
                            reviewUi.value,
                            windowSize = DpSize(800.dp, 1_280.dp),
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
    }

    @Test
    fun signalComposerKeepsSixteenDpPaddingAroundItsContentOnEveryEdge() {
        val reviewUi = mutableStateOf(ReviewUiState())
        val size = DpSize(360.dp, 800.dp)
        setContentInLogicalRoot(size, physicalSmallestWidthDp = 360) {
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
                windowSize = size,
            )
        }
        compose.onNodeWithTag("reader-text-0", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetSelection) { it(0, 5, false) }
        compose.onNodeWithContentDescription("Добавить заметку").performClick()

        var previousComposerBounds = androidx.compose.ui.geometry.Rect.Zero
        var stableSamples = 0
        compose.waitUntil(timeoutMillis = 20_000) {
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
        val minimumPaddingPx = 16f * renderDensityFor(size) - 1f

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
    fun detachedSignalComposerQuotesTheSelectedText() {
        val selection = ReviewSelection(0, 0, 38, RawRange(0, 38), "Keep the quiet pressure through the end.")
        val draft = ReviewDraft.Signal(null, selection, SignalType.WARNING, "")
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                InlineAnnotationComposer(
                    session = ReviewDraftSession(draft),
                    callbacks = ReaderCallbacks(),
                    placement = AnnotationComposerPlacement.TabletModal,
                )
            }
        }

        compose.onNodeWithTag("signal-selection-quote")
            .assertTextContains("Keep the quiet pressure through the end.")
        compose.onNodeWithTag("signal-selection-marker").assertIsDisplayed()
    }

    @Test
    fun phoneComposerStacksAFullWidthSaveAboveCancel() {
        val selection = ReviewSelection(0, 0, 5, RawRange(0, 5), "quiet")
        val draft = ReviewDraft.Signal(null, selection, SignalType.NOTE, "")
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                InlineAnnotationComposer(
                    session = ReviewDraftSession(draft),
                    callbacks = ReaderCallbacks(),
                    placement = AnnotationComposerPlacement.PhoneSheet,
                )
            }
        }

        val form = compose.onNodeWithTag("signal-composer").fetchSemanticsNode().boundsInRoot
        val save = compose.onNodeWithTag("save-draft").fetchSemanticsNode().boundsInRoot
        val cancel = compose.onNodeWithTag("cancel-draft").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            assertTrue(save.width >= form.width - 32f * compose.density.density - 2f)
            assertTrue(save.bottom <= cancel.top)
        }
    }

    @Test
    fun tabletComposerKeepsCancelLeftOfSave() {
        val selection = ReviewSelection(0, 0, 5, RawRange(0, 5), "quiet")
        val draft = ReviewDraft.Edit(null, selection, "changed")
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                InlineAnnotationComposer(
                    session = ReviewDraftSession(draft),
                    callbacks = ReaderCallbacks(),
                    placement = AnnotationComposerPlacement.TabletModal,
                )
            }
        }

        val save = compose.onNodeWithTag("save-draft").fetchSemanticsNode().boundsInRoot
        val cancel = compose.onNodeWithTag("cancel-draft").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { assertTrue(cancel.right <= save.left) }
    }

    @Test
    fun reviewInputKeepsRapidCharactersAndCursorWhileParentStateLags() {
        val writes = mutableListOf<String>()
        val selection = ReviewSelection(0, 0, 5, RawRange(0, 5), "quiet")
        val draft = ReviewDraft.Signal(
            recordId = "signal-1",
            selection = selection,
            type = SignalType.NOTE,
            comment = "quiet",
            savedType = SignalType.NOTE,
            savedComment = "quiet",
        )
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                InlineAnnotationComposer(
                    session = ReviewDraftSession(draft),
                    callbacks = ReaderCallbacks(onDraftTextChanged = writes::add),
                    placement = AnnotationComposerPlacement.TabletModal,
                )
            }
        }

        val input = compose.onNodeWithTag("inline-annotation-input")
        input.performSemanticsAction(SemanticsActions.SetSelection) { it(3, 3, false) }
        compose.runOnIdle { assertEquals(emptyList<String>(), writes) }
        input.performTextInput("X")
        input.performTextInput("Y")

        input.assertTextContains("quiXYet")
        compose.runOnIdle {
            assertEquals(listOf("quiXet", "quiXYet"), writes)
        }
    }

    @Test
    fun editValidationUsesLocalTextWhileParentStateLags() {
        val writes = mutableListOf<String>()
        val selection = ReviewSelection(0, 0, 5, RawRange(0, 5), "quiet")
        val draft = ReviewDraft.Edit(
            recordId = null,
            selection = selection,
            after = "quiet",
        )
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                InlineAnnotationComposer(
                    session = ReviewDraftSession(draft),
                    callbacks = ReaderCallbacks(onDraftTextChanged = writes::add),
                    placement = AnnotationComposerPlacement.TabletModal,
                )
            }
        }

        compose.onNodeWithTag("inline-annotation-input").performTextInput(" ending")

        compose.onNodeWithTag("save-draft").assertIsEnabled()
        compose.onNodeWithTag("inline-annotation-input").assertTextContains("quiet ending")
        compose.runOnIdle { assertEquals(listOf("quiet ending"), writes) }
    }

    @Test
    fun savedSignalTypeUpdatesImmediatelyWhileParentStateLags() {
        val typeWrites = mutableListOf<SignalType>()
        val selection = ReviewSelection(0, 0, 5, RawRange(0, 5), "quiet")
        val draft = ReviewDraft.Signal(
            recordId = "signal-1",
            selection = selection,
            type = SignalType.NOTE,
            comment = "",
            savedType = SignalType.NOTE,
            savedComment = "",
        )
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                InlineAnnotationComposer(
                    session = ReviewDraftSession(draft),
                    callbacks = ReaderCallbacks(onSignalTypeChanged = typeWrites::add),
                    placement = AnnotationComposerPlacement.TabletModal,
                )
            }
        }

        compose.onNodeWithTag("signal-warning").performClick()

        compose.onNodeWithTag("signal-warning").assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(SignalType.WARNING), typeWrites) }
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
            physicalSmallestWidthDp = 800,
        )

        compose.onNodeWithTag("inline-annotation-modal").assertIsDisplayed()
        compose.onNodeWithTag("inline-annotation-composer").assertIsDisplayed()
        compose.onNodeWithContentDescription("Комментарий к сигналу, необязательно").assertTextContains("Restored draft")
        compose.onAllNodesWithTag("review-sheet").assertCountEquals(0)
    }

    @Test
    fun savedInlineDraftDoesNotAnchorLaterIndependentDraftToTheOldSelection() {
        val reviewUi = mutableStateOf(ReviewUiState())
        setContentInLogicalRoot(DpSize(360.dp, 800.dp), physicalSmallestWidthDp = 360) {
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

        compose.onNodeWithTag("reader-text-0", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetSelection) { it(0, 5, false) }
        compose.onNodeWithContentDescription("Добавить заметку").performClick()
        compose.onNodeWithTag("inline-annotation-composer").assertIsDisplayed()
        compose.onNodeWithText("Сохранить").performSemanticsAction(SemanticsActions.OnClick) { it() }

        compose.waitUntil(5_000) {
            runCatching {
                compose.onNodeWithTag("inline-annotation-phone-sheet").assertIsDisplayed()
            }.isSuccess
        }
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
        val tablet = Configuration(compose.activity.resources.configuration).apply {
            smallestScreenWidthDp = 800
        }
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides tablet) {
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
    fun landscapeSelectionUsesModalComposerWithoutOpeningReviewSidebar() {
        val reviewUi = mutableStateOf(ReviewUiState())
        val size = DpSize(1_280.dp, 800.dp)
        val renderDensity = renderDensityFor(size)
        val tablet = Configuration(compose.activity.resources.configuration).apply {
            smallestScreenWidthDp = 800
        }
        compose.setContent {
            CompositionLocalProvider(
                LocalConfiguration provides tablet,
                LocalDensity provides Density(renderDensity, 1f),
            ) {
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
        val size = DpSize(1_280.dp, 800.dp)
        val renderDensity = renderDensityFor(size)
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
        val phone = Configuration(compose.activity.resources.configuration).apply {
            smallestScreenWidthDp = 360
        }
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides phone) {
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

        compose.onNodeWithTag("inline-annotation-phone-sheet").assertIsDisplayed()
        compose.onNodeWithTag("inline-annotation-composer").assertIsDisplayed()
    }

    @Test
    fun crampedTabletSelectionUsesAnAccessibleModalComposer() {
        val reviewUi = mutableStateOf(ReviewUiState())
        val tablet = Configuration(compose.activity.resources.configuration).apply {
            smallestScreenWidthDp = 800
        }
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides tablet) {
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
        setContentInLogicalRoot(DpSize(360.dp, 360.dp), physicalSmallestWidthDp = 360) {
            val reviewUi by controller.state.collectAsState()
            ReaderScreen(
                multiBlockState().copy(document = document.value),
                callbacks,
                reviewUi,
                windowSize = DpSize(360.dp, 360.dp),
            )
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
        compose.onNodeWithTag("reader-text-1", useUnmergedTree = true).fetchSemanticsNode()
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
    fun dirtyEditOffersDiscardConfirmationForBackAndExtremeOuterMarginDismiss() {
        val reviewUi = mutableStateOf(ReviewUiState())
        var cancels = 0
        val tablet = Configuration(compose.activity.resources.configuration).apply {
            smallestScreenWidthDp = 800
        }
        val selection = ReviewSelection(0, 0, 9, RawRange(0, 9), "Canonical")
        val draft = ReviewDraft.Edit(
            recordId = "edit-1",
            selection = selection,
            after = "Replacement",
            savedAfter = "Canonical",
        )
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides tablet) {
                PocketEditorTheme(darkTheme = true) {
                    ReaderScreen(
                        sampleState(true),
                        ReaderCallbacks(onCancelDraft = { cancels++; reviewUi.value = ReviewUiState() }),
                        reviewUi.value,
                        windowSize = DpSize(800.dp, 1_280.dp),
                    )
                }
            }
        }
        compose.runOnIdle {
            reviewUi.value = ReviewUiState(draftSession = ReviewDraftSession(draft))
        }

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Отменить изменения?").assertIsDisplayed()
        compose.onNodeWithText("Продолжить редактирование").performClick()
        compose.onNodeWithTag("inline-annotation-input").assertTextContains("Replacement")
        compose.onNodeWithTag("inline-annotation-input").assertIsFocused()

        compose.onNodeWithTag("inline-annotation-modal")
            .performTouchInput { click(Offset(1f, 1f)) }
        compose.onNodeWithText("Отменить изменения?").assertIsDisplayed()
        compose.onNodeWithText("Отменить изменения").performClick()

        compose.runOnIdle { assertEquals(1, cancels) }
    }

    @Test
    fun dirtyPhoneSheetDismissalKeepsComposerVisibleAfterContinue() {
        val selection = ReviewSelection(0, 0, 9, RawRange(0, 9), "Canonical")
        val draft = ReviewDraft.Edit(
            recordId = "edit-1",
            selection = selection,
            after = "Canonical",
            savedAfter = "Canonical",
        )
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                InlineAnnotationComposer(
                    session = ReviewDraftSession(draft),
                    callbacks = ReaderCallbacks(),
                    placement = AnnotationComposerPlacement.PhoneSheet,
                )
            }
        }

        compose.onNodeWithTag("inline-annotation-input").performTextInput(" changed")
        compose.onNodeWithTag("inline-annotation-phone-sheet")
            .performTouchInput { swipeDown() }
        compose.onNodeWithText("Отменить изменения?").assertIsDisplayed()
        compose.onNodeWithText("Продолжить редактирование").performClick()

        compose.onNodeWithTag("inline-annotation-phone-sheet").assertIsDisplayed()
        compose.onNodeWithTag("inline-annotation-input").assertTextContains("Canonical changed")
        compose.onNodeWithTag("inline-annotation-input").assertIsFocused()
    }

    @Test
    fun emptyNewSignalDismissesWithoutConfirmation() {
        var cancels = 0
        val selection = ReviewSelection(0, 0, 5, RawRange(0, 5), "quiet")
        val draft = ReviewDraft.Signal(null, selection, SignalType.NOTE, "")
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                InlineAnnotationComposer(
                    session = ReviewDraftSession(draft),
                    callbacks = ReaderCallbacks(onCancelDraft = { cancels++ }),
                    placement = AnnotationComposerPlacement.TabletModal,
                )
            }
        }

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        compose.runOnIdle { assertEquals(1, cancels) }
        compose.onAllNodesWithText("Отменить изменения?").assertCountEquals(0)
    }

    @Test
    fun savedSignalTypeChangeProtectsDismissBeforeParentStateCatchesUp() {
        val selection = ReviewSelection(0, 0, 5, RawRange(0, 5), "quiet")
        val draft = ReviewDraft.Signal(
            recordId = "signal-1",
            selection = selection,
            type = SignalType.NOTE,
            comment = "",
            savedType = SignalType.NOTE,
            savedComment = "",
        )
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                InlineAnnotationComposer(
                    session = ReviewDraftSession(draft),
                    callbacks = ReaderCallbacks(),
                    placement = AnnotationComposerPlacement.TabletModal,
                )
            }
        }

        compose.onNodeWithTag("signal-warning").performClick()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        compose.onNodeWithText("Отменить изменения?").assertIsDisplayed()
    }

    @Test
    fun reopeningTheSameSelectionStartsFromTheNewDraftValue() {
        val draft = mutableStateOf<ReviewDraft?>(
            ReviewDraft.Signal(
                null,
                ReviewSelection(0, 0, 5, RawRange(0, 5), "quiet"),
                SignalType.NOTE,
                "",
            ),
        )
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                draft.value?.let {
                    InlineAnnotationComposer(
                        session = ReviewDraftSession(it),
                        callbacks = ReaderCallbacks(onCancelDraft = { draft.value = null }),
                        placement = AnnotationComposerPlacement.TabletModal,
                    )
                }
            }
        }

        compose.onNodeWithTag("inline-annotation-input").performTextInput("old")
        compose.onNodeWithTag("cancel-draft").performClick()
        compose.runOnIdle {
            draft.value = ReviewDraft.Signal(
                null,
                ReviewSelection(0, 0, 5, RawRange(0, 5), "quiet"),
                SignalType.NOTE,
                "",
            )
        }

        compose.onNodeWithTag("inline-annotation-input").assertTextEquals("")
        compose.onAllNodesWithText("old").assertCountEquals(0)
    }

    @Test
    fun tabletModalCentersInTheVisibleAreaAboveIme() {
        val selection = ReviewSelection(0, 0, 5, RawRange(0, 5), "quiet")
        val draft = ReviewDraft.Signal(null, selection, SignalType.NOTE, "")
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                InlineAnnotationComposer(
                    session = ReviewDraftSession(draft),
                    callbacks = ReaderCallbacks(),
                    placement = AnnotationComposerPlacement.TabletModal,
                )
            }
        }
        compose.onNodeWithTag("inline-annotation-input").performClick()
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            val imm = compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(view.findFocus(), InputMethodManager.SHOW_IMPLICIT)
        }
        compose.waitUntil(5_000) {
            val view = compose.activity.window.decorView
            WindowInsetsCompat.toWindowInsetsCompat(view.rootWindowInsets, view)
                .isVisible(WindowInsetsCompat.Type.ime())
        }

        val root = compose.onNodeWithTag("inline-annotation-modal-content").fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("inline-annotation-composer").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            assertTrue(card.bottom <= root.bottom + 1f)
            assertTrue(kotlin.math.abs(card.center.y - root.center.y) <= 2f)
        }
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

        val modal = compose.onNodeWithTag("inline-annotation-modal-content").fetchSemanticsNode().boundsInRoot
        val composer = compose.onNodeWithTag("inline-annotation-composer").fetchSemanticsNode().boundsInRoot
        val maxWidthPx = 420f * compose.activity.resources.displayMetrics.density
        assertTrue("tablet modal surface must stay compact", composer.width <= maxWidthPx + 1f)
        assertTrue("tablet modal surface must be centered", kotlin.math.abs(composer.center.x - modal.center.x) <= 1f)
    }

    @Test
    fun dirtyComposerSurvivesAdaptiveRotation() {
        val size = mutableStateOf(DpSize(360.dp, 800.dp))
        val phone = Configuration(compose.activity.resources.configuration).apply {
            smallestScreenWidthDp = 360
        }
        val draft = ReviewDraft.Signal(
            null,
            ReviewSelection(0, 0, 9, RawRange(0, 9), "Canonical"),
            SignalType.WARNING,
            "Unfinished",
        )
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides phone) {
                PocketEditorTheme(darkTheme = true) {
                    ReaderScreen(
                        sampleState(true),
                        ReaderCallbacks(),
                        ReviewUiState(draftSession = ReviewDraftSession(draft)),
                        windowSize = size.value,
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()
        compose.onNodeWithTag("inline-annotation-phone-sheet").assertIsDisplayed()
        compose.onNodeWithTag("signal-composer").assertIsDisplayed()
        compose.onNodeWithTag("inline-annotation-input").performTextInput(" local")

        compose.runOnIdle { size.value = DpSize(800.dp, 360.dp) }

        compose.onNodeWithTag("inline-annotation-phone-sheet").assertIsDisplayed()
        compose.onAllNodesWithTag("inline-annotation-modal").assertCountEquals(0)
        compose.onNodeWithTag("signal-composer").assertIsDisplayed()
        compose.onNodeWithTag("inline-annotation-input").assertTextContains("Unfinished local")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Отменить изменения?").assertIsDisplayed()
    }

    @Test
    fun chapterNoteIsPlainTextWithQuietStatusAndDeleteOffersUndo() {
        var note = ""
        var undo = ""
        val reviewUi = ReviewUiState(
            chapterNote = "Draft rhythm note",
            noteSaveStatus = NoteSaveStatus.WAITING,
            pendingDeletions = listOf("delete-token"),
        )
        val callbacks = ReaderCallbacks(
            onChapterNoteChanged = { note = it },
            onUndoDeletion = { undo = it },
        )
        setContentInLogicalRoot(DpSize(360.dp, 800.dp)) {
            ReaderScreen(
                sampleState(true),
                callbacks,
                reviewUi,
                windowSize = DpSize(360.dp, 800.dp),
            )
        }
        compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("chapter-note").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("chapter-note").performTextClearance()
        compose.onNodeWithTag("chapter-note").performTextInput("New note")
        compose.onAllNodesWithText("Ожидает синхронизации").assertCountEquals(1)
        compose.onNodeWithContentDescription("Заметка к главе: Ожидает синхронизации").assertIsDisplayed()
        compose.onNodeWithText("Отменить").performClick()

        assertEquals("New note", note)
        assertEquals("delete-token", undo)
    }

    @Test
    fun reviewCardsUseFullWidthMutedSourceAndBoundedBodyWithoutInlineActions() {
        val longSource = List(12) { "исходный фрагмент $it" }.joinToString(" ")
        val longComment = List(24) { "полный комментарий $it" }.joinToString(" ")
        var expectedSourceColor = Color.Unspecified
        compose.setContent {
            PocketEditorTheme(darkTheme = false) {
                expectedSourceColor = MaterialTheme.colorScheme.onSurfaceVariant
                ReaderScreen(
                    state = reviewCardState(longSource, longComment),
                    callbacks = ReaderCallbacks(),
                    windowSize = DpSize(360.dp, 800.dp),
                )
            }
        }

        compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()

        val density = compose.activity.resources.displayMetrics.density
        val panel = compose.onNodeWithTag("review-sheet").fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("review-record-card-signal-card").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "card must fill the panel content width; card=$card panel=$panel",
            kotlin.math.abs(card.width - (panel.width - 40.dp.value * density)) <= 2f,
        )

        val marker = compose.onNodeWithTag("review-record-marker-signal-card").fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(marker.width - 4.dp.value * density) <= 1f)

        val sourceLayout = compose.onNodeWithTag("review-record-source-signal-card").textLayout()
        assertEquals(2, sourceLayout.lineCount)
        assertTrue(sourceLayout.isLineEllipsized(1))
        assertEquals(expectedSourceColor, sourceLayout.layoutInput.style.color)

        val bodyLayout = compose.onNodeWithTag("review-record-body-signal-card").textLayout()
        assertEquals(4, bodyLayout.lineCount)
        assertTrue(bodyLayout.isLineEllipsized(3))
        val sourceBounds = compose.onNodeWithTag("review-record-source-signal-card")
            .fetchSemanticsNode().boundsInRoot
        val bodyBounds = compose.onNodeWithTag("review-record-body-signal-card")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("source must be rendered above the review body", sourceBounds.bottom <= bodyBounds.top)
        assertTrue(
            "source typography must be smaller than the review body",
            sourceLayout.layoutInput.style.fontSize.value < bodyLayout.layoutInput.style.fontSize.value,
        )
        compose.onNodeWithContentDescription("Изменить сигнал signal-card").assertDoesNotExist()
        compose.onNodeWithContentDescription("Удалить сигнал signal-card").assertDoesNotExist()
    }

    @Test
    fun reviewCardsFillEveryPanelModeAndOmitBlankSignalBody() {
        val cases = listOf(
            DpSize(360.dp, 800.dp) to "review-sheet",
            DpSize(800.dp, 1_280.dp) to "review-overlay",
            DpSize(1_280.dp, 800.dp) to "review-sidebar",
        )
        val activeCase = mutableStateOf(cases.first())
        compose.setContent {
            val (size, panelTag) = activeCase.value
            val state = reviewCardState("Короткий исходный текст", "")
            val reviewItems = state.reviewItems!!
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(
                    state = state.copy(
                        chapterId = panelTag,
                        reviewItems = reviewItems.copy(
                            edits = reviewItems.edits.map { it.copy(after = "Исправленный текст") },
                        ),
                    ),
                    callbacks = ReaderCallbacks(),
                    windowSize = size,
                )
            }
        }

        cases.forEachIndexed { index, case ->
            val (size, panelTag) = case
            if (index > 0) {
                compose.runOnIdle {
                    activeCase.value = case
                }
            }
            if (size.width < 1_000.dp) {
                compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()
            }

            val density = compose.activity.resources.displayMetrics.density
            val panel = compose.onNodeWithTag(panelTag).fetchSemanticsNode().boundsInRoot
            val signalCard = compose.onNodeWithTag("review-record-card-signal-card")
                .fetchSemanticsNode().boundsInRoot
            val editCard = compose.onNodeWithTag("review-record-card-edit-card")
                .fetchSemanticsNode().boundsInRoot
            val expectedWidth = panel.width - 40.dp.value * density

            assertTrue(kotlin.math.abs(signalCard.width - expectedWidth) <= 2f)
            assertTrue(kotlin.math.abs(editCard.width - expectedWidth) <= 2f)
            compose.onNodeWithTag("review-record-body-signal-card").assertDoesNotExist()
            compose.onNodeWithTag("review-record-body-edit-card").assertIsDisplayed()
            compose.onNodeWithTag("review-record-card-edit-card")
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.ContentDescription,
                        listOf("Правка"),
                    ),
                )

            if (size.width < 1_000.dp) {
                compose.onNodeWithContentDescription("Закрыть панель рецензии").performClick()
            }
        }
    }

    @Test
    fun reviewCardLongPressOffersEditAndDeleteWithoutTriggeringNavigation() {
        var editedSignal: ReaderSignalItem? = null
        var editedEdit: ReaderEditItem? = null
        var signalDeletes = 0
        var editDeletes = 0
        compose.setContent {
            PocketEditorTheme(darkTheme = false) {
                ReaderScreen(
                    state = reviewCardState("Привязанный текст", "Комментарий"),
                    callbacks = ReaderCallbacks(
                        onEditSignal = { editedSignal = it },
                        onEditEdit = { editedEdit = it },
                        onDeleteSignal = { signalDeletes++ },
                        onDeleteEdit = { editDeletes++ },
                    ),
                    windowSize = DpSize(360.dp, 800.dp),
                )
            }
        }
        compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()

        val card = compose.onNodeWithTag("review-record-card-signal-card")
        card.performTouchInput { longClick() }
        compose.onNodeWithText("Редактировать").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("Комментарий", editedSignal?.comment)
            assertEquals(0, signalDeletes)
            assertEquals(null, editedEdit)
            assertEquals(0, editDeletes)
        }

        card.performTouchInput { longClick() }
        compose.onNodeWithText("Удалить").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("Комментарий", editedSignal?.comment)
            assertEquals(1, signalDeletes)
            assertEquals(null, editedEdit)
            assertEquals(0, editDeletes)
        }

        val editCard = compose.onNodeWithTag("review-record-card-edit-card")
        editCard.performTouchInput { longClick() }
        compose.onNodeWithText("Редактировать").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("Комментарий", editedEdit?.after)
            assertEquals(1, signalDeletes)
            assertEquals(0, editDeletes)
        }

        editCard.performTouchInput { longClick() }
        compose.onNodeWithText("Удалить").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("Комментарий", editedEdit?.after)
            assertEquals(1, signalDeletes)
            assertEquals(1, editDeletes)
        }
    }

    @Test
    fun reviewCardMenuDoesNotMigrateToAnotherRecordAfterReorder() {
        val baseState = reviewCardState("Первый фрагмент", "Первый комментарий")
        val firstSignal = baseState.reviewItems!!.signals.single()
        val secondSignal = firstSignal.copy(
            id = "signal-second",
            selectedText = "Второй фрагмент",
            comment = "Второй комментарий",
        )
        val readerState = mutableStateOf(
            baseState.copy(
                reviewItems = baseState.reviewItems.copy(
                    signals = listOf(firstSignal, secondSignal),
                    edits = emptyList(),
                ),
            ),
        )
        var editedId: String? = null
        compose.setContent {
            PocketEditorTheme(darkTheme = false) {
                ReaderScreen(
                    state = readerState.value,
                    callbacks = ReaderCallbacks(onEditSignal = { editedId = it.id }),
                    windowSize = DpSize(360.dp, 800.dp),
                )
            }
        }
        compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()
        compose.onNodeWithTag("review-record-card-signal-card").performTouchInput { longClick() }
        compose.onNodeWithText("Редактировать").assertIsDisplayed()

        compose.runOnIdle {
            val reviewItems = readerState.value.reviewItems!!
            readerState.value = readerState.value.copy(
                reviewItems = reviewItems.copy(signals = reviewItems.signals.reversed()),
            )
        }

        compose.onAllNodesWithText("Редактировать").assertCountEquals(0)
        compose.runOnIdle { assertEquals(null, editedId) }

        compose.onNodeWithTag("review-record-card-signal-card").performTouchInput { longClick() }
        compose.onNodeWithText("Редактировать").performClick()
        compose.runOnIdle { assertEquals("signal-card", editedId) }
    }

    @Test
    fun reviewCardTapUsesReaderSearchTargetAndClosesOnlyOverlayPanels() {
        val cases = listOf(
            DpSize(360.dp, 800.dp) to "review-sheet",
            DpSize(800.dp, 1_280.dp) to "review-overlay",
            DpSize(1_280.dp, 800.dp) to "review-sidebar",
        )
        val activeCase = mutableStateOf(cases.first())
        compose.setContent {
            val (size, panelTag) = activeCase.value
            PocketEditorTheme(darkTheme = false) {
                ReaderScreen(
                    state = reviewCardState("Привязанный текст", "Комментарий").copy(chapterId = panelTag),
                    callbacks = ReaderCallbacks(),
                    windowSize = size,
                )
            }
        }

        cases.forEachIndexed { index, case ->
            val (size, panelTag) = case
            if (index > 0) {
                compose.runOnIdle {
                    activeCase.value = case
                }
            }
            if (size.width < 1_000.dp) {
                compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()
            }

            compose.onNodeWithTag("review-record-card-signal-card")
                .assertHasClickAction()
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.ContentDescription,
                        listOf("Сигнал: Предупреждение"),
                    ),
                )
                .performClick()

            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("reader-block-80", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            if (panelTag == "review-sidebar") {
                compose.onNodeWithTag(panelTag).assertIsDisplayed()
            } else {
                compose.onAllNodesWithTag(panelTag).assertCountEquals(0)
            }
        }
    }

    @Test
    fun repeatedReviewCardTapNavigatesBackToSameAnchorFromLandscapeSidebar() {
        compose.setContent {
            PocketEditorTheme(darkTheme = false) {
                ReaderScreen(
                    state = reviewCardState("Привязанный текст", "Комментарий"),
                    callbacks = ReaderCallbacks(),
                    windowSize = DpSize(1_280.dp, 800.dp),
                )
            }
        }

        val card = compose.onNodeWithTag("review-record-card-signal-card")
        card.performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("reader-block-80", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("reader-scroll", useUnmergedTree = true).performScrollToIndex(0)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("reader-block-0", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithTag("reader-block-80", useUnmergedTree = true).assertCountEquals(0)

        card.performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("reader-block-80", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("review-sidebar").assertIsDisplayed()
    }

    @Test
    fun reviewCardTapWithoutAnchorIsANoOpAndKeepsThePanelOpen() {
        compose.setContent {
            PocketEditorTheme(darkTheme = false) {
                ReaderScreen(
                    state = reviewCardStateWithoutAnchor("Привязанный текст", "Комментарий"),
                    callbacks = ReaderCallbacks(),
                    windowSize = DpSize(360.dp, 800.dp),
                )
            }
        }
        compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()

        compose.onNodeWithTag("review-record-card-signal-card").performClick()

        compose.onNodeWithTag("review-sheet").assertIsDisplayed()
        compose.onAllNodesWithTag("reader-block-80", useUnmergedTree = true).assertCountEquals(0)
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
            reviewUi = ReviewUiState(error = ReviewUiError("Сохранение элемента рецензии: не удалось выполнить действие.")),
            callbacks = ReaderCallbacks(onRetryReviewError = { retries++ }),
        )
        compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()

        compose.onNodeWithText("Сохранение элемента рецензии: не удалось выполнить действие.").assertIsDisplayed()
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
        physicalSmallestWidthDp: Int = 360,
    ) {
        val configuration = Configuration(compose.activity.resources.configuration).apply {
            smallestScreenWidthDp = physicalSmallestWidthDp
        }
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                PocketEditorTheme(darkTheme = true) {
                    ReaderScreen(sampleState(reviewEnabled), callbacks, reviewUi, windowSize = size)
                }
            }
        }
    }

    private fun setContentInLogicalRoot(
        size: DpSize,
        physicalSmallestWidthDp: Int = 360,
        content: @Composable () -> Unit,
    ) {
        val renderDensity = renderDensityFor(size)
        val configuration = Configuration(compose.activity.resources.configuration).apply {
            smallestScreenWidthDp = physicalSmallestWidthDp
        }
        compose.setContent {
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(renderDensity, 1f),
            ) {
                PocketEditorTheme(darkTheme = true) {
                    Box(Modifier.requiredSize(size)) {
                        content()
                    }
                }
            }
        }
    }

    private fun renderDensityFor(size: DpSize): Float {
        val metrics = compose.activity.resources.displayMetrics
        return minOf(
            metrics.widthPixels / size.width.value,
            metrics.heightPixels / size.height.value,
        )
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
            kotlin.math.abs(node.left - expectedLeft) <= 2f,
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

    private fun reviewCardState(source: String, comment: String) = multiBlockState().copy(
        reviewItems = ReaderReviewItems(
            signals = listOf(
                ReaderSignalItem(
                    id = "signal-card",
                    type = SignalType.WARNING,
                    selectedText = source,
                    comment = comment,
                    anchor = reviewAnchor(startByte = 8_000, endByte = 8_020),
                ),
            ),
            edits = listOf(
                ReaderEditItem(
                    id = "edit-card",
                    before = source,
                    after = comment,
                    anchor = reviewAnchor(startByte = 8_100, endByte = 8_120),
                ),
            ),
        ),
    )

    private fun reviewCardStateWithoutAnchor(source: String, comment: String) = multiBlockState().copy(
        reviewItems = ReaderReviewItems(
            signals = listOf(
                ReaderSignalItem(
                    id = "signal-card",
                    type = SignalType.WARNING,
                    selectedText = source,
                    comment = comment,
                    anchor = null,
                ),
            ),
            edits = emptyList(),
        ),
    )

    private fun reviewAnchor(startByte: Long, endByte: Long) = Anchor(
        sourceSha256 = "source",
        selectionSha256 = "selection",
        startByte = startByte,
        endByte = endByte,
        startLine = 1,
        endLine = 1,
        prefix = "",
        suffix = "",
    )

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        var results: List<TextLayoutResult> = emptyList()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            val captured = mutableListOf<TextLayoutResult>()
            check(action(captured))
            results = captured
        }
        return results.single()
    }

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
