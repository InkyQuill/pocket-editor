package net.inkyquill.pocketeditor.ui.reader

import net.inkyquill.pocketeditor.reader.ReaderSyncState

internal data class ReaderTopBarStatus(
    val chapterLoading: Boolean,
    val syncState: ReaderSyncState?,
)

internal fun readyReaderTopBarStatus(syncState: ReaderSyncState): ReaderTopBarStatus = ReaderTopBarStatus(
    chapterLoading = false,
    syncState = syncState,
)

internal fun pendingReaderTopBarStatus(): ReaderTopBarStatus = ReaderTopBarStatus(
    chapterLoading = true,
    syncState = null,
)
