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
        PendingDeletionEntity::class,
        ReadingPositionEntity::class,
        DraftEntity::class,
        ImportDraftEntity::class,
        SearchEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
abstract class PocketEditorDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun syncDao(): SyncDao
    abstract fun draftDao(): DraftDao
    abstract fun importDraftDao(): ImportDraftDao
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
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_deletions` (" +
                        "`token_id` TEXT NOT NULL, `book_id` TEXT NOT NULL, `chapter_id` TEXT NOT NULL, " +
                        "`review_path` TEXT NOT NULL, `record_id` TEXT NOT NULL, `record_type` TEXT NOT NULL, " +
                        "`record_payload` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`token_id`))",
                )
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `import_drafts` (" +
                        "`book_id` TEXT NOT NULL, `remote_root_path` TEXT NOT NULL, " +
                        "`local_directory` TEXT NOT NULL, `document_json` TEXT NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`book_id`))",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_import_drafts_remote_root_path` " +
                        "ON `import_drafts` (`remote_root_path`)",
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
