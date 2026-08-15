package net.inkyquill.pocketeditor.review

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A composition-root-owned lock registry shared by every local and sync review writer. */
class ReviewMutationCoordinator {
    private val bookLocks = ConcurrentHashMap<String, ReadWriteMutex>()
    private val locks = ConcurrentHashMap<ReviewKey, Mutex>()

    suspend fun <T> withReview(bookId: String, path: String, block: suspend () -> T): T {
        require(bookId.isNotBlank() && path.isNotBlank())
        return withBookShared(bookId) { withReview(path, block) }
    }

    /**
     * Holds a shared per-book lease. Sync passes and reader mutations may overlap, while an
     * identity-changing cache replacement waits until every shared writer has left.
     */
    suspend fun <T> withBookShared(bookId: String, block: suspend BookMutationScope.() -> T): T {
        require(bookId.isNotBlank())
        val gate = bookLocks.computeIfAbsent(bookId) { ReadWriteMutex() }
        return gate.withShared { BookMutationScope(bookId).block() }
    }

    /** Holds the exclusive per-book lease used by filesystem-swap mutations. */
    suspend fun <T> withBookExclusive(bookId: String, block: suspend () -> T): T {
        require(bookId.isNotBlank())
        val gate = bookLocks.computeIfAbsent(bookId) { ReadWriteMutex() }
        return gate.withExclusive(block)
    }

    inner class BookMutationScope internal constructor(private val bookId: String) {
        suspend fun <T> withReview(path: String, block: suspend () -> T): T {
            require(path.isNotBlank())
            val key = ReviewKey(bookId, path)
            val mutex = locks.computeIfAbsent(key) { Mutex() }
            return mutex.withLock { block() }
        }
    }

    private data class ReviewKey(val bookId: String, val path: String)

    /** Fair coroutine read/write gate: writers close the turnstile before waiting for readers. */
    private class ReadWriteMutex {
        private val turnstile = Mutex()
        private val roomEmpty = Mutex()
        private val readerState = Mutex()
        private var readers = 0

        suspend fun <T> withShared(block: suspend () -> T): T {
            turnstile.withLock { }
            readerState.withLock {
                if (readers == 0) roomEmpty.lock()
                readers++
            }
            return try {
                block()
            } finally {
                readerState.withLock {
                    readers--
                    check(readers >= 0)
                    if (readers == 0) roomEmpty.unlock()
                }
            }
        }

        suspend fun <T> withExclusive(block: suspend () -> T): T {
            turnstile.lock()
            try {
                roomEmpty.lock()
            } catch (error: Throwable) {
                turnstile.unlock()
                throw error
            }
            return try {
                block()
            } finally {
                roomEmpty.unlock()
                turnstile.unlock()
            }
        }
    }
}
