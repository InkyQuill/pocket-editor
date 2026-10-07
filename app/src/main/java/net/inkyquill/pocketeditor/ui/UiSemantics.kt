package net.inkyquill.pocketeditor.ui

import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import net.inkyquill.pocketeditor.reader.ReaderSyncState
import net.inkyquill.pocketeditor.sync.ConflictChoice
import net.inkyquill.pocketeditor.source.SyncIssue
import net.inkyquill.pocketeditor.ui.review.NoteSaveStatus
import net.inkyquill.pocketeditor.ui.review.ReviewErrorCode

/** Stable UI state contracts. Localized accessibility descriptions remain independent. */
val ReaderSyncStateKey = SemanticsPropertyKey<ReaderSyncState>("ReaderSyncState")
var SemanticsPropertyReceiver.readerSyncState by ReaderSyncStateKey
val SyncIssueKey = SemanticsPropertyKey<SyncIssue>("SyncIssue")
var SemanticsPropertyReceiver.syncIssueCode by SyncIssueKey
val NoteSaveStatusKey = SemanticsPropertyKey<NoteSaveStatus>("NoteSaveStatus")
var SemanticsPropertyReceiver.noteSaveStatus by NoteSaveStatusKey
val ReviewErrorKey = SemanticsPropertyKey<ReviewErrorCode>("ReviewError")
var SemanticsPropertyReceiver.reviewErrorCode by ReviewErrorKey
val ConflictKey = SemanticsPropertyKey<String>("ConflictKey")
var SemanticsPropertyReceiver.conflictKey by ConflictKey
val ConflictChoiceKey = SemanticsPropertyKey<ConflictChoice>("ConflictChoice")
var SemanticsPropertyReceiver.conflictChoice by ConflictChoiceKey
val FolderSelectionKey = SemanticsPropertyKey<Boolean>("FolderSelectionInProgress")
var SemanticsPropertyReceiver.folderSelectionInProgress by FolderSelectionKey

val SourceErrorKey = SemanticsPropertyKey<net.inkyquill.pocketeditor.ui.books.SourceErrorCode>("SourceError")
var SemanticsPropertyReceiver.sourceErrorCode by SourceErrorKey
