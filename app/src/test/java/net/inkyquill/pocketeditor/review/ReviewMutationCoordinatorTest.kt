package net.inkyquill.pocketeditor.review

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReviewMutationCoordinatorTest {
    @Test
    fun `same review key is serialized while different chapters remain independent`() = runBlocking {
        val coordinator = ReviewMutationCoordinator()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val maximum = AtomicInteger()

        val first = async {
            coordinator.withReview("book", "chapter.review.json") {
                maximum.updateAndGet { maxOf(it, active.incrementAndGet()) }
                entered.complete(Unit)
                release.await()
                active.decrementAndGet()
            }
        }
        entered.await()
        val second = async {
            coordinator.withReview("book", "chapter.review.json") {
                maximum.updateAndGet { maxOf(it, active.incrementAndGet()) }
                active.decrementAndGet()
            }
        }
        val other = async {
            coordinator.withReview("book", "other.review.json") {
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
}
