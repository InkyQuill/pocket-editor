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

    @Test
    fun versionFiveBackfillsRemoteNameFromHistoricalPath() {
        helper.createDatabase(DATABASE_NAME_V5, 5).use { database ->
            database.execSQL(
                "INSERT INTO progressive_load_files " +
                    "(book_id, path, chapter_id, spine_index, expected_revision, expected_size, sha256, state, priority, claim_generation) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(BOOK_ID, "chapter.md", CHAPTER_ID, 0, "r1", 12L, null, "PENDING", 1, null),
            )
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME_V5, 6, true, PocketEditorDatabase.MIGRATION_5_6,
        ).use { database ->
            val remoteName = database.query(
                "SELECT remote_name FROM progressive_load_files WHERE book_id = ? AND path = ?",
                arrayOf<Any>(BOOK_ID, "chapter.md"),
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0)
            }
            org.junit.Assert.assertEquals("chapter.md", remoteName)
        }
    }

    @Test
    fun versionFourMigratesThroughHistoricalFiveToSix() {
        helper.createDatabase(DATABASE_NAME_V4_TO_V6, 4).close()

        helper.runMigrationsAndValidate(
            DATABASE_NAME_V4_TO_V6,
            6,
            true,
            PocketEditorDatabase.MIGRATION_4_5,
            PocketEditorDatabase.MIGRATION_5_6,
        ).use { database ->
            assertRowCount(database, "progressive_load_files", 0)
            assertRowCount(database, "import_drafts", 0)
        }
    }

    @Test
    fun versionSixAddsDurableRootKeyedDiscoveryRequestsWithoutChangingLoads() {
        helper.createDatabase(DATABASE_NAME_V6, 6).use { database ->
            database.execSQL(
                "INSERT INTO progressive_load_jobs " +
                    "(book_id, remote_root_path, phase, total_files, completed_files, active_path, retry_attempt, " +
                    "retry_at, generation, paused, cancelled, last_error_category) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(BOOK_ID, "disk:/Book", "BACKGROUND", 2, 1, null, 0, null, 4L, 0, 0, null),
            )
            database.execSQL(
                "INSERT INTO progressive_load_files " +
                    "(book_id, path, chapter_id, spine_index, expected_revision, expected_size, sha256, state, " +
                    "priority, claim_generation, remote_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(BOOK_ID, "chapter.md", CHAPTER_ID, 0, "r1", 12L, "hash", "CACHED", 0, null, "chapter.md"),
            )
            database.execSQL(
                "INSERT INTO progressive_load_jobs " +
                    "(book_id, remote_root_path, phase, total_files, completed_files, active_path, retry_attempt, " +
                    "retry_at, generation, paused, cancelled, last_error_category) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(SENTINEL_ID, "disk:/Pending", "PAUSED", 0, 0, null, 3, 4321L, 8L, 1, 0, "OFFLINE"),
            )
            database.execSQL(
                "INSERT INTO progressive_load_files " +
                    "(book_id, path, chapter_id, spine_index, expected_revision, expected_size, sha256, state, " +
                    "priority, claim_generation, remote_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(SENTINEL_ID, "orphan.md", SENTINEL_CHAPTER_ID, 0, "r0", null, null, "PENDING", 1, null, "orphan.md"),
            )
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME_V6, 7, true, PocketEditorDatabase.MIGRATION_6_7,
        ).use { database ->
            assertRowCount(database, "progressive_load_jobs", 1)
            assertRowCount(database, "progressive_load_files", 1)
            assertRowCount(database, "progressive_load_requests", 1)

            val migrated = database.query(
                "SELECT request_id, generation, phase, retry_attempt, retry_at, last_error_category, paused, " +
                    "cancelled, updated_at FROM progressive_load_requests WHERE remote_root_path = ?",
                arrayOf<Any>("disk:/Pending"),
            ).use { cursor ->
                check(cursor.moveToFirst())
                listOf(
                    cursor.getString(0), cursor.getLong(1), cursor.getString(2), cursor.getInt(3),
                    cursor.getLong(4), cursor.getString(5), cursor.getInt(6), cursor.getInt(7), cursor.getLong(8),
                )
            }
            org.junit.Assert.assertEquals(
                listOf(SENTINEL_ID, 8L, "PAUSED", 3, 4321L, "OFFLINE", 1, 0),
                migrated.dropLast(1),
            )
            org.junit.Assert.assertTrue("migration timestamp", migrated.last() as Long > 0L)

            database.execSQL(
                "INSERT INTO progressive_load_requests " +
                    "(remote_root_path, request_id, generation, phase, retry_attempt, retry_at, " +
                    "last_error_category, paused, cancelled, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("disk:/Book", "request-1", 7L, "PREPARING", 2, 1234L, "OFFLINE", 1, 0, 5678L),
            )
            val row = database.query(
                "SELECT request_id, generation, phase, retry_attempt, retry_at, last_error_category, paused, " +
                    "cancelled, updated_at FROM progressive_load_requests WHERE remote_root_path = ?",
                arrayOf<Any>("disk:/Book"),
            ).use { cursor ->
                check(cursor.moveToFirst())
                listOf(
                    cursor.getString(0), cursor.getLong(1), cursor.getString(2), cursor.getInt(3),
                    cursor.getLong(4), cursor.getString(5), cursor.getInt(6), cursor.getInt(7), cursor.getLong(8),
                )
            }
            org.junit.Assert.assertEquals(
                listOf("request-1", 7L, "PREPARING", 2, 1234L, "OFFLINE", 1, 0, 5678L),
                row,
            )
        }
    }

    @Test
    fun versionFiveMigratesThroughSixToSevenWithoutLosingProgressiveFiles() {
        helper.createDatabase(DATABASE_NAME_V5_TO_V7, 5).use { database ->
            database.execSQL(
                "INSERT INTO progressive_load_jobs " +
                    "(book_id, remote_root_path, phase, total_files, completed_files, active_path, retry_attempt, " +
                    "retry_at, generation, paused, cancelled, last_error_category) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(SENTINEL_ID, "disk:/Pending", "PREPARING", 0, 0, null, 1, null, 2L, 0, 1, "TIMEOUT"),
            )
            database.execSQL(
                "INSERT INTO progressive_load_files " +
                    "(book_id, path, chapter_id, spine_index, expected_revision, expected_size, sha256, state, " +
                    "priority, claim_generation) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(BOOK_ID, "nested/chapter.md", CHAPTER_ID, 0, "r1", 12L, null, "PENDING", 1, null),
            )
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME_V5_TO_V7,
            7,
            true,
            PocketEditorDatabase.MIGRATION_5_6,
            PocketEditorDatabase.MIGRATION_6_7,
        ).use { database ->
            val remoteName = database.query(
                "SELECT remote_name FROM progressive_load_files WHERE book_id = ? AND path = ?",
                arrayOf<Any>(BOOK_ID, "nested/chapter.md"),
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0)
            }
            org.junit.Assert.assertEquals("nested/chapter.md", remoteName)
            assertRowCount(database, "progressive_load_requests", 1)
            assertRowCount(database, "progressive_load_jobs", 0)
            assertRowCount(database, "progressive_load_files", 1)
            val request = database.query(
                "SELECT request_id, generation, phase, retry_attempt, retry_at, last_error_category, paused, cancelled " +
                    "FROM progressive_load_requests WHERE remote_root_path = ?",
                arrayOf<Any>("disk:/Pending"),
            ).use { cursor ->
                check(cursor.moveToFirst())
                listOf(
                    cursor.getString(0), cursor.getLong(1), cursor.getString(2), cursor.getInt(3),
                    if (cursor.isNull(4)) null else cursor.getLong(4), cursor.getString(5),
                    cursor.getInt(6), cursor.getInt(7),
                )
            }
            org.junit.Assert.assertEquals(
                listOf(SENTINEL_ID, 2L, "PREPARING", 1, null, "TIMEOUT", 0, 1),
                request,
            )
        }
    }

    @Test
    fun versionSixCollapsesDuplicatePendingRootsToTheNewestGeneration() {
        helper.createDatabase(DATABASE_NAME_V6_DUPLICATES, 6).use { database ->
            listOf(
                arrayOf<Any?>(BOOK_ID, "disk:/Pending", "PREPARING", 0, 0, null, 1, null, 4L, 0, 0, "OFFLINE"),
                arrayOf<Any?>(SENTINEL_ID, "disk:/Pending", "PAUSED", 0, 0, null, 3, 4321L, 8L, 1, 0, "TIMEOUT"),
            ).forEach { values ->
                database.execSQL(
                    "INSERT INTO progressive_load_jobs " +
                        "(book_id, remote_root_path, phase, total_files, completed_files, active_path, retry_attempt, " +
                        "retry_at, generation, paused, cancelled, last_error_category) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    values,
                )
            }
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME_V6_DUPLICATES, 7, true, PocketEditorDatabase.MIGRATION_6_7,
        ).use { database ->
            assertRowCount(database, "progressive_load_requests", 1)
            assertRowCount(database, "progressive_load_jobs", 0)
            database.query(
                "SELECT request_id, generation FROM progressive_load_requests WHERE remote_root_path = ?",
                arrayOf<Any>("disk:/Pending"),
            ).use { cursor ->
                check(cursor.moveToFirst())
                org.junit.Assert.assertEquals(SENTINEL_ID, cursor.getString(0))
                org.junit.Assert.assertEquals(8L, cursor.getLong(1))
            }
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
        const val DATABASE_NAME_V5 = "migration-version-five"
        const val DATABASE_NAME_V4_TO_V6 = "migration-version-four-to-six"
        const val DATABASE_NAME_V6 = "migration-version-six"
        const val DATABASE_NAME_V6_DUPLICATES = "migration-version-six-duplicates"
        const val DATABASE_NAME_V5_TO_V7 = "migration-version-five-to-seven"
        const val BOOK_ID = "11111111-1111-1111-1111-111111111111"
        const val CHAPTER_ID = "22222222-2222-2222-2222-222222222222"
        const val SENTINEL_ID = "33333333-3333-3333-3333-333333333333"
        const val SENTINEL_CHAPTER_ID = "44444444-4444-4444-4444-444444444444"
        const val LEGACY_DRAFT_JSON = """{"schemaVersion":1,"bookId":"11111111-1111-1111-1111-111111111111","remoteRootPath":"disk:/Book","title":"Book","phase":"READY","chapters":[{"id":"22222222-2222-2222-2222-222222222222","path":"chapter.md","title":"Chapter","included":true,"remoteRevision":"r1","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","byteSize":7}],"lastError":null}"""
    }
}
