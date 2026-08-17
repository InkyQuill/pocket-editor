package net.inkyquill.pocketeditor.ui.reader

import net.inkyquill.pocketeditor.reader.ReaderSyncState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.api.Test

class ReaderTopBarStatusTest {
    @ParameterizedTest
    @MethodSource("readyStatuses")
    fun `ready chapter status exposes every sync state without a text row`(
        syncState: ReaderSyncState,
    ) {
        assertEquals(
            ReaderTopBarStatus(
                chapterLoading = false,
                syncState = syncState,
            ),
            readyReaderTopBarStatus(syncState),
        )
    }

    @Test
    fun `pending chapter exposes only a compact loading indicator`() {
        assertEquals(
            ReaderTopBarStatus(
                chapterLoading = true,
                syncState = null,
            ),
            pendingReaderTopBarStatus(),
        )
    }

    companion object {
        @JvmStatic
        fun readyStatuses() = listOf(
            Arguments.of(ReaderSyncState.SAVED),
            Arguments.of(ReaderSyncState.WAITING_TO_SYNC),
            Arguments.of(ReaderSyncState.SYNCING),
            Arguments.of(ReaderSyncState.SIGN_IN_REQUIRED),
            Arguments.of(ReaderSyncState.ACTION_REQUIRED),
        )
    }
}
