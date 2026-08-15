package net.inkyquill.pocketeditor.load

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import net.inkyquill.pocketeditor.sync.NetworkAvailability

fun interface ProgressiveLoadRunner {
    suspend fun runOne(bookId: String, generation: Long): ProgressiveLoadRunResult
}

sealed interface ProgressiveLoadRunResult {
    data object FileCached : ProgressiveLoadRunResult
    data object Complete : ProgressiveLoadRunResult
    data class Retry(val retryAt: Instant) : ProgressiveLoadRunResult
    data object SignInRequired : ProgressiveLoadRunResult
    data object ActionRequired : ProgressiveLoadRunResult
    data object Stale : ProgressiveLoadRunResult
    data object NoValidatedNetwork : ProgressiveLoadRunResult
}

class ProgressiveLoadWorkerLogic(
    private val runner: ProgressiveLoadRunner,
    private val scheduleStore: ProgressiveLoadScheduleStore,
    private val network: NetworkAvailability,
) {
    suspend fun run(bookId: String, generation: Long): ProgressiveLoadRunResult {
        if (scheduleStore.admit(bookId, generation) == GenerationAdmission.STALE) {
            return ProgressiveLoadRunResult.Stale
        }
        if (!network.hasValidatedInternet()) {
            return ProgressiveLoadRunResult.NoValidatedNetwork
        }
        return runner.runOne(bookId, generation)
    }
}

class ProgressiveLoadWorkerCompletion(
    private val scheduler: ProgressiveLoadScheduler,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun complete(
        bookId: String,
        generation: Long,
        result: ProgressiveLoadRunResult,
    ) {
        when (result) {
            ProgressiveLoadRunResult.FileCached ->
                scheduler.enqueueCurrent(bookId, generation, Duration.ZERO)
            is ProgressiveLoadRunResult.Retry -> scheduler.enqueueCurrent(
                bookId,
                generation,
                Duration.between(now(), result.retryAt).coerceAtLeast(Duration.ZERO),
            )
            ProgressiveLoadRunResult.NoValidatedNetwork -> scheduler.enqueueCurrent(
                bookId,
                generation,
                NO_VALIDATED_NETWORK_DELAY,
            )
            ProgressiveLoadRunResult.Complete,
            ProgressiveLoadRunResult.SignInRequired,
            ProgressiveLoadRunResult.ActionRequired,
            ProgressiveLoadRunResult.Stale,
            -> Unit
        }
    }

    private companion object {
        val NO_VALIDATED_NETWORK_DELAY: Duration = Duration.ofSeconds(30)
    }
}

class ProgressiveLoadWorker internal constructor(
    appContext: Context,
    parameters: WorkerParameters,
    private val logic: ProgressiveLoadWorkerLogic,
    private val completion: ProgressiveLoadWorkerCompletion,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val bookId = inputData.getString(BOOK_ID_KEY) ?: return Result.failure()
        val generation = inputData.getLong(GENERATION_KEY, MISSING_GENERATION)
        if (generation == MISSING_GENERATION) return Result.failure()
        val result = logic.run(bookId, generation)
        return try {
            completion.complete(bookId, generation, result)
            Result.success()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val BOOK_ID_KEY = "book_id"
        const val GENERATION_KEY = "generation"
        private const val MISSING_GENERATION = Long.MIN_VALUE
    }
}

class ProgressiveLoadWorkerFactory(
    private val runner: ProgressiveLoadRunner,
    private val scheduler: ProgressiveLoadScheduler,
    private val scheduleStore: ProgressiveLoadScheduleStore,
    private val network: NetworkAvailability,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = if (workerClassName == ProgressiveLoadWorker::class.java.name) {
        ProgressiveLoadWorker(
            appContext,
            workerParameters,
            ProgressiveLoadWorkerLogic(runner, scheduleStore, network),
            ProgressiveLoadWorkerCompletion(scheduler),
        )
    } else {
        null
    }
}
