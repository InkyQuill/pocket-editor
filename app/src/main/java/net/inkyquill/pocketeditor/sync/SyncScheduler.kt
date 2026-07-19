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

enum class SyncWorkStage { DEBOUNCE_LAUNCHER, ACTIVE_SYNC }

enum class ExistingSyncPolicy { REPLACE_DELAYED, APPEND_OR_REPLACE_ACTIVE }

data class SyncWorkRequest(
    val uniqueName: String,
    val bookId: String,
    val remoteRootPath: String,
    val trigger: SyncTrigger,
    val stage: SyncWorkStage,
    val existingPolicy: ExistingSyncPolicy,
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
        val request = if (trigger == SyncTrigger.LOCAL_CHANGE) {
            delayedRequest(bookId, remoteRootPath)
        } else {
            activeRequest(bookId, remoteRootPath, trigger)
        }
        queue.enqueue(request)
    }

    private fun delayedRequest(bookId: String, remoteRootPath: String) = SyncWorkRequest(
        uniqueName = "sync-debounce-$bookId",
        bookId = bookId,
        remoteRootPath = remoteRootPath,
        trigger = SyncTrigger.LOCAL_CHANGE,
        stage = SyncWorkStage.DEBOUNCE_LAUNCHER,
        existingPolicy = ExistingSyncPolicy.REPLACE_DELAYED,
        networkRequirement = NetworkRequirement.CONNECTED,
        backoffPolicy = BackoffPolicy.EXPONENTIAL,
        initialDelay = changeDebounce,
    )

    companion object {
        internal fun activeRequest(bookId: String, remoteRootPath: String, trigger: SyncTrigger) = SyncWorkRequest(
            uniqueName = "sync-book-$bookId",
            bookId = bookId,
            remoteRootPath = remoteRootPath,
            trigger = trigger,
            stage = SyncWorkStage.ACTIVE_SYNC,
            existingPolicy = ExistingSyncPolicy.APPEND_OR_REPLACE_ACTIVE,
            networkRequirement = NetworkRequirement.CONNECTED,
            backoffPolicy = BackoffPolicy.EXPONENTIAL,
            initialDelay = Duration.ZERO,
        )
    }
}

class SyncDebounceLauncher(private val queue: SyncWorkQueue) {
    fun launch(bookId: String, remoteRootPath: String) {
        queue.enqueue(SyncScheduler.activeRequest(bookId, remoteRootPath, SyncTrigger.LOCAL_CHANGE))
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
        val workerClass = when (request.stage) {
            SyncWorkStage.DEBOUNCE_LAUNCHER -> SyncDebounceWorker::class.java
            SyncWorkStage.ACTIVE_SYNC -> SyncWorker::class.java
        }
        val work = OneTimeWorkRequest.Builder(workerClass)
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
        val policy = when (request.existingPolicy) {
            ExistingSyncPolicy.REPLACE_DELAYED -> ExistingWorkPolicy.REPLACE
            ExistingSyncPolicy.APPEND_OR_REPLACE_ACTIVE -> ExistingWorkPolicy.APPEND_OR_REPLACE
        }
        workManager.enqueueUniqueWork(request.uniqueName, policy, work)
    }
}
