package net.inkyquill.pocketeditor.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Upsert
    suspend fun upsertRoot(root: BookRootEntity)

    @Query("SELECT * FROM book_roots ORDER BY registered_at, book_id")
    fun observeRoots(): Flow<List<BookRootEntity>>

    @Query("SELECT * FROM book_roots ORDER BY registered_at, book_id")
    suspend fun getRoots(): List<BookRootEntity>

    @Upsert
    suspend fun upsertReadingPosition(position: ReadingPositionEntity)

    @Query("SELECT * FROM reading_positions WHERE book_id = :bookId")
    fun observeReadingPosition(bookId: String): Flow<ReadingPositionEntity?>
}
