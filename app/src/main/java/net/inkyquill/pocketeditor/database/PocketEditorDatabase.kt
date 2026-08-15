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
        ProgressiveLoadRequestEntity::class,
        ProgressiveLoadFileEntity::class,
        SearchEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
abstract class PocketEditorDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun syncDao(): SyncDao
    abstract fun draftDao(): DraftDao
    abstract fun importDraftDao(): ImportDraftDao
    abstract fun progressiveLoadDao(): ProgressiveLoadDao
    abstract fun progressiveLoadRequestDao(): ProgressiveLoadRequestDao
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

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `progressive_load_jobs` (" +
                        "`book_id` TEXT NOT NULL, `remote_root_path` TEXT NOT NULL, `phase` TEXT NOT NULL, " +
                        "`total_files` INTEGER NOT NULL, `completed_files` INTEGER NOT NULL, `active_path` TEXT, " +
                        "`retry_attempt` INTEGER NOT NULL, `retry_at` INTEGER, `generation` INTEGER NOT NULL, " +
                        "`paused` INTEGER NOT NULL, `cancelled` INTEGER NOT NULL, `last_error_category` TEXT, " +
                        "PRIMARY KEY(`book_id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `progressive_load_files` (" +
                        "`book_id` TEXT NOT NULL, `path` TEXT NOT NULL, `chapter_id` TEXT NOT NULL, " +
                        "`spine_index` INTEGER NOT NULL, `expected_revision` TEXT NOT NULL, `expected_size` INTEGER, " +
                        "`sha256` TEXT, `state` TEXT NOT NULL, `priority` INTEGER NOT NULL, `claim_generation` INTEGER, " +
                        "PRIMARY KEY(`book_id`, `path`))",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_progressive_load_files_book_id_chapter_id` " +
                        "ON `progressive_load_files` (`book_id`, `chapter_id`)",
                )
            }
        }

        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE `progressive_load_files_new` (" +
                        "`book_id` TEXT NOT NULL, `path` TEXT NOT NULL, `chapter_id` TEXT NOT NULL, " +
                        "`spine_index` INTEGER NOT NULL, `expected_revision` TEXT NOT NULL, `expected_size` INTEGER, " +
                        "`sha256` TEXT, `state` TEXT NOT NULL, `priority` INTEGER NOT NULL, `claim_generation` INTEGER, " +
                        "`remote_name` TEXT NOT NULL, PRIMARY KEY(`book_id`, `path`))",
                )
                db.execSQL(
                    "INSERT INTO `progressive_load_files_new` (" +
                        "`book_id`, `path`, `chapter_id`, `spine_index`, `expected_revision`, `expected_size`, " +
                        "`sha256`, `state`, `priority`, `claim_generation`, `remote_name`) " +
                        "SELECT `book_id`, `path`, `chapter_id`, `spine_index`, `expected_revision`, `expected_size`, " +
                        "`sha256`, `state`, `priority`, `claim_generation`, `path` FROM `progressive_load_files`",
                )
                db.execSQL("DROP TABLE `progressive_load_files`")
                db.execSQL("ALTER TABLE `progressive_load_files_new` RENAME TO `progressive_load_files`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_progressive_load_files_book_id_chapter_id` " +
                        "ON `progressive_load_files` (`book_id`, `chapter_id`)",
                )
            }
        }

        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `progressive_load_requests` (" +
                        "`remote_root_path` TEXT NOT NULL, `request_id` TEXT NOT NULL, " +
                        "`generation` INTEGER NOT NULL, `phase` TEXT NOT NULL, " +
                        "`retry_attempt` INTEGER NOT NULL, `retry_at` INTEGER, " +
                        "`last_error_category` TEXT, `paused` INTEGER NOT NULL, " +
                        "`cancelled` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`remote_root_path`))",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_progressive_load_requests_request_id` " +
                        "ON `progressive_load_requests` (`request_id`)",
                )
                db.execSQL(
                    "INSERT INTO `progressive_load_requests` (" +
                        "`remote_root_path`, `request_id`, `generation`, `phase`, `retry_attempt`, `retry_at`, " +
                        "`last_error_category`, `paused`, `cancelled`, `updated_at`) " +
                        "SELECT `remote_root_path`, `book_id`, `generation`, `phase`, `retry_attempt`, `retry_at`, " +
                        "`last_error_category`, `paused`, `cancelled`, " +
                        "CAST(strftime('%s', 'now') AS INTEGER) * 1000 " +
                        "FROM `progressive_load_jobs` WHERE `total_files` = 0",
                )
                db.execSQL(
                    "DELETE FROM `progressive_load_files` WHERE `book_id` IN (" +
                        "SELECT `book_id` FROM `progressive_load_jobs` WHERE `total_files` = 0)",
                )
                db.execSQL("DELETE FROM `progressive_load_jobs` WHERE `total_files` = 0")
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
