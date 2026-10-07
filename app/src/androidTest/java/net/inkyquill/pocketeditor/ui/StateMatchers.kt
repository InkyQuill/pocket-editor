package net.inkyquill.pocketeditor.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import net.inkyquill.pocketeditor.reader.ReaderSyncState
import net.inkyquill.pocketeditor.sync.ConflictChoice
import net.inkyquill.pocketeditor.ui.review.NoteSaveStatus
import net.inkyquill.pocketeditor.ui.review.ReviewErrorCode

fun hasSyncState(state: ReaderSyncState) = SemanticsMatcher.expectValue(ReaderSyncStateKey, state)
fun hasNoteSaveStatus(state: NoteSaveStatus) = SemanticsMatcher.expectValue(NoteSaveStatusKey, state)
fun hasConflictChoice(key: String, choice: ConflictChoice) =
    SemanticsMatcher.expectValue(ConflictKey, key) and SemanticsMatcher.expectValue(ConflictChoiceKey, choice)
fun hasReviewError(code: ReviewErrorCode) = SemanticsMatcher.expectValue(ReviewErrorKey, code)
fun SemanticsNodeInteraction.assertHasAccessibleDescription() = assert(
    SemanticsMatcher("has a non-empty accessibility description") {
        it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.any(String::isNotBlank)
    },
)
fun SemanticsNodeInteraction.assertNoVisibleStatusText() = assert(
    SemanticsMatcher("status uses an icon instead of visible text") {
        it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.isEmpty()
    },
)
