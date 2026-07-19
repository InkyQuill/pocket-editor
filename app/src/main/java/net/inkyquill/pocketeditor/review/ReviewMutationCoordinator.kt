package net.inkyquill.pocketeditor.review

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A composition-root-owned lock registry shared by every local and sync review writer. */
class ReviewMutationCoordinator {
    private val locks = ConcurrentHashMap<ReviewKey, Mutex>()

    suspend fun <T> withReview(bookId: String, path: String, block: suspend () -> T): T {
        require(bookId.isNotBlank() && path.isNotBlank())
        val key = ReviewKey(bookId, path)
        val mutex = locks.computeIfAbsent(key) { Mutex() }
        return mutex.withLock { block() }
    }

    private data class ReviewKey(val bookId: String, val path: String)
}
