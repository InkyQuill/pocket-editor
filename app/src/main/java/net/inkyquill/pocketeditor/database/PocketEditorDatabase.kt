package net.inkyquill.pocketeditor.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.inkyquill.pocketeditor.search.SearchDao
import net.inkyquill.pocketeditor.search.SearchEntity

@Database(
    entities = [
        BookRootEntity::class,
        RemoteRevisionEntity::class,
        MergeBaseEntity::class,
        OutboxEntity::class,
        ReadingPositionEntity::class,
        DraftEntity::class,
        SearchEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
abstract class PocketEditorDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun syncDao(): SyncDao
    abstract fun draftDao(): DraftDao
    abstract fun searchDao(): SearchDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `source_search` USING FTS4(" +
                        "`book_id` TEXT NOT NULL, `chapter_id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, `raw_boundaries` TEXT NOT NULL, tokenize=unicode61, " +
                        "notindexed=`book_id`, notindexed=`chapter_id`, notindexed=`title`, " +
                        "notindexed=`raw_boundaries`)",
                )
            }
        }
    }
}

internal class DatabaseConverters {
    @TypeConverter
    fun fromOutboxState(value: OutboxState): String = value.name

    @TypeConverter
    fun toOutboxState(value: String): OutboxState = OutboxState.valueOf(value)
}
