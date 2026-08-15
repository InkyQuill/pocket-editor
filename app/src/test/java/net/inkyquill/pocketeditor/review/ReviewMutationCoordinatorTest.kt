package net.inkyquill.pocketeditor.review

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewMutationCoordinatorTest {
    @Test
    fun `same review key is serialized while different chapters remain independent`() = runTest {
        val coordinator = ReviewMutationCoordinator()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val maximum = AtomicInteger()

        val first = async {
            coordinator.withReview(BOOK_ID, REVIEW_PATH) {
                maximum.updateAndGet { maxOf(it, active.incrementAndGet()) }
                entered.complete(Unit)
                release.await()
                active.decrementAndGet()
            }
        }
        entered.await()
        val second = async {
            coordinator.withReview(BOOK_ID, REVIEW_PATH) {
                maximum.updateAndGet { maxOf(it, active.incrementAndGet()) }
                active.decrementAndGet()
            }
        }
        val other = async {
            coordinator.withReview(BOOK_ID, "other.review.json") {
                maximum.updateAndGet { maxOf(it, active.incrementAndGet()) }
                active.decrementAndGet()
            }
        }
        other.await()
        release.complete(Unit)
        first.await()
        second.await()

        assertEquals(2, maximum.get(), "different keys may overlap, same keys must not")
    }

    @Test
    fun `exclusive book mutation waits for shared sync and blocks later reader mutation`() = runTest {
        val coordinator = ReviewMutationCoordinator()
        val syncEntered = CompletableDeferred<Unit>()
        val releaseSync = CompletableDeferred<Unit>()
        val replacementEntered = CompletableDeferred<Unit>()
        val releaseReplacement = CompletableDeferred<Unit>()
        val readerEntered = CompletableDeferred<Unit>()

        val sync = async {
            coordinator.withBookShared(BOOK_ID) {
                syncEntered.complete(Unit)
                releaseSync.await()
            }
        }
        syncEntered.await()
        val replacement = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.withBookExclusive(BOOK_ID) {
                replacementEntered.complete(Unit)
                releaseReplacement.await()
            }
        }
        assertNull(withTimeoutOrNull(50) { replacementEntered.await() })

        releaseSync.complete(Unit)
        replacementEntered.await()
        val reader = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.withReview(BOOK_ID, REVIEW_PATH) { readerEntered.complete(Unit) }
        }
        assertNull(withTimeoutOrNull(50) { readerEntered.await() })

        releaseReplacement.complete(Unit)
        reader.await()
        sync.await()
        replacement.await()
        assertEquals(Unit, readerEntered.await())
    }

    @Test
    fun `cancelled shared release cannot strand an exclusive waiter`() = runTest {
        val coordinator = ReviewMutationCoordinator()
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val exclusiveEntered = CompletableDeferred<Unit>()

        val first = async {
            coordinator.withBookShared(BOOK_ID) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        val second = async {
            coordinator.withBookShared(BOOK_ID) {
                secondEntered.complete(Unit)
                releaseSecond.await()
            }
        }
        firstEntered.await()
        secondEntered.await()

        val readerState = coordinator.readerStateMutex(BOOK_ID)
        readerState.lock()
        val exclusive = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.withBookExclusive(BOOK_ID) { exclusiveEntered.complete(Unit) }
        }
        releaseFirst.complete(Unit)
        releaseSecond.complete(Unit)
        runCurrent()
        second.cancel()
        runCurrent()
        readerState.unlock()
        runCurrent()

        val entered = withTimeoutOrNull(100) { exclusiveEntered.await() }
        if (entered == null) exclusive.cancel()
        first.join()
        second.join()
        exclusive.join()

        assertTrue(second.isCancelled, "cancellation of the shared block must still propagate")
        assertEquals(Unit, entered, "all shared holders must release the exclusive book gate")
    }

    private fun ReviewMutationCoordinator.readerStateMutex(bookId: String): Mutex {
        val bookLocksField = javaClass.getDeclaredField("bookLocks").apply { isAccessible = true }
        val bookLocks = bookLocksField.get(this) as Map<*, *>
        val gate = requireNotNull(bookLocks[bookId])
        val readerStateField = gate.javaClass.getDeclaredField("readerState").apply { isAccessible = true }
        return readerStateField.get(gate) as Mutex
    }

    private companion object {
        const val BOOK_ID = "book"
        const val REVIEW_PATH = "chapter.md.review.json"
    }
}
