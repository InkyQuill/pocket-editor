package net.inkyquill.pocketeditor.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PocketEditorMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PocketEditorDatabase::class.java,
    )

    @Test
    fun versionOneExportedSchemaCanBeCreatedAndValidated() {
        helper.createDatabase(DATABASE_NAME, 1).close()
        helper.runMigrationsAndValidate(DATABASE_NAME, 2, true, PocketEditorDatabase.MIGRATION_1_2).use { database ->
            val tables = database.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'source_search'",
            ).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            org.junit.Assert.assertEquals(listOf("source_search"), tables)
        }
    }

    @Test
    fun versionTwoDataSurvivesImportDraftMigration() {
        helper.createDatabase(DATABASE_NAME_V2, 2).use { database ->
            database.execSQL(
                "INSERT INTO book_roots (book_id, remote_root_path, local_directory, registered_at) " +
                    "VALUES ('$BOOK_ID', 'disk:/book', '/cache/$BOOK_ID', 10)",
            )
            database.execSQL(
                "INSERT INTO reading_positions (book_id, chapter_id, block_index, byte_offset, updated_at) " +
                    "VALUES ('$BOOK_ID', '$CHAPTER_ID', 3, 7, 11)",
            )
            database.execSQL(
                "INSERT INTO outbox " +
                    "(book_id, path, local_sha256, base_sha256, state, attempts, next_attempt_at, last_error) " +
                    "VALUES ('$BOOK_ID', 'chapter.md.review.json', 'local', NULL, 'PENDING', 0, NULL, NULL)",
            )
            database.execSQL(
                "INSERT INTO drafts " +
                    "(book_id, chapter_id, draft_type, record_id, text, selection_start, selection_end, updated_at, record_key) " +
                    "VALUES ('$BOOK_ID', '$CHAPTER_ID', 'chapter_note', NULL, 'saved review', 0, 0, 12, '')",
            )
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME_V2,
            3,
            true,
            PocketEditorDatabase.MIGRATION_2_3,
        ).use { database ->
            assertRowCount(database, "book_roots", 1)
            assertRowCount(database, "reading_positions", 1)
            assertRowCount(database, "outbox", 1)
            assertRowCount(database, "drafts", 1)
            assertRowCount(database, "import_drafts", 0)
        }
    }

    @Test
    fun versionThreeDataSurvivesPublicationJournalMigration() {
        helper.createDatabase(DATABASE_NAME_V3, 3).use { database ->
            database.execSQL(
                "INSERT INTO remote_revisions (book_id, path, remote_revision, sha256) " +
                    "VALUES ('$BOOK_ID', 'chapter.md.review.json', 'remote-1', 'hash')",
            )
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME_V3,
            4,
            true,
            PocketEditorDatabase.MIGRATION_3_4,
        ).use { database ->
            assertRowCount(database, "remote_revisions", 1)
            assertRowCount(database, "pending_publications", 0)
        }
    }

    @Test
    fun versionFourAddsProgressiveTablesWithoutDroppingLegacyDrafts() {
        helper.createDatabase(DATABASE_NAME_V4, 4).use { database ->
            database.execSQL(
                "INSERT INTO import_drafts (book_id, remote_root_path, local_directory, document_json, updated_at) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any>(BOOK_ID, "disk:/Book", "/cache/$BOOK_ID", LEGACY_DRAFT_JSON, 20L),
            )
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME_V4, 5, true, PocketEditorDatabase.MIGRATION_4_5,
        ).use { database ->
            assertRowCount(database, "progressive_load_jobs", 0)
            assertRowCount(database, "progressive_load_files", 0)
            assertRowCount(database, "import_drafts", 1)
        }
    }

    private fun assertRowCount(database: androidx.sqlite.db.SupportSQLiteDatabase, table: String, expected: Int) {
        val count = database.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
        org.junit.Assert.assertEquals("$table row count", expected, count)
    }

    private companion object {
        const val DATABASE_NAME = "migration-version-one"
        const val DATABASE_NAME_V2 = "migration-version-two"
        const val DATABASE_NAME_V3 = "migration-version-three"
        const val DATABASE_NAME_V4 = "migration-version-four"
        const val BOOK_ID = "11111111-1111-1111-1111-111111111111"
        const val CHAPTER_ID = "22222222-2222-2222-2222-222222222222"
        const val LEGACY_DRAFT_JSON = """{"schemaVersion":1,"bookId":"11111111-1111-1111-1111-111111111111","remoteRootPath":"disk:/Book","title":"Book","phase":"READY","chapters":[{"id":"22222222-2222-2222-2222-222222222222","path":"chapter.md","title":"Chapter","included":true,"remoteRevision":"r1","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","byteSize":7}],"lastError":null}"""
    }
}
