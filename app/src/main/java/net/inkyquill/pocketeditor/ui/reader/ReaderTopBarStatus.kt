package net.inkyquill.pocketeditor.ui.reader

import androidx.annotation.StringRes
import net.inkyquill.pocketeditor.R
import net.inkyquill.pocketeditor.reader.ReaderSyncState

internal data class ReaderTopBarStatus(
    @param:StringRes val localLabel: Int,
    @param:StringRes val remoteLabel: Int?,
    @param:StringRes val syncActionLabel: Int?,
)

internal fun readyReaderTopBarStatus(syncState: ReaderSyncState): ReaderTopBarStatus = ReaderTopBarStatus(
    localLabel = R.string.reader_chapter_on_device,
    remoteLabel = when (syncState) {
        ReaderSyncState.SAVED -> R.string.yandex_sync_saved
        ReaderSyncState.WAITING_TO_SYNC -> R.string.yandex_sync_waiting
        ReaderSyncState.SYNCING -> R.string.yandex_syncing
        ReaderSyncState.SIGN_IN_REQUIRED -> R.string.yandex_sync_sign_in
        ReaderSyncState.ACTION_REQUIRED -> R.string.yandex_sync_action_required
    },
    syncActionLabel = when (syncState) {
        ReaderSyncState.WAITING_TO_SYNC -> R.string.sync_now
        ReaderSyncState.SIGN_IN_REQUIRED,
        ReaderSyncState.ACTION_REQUIRED,
        -> R.string.retry_sync
        ReaderSyncState.SAVED,
        ReaderSyncState.SYNCING,
        -> null
    },
)

internal fun pendingReaderTopBarStatus(): ReaderTopBarStatus = ReaderTopBarStatus(
    localLabel = R.string.reader_chapter_loading,
    remoteLabel = null,
    syncActionLabel = null,
)
