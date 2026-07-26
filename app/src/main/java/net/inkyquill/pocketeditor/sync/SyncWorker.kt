package net.inkyquill.pocketeditor.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

fun interface SyncBookRunner {
    suspend fun syncBook(bookId: String, remoteRootPath: String): SyncStatus
}

internal const val MAX_RETRY_ATTEMPTS = 5

enum class SyncWorkerOutcome { SUCCESS, TERMINAL, RETRY, STALE, NO_VALIDATED_NETWORK }

class SyncWorkerLogic(
    private val runner: SyncBookRunner,
    private val generations: RetryGenerationStore? = null,
    private val network: NetworkAvailability = NetworkAvailability { true },
) {
    suspend fun run(
        bookId: String,
        remoteRootPath: String,
        isRetry: Boolean = false,
        retryGeneration: Long = 0L,
    ): SyncWorkerOutcome {
        if (isRetry && generations?.isCurrent(bookId, retryGeneration) != true) return SyncWorkerOutcome.STALE
        if (!network.hasValidatedInternet()) return SyncWorkerOutcome.NO_VALIDATED_NETWORK
        return when (runner.syncBook(bookId, remoteRootPath)) {
            SyncStatus.Saved -> SyncWorkerOutcome.SUCCESS
            SyncStatus.WaitingToSync -> SyncWorkerOutcome.RETRY
            else -> SyncWorkerOutcome.TERMINAL
        }
    }
}

class SyncWorkerCompletion(
    private val queue: SyncWorkQueue,
    private val generations: RetryGenerationStore,
) {
    fun complete(
        bookId: String,
        remoteRootPath: String,
        outcome: SyncWorkerOutcome,
        retryAttempt: Int,
        retryGeneration: Long = generations.current(bookId),
    ) {
        require(retryAttempt >= 0)
        when (outcome) {
            SyncWorkerOutcome.SUCCESS,
            SyncWorkerOutcome.TERMINAL,
            -> if (generations.invalidateIfCurrent(bookId, retryGeneration)) {
                queue.cancel("sync-retry-$bookId")
            }
            SyncWorkerOutcome.STALE -> Unit
            SyncWorkerOutcome.RETRY,
            SyncWorkerOutcome.NO_VALIDATED_NETWORK,
            -> if (retryAttempt < MAX_RETRY_ATTEMPTS) {
                SyncRetryLauncher(queue, generations).launch(
                    bookId,
                    remoteRootPath,
                    retryAttempt + 1,
                    retryGeneration,
                )
            } else if (generations.invalidateIfCurrent(bookId, retryGeneration)) {
                queue.cancel("sync-retry-$bookId")
            }
        }
    }
}

class SyncWorker internal constructor(
    appContext: Context,
    parameters: WorkerParameters,
    private val logic: SyncWorkerLogic,
    private val queue: SyncWorkQueue,
    private val generations: RetryGenerationStore,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val bookId = inputData.getString(BOOK_ID_KEY) ?: return Result.failure()
        val remoteRootPath = inputData.getString(REMOTE_ROOT_PATH_KEY) ?: return Result.failure()
        val retryAttempt = inputData.getInt(RETRY_ATTEMPT_KEY, 0)
        val retryGeneration = inputData.getLong(RETRY_GENERATION_KEY, 0L)
        val isRetry = inputData.getBoolean(IS_RETRY_KEY, false)
        val outcome = logic.run(bookId, remoteRootPath, isRetry, retryGeneration)
        SyncWorkerCompletion(queue, generations).complete(
            bookId,
            remoteRootPath,
            outcome,
            retryAttempt,
            retryGeneration,
        )
        return Result.success()
    }

    companion object {
        const val BOOK_ID_KEY = "book_id"
        const val REMOTE_ROOT_PATH_KEY = "remote_root_path"
        const val TRIGGER_KEY = "trigger"
        const val RETRY_ATTEMPT_KEY = "retry_attempt"
        const val RETRY_GENERATION_KEY = "retry_generation"
        const val IS_RETRY_KEY = "is_retry"
    }
}

class SyncDebounceWorker(
    appContext: Context,
    parameters: WorkerParameters,
    private val queue: SyncWorkQueue,
    private val generations: RetryGenerationStore,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val bookId = inputData.getString(SyncWorker.BOOK_ID_KEY) ?: return Result.failure()
        val remoteRootPath = inputData.getString(SyncWorker.REMOTE_ROOT_PATH_KEY) ?: return Result.failure()
        val trigger = inputData.getString(SyncWorker.TRIGGER_KEY)
            ?.let { runCatching { SyncTrigger.valueOf(it) }.getOrNull() }
            ?: return Result.failure()
        val retryAttempt = inputData.getInt(SyncWorker.RETRY_ATTEMPT_KEY, 0)
        val retryGeneration = inputData.getLong(SyncWorker.RETRY_GENERATION_KEY, 0L)
        val isRetry = inputData.getBoolean(SyncWorker.IS_RETRY_KEY, false)
        if (isRetry) {
            SyncRetryLauncher(queue, generations).appendIfCurrent(
                bookId,
                remoteRootPath,
                retryAttempt,
                retryGeneration,
            )
        } else if (generations.isCurrent(bookId, retryGeneration)) {
            queue.enqueue(
                SyncScheduler.activeRequest(
                    bookId,
                    remoteRootPath,
                    trigger,
                    retryAttempt,
                    retryGeneration,
                ),
            )
        }
        return Result.success()
    }
}

class SyncWorkerFactory(
    private val runner: SyncBookRunner,
    internal val syncWorkQueue: SyncWorkQueue,
    internal val retryGenerationStore: RetryGenerationStore,
    private val network: NetworkAvailability = NetworkAvailability { true },
) : WorkerFactory() {

    internal fun supports(workerClassName: String): Boolean =
        workerClassName == SyncWorker::class.java.name || workerClassName == SyncDebounceWorker::class.java.name

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        SyncWorker::class.java.name -> SyncWorker(
            appContext,
            workerParameters,
            SyncWorkerLogic(runner, retryGenerationStore, network),
            syncWorkQueue,
            retryGenerationStore,
        )
        SyncDebounceWorker::class.java.name -> SyncDebounceWorker(
            appContext,
            workerParameters,
            syncWorkQueue,
            retryGenerationStore,
        )
        else -> null
    }
}
