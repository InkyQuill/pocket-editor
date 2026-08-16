package net.inkyquill.pocketeditor.ui.reader

import net.inkyquill.pocketeditor.R
import net.inkyquill.pocketeditor.reader.ReaderSyncState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.api.Test

class ReaderTopBarStatusTest {
    @ParameterizedTest
    @MethodSource("readyStatuses")
    fun `ready chapter status keeps local availability separate from every Yandex state`(
        syncState: ReaderSyncState,
        remoteLabel: Int,
        actionLabel: Int?,
    ) {
        assertEquals(
            ReaderTopBarStatus(
                localLabel = R.string.reader_chapter_on_device,
                remoteLabel = remoteLabel,
                syncActionLabel = actionLabel,
            ),
            readyReaderTopBarStatus(syncState),
        )
    }

    @Test
    fun `pending chapter reports only its local load state`() {
        assertEquals(
            ReaderTopBarStatus(
                localLabel = R.string.reader_chapter_loading,
                remoteLabel = null,
                syncActionLabel = null,
            ),
            pendingReaderTopBarStatus(),
        )
    }

    companion object {
        @JvmStatic
        fun readyStatuses() = listOf(
            Arguments.of(ReaderSyncState.SAVED, R.string.yandex_sync_saved, null),
            Arguments.of(ReaderSyncState.WAITING_TO_SYNC, R.string.yandex_sync_waiting, R.string.sync_now),
            Arguments.of(ReaderSyncState.SYNCING, R.string.yandex_syncing, null),
            Arguments.of(ReaderSyncState.SIGN_IN_REQUIRED, R.string.yandex_sync_sign_in, R.string.retry_sync),
            Arguments.of(ReaderSyncState.ACTION_REQUIRED, R.string.yandex_sync_action_required, R.string.retry_sync),
        )
    }
}
