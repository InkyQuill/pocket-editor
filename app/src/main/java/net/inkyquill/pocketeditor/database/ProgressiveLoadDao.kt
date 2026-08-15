package net.inkyquill.pocketeditor.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import net.inkyquill.pocketeditor.load.BACKGROUND_PRIORITY
import net.inkyquill.pocketeditor.load.ProgressiveLoadErrorCategory
import net.inkyquill.pocketeditor.load.ProgressiveLoadFileState
import net.inkyquill.pocketeditor.load.ProgressiveLoadJobWithFiles
import net.inkyquill.pocketeditor.load.ProgressiveLoadPhase
import net.inkyquill.pocketeditor.load.toSnapshot
import net.inkyquill.pocketeditor.load.initialPriority
import net.inkyquill.pocketeditor.load.ON_DEMAND_PRIORITY

@Dao
interface ProgressiveLoadDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJob(job: ProgressiveLoadJobEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFiles(files: List<ProgressiveLoadFileEntity>)

    @Transaction
    @Query("SELECT * FROM progressive_load_jobs WHERE book_id = :bookId")
    fun observe(bookId: String): Flow<ProgressiveLoadJobWithFiles?>

    @Transaction
    @Query("SELECT * FROM progressive_load_jobs ORDER BY book_id")
    fun observeAll(): Flow<List<ProgressiveLoadJobWithFiles>>

    @Query("SELECT * FROM progressive_load_jobs WHERE book_id = :bookId")
    suspend fun getJob(bookId: String): ProgressiveLoadJobEntity?

    @Query("SELECT * FROM progressive_load_jobs WHERE remote_root_path = :remoteRootPath LIMIT 1")
    suspend fun getJobByRemoteRoot(remoteRootPath: String): ProgressiveLoadJobEntity?

    @Query("SELECT * FROM progressive_load_files WHERE book_id = :bookId ORDER BY spine_index")
    suspend fun getFiles(bookId: String): List<ProgressiveLoadFileEntity>

    @Query("SELECT * FROM progressive_load_files WHERE book_id = :bookId AND chapter_id = :chapterId")
    fun observeChapter(bookId: String, chapterId: String): Flow<ProgressiveLoadFileEntity?>

    @Query("SELECT * FROM progressive_load_files WHERE book_id = :bookId AND state = 'PENDING' ORDER BY priority DESC, spine_index ASC LIMIT 1")
    suspend fun nextPending(bookId: String): ProgressiveLoadFileEntity?

    @Update
    suspend fun updateJob(job: ProgressiveLoadJobEntity)

    @Update
    suspend fun updateFile(file: ProgressiveLoadFileEntity)

    @Query("UPDATE progressive_load_files SET priority = 2 WHERE book_id = :bookId AND path = :path AND state = 'PENDING' AND priority < 2")
    suspend fun prioritize(bookId: String, path: String): Int

    @Query(
        "SELECT EXISTS(SELECT 1 FROM progressive_load_files f JOIN progressive_load_jobs j ON j.book_id = f.book_id " +
            "WHERE f.book_id = :bookId AND f.path = :path AND f.state = 'DOWNLOADING' " +
            "AND f.claim_generation = :generation AND j.generation = :generation)",
    )
    suspend fun ownsClaim(bookId: String, path: String, generation: Long): Boolean

    @Query("DELETE FROM progressive_load_jobs WHERE book_id = :bookId")
    suspend fun deleteJob(bookId: String)

    @Query("DELETE FROM progressive_load_files WHERE book_id = :bookId")
    suspend fun deleteFiles(bookId: String)

    /** Reorders the complete durable spine without changing path or chapter identity. */
    @Transaction
    suspend fun reorderSpine(bookId: String, orderedChapterIds: List<String>) {
        val job = getJob(bookId) ?: return
        val files = getFiles(bookId)
        require(
            orderedChapterIds.size == files.size &&
                orderedChapterIds.distinct().size == orderedChapterIds.size &&
                orderedChapterIds.toSet() == files.map(ProgressiveLoadFileEntity::chapterId).toSet(),
        ) { "Reorder must contain the complete unique load spine" }
        val byId = files.associateBy(ProgressiveLoadFileEntity::chapterId)
        orderedChapterIds.forEachIndexed { index, chapterId ->
            val file = byId.getValue(chapterId)
            val reorderedPriority = when {
                file.state == ProgressiveLoadFileState.CACHED -> BACKGROUND_PRIORITY
                file.priority == ON_DEMAND_PRIORITY -> ON_DEMAND_PRIORITY
                else -> initialPriority(index)
            }
            updateFile(file.copy(spineIndex = index, priority = reorderedPriority))
        }
        val reordered = getFiles(bookId)
        val initialReady = reordered.take(minOf(3, reordered.size))
            .all { it.state == ProgressiveLoadFileState.CACHED }
        val phase = when (job.phase) {
            ProgressiveLoadPhase.INITIAL, ProgressiveLoadPhase.BACKGROUND ->
                if (initialReady) ProgressiveLoadPhase.BACKGROUND else ProgressiveLoadPhase.INITIAL
            else -> job.phase
        }
        updateJob(job.copy(phase = phase))
    }

    /** Replaces the exact manifest spine in the same Room transaction as its filesystem swap. */
    @Transaction
    suspend fun replaceManifestSpine(bookId: String, replacement: List<ProgressiveLoadFileEntity>) {
        val job = getJob(bookId) ?: return
        require(replacement.all { it.bookId == bookId })
        require(replacement.map { it.path }.distinct().size == replacement.size)
        require(replacement.map { it.chapterId }.distinct().size == replacement.size)
        require(replacement.sortedBy { it.spineIndex }.map { it.spineIndex } == replacement.indices.toList())
        deleteFiles(bookId)
        if (replacement.isNotEmpty()) insertFiles(replacement.sortedBy { it.spineIndex })
        val completed = replacement.count { it.state == ProgressiveLoadFileState.CACHED }
        val initialReady = replacement.isNotEmpty() && replacement
            .sortedBy { it.spineIndex }
            .take(minOf(3, replacement.size))
            .all { it.state == ProgressiveLoadFileState.CACHED }
        updateJob(
            job.copy(
                totalFiles = replacement.size,
                completedFiles = completed,
                activePath = null,
                phase = when {
                    completed == replacement.size -> ProgressiveLoadPhase.COMPLETE
                    job.cancelled -> ProgressiveLoadPhase.CANCELLED
                    job.paused -> ProgressiveLoadPhase.PAUSED
                    initialReady -> ProgressiveLoadPhase.BACKGROUND
                    else -> ProgressiveLoadPhase.INITIAL
                },
            ),
        )
    }

    @Transaction
    suspend fun snapshot(bookId: String) = getJob(bookId)?.let { job ->
        ProgressiveLoadJobWithFiles(job, getFiles(bookId)).toSnapshot()
    }

    @Transaction
    suspend fun resetCachedMismatch(bookId: String, path: String) {
        val job = getJob(bookId) ?: return
        val file = getFiles(bookId).singleOrNull { it.path == path } ?: return
        if (file.state != ProgressiveLoadFileState.CACHED) return
        updateFile(file.copy(
            state = ProgressiveLoadFileState.PENDING,
            sha256 = null,
            priority = initialPriority(file.spineIndex),
            claimGeneration = null,
        ))
        val files = getFiles(bookId)
        val completed = files.count { it.state == ProgressiveLoadFileState.CACHED }
        val initialReady = files.sortedBy { it.spineIndex }.take(minOf(3, files.size))
            .all { it.state == ProgressiveLoadFileState.CACHED }
        updateJob(job.copy(
            phase = if (initialReady) ProgressiveLoadPhase.BACKGROUND else ProgressiveLoadPhase.INITIAL,
            completedFiles = completed,
            activePath = null,
        ))
    }

    @Transaction
    suspend fun markActionRequired(bookId: String, path: String, generation: Long, category: ProgressiveLoadErrorCategory) {
        val job = getJob(bookId) ?: return
        val file = getFiles(bookId).singleOrNull { it.path == path } ?: return
        if (job.generation != generation || file.claimGeneration != generation) return
        updateFile(file.copy(state = ProgressiveLoadFileState.ACTION_REQUIRED, claimGeneration = null))
        updateJob(job.copy(
            phase = ProgressiveLoadPhase.ACTION_REQUIRED,
            activePath = null,
            retryAt = null,
            lastErrorCategory = category,
        ))
    }

    @Transaction
    suspend fun pauseUnauthorized(bookId: String, path: String, generation: Long) {
        val job = getJob(bookId) ?: return
        val file = getFiles(bookId).singleOrNull { it.path == path } ?: return
        if (job.generation != generation || file.claimGeneration != generation) return
        updateFile(file.copy(state = ProgressiveLoadFileState.PENDING, claimGeneration = null))
        updateJob(job.copy(
            phase = ProgressiveLoadPhase.PAUSED,
            activePath = null,
            retryAt = null,
            paused = true,
            lastErrorCategory = ProgressiveLoadErrorCategory.UNAUTHORIZED,
        ))
    }

    @Transaction
    suspend fun claimNext(bookId: String, generation: Long): ProgressiveLoadFileEntity? {
        val job = getJob(bookId) ?: return null
        if (job.generation != generation || job.paused || job.cancelled || job.phase == ProgressiveLoadPhase.ACTION_REQUIRED) return null
        // A persisted claim can outlive its worker after OS process death. The runner reconciles
        // it before claiming; returning null here keeps a missed recovery path safe and retryable.
        if (getFiles(bookId).any { it.state == ProgressiveLoadFileState.DOWNLOADING }) return null
        val next = nextPending(bookId) ?: return null
        val claimed = next.copy(state = ProgressiveLoadFileState.DOWNLOADING, claimGeneration = generation)
        updateFile(claimed)
        updateJob(job.copy(activePath = next.path, retryAt = null, lastErrorCategory = null))
        return claimed
    }

    @Transaction
    suspend fun markCached(bookId: String, path: String, generation: Long, sha256: String) {
        val job = requireNotNull(getJob(bookId))
        if (job.generation != generation) return
        val file = getFiles(bookId).single { it.path == path }
        if (file.claimGeneration != generation) return
        updateFile(file.copy(
            state = ProgressiveLoadFileState.CACHED,
            sha256 = sha256,
            priority = BACKGROUND_PRIORITY,
            claimGeneration = null,
        ))
        val completed = getFiles(bookId).count { it.state == ProgressiveLoadFileState.CACHED }
        val phase = when {
            completed == job.totalFiles -> ProgressiveLoadPhase.COMPLETE
            getFiles(bookId).sortedBy { it.spineIndex }.take(minOf(3, job.totalFiles))
                .all { it.state == ProgressiveLoadFileState.CACHED } -> ProgressiveLoadPhase.BACKGROUND
            else -> ProgressiveLoadPhase.INITIAL
        }
        updateJob(job.copy(phase = phase, completedFiles = completed, activePath = null, retryAttempt = 0, retryAt = null))
    }

    @Transaction
    suspend fun restorePending(
        bookId: String,
        path: String,
        generation: Long,
        category: ProgressiveLoadErrorCategory?,
        retryAttempt: Int,
        retryAt: Long?,
    ) {
        val job = getJob(bookId) ?: return
        val file = getFiles(bookId).singleOrNull { it.path == path } ?: return
        val ownsFile =
            file.state == ProgressiveLoadFileState.DOWNLOADING &&
            file.claimGeneration == generation
        if (!ownsFile) return
        updateFile(file.copy(state = ProgressiveLoadFileState.PENDING, claimGeneration = null))
        if (job.generation == generation) {
            updateJob(job.copy(
                activePath = job.activePath.takeUnless { it == path },
                retryAttempt = retryAttempt,
                retryAt = retryAt,
                lastErrorCategory = category,
            ))
        } else if (job.activePath == path) {
            updateJob(job.copy(activePath = null))
        }
    }

    @Transaction
    suspend fun restoreOrphanedClaim(bookId: String, path: String, claimGeneration: Long) {
        val job = getJob(bookId) ?: return
        val file = getFiles(bookId).singleOrNull { it.path == path } ?: return
        if (file.state != ProgressiveLoadFileState.DOWNLOADING || file.claimGeneration != claimGeneration) return
        updateFile(file.copy(state = ProgressiveLoadFileState.PENDING, claimGeneration = null))
        if (job.activePath == path) updateJob(job.copy(activePath = null))
    }

    @Transaction
    suspend fun repairRemoteName(
        bookId: String,
        path: String,
        claimGeneration: Long,
        remoteName: String,
    ) {
        val file = getFiles(bookId).singleOrNull { it.path == path } ?: return
        if (file.state == ProgressiveLoadFileState.DOWNLOADING && file.claimGeneration == claimGeneration) {
            updateFile(file.copy(remoteName = remoteName))
        }
    }
}
