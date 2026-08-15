package net.inkyquill.pocketeditor.reader

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.inkyquill.pocketeditor.database.ProgressiveLoadDao
import net.inkyquill.pocketeditor.load.ProgressiveLoadFileState

fun interface ChapterAvailability {
    fun observe(bookId: String, chapterId: String): Flow<ProgressiveLoadFileState?>
}

class RoomChapterAvailability(private val loads: ProgressiveLoadDao) : ChapterAvailability {
    override fun observe(bookId: String, chapterId: String): Flow<ProgressiveLoadFileState?> =
        loads.observeChapter(bookId, chapterId).map { it?.state }
}

sealed interface ReaderLoadState {
    data class Pending(val bookId: String, val chapterId: String, val title: String) : ReaderLoadState
    data class Ready(val state: ReaderState) : ReaderLoadState
}

fun ReaderLoadState.requireReady(): ReaderState = (this as ReaderLoadState.Ready).state
