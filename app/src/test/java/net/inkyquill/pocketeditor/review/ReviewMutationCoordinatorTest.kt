package net.inkyquill.pocketeditor.review

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

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

    private companion object {
        const val BOOK_ID = "book"
        const val REVIEW_PATH = "chapter.md.review.json"
    }
}
