package net.inkyquill.pocketeditor.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ReadingPositionCoordinator(
    private val applicationScope: CoroutineScope,
    private val save: suspend (String, String, Int, Int) -> Unit,
    private val debounceMillis: Long = 450,
) {
    private data class Entry(
        val bookId: String,
        val chapterId: String,
        val position: ReaderPosition,
        val generation: Long,
    )

    private val stateLock = Any()
    private val writeMutex = Mutex()
    private val latestByBook = mutableMapOf<String, Entry>()
    private val debounceByBook = mutableMapOf<String, Job>()
    private val persistedGeneration = mutableMapOf<String, Long>()

    fun observed(bookId: String, chapterId: String, position: ReaderPosition) {
        val entry = synchronized(stateLock) {
            val generation = (latestByBook[bookId]?.generation ?: 0L) + 1L
            Entry(bookId, chapterId, position, generation).also { latestByBook[bookId] = it }
        }
        synchronized(stateLock) {
            debounceByBook.remove(bookId)?.cancel()
            debounceByBook[bookId] = applicationScope.launch {
                delay(debounceMillis)
                persistIfCurrent(entry)
            }
        }
    }

    suspend fun flush(bookId: String, chapterId: String) {
        val entry = synchronized(stateLock) {
            debounceByBook.remove(bookId)?.cancel()
            latestByBook[bookId]?.takeIf { it.chapterId == chapterId }
        } ?: return
        persistIfCurrent(entry)
    }

    fun requestFlush(bookId: String, chapterId: String): Job = applicationScope.launch {
        flush(bookId, chapterId)
    }

    private suspend fun persistIfCurrent(entry: Entry) {
        writeMutex.withLock {
            val current = synchronized(stateLock) { latestByBook[entry.bookId] }
            if (current != entry) return
            if ((persistedGeneration[entry.bookId] ?: 0L) >= entry.generation) return
            save(
                entry.bookId,
                entry.chapterId,
                entry.position.blockIndex,
                entry.position.byteOffset,
            )
            persistedGeneration[entry.bookId] = entry.generation
        }
    }
}
