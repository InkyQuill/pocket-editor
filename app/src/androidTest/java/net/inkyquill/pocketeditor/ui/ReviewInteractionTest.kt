package net.inkyquill.pocketeditor.ui

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
        compose.onNodeWithContentDescription("Warning signal").assertIsDisplayed()
        compose.onNodeWithContentDescription("Deleted source text: removed", substring = true)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetSelection))
        compose.onNodeWithContentDescription("Added replacement text: added", substring = true).assertIsDisplayed()
        compose.onNodeWithText("First comment").assertIsDisplayed()
        compose.onNodeWithText("Second comment").assertIsDisplayed()

        compose.onNodeWithContentDescription("Review mode on").performClick()
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
                        onSaveDraft = { saves++ },
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
        listOf("Note", "Warning", "Change needed", "Review", "Edit").forEach { label ->
            val action = compose.onNodeWithContentDescription(label)
            action.assertIsDisplayed()
            val bounds = action.fetchSemanticsNode().boundsInRoot
            assertTrue("$label action is adjacent to the selected reader block", bounds.top >= selectedBlockBounds.top)
            assertTrue("$label action keeps a 44dp touch target", bounds.width / density >= 44f)
            assertTrue("$label action keeps a 44dp touch target", bounds.height / density >= 44f)
        }
        compose.onNodeWithTag("selection-flyout", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("Edit").assertCountEquals(0)
        compose.onNodeWithContentDescription("Edit").performClick()
        assertEquals(1, editSelections)

        val draft = ReviewDraft.Signal(
            null,
            ReviewSelection(0, 0, 9, RawRange(0, 9), "Canonical"),
            SignalType.NOTE,
            "",
        )
        compose.runOnIdle { reviewUi.value = ReviewUiState(draftSession = ReviewDraftSession(draft)) }
        compose.onNodeWithContentDescription("Open review panel").performClick()

        compose.onNodeWithTag("signal-review").performClick()
        compose.onNodeWithContentDescription("Signal comment, optional").performTextInput("Check this")
        compose.onNodeWithTag("save-draft").performScrollTo().assertIsDisplayed().performClick()

        assertEquals(1, saves)
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
        compose.onNodeWithContentDescription("Note").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            controller.state.value.draftSession.draft is ReviewDraft.Signal
        }
        compose.onNodeWithContentDescription("Open review panel").performClick()
        compose.onNodeWithContentDescription("Signal comment, optional").performTextInput("Keep this draft")
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
        compose.onNodeWithContentDescription("Signal comment, optional").assertTextContains("Keep this draft")
        compose.runOnIdle {
            assertEquals(
                "Keep this draft",
                (controller.state.value.draftSession.draft as? ReviewDraft.Signal)?.comment,
            )
        }

        compose.runOnIdle { document.value = multiBlockState().document }
        compose.onNodeWithTag("reader-text-0", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Signal comment, optional").assertTextContains("Keep this draft")
    }

    @Test
    fun dirtyEditSurvivesBackAndOutsideDismissUntilExplicitCancel() {
        var cancels = 0
        val draft = ReviewDraft.Edit(
            null,
            ReviewSelection(0, 0, 9, RawRange(0, 9), "Canonical"),
            "Replacement",
        )
        setReader(
            reviewEnabled = true,
            reviewUi = ReviewUiState(draftSession = ReviewDraftSession(draft)),
            callbacks = ReaderCallbacks(onCancelDraft = { cancels++ }),
            size = DpSize(800.dp, 1280.dp),
        )
        compose.onNodeWithContentDescription("Expand review panel").performClick()

        compose.onNodeWithTag("review-scrim").performClick()
        compose.onNodeWithTag("edit-composer").assertIsDisplayed()

        InstrumentationKeys.back()
        compose.onNodeWithTag("edit-composer").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancels)
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
        compose.onNodeWithContentDescription("Open review panel").performClick()
        compose.onNodeWithContentDescription("Signal comment, optional").assertTextContains("Unfinished")

        compose.runOnIdle { size.value = DpSize(800.dp, 1280.dp) }

        compose.onNodeWithContentDescription("Signal comment, optional").assertTextContains("Unfinished")
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
        compose.onNodeWithContentDescription("Open review panel").performClick()

        compose.onNodeWithTag("chapter-note").performTextClearance()
        compose.onNodeWithTag("chapter-note").performTextInput("New note")
        compose.onAllNodesWithText("Waiting to sync").assertCountEquals(1)
        compose.onNodeWithContentDescription("Chapter note: Waiting to sync").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()

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
        compose.onNodeWithContentDescription("Open review panel").performClick()

        compose.onNodeWithContentDescription("Keep mine for signal-1, not selected").performClick()
        compose.onNodeWithContentDescription("Keep Yandex Disk for signal-1, not selected").performClick()

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
        compose.onNodeWithContentDescription("Open review panel").performClick()

        compose.onNodeWithContentDescription("Keep mine for signal-1, selected").assertIsSelected()
        compose.onNodeWithContentDescription("Keep Yandex Disk for signal-1, not selected").assertIsDisplayed()
        compose.onNodeWithText("Selected", substring = true).assertIsDisplayed()
    }

    @Test
    fun reviewFailureKeepsActionableRetryInsideReviewPanel() {
        var retries = 0
        setReader(
            reviewEnabled = true,
            reviewUi = ReviewUiState(error = ReviewUiError("Save review item failed: disk full")),
            callbacks = ReaderCallbacks(onRetryReviewError = { retries++ }),
        )
        compose.onNodeWithContentDescription("Open review panel").performClick()

        compose.onNodeWithText("Save review item failed", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()

        assertEquals(1, retries)
    }

    @Test
    fun chapterNoteFlushesOnlyAfterARealFocusedToUnfocusedTransition() {
        var focusLosses = 0
        setReader(
            reviewEnabled = true,
            callbacks = ReaderCallbacks(onChapterNoteFocusLost = { focusLosses++ }),
        )
        compose.onNodeWithContentDescription("Open review panel").performClick()
        compose.waitForIdle()
        assertEquals(0, focusLosses)

        compose.onNodeWithTag("chapter-note").performClick()
        compose.onNodeWithContentDescription("Close review panel").performClick()
        compose.waitForIdle()

        assertEquals(1, focusLosses)
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
