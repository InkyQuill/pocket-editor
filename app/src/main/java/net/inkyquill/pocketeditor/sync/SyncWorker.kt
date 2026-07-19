package net.inkyquill.pocketeditor.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.WorkManager

fun interface SyncBookRunner {
    suspend fun syncBook(bookId: String, remoteRootPath: String): SyncStatus
}

enum class SyncWorkerOutcome { SUCCESS, RETRY }

class SyncWorkerLogic(private val runner: SyncBookRunner) {
    suspend fun run(bookId: String, remoteRootPath: String): SyncWorkerOutcome =
        if (runner.syncBook(bookId, remoteRootPath) == SyncStatus.WaitingToSync) {
            SyncWorkerOutcome.RETRY
        } else {
            SyncWorkerOutcome.SUCCESS
        }
}

class SyncWorker internal constructor(
    appContext: Context,
    parameters: WorkerParameters,
    private val logic: SyncWorkerLogic,
    private val queue: SyncWorkQueue,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val bookId = inputData.getString(BOOK_ID_KEY) ?: return Result.failure()
        val remoteRootPath = inputData.getString(REMOTE_ROOT_PATH_KEY) ?: return Result.failure()
        val retryAttempt = inputData.getInt(RETRY_ATTEMPT_KEY, 0)
        return when (logic.run(bookId, remoteRootPath)) {
            SyncWorkerOutcome.SUCCESS -> Result.success()
            SyncWorkerOutcome.RETRY -> {
                SyncRetryLauncher(queue).launch(bookId, remoteRootPath, retryAttempt + 1)
                Result.success()
            }
        }
    }

    companion object {
        const val BOOK_ID_KEY = "book_id"
        const val REMOTE_ROOT_PATH_KEY = "remote_root_path"
        const val TRIGGER_KEY = "trigger"
        const val RETRY_ATTEMPT_KEY = "retry_attempt"
    }
}

class SyncDebounceWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val bookId = inputData.getString(SyncWorker.BOOK_ID_KEY) ?: return Result.failure()
        val remoteRootPath = inputData.getString(SyncWorker.REMOTE_ROOT_PATH_KEY) ?: return Result.failure()
        val trigger = inputData.getString(SyncWorker.TRIGGER_KEY)
            ?.let { runCatching { SyncTrigger.valueOf(it) }.getOrNull() }
            ?: return Result.failure()
        val retryAttempt = inputData.getInt(SyncWorker.RETRY_ATTEMPT_KEY, 0)
        val queue = WorkManagerSyncWorkQueue(WorkManager.getInstance(applicationContext))
        queue.enqueue(SyncScheduler.activeRequest(bookId, remoteRootPath, trigger, retryAttempt))
        return Result.success()
    }
}

class SyncWorkerFactory(runner: SyncBookRunner) : WorkerFactory() {
    private val logic = SyncWorkerLogic(runner)

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = if (workerClassName == SyncWorker::class.java.name) {
        SyncWorker(
            appContext,
            workerParameters,
            logic,
            WorkManagerSyncWorkQueue(WorkManager.getInstance(appContext)),
        )
    } else {
        null
    }
}
