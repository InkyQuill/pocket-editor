package net.inkyquill.pocketeditor.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import net.inkyquill.pocketeditor.load.ProgressiveLoadErrorCategory
import net.inkyquill.pocketeditor.load.ProgressiveLoadPhase

@Dao
interface ProgressiveLoadRequestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(request: ProgressiveLoadRequestEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(request: ProgressiveLoadRequestEntity): Long

    @Query("SELECT * FROM progressive_load_requests WHERE remote_root_path = :remoteRootPath")
    suspend fun get(remoteRootPath: String): ProgressiveLoadRequestEntity?

    @Query("SELECT * FROM progressive_load_requests WHERE request_id = :requestId")
    suspend fun getByRequestId(requestId: String): ProgressiveLoadRequestEntity?

    @Query("SELECT * FROM progressive_load_requests ORDER BY updated_at DESC, remote_root_path ASC")
    suspend fun getAll(): List<ProgressiveLoadRequestEntity>

    @Query("SELECT * FROM progressive_load_requests ORDER BY updated_at DESC, remote_root_path ASC")
    fun observeAll(): Flow<List<ProgressiveLoadRequestEntity>>

    @Query(
        "UPDATE progressive_load_requests SET request_id = :requestId, generation = :generation, " +
            "phase = :phase, retry_attempt = :retryAttempt, retry_at = :retryAt, " +
            "last_error_category = :lastErrorCategory, paused = :paused, cancelled = :cancelled, " +
            "updated_at = :updatedAt WHERE remote_root_path = :remoteRootPath AND generation = :expectedGeneration",
    )
    suspend fun updateColumnsIfGeneration(
        remoteRootPath: String,
        requestId: String,
        generation: Long,
        phase: ProgressiveLoadPhase,
        retryAttempt: Int,
        retryAt: Long?,
        lastErrorCategory: ProgressiveLoadErrorCategory?,
        paused: Boolean,
        cancelled: Boolean,
        updatedAt: Long,
        expectedGeneration: Long,
    ): Int

    @Transaction
    suspend fun updateIfGeneration(request: ProgressiveLoadRequestEntity, expectedGeneration: Long): Int =
        updateColumnsIfGeneration(
            request.remoteRootPath,
            request.requestId,
            request.generation,
            request.phase,
            request.retryAttempt,
            request.retryAt,
            request.lastErrorCategory,
            request.paused,
            request.cancelled,
            request.updatedAt,
            expectedGeneration,
        )

    @Transaction
    suspend fun compareAndSet(request: ProgressiveLoadRequestEntity, expectedGeneration: Long): Boolean =
        updateIfGeneration(request, expectedGeneration) == 1

    @Query("DELETE FROM progressive_load_requests WHERE remote_root_path = :remoteRootPath")
    suspend fun delete(remoteRootPath: String)

    @Query("DELETE FROM progressive_load_requests WHERE remote_root_path = :remoteRootPath AND generation = :expectedGeneration")
    suspend fun deleteIfGeneration(remoteRootPath: String, expectedGeneration: Long): Int
}
