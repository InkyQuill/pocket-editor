package net.inkyquill.pocketeditor.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.inkyquill.pocketeditor.search.SearchDao
import net.inkyquill.pocketeditor.search.SearchEntity
import net.inkyquill.pocketeditor.load.ProgressiveLoadErrorCategory
import net.inkyquill.pocketeditor.load.ProgressiveLoadFileState
import net.inkyquill.pocketeditor.load.ProgressiveLoadPhase

@Database(
    entities = [
        BookRootEntity::class,
        RemoteRevisionEntity::class,
        PendingPublicationEntity::class,
        MergeBaseEntity::class,
        OutboxEntity::class,
        PendingDeletionEntity::class,
        ReadingPositionEntity::class,
        DraftEntity::class,
        ImportDraftEntity::class,
        ProgressiveLoadJobEntity::class,
        ProgressiveLoadFileEntity::class,
        SearchEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
abstract class PocketEditorDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun syncDao(): SyncDao
    abstract fun draftDao(): DraftDao
    abstract fun importDraftDao(): ImportDraftDao
    abstract fun progressiveLoadDao(): ProgressiveLoadDao
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

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_publications` (" +
                        "`book_id` TEXT NOT NULL, `path` TEXT NOT NULL, " +
                        "PRIMARY KEY(`book_id`, `path`))",
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

    @TypeConverter
    fun fromProgressiveLoadPhase(value: ProgressiveLoadPhase): String = value.name

    @TypeConverter
    fun toProgressiveLoadPhase(value: String): ProgressiveLoadPhase = ProgressiveLoadPhase.valueOf(value)

    @TypeConverter
    fun fromProgressiveLoadFileState(value: ProgressiveLoadFileState): String = value.name

    @TypeConverter
    fun toProgressiveLoadFileState(value: String): ProgressiveLoadFileState = ProgressiveLoadFileState.valueOf(value)

    @TypeConverter
    fun fromProgressiveLoadErrorCategory(value: ProgressiveLoadErrorCategory?): String? = value?.name

    @TypeConverter
    fun toProgressiveLoadErrorCategory(value: String?): ProgressiveLoadErrorCategory? = value?.let(ProgressiveLoadErrorCategory::valueOf)
}
