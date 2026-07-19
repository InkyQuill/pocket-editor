package net.inkyquill.pocketeditor.sync

import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkRequest

enum class SyncTrigger { OPEN, RECONNECT, LOCAL_CHANGE, SYNC_NOW }

enum class NetworkRequirement { CONNECTED }

enum class BackoffPolicy { EXPONENTIAL }

data class SyncWorkRequest(
    val uniqueName: String,
    val bookId: String,
    val remoteRootPath: String,
    val trigger: SyncTrigger,
    val networkRequirement: NetworkRequirement,
    val backoffPolicy: BackoffPolicy,
    val initialDelay: Duration,
)

fun interface SyncWorkQueue {
    fun enqueue(request: SyncWorkRequest)
}

class SyncScheduler(
    private val queue: SyncWorkQueue,
    private val changeDebounce: Duration = Duration.ofSeconds(2),
) {
    fun enqueue(bookId: String, remoteRootPath: String, trigger: SyncTrigger) {
        require(runCatching { UUID.fromString(bookId).toString() == bookId }.getOrDefault(false))
        require(remoteRootPath.isNotBlank())
        require(!changeDebounce.isNegative)
        queue.enqueue(
            SyncWorkRequest(
                uniqueName = "sync-book-$bookId",
                bookId = bookId,
                remoteRootPath = remoteRootPath,
                trigger = trigger,
                networkRequirement = NetworkRequirement.CONNECTED,
                backoffPolicy = BackoffPolicy.EXPONENTIAL,
                initialDelay = if (trigger == SyncTrigger.LOCAL_CHANGE) changeDebounce else Duration.ZERO,
            ),
        )
    }
}

class WorkManagerSyncWorkQueue(private val workManager: WorkManager) : SyncWorkQueue {
    override fun enqueue(request: SyncWorkRequest) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val input = Data.Builder()
            .putString(SyncWorker.BOOK_ID_KEY, request.bookId)
            .putString(SyncWorker.REMOTE_ROOT_PATH_KEY, request.remoteRootPath)
            .build()
        val work = OneTimeWorkRequest.Builder(SyncWorker::class.java)
            .setInputData(input)
            .setConstraints(constraints)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .setInitialDelay(request.initialDelay.toMillis(), TimeUnit.MILLISECONDS)
            .addTag(request.uniqueName)
            .build()
        workManager.enqueueUniqueWork(request.uniqueName, ExistingWorkPolicy.REPLACE, work)
    }
}
