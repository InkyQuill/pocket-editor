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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import net.inkyquill.pocketeditor.markdown.BlockKind
import net.inkyquill.pocketeditor.markdown.RawRange
import net.inkyquill.pocketeditor.reader.ReaderBlock
import net.inkyquill.pocketeditor.reader.ReaderComment
import net.inkyquill.pocketeditor.reader.ReaderDocument
import net.inkyquill.pocketeditor.reader.ReaderRun
import net.inkyquill.pocketeditor.reader.ReaderRunKind
import net.inkyquill.pocketeditor.reader.ReaderState
import net.inkyquill.pocketeditor.reader.ReaderSyncState
import net.inkyquill.pocketeditor.review.SignalType
import net.inkyquill.pocketeditor.sync.ConflictChoice
import net.inkyquill.pocketeditor.ui.reader.ReaderCallbacks
import net.inkyquill.pocketeditor.ui.reader.ReaderScreen
import net.inkyquill.pocketeditor.ui.review.ConflictCard
import net.inkyquill.pocketeditor.ui.review.NoteSaveStatus
import net.inkyquill.pocketeditor.ui.review.ReviewDraft
import net.inkyquill.pocketeditor.ui.review.ReviewDraftSession
import net.inkyquill.pocketeditor.ui.review.ReviewDraftStateMachine
import net.inkyquill.pocketeditor.ui.review.ReviewSelection
import net.inkyquill.pocketeditor.ui.review.ReviewUiState
import net.inkyquill.pocketeditor.ui.review.ReviewUiError
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
    fun signalComposerChangesColorAcceptsEmptyCommentAndNeedsExplicitSaveOrCancel() {
        var editSelections = 0
        var saves = 0
        val selection = ReviewSelection(0, 0, 9, RawRange(0, 9), "Canonical")
        val reviewUi = mutableStateOf(
            ReviewUiState(draftSession = ReviewDraftStateMachine.select(selection)),
        )
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(
                    sampleState(true),
                    ReaderCallbacks(onEditChosen = { editSelections++ }, onSaveDraft = { saves++ }),
                    reviewUi.value,
                    windowSize = DpSize(360.dp, 800.dp),
                )
            }
        }
        compose.onNodeWithContentDescription("Open review panel").performClick()

        val density = compose.activity.resources.displayMetrics.density
        listOf("Note", "Warning", "Change needed", "Review", "Edit").forEach { label ->
            val action = compose.onNodeWithContentDescription(label)
            action.assertIsDisplayed()
            val bounds = action.fetchSemanticsNode().boundsInRoot
            assertTrue("$label action keeps a 44dp touch target", bounds.width / density >= 44f)
            assertTrue("$label action keeps a 44dp touch target", bounds.height / density >= 44f)
        }
        compose.onAllNodesWithText("Edit").assertCountEquals(0)
        compose.onNodeWithContentDescription("Edit").performClick()
        assertEquals(1, editSelections)

        val draft = ReviewDraft.Signal(
            null,
            selection,
            SignalType.NOTE,
            "",
        )
        compose.runOnIdle { reviewUi.value = ReviewUiState(draftSession = ReviewDraftSession(draft)) }

        compose.onNodeWithTag("signal-review").performClick()
        compose.onNodeWithContentDescription("Signal comment, optional").performTextInput("Check this")
        compose.onNodeWithTag("save-draft").performScrollTo().assertIsDisplayed().performClick()

        assertEquals(1, saves)
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
                listOf(ReaderRun("Canonical sentence.", ReaderRunKind.CANONICAL)),
            ),
        ),
    )

    private object InstrumentationKeys {
        fun back() {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
    }
}
