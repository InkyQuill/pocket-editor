# Progressive Yandex Disk Book Loading Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a selected Yandex Disk book readable after its first three chapters are durably cached, then finish a sequential, restart-safe background load with observable controls and on-demand chapter priority.

**Architecture:** Room v5 owns one durable job and ordered file rows per book; a unique WorkManager chain claims and publishes exactly one file per run under the existing per-book mutation gate. `RoomYandexBookLibraryData` becomes the UI adapter over a progressive loader, while `PocketEditorRoot` hosts a compact persistent progress card and Contents can prioritize or reorder the complete spine without waiting for the cache.

**Tech Stack:** Kotlin 2.3.10, Android 26–36, Room 2.8.4 with KSP schema export, WorkManager 2.11.2, coroutines/Flow 1.10.2, OkHttp 5.2.1, Jetpack Compose Material 3, JUnit 5, AndroidX instrumentation, MockWebServer.

## Global Constraints

- Do not synchronize reading position.
- Do not add editable chapter titles or persist a title cache. Titles continue to derive from cached source bytes using `ChapterTitleExtractor`.
- Do not infer or promise a specific Yandex request quota. No captured failure establishes that a quota caused the original symptom.
- Do not download several chapters concurrently.
- Do not change chapter source or review files during a read-only installation of an existing manifest-backed book.
- Keep all Yandex operations sequential per book; progressive loading must not create a request burst.
- The initial priority set is the first `min(3, chapterCount)` spine entries.
- Only one progressive-load worker is active per book.
- A validated-network constraint prevents calls on an unvalidated connection.
- Work cancellation restores invariants non-cancellably. It must not leave a file permanently `DOWNLOADING`, leak a generation, or block the exclusive book gate.
- The verification must not upload, delete, replace, reorder, or otherwise modify the remote `aria` folder. Reorder is covered against disposable fixtures only.
- Full unit/lint/instrumentation compilation passes, and connected runtime is reported separately from compilation evidence.
- Work on the current branch; do not create a worktree, and preserve unrelated local or staged files.

---

## File structure and interface ledger

| Area | Files | Responsibility |
| --- | --- | --- |
| Durable state | `database/Entities.kt`, `database/ProgressiveLoadDao.kt`, `database/PocketEditorDatabase.kt`, schema `5.json` | Store the job, stable spine IDs/order, per-file cache state, generation, user intent, sanitized retry state. |
| Legacy bridge | `load/LegacyImportDraftAdapter.kt` | Decode v4 `ImportDraftDocument`, validate matching cache metadata, and emit an exact install seed without changing IDs. |
| Retry/worker | `load/ProgressiveLoadRetry.kt`, `load/ProgressiveLoadScheduler.kt`, `load/ProgressiveLoadWorker.kt` | Classify transient/action failures, schedule one unique validated-network worker, and fence stale generations. |
| Install/runner | `load/ProgressiveBookLoader.kt`, `load/ProgressiveBookInstaller.kt` | Build a spine from one listing, publish the durable base, claim one file, cache/index/notify it, and restore claims after cancellation. |
| Library/UI | `BookLibraryController.kt`, `ProgressiveLoadCard.kt`, `PocketEditorRoot.kt`, reader/Contents files | Observe durable state, open at three, show non-blocking progress, prioritize an uncached chapter, and show a body-only skeleton. |
| Reorder | `ContentsReorderState.kt`, `ContentsPanel.kt`, `RoomYandexBookLibraryData.kt` | Keep an in-memory ordering draft and publish one exclusive schema-v2 binder mutation against the verified base. |

The canonical cross-task types are defined in Task 1 and repeated where consumed:

```kotlin
enum class ProgressiveLoadPhase { PREPARING, INITIAL, BACKGROUND, PAUSED, CANCELLED, ACTION_REQUIRED, COMPLETE }
enum class ProgressiveLoadFileState { PENDING, DOWNLOADING, CACHED, ACTION_REQUIRED }
enum class ProgressiveLoadErrorCategory {
    OFFLINE, TIMEOUT, RATE_LIMITED, SERVER, TEMPORARY_AVAILABILITY, UNAUTHORIZED, INVALID_REMOTE
}

data class ProgressiveLoadSnapshot(
    val bookId: String,
    val remoteRootPath: String,
    val phase: ProgressiveLoadPhase,
    val totalFiles: Int,
    val completedFiles: Int,
    val activePath: String?,
    val retryAttempt: Int,
    val retryAt: Long?,
    val generation: Long,
    val paused: Boolean,
    val cancelled: Boolean,
    val lastErrorCategory: ProgressiveLoadErrorCategory?,
    val files: List<ProgressiveLoadFileEntity>,
) {
    val initialReady: Boolean
        get() = files.take(minOf(3, files.size)).all { it.state == ProgressiveLoadFileState.CACHED }
}
```

### Task 1: Room v5 persistence, priority model, and legacy-draft adapter

**Files:**
- Create: `app/src/main/java/net/inkyquill/pocketeditor/database/ProgressiveLoadDao.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/load/ProgressiveLoadModels.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/load/LegacyImportDraftAdapter.kt`
- Create: `app/src/test/java/net/inkyquill/pocketeditor/load/ProgressiveLoadStateTest.kt`
- Create: `app/src/test/java/net/inkyquill/pocketeditor/load/LegacyImportDraftAdapterTest.kt`
- Create: `app/src/androidTest/java/net/inkyquill/pocketeditor/database/ProgressiveLoadDaoTest.kt`
- Create: `app/schemas/net.inkyquill.pocketeditor.database.PocketEditorDatabase/5.json` (generated by KSP, then reviewed and committed)
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/database/Entities.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/database/PocketEditorDatabase.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/PocketEditorApp.kt`
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/database/PocketEditorMigrationTest.kt`

**Interfaces:**
- Consumes: `ImportDraftDocument.decode(String): ImportDraftDocument`; `ImportDraftStore.readMatchingSource(bookId: String, path: String, remoteRevision: String, sha256: String): ByteArray?`; `BookManifest(bookId: String, title: String, chapters: List<ChapterEntry>)`; existing `DatabaseConverters`.
- Produces: `ProgressiveLoadJobEntity`, `ProgressiveLoadFileEntity`, `ProgressiveLoadDao`, `ProgressiveLoadSnapshot`, `ProgressiveLoadPhase`, `ProgressiveLoadFileState`, `ProgressiveLoadErrorCategory`, `initialPriority(spineIndex: Int): Int`, `LegacyProgressiveSeed`, `LegacyImportDraftAdapter.seeds(): List<LegacyProgressiveSeed>`.

- [ ] **Step 1: Add the pure readiness and priority RED tests**

Create `ProgressiveLoadStateTest.kt` with stable IDs and no Android dependencies:

```kotlin
package net.inkyquill.pocketeditor.load

import net.inkyquill.pocketeditor.database.ProgressiveLoadFileEntity
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProgressiveLoadStateTest {
    @Test
    fun `only first three spine rows receive initial priority`() {
        assertEquals(listOf(1, 1, 1, 0, 0), (0 until 5).map(::initialPriority))
    }

    @Test
    fun `readiness requires exactly first three or the whole shorter book`() {
        assertFalse(snapshot(states = listOf(CACHED, CACHED, PENDING, CACHED)).initialReady)
        assertTrue(snapshot(states = listOf(CACHED, CACHED, CACHED, PENDING)).initialReady)
        assertTrue(snapshot(states = listOf(CACHED, CACHED)).initialReady)
    }

    private fun snapshot(states: List<ProgressiveLoadFileState>) = ProgressiveLoadSnapshot(
        bookId = BOOK_ID,
        remoteRootPath = "disk:/Book",
        phase = ProgressiveLoadPhase.INITIAL,
        totalFiles = states.size,
        completedFiles = states.count { it == CACHED },
        activePath = null,
        retryAttempt = 0,
        retryAt = null,
        generation = 1,
        paused = false,
        cancelled = false,
        lastErrorCategory = null,
        files = states.mapIndexed { index, state ->
            ProgressiveLoadFileEntity(
                BOOK_ID, "chapter-$index.md", "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}",
                index, "r$index", 10, null, state, initialPriority(index),
            )
        },
    )

    private companion object {
        const val BOOK_ID = "11111111-1111-1111-1111-111111111111"
        val CACHED = ProgressiveLoadFileState.CACHED
        val PENDING = ProgressiveLoadFileState.PENDING
    }
}
```

- [ ] **Step 2: Run the pure model test and record RED**

Run: `./gradlew testDebugUnitTest --tests "net.inkyquill.pocketeditor.load.ProgressiveLoadStateTest"`

Expected: compilation fails with unresolved `ProgressiveLoadSnapshot`, `ProgressiveLoadFileEntity`, and `initialPriority`.

- [ ] **Step 3: Add exact entities and pure models**

Append these entities to `Entities.kt` and create `ProgressiveLoadModels.kt`:

```kotlin
@Entity(tableName = "progressive_load_jobs")
data class ProgressiveLoadJobEntity(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "remote_root_path") val remoteRootPath: String,
    val phase: ProgressiveLoadPhase,
    @ColumnInfo(name = "total_files") val totalFiles: Int,
    @ColumnInfo(name = "completed_files") val completedFiles: Int,
    @ColumnInfo(name = "active_path") val activePath: String?,
    @ColumnInfo(name = "retry_attempt") val retryAttempt: Int,
    @ColumnInfo(name = "retry_at") val retryAt: Long?,
    val generation: Long,
    val paused: Boolean,
    val cancelled: Boolean,
    @ColumnInfo(name = "last_error_category") val lastErrorCategory: ProgressiveLoadErrorCategory?,
)

@Entity(
    tableName = "progressive_load_files",
    primaryKeys = ["book_id", "path"],
    indices = [Index(value = ["book_id", "chapter_id"], unique = true)],
)
data class ProgressiveLoadFileEntity(
    @ColumnInfo(name = "book_id") val bookId: String,
    val path: String,
    @ColumnInfo(name = "chapter_id") val chapterId: String,
    @ColumnInfo(name = "spine_index") val spineIndex: Int,
    @ColumnInfo(name = "expected_revision") val expectedRevision: String,
    @ColumnInfo(name = "expected_size") val expectedSize: Long?,
    val sha256: String?,
    val state: ProgressiveLoadFileState,
    val priority: Int,
    @ColumnInfo(name = "claim_generation") val claimGeneration: Long? = null,
)
```

```kotlin
package net.inkyquill.pocketeditor.load

import androidx.room.Embedded
import androidx.room.Relation
import net.inkyquill.pocketeditor.database.ProgressiveLoadFileEntity
import net.inkyquill.pocketeditor.database.ProgressiveLoadJobEntity

enum class ProgressiveLoadPhase { PREPARING, INITIAL, BACKGROUND, PAUSED, CANCELLED, ACTION_REQUIRED, COMPLETE }
enum class ProgressiveLoadFileState { PENDING, DOWNLOADING, CACHED, ACTION_REQUIRED }
enum class ProgressiveLoadErrorCategory {
    OFFLINE, TIMEOUT, RATE_LIMITED, SERVER, TEMPORARY_AVAILABILITY, UNAUTHORIZED, INVALID_REMOTE
}

data class ProgressiveLoadJobWithFiles(
    @Embedded val job: ProgressiveLoadJobEntity,
    @Relation(parentColumn = "book_id", entityColumn = "book_id")
    val files: List<ProgressiveLoadFileEntity>,
)

data class ProgressiveLoadSnapshot(
    val bookId: String,
    val remoteRootPath: String,
    val phase: ProgressiveLoadPhase,
    val totalFiles: Int,
    val completedFiles: Int,
    val activePath: String?,
    val retryAttempt: Int,
    val retryAt: Long?,
    val generation: Long,
    val paused: Boolean,
    val cancelled: Boolean,
    val lastErrorCategory: ProgressiveLoadErrorCategory?,
    val files: List<ProgressiveLoadFileEntity>,
) {
    val initialReady: Boolean
        get() = files.sortedBy(ProgressiveLoadFileEntity::spineIndex)
            .take(minOf(INITIAL_CHAPTER_COUNT, files.size))
            .all { it.state == ProgressiveLoadFileState.CACHED }
}

fun ProgressiveLoadJobWithFiles.toSnapshot() = ProgressiveLoadSnapshot(
    job.bookId, job.remoteRootPath, job.phase, job.totalFiles, job.completedFiles,
    job.activePath, job.retryAttempt, job.retryAt, job.generation, job.paused,
    job.cancelled, job.lastErrorCategory, files.sortedBy(ProgressiveLoadFileEntity::spineIndex),
)

fun initialPriority(spineIndex: Int): Int {
    require(spineIndex >= 0)
    return if (spineIndex < INITIAL_CHAPTER_COUNT) INITIAL_PRIORITY else BACKGROUND_PRIORITY
}

const val ON_DEMAND_PRIORITY = 2
const val INITIAL_PRIORITY = 1
const val BACKGROUND_PRIORITY = 0
private const val INITIAL_CHAPTER_COUNT = 3
```

Add converters for all three enums to `DatabaseConverters` using `value.name`/`valueOf`, then rerun the focused unit test and expect PASS.

- [ ] **Step 4: Commit the pure model slice**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/database/Entities.kt app/src/main/java/net/inkyquill/pocketeditor/load/ProgressiveLoadModels.kt app/src/test/java/net/inkyquill/pocketeditor/load/ProgressiveLoadStateTest.kt
git commit -m "feat: model progressive book loads"
```

- [ ] **Step 5: Add the DAO RED test for one-file claiming and repeated priority coalescing**

Create `ProgressiveLoadDaoTest.kt` using an in-memory Room database. The decisive test body is:

```kotlin
@RunWith(AndroidJUnit4::class)
class ProgressiveLoadDaoTest {
    private lateinit var database: PocketEditorDatabase
    private lateinit var dao: ProgressiveLoadDao

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), PocketEditorDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.progressiveLoadDao()
    }

    @After fun tearDown() = database.close()

    @Test fun claimIsSequentialAndPriorityRequestCoalesces() = runBlocking {
        dao.insertJob(job())
        dao.insertFiles((0..3).map(::file))
        dao.prioritize(BOOK_ID, "chapter-3.md")
        dao.prioritize(BOOK_ID, "chapter-3.md")

        val claimed = dao.claimNext(BOOK_ID, generation = 1)

        assertEquals("chapter-3.md", claimed?.path)
        assertEquals(1, dao.getFiles(BOOK_ID).count { it.state == ProgressiveLoadFileState.DOWNLOADING })
        assertEquals(ON_DEMAND_PRIORITY, dao.getFiles(BOOK_ID).single { it.path == "chapter-3.md" }.priority)
    }
}
```

Use private `job()` and `file(index)` helpers that construct the exact entities from Step 3 with `generation = 1`, `phase = INITIAL`, and four `PENDING` rows.

- [ ] **Step 6: Run the DAO test and record RED**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.database.ProgressiveLoadDaoTest`

Expected: compilation fails because `ProgressiveLoadDao` and `PocketEditorDatabase.progressiveLoadDao()` do not exist. If no device is connected, use `./gradlew compileDebugAndroidTestKotlin` only to capture the compile RED; label runtime `NOT RUN`, never PASS.

- [ ] **Step 7: Implement the DAO with transactional claim and durable control mutations**

Create `ProgressiveLoadDao.kt` with these exact methods; keep `claimNext`, `markCached`, and cancellation recovery as Room default `@Transaction` methods so each state transition is serialized:

```kotlin
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

    @Query("SELECT * FROM progressive_load_files WHERE book_id = :bookId ORDER BY spine_index")
    suspend fun getFiles(bookId: String): List<ProgressiveLoadFileEntity>

    @Query("SELECT * FROM progressive_load_files WHERE book_id = :bookId AND chapter_id = :chapterId")
    fun observeChapter(bookId: String, chapterId: String): Flow<ProgressiveLoadFileEntity?>

    @Query("SELECT * FROM progressive_load_files WHERE book_id = :bookId AND state = 'PENDING' ORDER BY priority DESC, spine_index ASC LIMIT 1")
    suspend fun nextPending(bookId: String): ProgressiveLoadFileEntity?

    @Update suspend fun updateJob(job: ProgressiveLoadJobEntity)
    @Update suspend fun updateFile(file: ProgressiveLoadFileEntity)

    @Query("UPDATE progressive_load_files SET priority = 2 WHERE book_id = :bookId AND path = :path AND state = 'PENDING'")
    suspend fun prioritize(bookId: String, path: String): Int

    @Query("DELETE FROM progressive_load_jobs WHERE book_id = :bookId")
    suspend fun deleteJob(bookId: String)

    @Query("DELETE FROM progressive_load_files WHERE book_id = :bookId")
    suspend fun deleteFiles(bookId: String)

    @Transaction
    suspend fun claimNext(bookId: String, generation: Long): ProgressiveLoadFileEntity? {
        val job = getJob(bookId) ?: return null
        if (job.generation != generation || job.paused || job.cancelled || job.phase == ProgressiveLoadPhase.ACTION_REQUIRED) return null
        check(getFiles(bookId).none { it.state == ProgressiveLoadFileState.DOWNLOADING })
        val next = nextPending(bookId) ?: return null
        updateFile(next.copy(state = ProgressiveLoadFileState.DOWNLOADING, claimGeneration = generation))
        updateJob(job.copy(activePath = next.path, retryAt = null, lastErrorCategory = null))
        return next.copy(state = ProgressiveLoadFileState.DOWNLOADING)
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
        if (file.state == ProgressiveLoadFileState.DOWNLOADING && file.claimGeneration == generation) {
            updateFile(file.copy(state = ProgressiveLoadFileState.PENDING, claimGeneration = null))
        }
        if (job.generation == generation) {
            updateJob(job.copy(activePath = null, retryAttempt = retryAttempt, retryAt = retryAt, lastErrorCategory = category))
        } else if (job.activePath == path) {
            updateJob(job.copy(activePath = null))
        }
    }
}
```

Register both entities and `abstract fun progressiveLoadDao(): ProgressiveLoadDao` in `PocketEditorDatabase`. Add foreign-key cleanup explicitly in `RoomYandexBookLibraryData.forget`: call `deleteFiles(bookId)` and `deleteJob(bookId)` in the same library transaction before deleting the root.

- [ ] **Step 8: Run the DAO test and record GREEN**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.database.ProgressiveLoadDaoTest`

Expected: PASS with one `DOWNLOADING` row and one coalesced on-demand priority. Without a device, run `./gradlew compileDebugAndroidTestKotlin`, record compilation only, and leave runtime unverified.

- [ ] **Step 9: Commit the DAO slice**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/database/ProgressiveLoadDao.kt app/src/main/java/net/inkyquill/pocketeditor/database/PocketEditorDatabase.kt app/src/main/java/net/inkyquill/pocketeditor/ui/books/RoomYandexBookLibraryData.kt app/src/androidTest/java/net/inkyquill/pocketeditor/database/ProgressiveLoadDaoTest.kt
git commit -m "feat: persist progressive load state"
```

- [ ] **Step 10: Add the v4→v5 migration RED test**

Extend `PocketEditorMigrationTest` with a v4 database containing a real serialized draft row, then validate the new tables and preserved legacy row:

```kotlin
@Test
fun versionFourAddsProgressiveTablesWithoutDroppingLegacyDrafts() {
    helper.createDatabase(DATABASE_NAME_V4, 4).use { database ->
        database.execSQL(
            "INSERT INTO import_drafts (book_id, remote_root_path, local_directory, document_json, updated_at) VALUES (?, ?, ?, ?, ?)",
            arrayOf(BOOK_ID, "disk:/Book", "/cache/$BOOK_ID", LEGACY_DRAFT_JSON, 20L),
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
```

Define `LEGACY_DRAFT_JSON` as a complete schema-v1 JSON string with `BOOK_ID`, `CHAPTER_ID`, `disk:/Book/chapter.md`, revision `r1`, 64-character SHA, size `7`, and phase `READY`.

- [ ] **Step 11: Run the migration test and record RED**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.database.PocketEditorMigrationTest`

Expected: compilation fails because `MIGRATION_4_5` and schema version 5 do not exist. Without a device, use `./gradlew compileDebugAndroidTestKotlin` and report only compile RED.

- [ ] **Step 12: Implement and register database v5**

Set `version = 5`, add both entities, and add `MIGRATION_4_5` with exact SQL matching the entities:

```kotlin
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
```

Add `MIGRATION_4_5` to `AppContainer`'s `addMigrations` list. Do not decode JSON inside SQL: v5 retains `import_drafts` until the Kotlin adapter atomically publishes a local root/spine in Task 3.

- [ ] **Step 13: Generate and inspect schema 5**

Run: `./gradlew :app:kspDebugKotlin`

Expected: `app/schemas/net.inkyquill.pocketeditor.database.PocketEditorDatabase/5.json` exists; its `version` is `5`, both tables are present, and the `(book_id, chapter_id)` unique index is present. Run `git diff -- app/schemas/net.inkyquill.pocketeditor.database.PocketEditorDatabase/5.json` and reject unrelated schema changes.

- [ ] **Step 14: Run the migration test and record GREEN**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.database.PocketEditorMigrationTest`

Expected: all historical edges 1→2, 2→3, 3→4, and 4→5 PASS. Without a device, compilation is not migration runtime evidence; record `NOT RUN — no connected device`.

- [ ] **Step 15: Add the legacy adapter RED test**

Create a fake `ImportDraftDao` row and fake cache probe, then assert stable IDs, cached matching rows, and pending mismatches:

```kotlin
@Test
fun `ready legacy draft becomes a complete seed without network`() = runTest {
    val adapter = LegacyImportDraftAdapter(
        rows = { listOf(entity(phase = ImportDraftPhase.READY)) },
        matchingSource = { _, path, _, _ -> if (path == "chapter-1.md") "# One".encodeToByteArray() else null },
    )

    val seed = adapter.seeds().single()

    assertEquals(listOf(CHAPTER_1_ID, CHAPTER_2_ID), seed.manifest.chapters.map(ChapterEntry::id))
    assertEquals(listOf("chapter-1.md", "chapter-2.md"), seed.manifest.chapters.map(ChapterEntry::path))
    assertEquals(setOf("chapter-1.md"), seed.cachedSources.keys)
    assertEquals(ProgressiveLoadFileState.CACHED, seed.files[0].state)
    assertEquals(ProgressiveLoadFileState.PENDING, seed.files[1].state)
    assertTrue(seed.readyWithoutNetwork.not())
}
```

Add a second two-chapter case where both cache probes match and assert `readyWithoutNetwork == true`.

- [ ] **Step 16: Run the legacy adapter test and record RED**

Run: `./gradlew testDebugUnitTest --tests "net.inkyquill.pocketeditor.load.LegacyImportDraftAdapterTest"`

Expected: compilation fails because `LegacyImportDraftAdapter` and `LegacyProgressiveSeed` do not exist.

- [ ] **Step 17: Implement the legacy adapter as a network-free seed producer**

Create `LegacyImportDraftAdapter.kt` with an injectable primary constructor for tests and a production constructor for the DAO/store:

```kotlin
data class LegacyProgressiveSeed(
    val manifest: BookManifest,
    val remoteRootPath: String,
    val files: List<ProgressiveLoadFileEntity>,
    val cachedSources: Map<String, ByteArray>,
) {
    val readyWithoutNetwork: Boolean get() = files.all { it.state == ProgressiveLoadFileState.CACHED }
}

class LegacyImportDraftAdapter internal constructor(
    private val rows: suspend () -> List<ImportDraftEntity>,
    private val matchingSource: suspend (String, String, String, String) -> ByteArray?,
) {
    constructor(dao: ImportDraftDao, store: ImportDraftStore) : this(
        rows = dao::getAll,
        matchingSource = store::readMatchingSource,
    )

    suspend fun seeds(): List<LegacyProgressiveSeed> = rows().map { entity ->
        val document = ImportDraftDocument.decode(entity.documentJson)
        require(document.bookId == entity.bookId && document.remoteRootPath == entity.remoteRootPath)
        val cached = linkedMapOf<String, ByteArray>()
        val files = document.chapters.mapIndexed { index, chapter ->
            val bytes = matchingSource(document.bookId, chapter.path, chapter.remoteRevision, chapter.sha256)
            if (bytes != null) cached[chapter.path] = bytes
            ProgressiveLoadFileEntity(
                document.bookId, chapter.path, chapter.id, index, chapter.remoteRevision,
                chapter.byteSize, bytes?.let { chapter.sha256 },
                if (bytes == null) ProgressiveLoadFileState.PENDING else ProgressiveLoadFileState.CACHED,
                initialPriority(index),
            )
        }
        LegacyProgressiveSeed(
            BookManifest(
                bookId = document.bookId,
                title = document.title.trim().ifBlank { entity.remoteRootPath.substringAfterLast('/') },
                chapters = document.chapters.map { ChapterEntry(it.id, it.path) },
            ),
            entity.remoteRootPath,
            files,
            cached,
        )
    }
}
```

The adapter deliberately ignores legacy editable `title`/`included` chapter fields: all paths enter the schema-v2 spine, IDs remain stable, and displayed titles are later extracted from cached bytes.

- [ ] **Step 18: Run Task 1 focused tests and commit**

Run: `./gradlew testDebugUnitTest --tests "net.inkyquill.pocketeditor.load.*" && ./gradlew compileDebugAndroidTestKotlin`

Expected: unit tests PASS and instrumentation sources compile. Then commit:

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/database app/src/main/java/net/inkyquill/pocketeditor/load app/src/main/java/net/inkyquill/pocketeditor/PocketEditorApp.kt app/src/test/java/net/inkyquill/pocketeditor/load app/src/androidTest/java/net/inkyquill/pocketeditor/database app/schemas/net.inkyquill.pocketeditor.database.PocketEditorDatabase/5.json
git commit -m "feat: migrate progressive load persistence"
```

### Task 2: Shared Yandex classification and unique progressive worker foundation

**Files:**
- Create: `app/src/main/java/net/inkyquill/pocketeditor/load/ProgressiveLoadRetry.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/load/ProgressiveLoadScheduler.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/load/ProgressiveLoadWorker.kt`
- Create: `app/src/test/java/net/inkyquill/pocketeditor/load/ProgressiveLoadRetryTest.kt`
- Create: `app/src/test/java/net/inkyquill/pocketeditor/load/ProgressiveLoadSchedulerTest.kt`
- Create: `app/src/test/java/net/inkyquill/pocketeditor/load/ProgressiveLoadWorkerTest.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/yandex/YandexDiskApi.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/yandex/YandexDiskGateway.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/database/ProgressiveLoadDao.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/sync/SyncWorker.kt`
- Modify: `app/src/test/java/net/inkyquill/pocketeditor/yandex/YandexDiskGatewayTest.kt`
- Modify: `app/src/test/java/net/inkyquill/pocketeditor/sync/SyncWorkerTest.kt`

**Interfaces:**
- Consumes: `ProgressiveLoadDao.getJob/updateJob/restorePending`; `NetworkAvailability.hasValidatedInternet(): Boolean`; `YandexDiskError`; WorkManager `NetworkType.CONNECTED`; Task 1 `ProgressiveLoadErrorCategory` and durable `generation`.
- Produces: `RetryAfterParser.parse(value: String?, now: Instant): Duration?`; `ProgressiveLoadRetryPolicy.classify(Throwable, attempt: Int): LoadFailureDisposition`; `ProgressiveLoadRunner.runOne(bookId: String, generation: Long): ProgressiveLoadRunResult`; `ProgressiveLoadScheduleStore.current/publishIfCurrent/admit/stop`; `ProgressiveLoadScheduler.start/replaceNow/pause/continueLoad/cancel`; `ProgressiveLoadWorkerFactory`; `PocketEditorWorkerFactory` delegating to sync and load factories.

- [ ] **Step 1: Add transfer-host response-classification RED tests**

Add these focused cases beside the current download redirect tests in `YandexDiskGatewayTest.kt`; each test must enqueue metadata, a download link, then the transfer response:

```kotlin
@Test
fun `transfer 429 preserves Retry-After`() {
    enqueueJson("""{"path":"disk:/Book/chapter.md","revision":"r1"}""")
    enqueueJson("""{"href":"${server.url("/transfer")}","method":"GET","templated":false}""")
    server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "18").build())

    val failure = assertThrows(YandexDiskError.RateLimited::class.java) {
        runBlocking { gateway.download("disk:/Book/chapter.md") }
    }
    assertEquals(18L, failure.retryAfterSeconds)
}

@Test
fun `transfer 503 preserves Retry-After while malformed value is ignored`() {
    enqueueJson("""{"path":"disk:/Book/chapter.md","revision":"r1"}""")
    enqueueJson("""{"href":"${server.url("/transfer")}","method":"GET","templated":false}""")
    server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "not-a-date").build())

    val failure = assertThrows(YandexDiskError.ServerFailure::class.java) {
        runBlocking { gateway.download("disk:/Book/chapter.md") }
    }
    assertEquals(503, failure.statusCode)
    assertEquals(null, failure.retryAfterSeconds)
}
```

Add table-driven transfer cases for 401→`Unauthorized`, 404→`NotFound`, and 500→`ServerFailure`. Preserve the existing redirect-count and trusted-host tests unchanged.

- [ ] **Step 2: Run the gateway tests and record RED**

Run: `./gradlew testDebugUnitTest --tests "net.inkyquill.pocketeditor.yandex.YandexDiskGatewayTest"`

Expected: `transfer 429` fails with `InvalidRemote`; `ServerFailure.retryAfterSeconds` does not compile.

- [ ] **Step 3: Route API and transfer responses through one classifier**

Change `ServerFailure` to carry the optional delay:

```kotlin
class ServerFailure(
    val statusCode: Int,
    val retryAfterSeconds: Long?,
) : YandexDiskError("Yandex Disk server failure ($statusCode)")
```

In `YandexDiskApi`, replace the duplicated non-success branches with one exact function, used by `execute` and by each non-redirect response in `executeDownload`:

```kotlin
private fun classify(response: Response, lockAcquisition: Boolean): YandexDiskError {
    val status = response.code
        val retryAfter = parseRetryAfterSeconds(response.header("Retry-After"), Instant.now())
    return when {
        status == 401 -> YandexDiskError.Unauthorized()
        status == 404 -> YandexDiskError.NotFound()
        status == 409 && lockAcquisition -> YandexDiskError.LockHeld()
        status == 429 -> YandexDiskError.RateLimited(retryAfter)
        status >= 500 -> YandexDiskError.ServerFailure(status, retryAfter)
        else -> YandexDiskError.InvalidRemote("Unexpected Yandex Disk response ($status)")
    }
}

private fun Response.closeAndClassify(lockAcquisition: Boolean = false): Nothing {
    val failure = classify(this, lockAcquisition)
    close()
    throw failure
}

private fun parseRetryAfterSeconds(value: String?, now: Instant): Long? {
    val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    text.toLongOrNull()?.takeIf { it >= 0 }?.let { return it }
    val target = runCatching {
        ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
    }.getOrNull() ?: return null
    return Duration.between(now, target).seconds.coerceAtLeast(0)
}
```

`executeDownload` must continue following only the existing trusted redirect codes/hosts; for every other non-success it calls `response.closeAndClassify()`.

- [ ] **Step 4: Run the gateway tests and record GREEN, then commit**

Run: `./gradlew testDebugUnitTest --tests "net.inkyquill.pocketeditor.yandex.YandexDiskGatewayTest"`

Expected: all gateway tests PASS, including transfer 401/404/429/5xx and existing URL-trust coverage.

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/yandex/YandexDiskApi.kt app/src/main/java/net/inkyquill/pocketeditor/yandex/YandexDiskGateway.kt app/src/test/java/net/inkyquill/pocketeditor/yandex/YandexDiskGatewayTest.kt
git commit -m "fix: classify Yandex transfer failures"
```

- [ ] **Step 5: Add retry policy RED tests for Retry-After, unbounded capped backoff, and action states**

Create `ProgressiveLoadRetryTest.kt`:

```kotlin
class ProgressiveLoadRetryTest {
    private val now = Instant.parse("2026-08-15T10:00:00Z")
    private val policy = ProgressiveLoadRetryPolicy(
        now = { now },
        jitterMillis = { 0L },
    )

    @Test fun `Retry-After seconds and HTTP date are honored`() {
        assertEquals(Duration.ofSeconds(18), RetryAfterParser.parse("18", now))
        assertEquals(
            Duration.ofSeconds(30),
            RetryAfterParser.parse("Sat, 15 Aug 2026 10:00:30 GMT", now),
        )
        assertEquals(null, RetryAfterParser.parse("invalid", now))
    }

    @Test fun `transient attempts never become terminal and cap at six hours`() {
        val failure = YandexDiskError.ServerFailure(503, retryAfterSeconds = null)
        val attemptOne = policy.classify(failure, attempt = 1) as LoadFailureDisposition.Retry
        val attemptFifty = policy.classify(failure, attempt = 50) as LoadFailureDisposition.Retry
        assertEquals(Duration.ofSeconds(10), Duration.between(now, attemptOne.retryAt))
        assertEquals(Duration.ofHours(6), Duration.between(now, attemptFifty.retryAt))
    }

    @Test fun `authorization and invalid data are the only action dispositions`() {
        assertEquals(LoadFailureDisposition.SignInRequired, policy.classify(YandexDiskError.Unauthorized(), 1))
        assertEquals(
            LoadFailureDisposition.ActionRequired(ProgressiveLoadErrorCategory.INVALID_REMOTE),
            policy.classify(YandexDiskError.InvalidRemote("bad binder"), 1),
        )
    }
}
```

Also assert `YandexDiskError.Offline`, `SocketTimeoutException`, 429, 5xx, and `TemporaryAvailabilityException` each return `Retry`, with the exact category stored and no signed URL/server body in the model.

- [ ] **Step 6: Run the retry tests and record RED**

Run: `./gradlew testDebugUnitTest --tests "net.inkyquill.pocketeditor.load.ProgressiveLoadRetryTest"`

Expected: compilation fails because `ProgressiveLoadRetryPolicy`, `RetryAfterParser`, and `LoadFailureDisposition` do not exist.

- [ ] **Step 7: Implement the retry classifier and capped jittered backoff**

Create `ProgressiveLoadRetry.kt` with these types and algorithms:

```kotlin
class TemporaryAvailabilityException(message: String) : IOException(message)

sealed interface LoadFailureDisposition {
    data class Retry(val category: ProgressiveLoadErrorCategory, val retryAt: Instant) : LoadFailureDisposition
    data object SignInRequired : LoadFailureDisposition
    data class ActionRequired(val category: ProgressiveLoadErrorCategory) : LoadFailureDisposition
}

object RetryAfterParser {
    fun parse(value: String?, now: Instant): Duration? {
        val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        text.toLongOrNull()?.takeIf { it >= 0 }?.let { return Duration.ofSeconds(it) }
        val instant = runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
            ?: return null
        return Duration.between(now, instant).coerceAtLeast(Duration.ZERO)
    }
}

class ProgressiveLoadRetryPolicy(
    private val now: () -> Instant = Instant::now,
    private val jitterMillis: (Long) -> Long = { ceiling -> Random.nextLong(0, ceiling + 1) },
) {
    fun classify(failure: Throwable, attempt: Int): LoadFailureDisposition {
        require(attempt > 0)
        return when (failure) {
            is YandexDiskError.Unauthorized -> LoadFailureDisposition.SignInRequired
            is YandexDiskError.InvalidRemote -> LoadFailureDisposition.ActionRequired(ProgressiveLoadErrorCategory.INVALID_REMOTE)
            is YandexDiskError.RateLimited -> retry(
                ProgressiveLoadErrorCategory.RATE_LIMITED, attempt,
                failure.retryAfterSeconds?.let(Duration::ofSeconds),
            )
            is YandexDiskError.ServerFailure -> retry(
                ProgressiveLoadErrorCategory.SERVER, attempt,
                failure.retryAfterSeconds?.let(Duration::ofSeconds),
            )
            is YandexDiskError.NotFound, is TemporaryAvailabilityException ->
                retry(ProgressiveLoadErrorCategory.TEMPORARY_AVAILABILITY, attempt)
            is SocketTimeoutException -> retry(ProgressiveLoadErrorCategory.TIMEOUT, attempt)
            is YandexDiskError.Offline, is IOException -> retry(ProgressiveLoadErrorCategory.OFFLINE, attempt)
            else -> LoadFailureDisposition.ActionRequired(ProgressiveLoadErrorCategory.INVALID_REMOTE)
        }
    }

    private fun retry(
        category: ProgressiveLoadErrorCategory,
        attempt: Int,
        explicit: Duration? = null,
    ): LoadFailureDisposition.Retry {
        val exponentialSeconds = 10L.shl((attempt - 1).coerceAtMost(20)).coerceAtMost(MAX_BACKOFF.seconds)
        val base = explicit ?: Duration.ofSeconds(exponentialSeconds)
        val capped = base.coerceAtMost(MAX_BACKOFF)
        val jitter = if (explicit != null || capped.isZero) 0L else jitterMillis((capped.toMillis() / 5).coerceAtLeast(1))
        return LoadFailureDisposition.Retry(category, now().plusMillis(capped.toMillis() + jitter))
    }

    private companion object { val MAX_BACKOFF: Duration = Duration.ofHours(6) }
}
```

API and transfer responses both call `parseRetryAfterSeconds` before constructing `RateLimited`/`ServerFailure`; `RetryAfterParser` converts the persisted/public representation to a delay. Tests must cover both header formats on both hosts.

- [ ] **Step 8: Run the retry tests and record GREEN, then commit**

Run: `./gradlew testDebugUnitTest --tests "net.inkyquill.pocketeditor.load.ProgressiveLoadRetryTest"`

Expected: PASS for attempts 1 and 50, Retry-After formats, every transient category, and both action dispositions.

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/load/ProgressiveLoadRetry.kt app/src/test/java/net/inkyquill/pocketeditor/load/ProgressiveLoadRetryTest.kt app/src/main/java/net/inkyquill/pocketeditor/yandex app/src/test/java/net/inkyquill/pocketeditor/yandex
git commit -m "feat: classify progressive load retries"
```

- [ ] **Step 9: Add scheduler RED tests for enqueue-before-publication and stop controls**

Create `ProgressiveLoadSchedulerTest.kt` around a `RecordingProgressiveLoadWorkQueue` and `InMemoryProgressiveLoadScheduleStore`. Add these exact race assertions:

```kotlin
@Test
fun `queue failure keeps current generation and claim valid`() = runTest {
    val store = InMemoryProgressiveLoadScheduleStore(job(generation = 4), claimedFile(generation = 4))
    val queue = RecordingProgressiveLoadWorkQueue(failure = IOException("queue unavailable"))

    assertThrows<IOException> { ProgressiveLoadScheduler(queue, store).replaceNow(BOOK_ID) }

    assertEquals(4, store.job.generation)
    assertEquals(ProgressiveLoadFileState.DOWNLOADING, store.file.state)
    assertEquals(4, store.file.claimGeneration)
}

@Test
fun `accepted priority request publishes only after enqueue and restores old claim`() = runTest {
    val store = InMemoryProgressiveLoadScheduleStore(
        job(generation = 4),
        claimedFile(generation = 4).copy(priority = ON_DEMAND_PRIORITY),
    )
    val queue = RecordingProgressiveLoadWorkQueue(
        beforeEnqueue = {
            assertEquals(4, store.job.generation)
            assertEquals(ProgressiveLoadFileState.DOWNLOADING, store.file.state)
            assertEquals(ON_DEMAND_PRIORITY, store.file.priority)
        },
    )

    ProgressiveLoadScheduler(queue, store).replaceNow(BOOK_ID)

    assertEquals(listOf(5L), queue.requests.map(ProgressiveLoadWorkRequest::generation))
    assertEquals(5, store.job.generation)
    assertEquals(ProgressiveLoadFileState.PENDING, store.file.state)
    assertNull(store.file.claimGeneration)
    assertEquals(ON_DEMAND_PRIORITY, store.file.priority)
}
```

Add pause and cancel tests proving `stop(bookId, paused = true/false, cancelled = true/false)` advances/restores the claim before `queue.cancel`, retains every `CACHED` row, and creates no replacement request. Add a Continue test proving it uses the enqueue-first replacement path.

- [ ] **Step 10: Run scheduler tests and record RED**

Run: `./gradlew testDebugUnitTest --tests "net.inkyquill.pocketeditor.load.ProgressiveLoadSchedulerTest"`

Expected: compilation fails because `publishIfCurrent`, `admit`, and `stop` do not exist.

- [ ] **Step 11: Implement the two-phase durable generation contract**

Define the exact scheduling types:

```kotlin
data class ProgressiveLoadWorkRequest(
    val uniqueName: String,
    val bookId: String,
    val generation: Long,
    val delay: Duration,
)

interface ProgressiveLoadWorkQueue {
    suspend fun enqueue(request: ProgressiveLoadWorkRequest)
    fun cancel(uniqueName: String)
}

enum class GenerationAdmission { CURRENT, PUBLISHED_NEXT, STALE }

interface ProgressiveLoadScheduleStore {
    suspend fun current(bookId: String): Long?
    suspend fun publishIfCurrent(
        bookId: String,
        expectedCurrent: Long,
        next: Long,
        paused: Boolean,
        cancelled: Boolean,
    ): Boolean
    suspend fun admit(bookId: String, requested: Long): GenerationAdmission
    suspend fun stop(bookId: String, paused: Boolean, cancelled: Boolean): Long
}
```

Implement every compare/reset/write inside `PocketEditorDatabase.withTransaction`:

```kotlin
class RoomProgressiveLoadScheduleStore(
    private val database: PocketEditorDatabase,
    private val dao: ProgressiveLoadDao,
) : ProgressiveLoadScheduleStore {
    override suspend fun current(bookId: String): Long? = dao.getJob(bookId)?.generation

    override suspend fun publishIfCurrent(
        bookId: String,
        expectedCurrent: Long,
        next: Long,
        paused: Boolean,
        cancelled: Boolean,
    ): Boolean = database.withTransaction {
        publishLocked(bookId, expectedCurrent, next, paused, cancelled)
    }

    override suspend fun admit(bookId: String, requested: Long): GenerationAdmission =
        database.withTransaction {
            val job = dao.getJob(bookId) ?: return@withTransaction GenerationAdmission.STALE
            val current = job.generation
            when {
                requested == current && !job.paused && !job.cancelled &&
                    job.phase != ProgressiveLoadPhase.ACTION_REQUIRED -> GenerationAdmission.CURRENT
                current != Long.MAX_VALUE && requested == current + 1 -> {
                    check(publishLocked(bookId, current, requested, paused = false, cancelled = false))
                    GenerationAdmission.PUBLISHED_NEXT
                }
                else -> GenerationAdmission.STALE
            }
        }

    override suspend fun stop(bookId: String, paused: Boolean, cancelled: Boolean): Long =
        database.withTransaction {
            require(paused.xor(cancelled))
            val current = requireNotNull(dao.getJob(bookId)).generation
            val next = Math.addExact(current, 1L)
            check(publishLocked(bookId, current, next, paused, cancelled))
            next
        }

    private suspend fun publishLocked(
        bookId: String,
        expectedCurrent: Long,
        next: Long,
        paused: Boolean,
        cancelled: Boolean,
    ): Boolean {
        require(next == Math.addExact(expectedCurrent, 1L))
        val job = dao.getJob(bookId) ?: return false
        if (job.generation != expectedCurrent) return false
        dao.getFiles(bookId)
            .filter {
                it.state == ProgressiveLoadFileState.DOWNLOADING &&
                    it.claimGeneration == expectedCurrent
            }
            .forEach { file ->
                dao.updateFile(file.copy(state = ProgressiveLoadFileState.PENDING, claimGeneration = null))
            }
        val files = dao.getFiles(bookId)
        val initialReady = files.sortedBy(ProgressiveLoadFileEntity::spineIndex)
            .take(minOf(3, files.size))
            .all { it.state == ProgressiveLoadFileState.CACHED }
        dao.updateJob(
            job.copy(
                generation = next,
                activePath = null,
                retryAt = null,
                paused = paused,
                cancelled = cancelled,
                phase = when {
                    cancelled -> ProgressiveLoadPhase.CANCELLED
                    paused -> ProgressiveLoadPhase.PAUSED
                    job.completedFiles == job.totalFiles -> ProgressiveLoadPhase.COMPLETE
                    initialReady -> ProgressiveLoadPhase.BACKGROUND
                    else -> ProgressiveLoadPhase.INITIAL
                },
            ),
        )
        return true
    }
}
```

This accepts only exact current or exact adjacent-next work. Older work and work two or more generations ahead are stale.

- [ ] **Step 12: Implement enqueue-before-publication in the scheduler**

```kotlin
class ProgressiveLoadScheduler(
    private val queue: ProgressiveLoadWorkQueue,
    private val store: ProgressiveLoadScheduleStore,
) {
    suspend fun start(bookId: String) = replace(bookId, Duration.ZERO)
    suspend fun replaceNow(bookId: String) = replace(bookId, Duration.ZERO)
    suspend fun continueLoad(bookId: String) = replace(bookId, Duration.ZERO)

    private suspend fun replace(bookId: String, delay: Duration) {
        val current = requireNotNull(store.current(bookId))
        val next = Math.addExact(current, 1L)
        queue.enqueue(request(bookId, next, delay))
        store.publishIfCurrent(bookId, current, next, paused = false, cancelled = false)
    }

    suspend fun enqueueCurrent(bookId: String, generation: Long, delay: Duration) {
        if (store.current(bookId) == generation) queue.enqueue(request(bookId, generation, delay))
    }

    suspend fun pause(bookId: String) {
        store.stop(bookId, paused = true, cancelled = false)
        queue.cancel(uniqueName(bookId))
    }

    suspend fun cancel(bookId: String) {
        store.stop(bookId, paused = false, cancelled = true)
        queue.cancel(uniqueName(bookId))
    }

    private fun request(bookId: String, generation: Long, delay: Duration) =
        ProgressiveLoadWorkRequest(uniqueName(bookId), bookId, generation, delay)
    private fun uniqueName(bookId: String) = "progressive-load-$bookId"
}
```

`WorkManagerProgressiveLoadQueue.enqueue` calls `enqueueUniqueWork`, then suspends until the returned `Operation.result` succeeds; only that success means the replacement request was accepted durably. If it fails, `enqueue` throws and publication is never attempted, so current generation/claim remain valid. If the process dies after successful enqueue but before publication, the queued adjacent-next worker performs publication through `admit`. The request still uses `NetworkType.CONNECTED` and `ExistingWorkPolicy.REPLACE`; it never shares sync's APPEND chain.

- [ ] **Step 13: Run scheduler tests and record GREEN**

Run: `./gradlew testDebugUnitTest --tests "net.inkyquill.pocketeditor.load.ProgressiveLoadSchedulerTest"`

Expected: PASS for queue failure, enqueue-before-publication, explicit priority invalidation after acceptance, pause/cancel claim restoration, Continue, and unique work naming.

- [ ] **Step 14: Add worker RED tests for the process-death gap and strict admission**

Create `ProgressiveLoadWorkerTest.kt` with:

```kotlin
@Test
fun `adjacent worker self-publishes after enqueue publication gap`() = runTest {
    val store = InMemoryProgressiveLoadScheduleStore(job(generation = 7), claimedFile(generation = 7))
    var calls = 0
    val logic = ProgressiveLoadWorkerLogic(
        runner = ProgressiveLoadRunner { _, generation ->
            calls++
            assertEquals(8, generation)
            ProgressiveLoadRunResult.FileCached
        },
        scheduleStore = store,
        network = NetworkAvailability { true },
    )

    assertEquals(ProgressiveLoadRunResult.FileCached, logic.run(BOOK_ID, 8))
    assertEquals(8, store.job.generation)
    assertEquals(ProgressiveLoadFileState.PENDING, store.file.state)
    assertEquals(1, calls)
}

@Test
fun `worker rejects older and further-ahead generations`() = runTest {
    val store = InMemoryProgressiveLoadScheduleStore(job(generation = 7), pendingFile())
    var calls = 0
    val logic = ProgressiveLoadWorkerLogic(
        runner = ProgressiveLoadRunner { _, _ -> calls++; ProgressiveLoadRunResult.Complete },
        scheduleStore = store,
        network = NetworkAvailability { true },
    )

    assertEquals(ProgressiveLoadRunResult.Stale, logic.run(BOOK_ID, 6))
    assertEquals(ProgressiveLoadRunResult.Stale, logic.run(BOOK_ID, 9))
    assertEquals(0, calls)
}
```

Add exact-current acceptance, no-validated-network/no-runner-call, FileCached same-generation continuation, attempt-50 retry, and terminal no-enqueue cases.

- [ ] **Step 15: Run worker tests and record RED**

Run: `./gradlew testDebugUnitTest --tests "net.inkyquill.pocketeditor.load.ProgressiveLoadWorkerTest"`

Expected: compilation fails because `GenerationAdmission` and the adjacent-next publication path do not exist.

- [ ] **Step 16: Implement strict worker admission and the delegating app factory**

```kotlin
fun interface ProgressiveLoadRunner {
    suspend fun runOne(bookId: String, generation: Long): ProgressiveLoadRunResult
}

sealed interface ProgressiveLoadRunResult {
    data object FileCached : ProgressiveLoadRunResult
    data object Complete : ProgressiveLoadRunResult
    data class Retry(val retryAt: Instant) : ProgressiveLoadRunResult
    data object SignInRequired : ProgressiveLoadRunResult
    data object ActionRequired : ProgressiveLoadRunResult
    data object Stale : ProgressiveLoadRunResult
    data object NoValidatedNetwork : ProgressiveLoadRunResult
}

class ProgressiveLoadWorkerLogic(
    private val runner: ProgressiveLoadRunner,
    private val scheduleStore: ProgressiveLoadScheduleStore,
    private val network: NetworkAvailability,
) {
    suspend fun run(bookId: String, generation: Long): ProgressiveLoadRunResult {
        if (scheduleStore.admit(bookId, generation) == GenerationAdmission.STALE) {
            return ProgressiveLoadRunResult.Stale
        }
        if (!network.hasValidatedInternet()) return ProgressiveLoadRunResult.NoValidatedNetwork
        return runner.runOne(bookId, generation)
    }
}
```

`ProgressiveLoadWorker.doWork` reads `book_id`/`generation`, invokes logic once, and uses `ProgressiveLoadScheduler.enqueueCurrent` for FileCached, Retry, and the 30-second unvalidated-network check. Exact-current continuation never advances generation. Create `ProgressiveLoadWorkerFactory`, then retain the application-level delegating factory:

```kotlin
class PocketEditorWorkerFactory(
    private vararg val delegates: WorkerFactory,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = delegates.firstNotNullOfOrNull {
        it.createWorker(appContext, workerClassName, workerParameters)
    }
}
```

Do not register the progressive delegate in `AppContainer` until Task 3 supplies the real `ProgressiveBookLoader`; registering a success/no-op runner would silently discard requested work. Extend `SyncWorkerTest` only to prove `PocketEditorWorkerFactory` still returns the sync worker from the existing `SyncWorkerFactory` delegate.

- [ ] **Step 17: Run all Task 2 tests and commit**

Run:

```bash
./gradlew testDebugUnitTest --tests "net.inkyquill.pocketeditor.load.*" --tests "net.inkyquill.pocketeditor.yandex.YandexDiskGatewayTest" --tests "net.inkyquill.pocketeditor.sync.SyncWorkerTest"
```

Expected: all selected tests PASS. Then commit:

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/load app/src/main/java/net/inkyquill/pocketeditor/database/ProgressiveLoadDao.kt app/src/main/java/net/inkyquill/pocketeditor/yandex app/src/main/java/net/inkyquill/pocketeditor/sync/SyncWorker.kt app/src/test/java/net/inkyquill/pocketeditor/load app/src/test/java/net/inkyquill/pocketeditor/yandex/YandexDiskGatewayTest.kt app/src/test/java/net/inkyquill/pocketeditor/sync/SyncWorkerTest.kt
git commit -m "feat: schedule progressive load workers"
```

### Task 3: Progressive spine installation and sequential one-file runner

**Files:**
- Create: `app/src/main/java/net/inkyquill/pocketeditor/load/ProgressiveBookInstaller.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/load/ProgressiveBookLoader.kt`
- Create: `app/src/test/java/net/inkyquill/pocketeditor/load/ProgressiveBookLoaderTest.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/database/ProgressiveLoadDao.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/RoomYandexBookLibraryData.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/ImportDraftRepository.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/PocketEditorApp.kt`
- Modify: `app/src/test/java/net/inkyquill/pocketeditor/ui/books/ImportDraftRepositoryTest.kt`
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/books/RoomYandexBookLibraryDataTest.kt`

**Interfaces:**
- Consumes: Task 1 `ProgressiveLoadDao`, `ProgressiveLoadJobEntity`, `ProgressiveLoadFileEntity`, `LegacyProgressiveSeed`; Task 2 `ProgressiveLoadRunner`, `ProgressiveLoadRunResult`, `ProgressiveLoadRetryPolicy`, `ProgressiveLoadScheduler`; existing `BookManifest`, `InstallRecoveryJournal`, `AtomicBookStore`, `SyncBaseStore`, `SourceSearch.replaceChapter`, `ContentChangeNotifier`, `ReviewMutationCoordinator.withBookShared`, and `LibraryTransaction.run`.
- Produces: `ProgressiveBookSeed`; `ProgressiveBookInstaller.install(seed: ProgressiveBookSeed, cachedSources: Map<String, ByteArray> = emptyMap())`; `ProgressiveBookLoader.start(remoteRootPath: String): ProgressiveLoadSnapshot`; `ProgressiveBookLoader.runOne(bookId: String, generation: Long): ProgressiveLoadRunResult`; `ProgressiveBookLoader.migrateLegacyDrafts()`; `RoomYandexBookLibraryData.startLoad/prioritizeChapter/loadChanges/pauseLoad/continueLoad/cancelLoad` adapters used by Task 4.

- [ ] **Step 1: Add raw and manifest spine-construction RED tests**

Create `ProgressiveBookLoaderTest.kt` with a `CountingGateway`, fixed UUID factories, a recording installer, and a fake scheduler. The first tests must contain these assertions:

```kotlin
@Test
fun `raw folder uses normalized case-folded path order and generates each id once`() = runTest {
    val gateway = CountingGateway(
        entries = listOf(
            entry("b.md", "rb"), entry("a.md", "ra"), entry("A.md", "rA"),
            entry("notes.txt", "rn"), entry("nested", "rd", type = "dir"),
        ),
    )
    val installer = RecordingInstaller()
    val ids = ArrayDeque(listOf(CHAPTER_B, CHAPTER_A_UPPER, CHAPTER_A_LOWER))
    val loader = loader(gateway, installer, chapterIdFactory = ids::removeFirst)

    loader.start("disk:/Book")
    loader.start("disk:/Book")

    assertEquals(1, gateway.listCalls)
    assertEquals(listOf("A.md", "a.md", "b.md"), installer.seed.manifest.chapters.map(ChapterEntry::path))
    assertEquals(listOf(CHAPTER_B, CHAPTER_A_UPPER, CHAPTER_A_LOWER), installer.seed.manifest.chapters.map(ChapterEntry::id))
    assertTrue(installer.seed.rawBinder)
    assertEquals(listOf(1, 1, 1), installer.seed.files.map(ProgressiveLoadFileEntity::priority))
}

@Test
fun `manifest folder preserves full binder ids and order`() = runTest {
    val manifest = BookManifest(
        bookId = BOOK_ID,
        title = "Aria",
        chapters = listOf(ChapterEntry(CHAPTER_2, "z.md"), ChapterEntry(CHAPTER_1, "a.md")),
    )
    val gateway = CountingGateway(
        entries = listOf(entry("a.md", "ra"), entry("z.md", "rz"), entry(".pocket-editor.json", "rm")),
        downloads = mutableMapOf("disk:/Book/.pocket-editor.json" to remoteManifest(manifest, "rm")),
    )
    val installer = RecordingInstaller()

    loader(gateway, installer).start("disk:/Book")

    assertEquals(1, gateway.listCalls)
    assertEquals(listOf(CHAPTER_2, CHAPTER_1), installer.seed.manifest.chapters.map(ChapterEntry::id))
    assertEquals(listOf("z.md", "a.md"), installer.seed.files.map(ProgressiveLoadFileEntity::path))
    assertFalse(installer.seed.rawBinder)
}
```

Add strict cases for duplicate normalized raw paths, duplicate manifest ID/path, invalid binder UTF-8, non-Markdown tracked source, and a tracked source missing from the listing. Each must produce `YandexDiskError.InvalidRemote`/Action required and must perform zero source-body downloads.

- [ ] **Step 2: Run the spine tests and record RED**

Run: `./gradlew testDebugUnitTest --tests "net.inkyquill.pocketeditor.load.ProgressiveBookLoaderTest"`

Expected: compilation fails because `ProgressiveBookLoader`, `ProgressiveBookSeed`, and installer interfaces do not exist.

- [ ] **Step 3: Define the seed and deterministic builder**

Create these exact data structures in `ProgressiveBookLoader.kt`:

```kotlin
data class ProgressiveBookSeed(
    val manifest: BookManifest,
    val remoteRootPath: String,
    val files: List<ProgressiveLoadFileEntity>,
    val rawBinder: Boolean,
    val remoteManifest: RemoteFile?,
)

fun interface ProgressiveSeedInstaller {
    suspend fun install(seed: ProgressiveBookSeed, cachedSources: Map<String, ByteArray>): ProgressiveLoadSnapshot
}
```

Implement `start` so an already registered root or job returns its persisted snapshot without another list. Otherwise call `gateway.listFolder(root)` exactly once and pass the result to:

```kotlin
private suspend fun buildSeed(root: String, entries: List<RemoteEntry>): ProgressiveBookSeed {
    val files = entries.filter { it.type == "file" }
    val manifestEntry = files.singleOrNull { it.name == BookPaths.MANIFEST_NAME }
    return if (manifestEntry != null) buildManifestSeed(root, files, manifestEntry) else buildRawSeed(root, files)
}

private fun normalizedRelativePath(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFC).also { normalized ->
        require(normalized.isNotEmpty() && '/' !in normalized && '\\' !in normalized)
    }

private val rawPathComparator = compareBy<String>(
    { it.lowercase(Locale.ROOT) },
    { it },
)
```

`buildRawSeed` filters ordinary direct-child `.md` paths, normalizes them, rejects normalization collisions, sorts with `rawPathComparator`, invokes `chapterIdFactory()` once per path, and creates `BookManifest(schemaVersion = 2, title = root.substringAfterLast('/'), chapters = ids/paths)`. `buildManifestSeed` downloads only `.pocket-editor.json`, decodes with `StrictUtf8`, uses exact binder order/IDs, rejects duplicates through `BookManifest.decode`, and resolves every tracked source against the already captured listing. Persist each listing entry's `revision` and `size` into its file row.

- [ ] **Step 4: Run the spine tests and record GREEN**

Run: `./gradlew testDebugUnitTest --tests "net.inkyquill.pocketeditor.load.ProgressiveBookLoaderTest" --tests "*raw*" --tests "*manifest*"`

Expected: PASS; the raw order is the exact normalized path order and the manifest order is byte-for-byte binder order, with one listing and no source download.

- [ ] **Step 5: Commit the spine-builder slice**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/load/ProgressiveBookLoader.kt app/src/test/java/net/inkyquill/pocketeditor/load/ProgressiveBookLoaderTest.kt
git commit -m "feat: build durable progressive spines"
```

- [ ] **Step 6: Add installer RED tests for transactional partial registration and binder publication**

In `RoomYandexBookLibraryDataTest`, add one manifest-backed and one raw seed test. The required assertions are:

```kotlin
@Test
fun manifestSeedRegistersCompleteSpineBeforeSourceDownloads() = runTest {
    val seed = manifestSeed(chapterCount = 5)

    installer.install(seed)

    assertNotNull(bookDao.getRoot(seed.manifest.bookId))
    assertEquals(seed.manifest, bookStore.readManifest(seed.manifest.bookId))
    assertEquals(5, loadDao.getFiles(seed.manifest.bookId).size)
    assertEquals(0, loadDao.getJob(seed.manifest.bookId)?.completedFiles)
    assertEquals(seed.remoteManifest?.revision, syncDao.getRemoteRevisions(seed.manifest.bookId)
        .single { it.path == BookPaths.MANIFEST_NAME }.remoteRevision)
    assertNull(syncDao.getOutbox(seed.manifest.bookId).singleOrNull { it.path == BookPaths.MANIFEST_NAME })
}

@Test
fun rawSeedCreatesOneSchemaV2ManifestOutboxMutation() = runTest {
    val seed = rawSeed(chapterCount = 4)

    installer.install(seed)

    assertEquals(2, bookStore.readManifest(seed.manifest.bookId).schemaVersion)
    val outbox = syncDao.getOutbox(seed.manifest.bookId).single()
    assertEquals(BookPaths.MANIFEST_NAME, outbox.path)
    assertNull(outbox.baseSha256)
    assertEquals(OutboxState.PENDING, outbox.state)
}
```

Use a checkpoint observer to throw immediately after filesystem swap and prove `InstallRecoveryJournal.recover()` removes an unregistered cache; throw after database commit and prove recovery retains the registered manifest/root/job.

- [ ] **Step 7: Run the installer tests and record RED**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.books.RoomYandexBookLibraryDataTest`

Expected: compilation fails because `ProgressiveBookInstaller` is missing. With no connected device, use `./gradlew compileDebugAndroidTestKotlin` as compile evidence only.

- [ ] **Step 8: Extract and implement the first-install protocol**

Move the existing `stageBook`/`installStaged` mechanics from `RoomYandexBookLibraryData` into `ProgressiveBookInstaller.kt` without weakening its journal checks. Its public contract is:

```kotlin
class ProgressiveBookInstaller(
    private val paths: BookPaths,
    private val store: AtomicBookStore,
    private val books: BookDao,
    private val sync: SyncDao,
    private val loads: ProgressiveLoadDao,
    private val search: SourceSearch,
    private val baseStore: SyncBaseStore,
    private val transaction: LibraryTransaction,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val checkpoint: (LibraryInstallCheckpoint) -> Unit = {},
) : ProgressiveSeedInstaller {
    override suspend fun install(
        seed: ProgressiveBookSeed,
        cachedSources: Map<String, ByteArray>,
    ): ProgressiveLoadSnapshot
}
```

The staged directory contains the schema-v2 manifest plus only the validated legacy cached sources passed to it. Before the filesystem swap, validate each cached source with `StrictUtf8`; after the swap, use one `transaction.run` to:

```kotlin
books.upsertRoot(BookRootEntity(bookId, seed.remoteRootPath, paths.bookDirectory(bookId).absolutePath, now))
loads.insertJob(
    ProgressiveLoadJobEntity(
        bookId, seed.remoteRootPath,
        when {
            cachedCount == seed.files.size -> ProgressiveLoadPhase.COMPLETE
            seed.files.sortedBy { it.spineIndex }.take(minOf(3, seed.files.size))
                .all { it.path in cachedSources } -> ProgressiveLoadPhase.BACKGROUND
            else -> ProgressiveLoadPhase.INITIAL
        },
        seed.files.size, cachedCount, null, 0, null, 0, paused = false, cancelled = false, null,
    ),
)
loads.insertFiles(seed.files.map { row ->
    row.copy(
        state = if (row.path in cachedSources) ProgressiveLoadFileState.CACHED else ProgressiveLoadFileState.PENDING,
        sha256 = cachedSources[row.path]?.sha256(),
    )
})
```

For a manifest seed, write the remote manifest bytes to `SyncBaseStore`, then insert `RemoteRevisionEntity` and `MergeBaseEntity` for `.pocket-editor.json`. For a raw seed, hash the locally encoded manifest and insert exactly one `OutboxEntity(bookId, ".pocket-editor.json", localSha, null, PENDING)`. Index only `cachedSources`; never call the old all-chapter `rebuildBook` against absent files.

- [ ] **Step 9: Run the installer tests and record GREEN, then commit**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.books.RoomYandexBookLibraryDataTest`

Expected: PASS for pre-download registration, schema-v2 outbox, and both journal recovery points. Without a device, keep runtime `NOT RUN`.

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/load/ProgressiveBookInstaller.kt app/src/main/java/net/inkyquill/pocketeditor/ui/books/RoomYandexBookLibraryData.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/books/RoomYandexBookLibraryDataTest.kt
git commit -m "feat: install partial book spines"
```

- [ ] **Step 10: Add one-file runner RED tests for initial three, priority, restart skip, and no concurrency**

Add tests using a gateway whose `download` increments an active-call counter and records paths:

```kotlin
@Test
fun `runner downloads one file and returns to earliest spine after on-demand file`() = runTest {
    val fixture = installedFixture(chapterCount = 6)
    fixture.loads.prioritize(BOOK_ID, "chapter-5.md")

    assertEquals(ProgressiveLoadRunResult.FileCached, fixture.loader.runOne(BOOK_ID, generation = 1))
    assertEquals(listOf("disk:/Book/chapter-5.md"), fixture.gateway.downloadedPaths)
    assertEquals(1, fixture.gateway.maxConcurrentDownloads)

    assertEquals(ProgressiveLoadRunResult.FileCached, fixture.loader.runOne(BOOK_ID, generation = 1))
    assertEquals("disk:/Book/chapter-0.md", fixture.gateway.downloadedPaths.last())
}

@Test
fun `initial readiness flips only after three and cached matching rows skip restart download`() = runTest {
    val fixture = installedFixture(chapterCount = 5)
    repeat(2) { fixture.loader.runOne(BOOK_ID, 1) }
    assertFalse(requireNotNull(fixture.loads.snapshot(BOOK_ID)).initialReady)
    fixture.loader.runOne(BOOK_ID, 1)
    assertTrue(requireNotNull(fixture.loads.snapshot(BOOK_ID)).initialReady)

    val calls = fixture.gateway.downloadedPaths.size
    fixture.recreateLoader().runOne(BOOK_ID, 1)
    assertEquals(calls + 1, fixture.gateway.downloadedPaths.size)
    assertEquals("disk:/Book/chapter-3.md", fixture.gateway.downloadedPaths.last())
}
```

Add repeated `prioritize` to prove coalescing, an expected revision/SHA cache match to prove zero redownload, and a mismatching local SHA to prove the row returns to `PENDING` and is fetched.

- [ ] **Step 11: Add cancellation and publication-order RED tests**

Use a suspended gateway and cancel while the file is `DOWNLOADING`:

```kotlin
@Test
fun `cancellation restores claim non-cancellably and releases shared lease`() = runTest {
    val fixture = installedFixture(chapterCount = 1, downloadGate = CompletableDeferred())
    val running = backgroundScope.launch { fixture.loader.runOne(BOOK_ID, 1) }
    fixture.awaitState("chapter-0.md", ProgressiveLoadFileState.DOWNLOADING)

    running.cancelAndJoin()

    assertEquals(ProgressiveLoadFileState.PENDING, fixture.loads.getFiles(BOOK_ID).single().state)
    assertNull(fixture.loads.getJob(BOOK_ID)?.activePath)
    withTimeout(1.seconds) { fixture.mutations.withBookExclusive(BOOK_ID) { } }
}
```

Add two reconstruction tests around an injected `CachePublicationCheckpoint`:

```kotlin
@Test
fun `crash after durable cache commit replays both notifications without redownload`() = runTest {
    val fixture = installedFixture(
        chapterCount = 1,
        failAt = CachePublicationCheckpoint.DURABLE_CACHE_COMMITTED,
    )
    assertThrows<SimulatedProcessDeath> { fixture.loader.runOne(BOOK_ID, 1) }
    assertEquals(ProgressiveLoadFileState.CACHED, fixture.loads.getFiles(BOOK_ID).single().state)
    assertEquals(listOf("chapter-0.md"), fixture.sync.getPendingPublicationPaths(BOOK_ID))
    val downloads = fixture.gateway.downloadedPaths.size

    assertEquals(ProgressiveLoadRunResult.Complete, fixture.recreateLoader().runOne(BOOK_ID, 1))

    assertEquals(downloads, fixture.gateway.downloadedPaths.size)
    assertEquals(listOf("chapter-0.md"), fixture.pathNotifications)
    assertEquals(listOf(BOOK_ID), fixture.bookNotifications)
    assertTrue(fixture.sync.getPendingPublicationPaths(BOOK_ID).isEmpty())
}

@Test
fun `crash after path notification replays path then book before acknowledgement`() = runTest {
    val fixture = installedFixture(
        chapterCount = 1,
        failAt = CachePublicationCheckpoint.PATH_NOTIFIED,
    )
    assertThrows<SimulatedProcessDeath> { fixture.loader.runOne(BOOK_ID, 1) }
    assertEquals(listOf("chapter-0.md"), fixture.pathNotifications)
    assertTrue(fixture.bookNotifications.isEmpty())
    val downloads = fixture.gateway.downloadedPaths.size

    fixture.recreateLoader().runOne(BOOK_ID, 1)

    assertEquals(downloads, fixture.gateway.downloadedPaths.size)
    assertEquals(listOf("chapter-0.md", "chapter-0.md"), fixture.pathNotifications)
    assertEquals(listOf(BOOK_ID), fixture.bookNotifications)
    assertTrue(fixture.sync.getPendingPublicationPaths(BOOK_ID).isEmpty())
}
```

Define `private class SimulatedProcessDeath : CancellationException("simulated process death")`; it aborts the current coroutine without executing a success acknowledgement. Add a cancellation-after-`JOURNAL_STAGED` case proving the claim returns to `PENDING` while the journal remains for replay.

- [ ] **Step 12: Run runner tests and record RED**

Run: `./gradlew testDebugUnitTest --tests "net.inkyquill.pocketeditor.load.ProgressiveBookLoaderTest"`

Expected: runner cases fail because `runOne` is not implemented.

- [ ] **Step 13: Implement cache reconciliation, one-file claim, and atomic publication**

Add `ProgressiveLoadDao.snapshot(bookId)`, `markActionRequired`, and `resetCachedMismatch` transactions. Define the injectable checkpoints:

```kotlin
enum class CachePublicationCheckpoint {
    JOURNAL_STAGED,
    DURABLE_CACHE_COMMITTED,
    PATH_NOTIFIED,
    BOOK_NOTIFIED,
    ACKNOWLEDGED,
}
```

Implement notification replay before cache reconciliation or a new claim:

```kotlin
private suspend fun replayPendingPublications(bookId: String) {
    sync.getPendingPublicationPaths(bookId).forEach { path ->
        contentChanges.changed(bookId, path)
        contentChanges.bookChanged(bookId)
        transaction.run { sync.deletePendingPublication(bookId, path) }
    }
}
```

Implement `runOne` with this exact control order:

```kotlin
override suspend fun runOne(bookId: String, generation: Long): ProgressiveLoadRunResult {
    if (loads.getJob(bookId)?.generation != generation) return ProgressiveLoadRunResult.Stale
    replayPendingPublications(bookId)
    reconcileCachedRows(bookId)
    val claimed = loads.claimNext(bookId, generation) ?: return when {
        loads.getJob(bookId)?.generation != generation -> ProgressiveLoadRunResult.Stale
        loads.getFiles(bookId).all { it.state == ProgressiveLoadFileState.CACHED } -> ProgressiveLoadRunResult.Complete
        else -> ProgressiveLoadRunResult.ActionRequired
    }
    return try {
        val job = requireNotNull(loads.getJob(bookId))
        val remote = gateway.download(childPath(job.remoteRootPath, claimed.path))
        if (loads.getJob(bookId)?.generation != generation) {
            loads.restorePending(bookId, claimed.path, generation, null, 0, null)
            return ProgressiveLoadRunResult.Stale
        }
        if (remote.revision != claimed.expectedRevision) {
            throw TemporaryAvailabilityException("Remote revision changed before cache publication")
        }
        val text = StrictUtf8.decode(remote.bytes, "Chapter ${claimed.path}")
        val title = ChapterTitleExtractor.extract(claimed.path, remote.bytes).title
        reviewMutations.withBookShared(bookId) {
            transaction.run { sync.upsertPendingPublication(PendingPublicationEntity(bookId, claimed.path)) }
            publicationCheckpoint(CachePublicationCheckpoint.JOURNAL_STAGED)
            val revision = store.replaceDownloadedSource(bookId, claimed.path, remote.bytes)
            transaction.run {
                search.replaceChapter(bookId, claimed.chapterId, title, remote.bytes)
                sync.upsertRemoteRevision(RemoteRevisionEntity(bookId, claimed.path, remote.revision, revision.sha256))
                loads.markCached(bookId, claimed.path, generation, revision.sha256)
            }
            publicationCheckpoint(CachePublicationCheckpoint.DURABLE_CACHE_COMMITTED)
        }
        contentChanges.changed(bookId, claimed.path)
        publicationCheckpoint(CachePublicationCheckpoint.PATH_NOTIFIED)
        contentChanges.bookChanged(bookId)
        publicationCheckpoint(CachePublicationCheckpoint.BOOK_NOTIFIED)
        transaction.run { sync.deletePendingPublication(bookId, claimed.path) }
        publicationCheckpoint(CachePublicationCheckpoint.ACKNOWLEDGED)
        ProgressiveLoadRunResult.FileCached
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) {
            loads.restorePending(bookId, claimed.path, generation, null, retryAttempt = 0, retryAt = null)
        }
        throw cancelled
    } catch (failure: Throwable) {
        classifyFailure(bookId, claimed, generation, failure)
    }
}
```

The pending-publication row is the acknowledgement journal. It is staged before source bytes, revision metadata, or `CACHED` state can suppress a retry; it remains present through durable bytes/FTS/revision/load commit and both in-memory notifications; it is deleted only after path then book notification complete. Any cancellation or failure before `ACKNOWLEDGED` leaves it for at-least-once replay. Replay always sends path then book and acknowledges before the runner claims another file, so either crash point above recovers without downloading an already durable matching source again.

`classifyFailure` increments the durable attempt without a maximum. For a retry disposition, non-cancellably call `restorePending` with category/`retryAt` and return `Retry`. For `Unauthorized`, restore `PENDING`, set job `phase = PAUSED`, `paused = true`, category `UNAUTHORIZED`, and return `SignInRequired`. For invalid data, set the claimed row and job to `ACTION_REQUIRED` and return `ActionRequired`.

On `NotFound`, perform one confirming `gateway.listFolder(remoteRoot)` after the failed download: if the captured normalized path is absent, persist Action required; if it is present, return a `TEMPORARY_AVAILABILITY` retry. This relist is sequential and is the only extra request path for missing-file confirmation.

`reconcileCachedRows` reads each `CACHED` source, validates strict UTF-8 and SHA against the row plus `RemoteRevisionEntity`; matching rows stay cached, while a missing/mismatched file is transactionally reset to `PENDING` and removed from FTS/remote revision before a claim. It never redownloads a confirmed match.

- [ ] **Step 14: Run runner tests and record GREEN**

Run: `./gradlew testDebugUnitTest --tests "net.inkyquill.pocketeditor.load.ProgressiveBookLoaderTest"`

Expected: PASS for max concurrency 1, exact initial-three transition, on-demand-next behavior, earliest-spine return, restart skip, durable publication order, and non-cancellable cleanup.

- [ ] **Step 15: Add legacy promotion RED tests**

Replace the old `ImportDraftRepositoryTest.createOrResume downloads every source` expectation with adapter/promotion coverage:

```kotlin
@Test
fun `fully cached READY draft installs and completes without gateway access`() = runTest {
    val legacy = legacySeed(states = listOf(CACHED, CACHED))
    val gateway = CountingGateway(errorOnAnyCall = true)

    loader(gateway = gateway, legacySeeds = listOf(legacy)).migrateLegacyDrafts()

    assertEquals(ProgressiveLoadPhase.COMPLETE, loadDao.getJob(legacy.manifest.bookId)?.phase)
    assertEquals(0, gateway.listCalls + gateway.downloadCalls)
    assertNull(importDraftDao.getByBookId(legacy.manifest.bookId))
}
```

Add a partial legacy draft case: matching sources become `CACHED`, mismatches become `PENDING`, the schema-v2 manifest preserves every original ID/path, and scheduler starts only after install commits.

- [ ] **Step 16: Run legacy promotion tests and record RED**

Run: `./gradlew testDebugUnitTest --tests "net.inkyquill.pocketeditor.ui.books.ImportDraftRepositoryTest" --tests "net.inkyquill.pocketeditor.load.ProgressiveBookLoaderTest"`

Expected: old all-download expectations fail and `migrateLegacyDrafts` is absent.

- [ ] **Step 17: Implement legacy promotion and retire the public all-download path**

Implement:

```kotlin
suspend fun migrateLegacyDrafts() {
    legacyAdapter.seeds().forEach { legacy ->
        if (loads.getJob(legacy.manifest.bookId) != null) return@forEach
        val seed = ProgressiveBookSeed(
            legacy.manifest, legacy.remoteRootPath, legacy.files,
            rawBinder = true, remoteManifest = null,
        )
        installer.install(seed, legacy.cachedSources)
        importDrafts.delete(legacy.manifest.bookId)
        importDraftStore.delete(legacy.manifest.bookId)
        if (!legacy.readyWithoutNetwork) scheduler.start(legacy.manifest.bookId)
    }
}
```

Delete `ImportDraftRepository.createOrResume`, `cachedChapters`, and title/inclusion update behavior only after their tests are replaced; retain the class/file temporarily as a thin legacy-row reader if removing it would mix UI cleanup into this task. The production entry point for every new folder is now `ProgressiveBookLoader.start`.

- [ ] **Step 18: Wire the real loader, unique queue, and app factory**

In `AppContainer`, construct in dependency order:

```kotlin
val progressiveLoads = database.progressiveLoadDao()
val progressiveLoadQueue = WorkManagerProgressiveLoadQueue(WorkManager.getInstance(applicationContext))
val progressiveLoadScheduleStore = RoomProgressiveLoadScheduleStore(database, progressiveLoads)
val progressiveLoadScheduler = ProgressiveLoadScheduler(
    progressiveLoadQueue,
    progressiveLoadScheduleStore,
)
val progressiveInstaller = ProgressiveBookInstaller(/* existing stores/DAOs/transaction */)
val progressiveLoader = ProgressiveBookLoader(
    gateway, progressiveLoads, progressiveInstaller, bookStore, database.syncDao(), sourceSearch,
    reviewMutations, contentChanges, LibraryTransaction { block -> database.withTransaction { block() } },
    progressiveLoadScheduler, ProgressiveLoadRetryPolicy(),
    LegacyImportDraftAdapter(database.importDraftDao(), importDraftStore),
    database.importDraftDao(), importDraftStore,
)
val workerFactory = PocketEditorWorkerFactory(
    SyncWorkerFactory(syncEngine::syncBook, workQueue, retryGenerations, AndroidNetworkAvailability(applicationContext)),
    ProgressiveLoadWorkerFactory(
        progressiveLoader,
        progressiveLoadQueue,
        progressiveLoadScheduler,
        progressiveLoadScheduleStore,
        AndroidNetworkAvailability(applicationContext),
    ),
)
```

Launch `progressiveLoader.migrateLegacyDrafts()` from `applicationScope` after `startupRecovery.recover()` and before controller-visible book refresh. Do not block `Application.onCreate`; the Room flows expose migration progress durably.

- [ ] **Step 19: Adapt `RoomYandexBookLibraryData` and verify focused GREEN**

Inject `ProgressiveBookLoader`, `ProgressiveLoadDao`, and `ProgressiveLoadScheduler`. Add exact adapters:

```kotlin
override fun loadChanges(): Flow<List<ProgressiveLoadSnapshot>> =
    loads.observeAll().map { values -> values.map(ProgressiveLoadJobWithFiles::toSnapshot) }
override suspend fun startLoad(path: String): ProgressiveLoadSnapshot = progressiveLoader.start(path)
override suspend fun prioritizeChapter(bookId: String, path: String) {
    if (loads.prioritize(bookId, path) > 0) progressiveLoadScheduler.replaceNow(bookId)
}
override suspend fun pauseLoad(bookId: String) = progressiveLoadScheduler.pause(bookId)
override suspend fun continueLoad(bookId: String) = progressiveLoadScheduler.continueLoad(bookId)
override suspend fun cancelLoad(bookId: String) = progressiveLoadScheduler.cancel(bookId)
```

Keep old interface methods compiling until Task 4 moves the controller; mark them internal/deprecated only, with no route from new folder selection.

Run:

```bash
./gradlew testDebugUnitTest --tests "net.inkyquill.pocketeditor.load.*" --tests "net.inkyquill.pocketeditor.ui.books.ImportDraftRepositoryTest"
./gradlew compileDebugAndroidTestKotlin
```

Expected: all unit tests PASS and Android tests compile.

- [ ] **Step 20: Commit the completed repository/install slice**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/load app/src/main/java/net/inkyquill/pocketeditor/database/ProgressiveLoadDao.kt app/src/main/java/net/inkyquill/pocketeditor/ui/books app/src/main/java/net/inkyquill/pocketeditor/PocketEditorApp.kt app/src/test/java/net/inkyquill/pocketeditor/load app/src/test/java/net/inkyquill/pocketeditor/ui/books/ImportDraftRepositoryTest.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/books/RoomYandexBookLibraryDataTest.kt
git commit -m "feat: load Yandex books progressively"
```

### Task 4: Controller, root progress card, complete Contents spine, and body-only reader skeleton

**Files:**
- Create: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/ProgressiveLoadCard.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/reader/ChapterAvailability.kt`
- Create: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ProgressiveLoadUiTest.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/BookLibraryController.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/RoomYandexBookLibraryData.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/contents/ContentsPanel.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/reader/ReaderRepository.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderViewModel.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/navigation/PocketEditorRoot.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/PocketEditorApp.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/test/java/net/inkyquill/pocketeditor/ui/books/BookLibraryControllerTest.kt`
- Modify: `app/src/test/java/net/inkyquill/pocketeditor/reader/ReaderRepositoryTest.kt`
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt`
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowScreenshotTest.kt`

**Interfaces:**
- Consumes: Task 3 `BookLibraryData.loadChanges/startLoad/prioritizeChapter/pauseLoad/continueLoad/cancelLoad`; `ProgressiveLoadSnapshot.initialReady`; `ProgressiveLoadFileState`; `ContentChangeNotifier`; `ChapterTitleExtractor`.
- Produces: `BookChapter(id: String, path: String, title: String, cached: Boolean)`; `BookSummary.fullyCached`; `BookLibraryState.loads`; `ReaderLoadState.Pending/Ready`; `ChapterAvailability.observe(bookId: String, chapterId: String)`; `ProgressiveLoadCard`; controller actions used by root and Contents.

- [ ] **Step 1: Add controller RED tests for non-blocking folder selection and automatic opening at three**

Extend `FakeBookLibraryData` with `MutableStateFlow<List<ProgressiveLoadSnapshot>>(emptyList())`, `startedPaths`, and `prioritizedPaths`. Add:

```kotlin
@Test
fun `folder selection stays usable and first three publication opens Reader`() = runTest {
    val data = FakeBookLibraryData().apply {
        listing = FolderListing("disk:/Aria", emptyList(), markdown = List(52) { "chapter-$it.md" })
    }
    val controller = BookLibraryController(data, backgroundScope, StandardTestDispatcher(testScheduler))
    controller.openFolderBrowser("disk:/Aria")

    controller.openFolder("disk:/Aria")

    assertEquals(listOf("disk:/Aria"), data.startedPaths)
    assertIs<BookDestination.FolderBrowser>(controller.state.value.destination)
    data.books = listOf(partialBook(cached = 3, total = 52))
    data.loads.value = listOf(loadSnapshot(cached = 3, total = 52, phase = ProgressiveLoadPhase.BACKGROUND))
    advanceUntilIdle()
    val reader = assertIs<BookDestination.Reader>(controller.state.value.destination)
    assertEquals(CHAPTER_0, reader.chapterId)
}

@Test
fun `uncached selection persists priority before Reader navigation`() = runTest {
    val data = FakeBookLibraryData().apply { books = listOf(partialBook(cached = 3, total = 6)) }
    val controller = BookLibraryController(data, backgroundScope, StandardTestDispatcher(testScheduler))

    controller.openChapter(BOOK_ID, CHAPTER_5)

    assertEquals(listOf(BOOK_ID to "chapter-5.md"), data.prioritizedPaths)
    assertEquals(CHAPTER_5, (controller.state.value.destination as BookDestination.Reader).chapterId)
}
```

Add one repeated-tap test proving two taps still leave one durable priority row and one navigation destination, plus pause/continue/cancel forwarding tests.

- [ ] **Step 2: Run controller tests and record RED**

Run: `./gradlew testDebugUnitTest --tests "net.inkyquill.pocketeditor.ui.books.BookLibraryControllerTest"`

Expected: compilation fails because the fake/data/controller load interfaces and `BookChapter.path/cached` do not exist.

- [ ] **Step 3: Extend the library view model without persisting titles**

Replace `BookChapter` and extend `BookSummary`:

```kotlin
data class BookChapter(
    val id: String,
    val path: String,
    val title: String,
    val cached: Boolean,
)

data class BookSummary(
    val bookId: String,
    val title: String,
    val remoteRootPath: String,
    val chapters: List<BookChapter>,
    val availableOffline: Boolean = chapters.any(BookChapter::cached),
    val fullyCached: Boolean = chapters.isNotEmpty() && chapters.all(BookChapter::cached),
    val recoveryError: String? = null,
    val needsRelink: Boolean = false,
)
```

Update `RoomYandexBookLibraryData.summaryFromCache` to read the manifest first, join it to `loadDao.getFiles(bookId).associateBy(path)`, and derive each label as:

```kotlin
val cachedBytes = row?.takeIf { it.state == ProgressiveLoadFileState.CACHED }
    ?.let { runCatching { store.readSource(bookId, chapter.path) }.getOrNull() }
BookChapter(
    id = chapter.id,
    path = chapter.path,
    title = cachedBytes?.let { ChapterTitleExtractor.extract(chapter.path, it).title }
        ?: chapter.path.removeSuffix(".md"),
    cached = cachedBytes != null,
)
```

Do not add a Room title column. Search indexes only cached sources.

- [ ] **Step 4: Replace controller import routing with durable load observation**

Extend `BookLibraryData` exactly:

```kotlin
fun loadChanges(): Flow<List<ProgressiveLoadSnapshot>> = emptyFlow()
suspend fun startLoad(path: String): ProgressiveLoadSnapshot
suspend fun prioritizeChapter(bookId: String, path: String)
suspend fun pauseLoad(bookId: String)
suspend fun continueLoad(bookId: String)
suspend fun cancelLoad(bookId: String)
```

Add `val loads: List<ProgressiveLoadSnapshot> = emptyList()` to `BookLibraryState`. In the controller `init`, collect `data.loadChanges()` and atomically refresh `books` plus `loads`. If a snapshot transitions to `initialReady`, the current destination is `FolderBrowser`, and its refreshed first chapter is cached, persist `ResumeLocation(bookId, first.id)`, call `data.opened`, and navigate to Reader. Keep a private `autoOpened = mutableSetOf<String>()` so later emissions and process-local recompositions cannot reopen it.

Replace `openFolder`'s saved-draft/preview/install/propose branches with:

```kotlin
val load = data.startLoad(path)
mutableState.update { current ->
    current.copy(
        loads = (current.loads.filterNot { it.bookId == load.bookId } + load),
        destination = current.destination as? BookDestination.FolderBrowser
            ?: BookDestination.FolderBrowser(),
        error = null,
    )
}
```

In `openChapter`, find the chapter by ID. If `cached == false`, call `data.prioritizeChapter(bookId, chapter.path)` before `persistResume` and before assigning `BookDestination.Reader`. Add `pauseLoad`, `continueLoad`, and `cancelLoad` controller methods using `runCatchingIo` and retaining the current destination.

- [ ] **Step 5: Run controller tests and record GREEN, then commit**

Run: `./gradlew testDebugUnitTest --tests "net.inkyquill.pocketeditor.ui.books.BookLibraryControllerTest"`

Expected: PASS; there is no `ImportConfirmation` transition, three cached rows auto-open the first chapter, and uncached selection calls priority first.

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui/books/BookLibraryController.kt app/src/main/java/net/inkyquill/pocketeditor/ui/books/RoomYandexBookLibraryData.kt app/src/test/java/net/inkyquill/pocketeditor/ui/books/BookLibraryControllerTest.kt
git commit -m "feat: drive navigation from load readiness"
```

- [ ] **Step 6: Add reader repository RED tests for pending then published content**

Introduce a fake availability flow in `ReaderRepositoryTest` and assert that the manifest/path is usable before source bytes exist:

```kotlin
@Test
fun `uncached chapter emits pending then ready after cache publication`() = runTest {
    val availability = MutableStateFlow(ProgressiveLoadFileState.PENDING)
    val repository = repository(
        chapterAvailability = ChapterAvailability { _, _ -> availability },
        source = "# Loaded chapter".encodeToByteArray(),
    )
    val states = mutableListOf<ReaderLoadState>()
    val collecting = backgroundScope.launch {
        repository.observeChapter(BOOK_ID, CHAPTER_ID, reviewEnabled = false).take(2).toList(states)
    }

    advanceUntilIdle()
    assertEquals(ReaderLoadState.Pending(BOOK_ID, CHAPTER_ID, "chapter"), states.single())
    availability.value = ProgressiveLoadFileState.CACHED
    contentChanges.changed(BOOK_ID, "chapter.md")
    advanceUntilIdle()

    assertEquals("Loaded chapter", (states.last() as ReaderLoadState.Ready).state.title)
    collecting.cancel()
}
```

Assert the pending state performs zero `readSource` calls and still exposes book/chapter/provisional title for reader chrome.

- [ ] **Step 7: Run reader repository tests and record RED**

Run: `./gradlew testDebugUnitTest --tests "net.inkyquill.pocketeditor.reader.ReaderRepositoryTest"`

Expected: compilation fails because `ChapterAvailability` and `ReaderLoadState` do not exist.

- [ ] **Step 8: Add the chapter-availability boundary and pending reader state**

Create `ChapterAvailability.kt`:

```kotlin
fun interface ChapterAvailability {
    fun observe(bookId: String, chapterId: String): Flow<ProgressiveLoadFileState?>
}

class RoomChapterAvailability(private val loads: ProgressiveLoadDao) : ChapterAvailability {
    override fun observe(bookId: String, chapterId: String): Flow<ProgressiveLoadFileState?> =
        loads.observeChapter(bookId, chapterId).map { it?.state }
}

sealed interface ReaderLoadState {
    data class Pending(val bookId: String, val chapterId: String, val title: String) : ReaderLoadState
    data class Ready(val state: ReaderState) : ReaderLoadState
}
```

Inject `ChapterAvailability` into `ReaderRepository`. Change `observeChapter` to `flatMapLatest` availability: for any state other than `CACHED`, read only the manifest to obtain the path and emit `Pending(bookId, chapterId, filenameFallback)`; for `CACHED`, enter the existing content/version/position/sync combine and wrap emitted `ReaderState` in `Ready`. Wire `RoomChapterAvailability(progressiveLoads)` in `AppContainer`.

- [ ] **Step 9: Run reader repository tests and record GREEN**

Run: `./gradlew testDebugUnitTest --tests "net.inkyquill.pocketeditor.reader.ReaderRepositoryTest"`

Expected: PASS; `readSource` starts only after `CACHED`, and cache publication replaces Pending with Ready.

- [ ] **Step 10: Add Compose RED tests for the compact card and body-only skeleton**

Create `ProgressiveLoadUiTest.kt`:

```kotlin
@Test
fun progressCardIsDeterminatePoliteAndExposesContextActions() {
    compose.setContent {
        ProgressiveLoadCard(
            snapshot = loadSnapshot(cached = 7, total = 52, activePath = "chapter-008-v2.md"),
            nowMillis = 0L,
            onPause = {}, onContinue = {}, onCancel = {}, onSignIn = {},
        )
    }
    compose.onNodeWithText("Загружаем chapter-008-v2.md").assertIsDisplayed()
    compose.onNodeWithContentDescription("Загружено 7 из 52").assertIsDisplayed()
    compose.onNodeWithText("Приостановить").assertHasClickAction()
    compose.onNodeWithText("Отменить").assertHasClickAction()
}

@Test
fun pendingReaderKeepsContentsChromeAndShowsSkeletonOnlyInBody() {
    compose.setContent {
        ReaderRoute(
            viewModel = pendingReaderViewModel(),
            contentsContent = { _, _ -> Text("Полное содержание") },
        )
    }
    compose.onNodeWithContentDescription("Открыть содержание").assertIsDisplayed()
    compose.onNodeWithText("Полное содержание").assertDoesNotExist()
    compose.onNodeWithTag("reader-body-skeleton").assertIsDisplayed()
    compose.onNodeWithText("Загружаем главу…").assertIsDisplayed()
}
```

Add paused→`Продолжить`, cancelled→`Продолжить`, unauthorized→`Нужно войти в Яндекс Диск`/`Войти`, invalid→`Требуется действие`/`Продолжить`, offline and 429 countdown text, and complete→`Книга доступна без сети` cases. Assert semantics change on phase/file count, not byte changes.

- [ ] **Step 11: Run Compose test compilation and record RED**

Run: `./gradlew compileDebugAndroidTestKotlin`

Expected: compilation fails because `ProgressiveLoadCard`, pending route support, and new strings do not exist.

- [ ] **Step 12: Implement the compact persistent progress card**

Create `ProgressiveLoadCard.kt`. Its primary text mapping must be exact:

```kotlin
fun ProgressiveLoadSnapshot.primaryText(nowMillis: Long): String = when {
    phase == ProgressiveLoadPhase.COMPLETE -> "Книга доступна без сети"
    lastErrorCategory == ProgressiveLoadErrorCategory.UNAUTHORIZED -> "Нужно войти в Яндекс Диск"
    lastErrorCategory == ProgressiveLoadErrorCategory.OFFLINE -> "Нет сети · продолжим автоматически"
    lastErrorCategory == ProgressiveLoadErrorCategory.RATE_LIMITED && retryAt != null ->
        "Лимит Яндекс Диска · повтор через ${((retryAt - nowMillis).coerceAtLeast(0) + 999) / 1000} с"
    activePath != null -> "Загружаем $activePath"
    completedFiles > 0 -> "Загружено $completedFiles из $totalFiles"
    else -> "Готовим книгу…"
}
```

Render a small `Card` with `LinearProgressIndicator(progress = { completedFiles.toFloat() / totalFiles })`. Add:

```kotlin
Modifier.semantics {
    liveRegion = LiveRegionMode.Polite
    contentDescription = "Загружено $completedFiles из $totalFiles"
}
```

Show `Приостановить` only for INITIAL/BACKGROUND active intent; `Продолжить` for PAUSED/CANCELLED/ACTION_REQUIRED; `Отменить` for every incomplete non-cancelled job; `Войти` only for `UNAUTHORIZED`. Do not display raw throwable messages, response bodies, access tokens, or URLs.

- [ ] **Step 13: Implement the body-only reader skeleton while retaining chrome and Contents**

Change `ReaderViewModel.state` to `StateFlow<ReaderLoadState?>`; expose `readyState = state.map { (it as? ReaderLoadState.Ready)?.state }` for review code. In `ReaderRoute`, keep the initial null fallback, pass `Ready.state` to `ReaderScreen`, and pass `Pending` to a new `PendingReaderScreen` in `ReaderScreen.kt`.

`PendingReaderScreen` must use the same `ReaderLayoutPolicy` and `AdaptiveReaderScaffold` as `ReaderScreen`, with `reviewEnabled = false`, the supplied `contentsContent`, and a reader pane containing the normal top bar/Contents button plus:

```kotlin
Column(
    Modifier.fillMaxSize().padding(24.dp).testTag("reader-body-skeleton"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
) {
    Text("Загружаем главу…", style = MaterialTheme.typography.titleMedium)
    repeat(8) { index ->
        Box(
            Modifier.fillMaxWidth(if (index % 3 == 2) 0.68f else 1f)
                .height(16.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
        )
    }
}
```

In `PocketEditorRoot`, change review/controller state access to `(readerState.value as? ReaderLoadState.Ready)?.state`; restore review drafts only from `filterIsInstance<ReaderLoadState.Ready>()`. The Contents callbacks remain available in both Pending and Ready states.

- [ ] **Step 14: Host the card outside the destination switch**

Wrap the existing root route switch in `Box(Modifier.fillMaxSize())`; render the destination first, then render the selected incomplete/recent load card aligned `TopCenter` with safe insets. This placement is inside `PocketEditorTheme` but outside `when (library.destination)`, so Folder Browser, Books, Contents/Reader, and Appearance navigation remain usable beneath it.

Wire card actions to `controller.pauseLoad`, `continueLoad`, `cancelLoad`, and the existing Yandex sign-in function. On `connectivityObserver.connected`, keep the sync trigger and additionally call `continueLoad` only for active snapshots with a transient category; do not resume a user-paused or cancelled job.

Update `ContentsPanel` chapter rows to show the complete `BookSummary.chapters`; add a trailing cached/offline icon or progress indicator based on `chapter.cached`, but keep the row clickable in both states.

- [ ] **Step 15: Run focused Compose tests and record GREEN**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.ProgressiveLoadUiTest`

Expected: all compact-card, semantics, control, and body-skeleton tests PASS. Without a device, `compileDebugAndroidTestKotlin` is only compilation evidence.

- [ ] **Step 16: Rewrite the main flow instrumentation around 0→3 and publication**

In `BookFlowTest`, replace the full-screen `InstallingExisting`/confirmation assertions with a fake load flow:

```kotlin
compose.onNodeWithText("Выбрать эту папку").performClick()
compose.onNodeWithContentDescription("Загружено 0 из 52").assertIsDisplayed()
fakeData.publish(cached = 1, total = 52)
compose.onNodeWithContentDescription("Загружено 1 из 52").assertIsDisplayed()
fakeData.publish(cached = 3, total = 52)
compose.waitUntil { compose.onAllNodesWithTag("reader-body").fetchSemanticsNodes().isNotEmpty() }
compose.onNodeWithContentDescription("Загружено 3 из 52").assertIsDisplayed()
```

Add a Books navigation assertion while the card remains displayed, then select uncached chapter 40 from Contents, assert the body skeleton and recorded priority path, publish it, and assert rendered H1 text appears without losing reader chrome. Add Pause/Continue/Cancel/Sign-in/Action-required semantics. Update `BookFlowScreenshotTest` with one compact-card state; do not create a full-screen loading gold image.

- [ ] **Step 17: Run Task 4 focused tests and commit**

Run:

```bash
./gradlew testDebugUnitTest --tests "net.inkyquill.pocketeditor.ui.books.BookLibraryControllerTest" --tests "net.inkyquill.pocketeditor.reader.ReaderRepositoryTest"
./gradlew compileDebugAndroidTestKotlin
```

Expected: unit tests PASS and all instrumentation sources compile.

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui app/src/main/java/net/inkyquill/pocketeditor/reader app/src/main/java/net/inkyquill/pocketeditor/PocketEditorApp.kt app/src/main/res/values/strings.xml app/src/test/java/net/inkyquill/pocketeditor/ui/books/BookLibraryControllerTest.kt app/src/test/java/net/inkyquill/pocketeditor/reader/ReaderRepositoryTest.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ProgressiveLoadUiTest.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowScreenshotTest.kt
git commit -m "feat: show progressive book loading"
```

### Task 5: Anytime Contents reorder with exclusive, base-verified manifest mutation

**Files:**
- Create: `app/src/main/java/net/inkyquill/pocketeditor/ui/contents/ContentsReorderState.kt`
- Create: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/contents/ContentsReorderTest.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/contents/ContentsPanel.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/BookLibraryController.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/RoomYandexBookLibraryData.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/navigation/PocketEditorRoot.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/test/java/net/inkyquill/pocketeditor/ui/books/BookLibraryControllerTest.kt`
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/books/RoomYandexBookLibraryDataTest.kt`
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt`

**Interfaces:**
- Consumes: Task 4 `BookChapter(id, path, title, cached)` and complete Contents spine; existing `ReviewMutationCoordinator.withBookExclusive`; `BookManifest`, `SyncBaseStore`, `MergeBaseEntity`, `OutboxEntity`, `SourceSearch`, `SyncScheduler`, `ConflictRepository`.
- Produces: `ContentsReorderState.move(fromIndex: Int, toIndex: Int)`, `orderedChapterIds: List<String>`; `BookLibraryData.reorder(bookId: String, orderedChapterIds: List<String>)`; `BookLibraryController.reorder`; Compose callbacks `onSaveOrder/onCancelOrder`.

- [ ] **Step 1: Add Compose RED tests for edit mode, semantic movement, cancel, and save**

Create `ContentsReorderTest.kt` using the existing Compose test rule:

```kotlin
@Test
fun editModeMovesOnlyChaptersAndCancelDiscardsDraft() {
    var saved: List<String>? = null
    compose.setContent {
        ContentsPanel(
            books = listOf(book("One", "Two", "Three")),
            currentBookId = BOOK_ID,
            currentChapterId = CHAPTER_1,
            query = "",
            searchResults = emptyList(),
            searching = false,
            closeLabel = "Закрыть",
            onClose = {}, onSwitchBook = {}, onChapterSelected = {}, onQueryChanged = {},
            onSearchResult = {}, onOpenBooks = {}, onAppearance = {},
            onSaveOrder = { saved = it },
        )
    }

    compose.onNodeWithText("Изменить порядок").performClick()
    compose.onNodeWithContentDescription("Переместить Three вверх").performSemanticsAction(SemanticsActions.OnClick)
    compose.onNodeWithText("Отмена").performClick()
    compose.onNodeWithText("One").assertIsDisplayed()
    assertNull(saved)

    compose.onNodeWithText("Изменить порядок").performClick()
    compose.onNodeWithContentDescription("Переместить Three вверх").performSemanticsAction(SemanticsActions.OnClick)
    compose.onNodeWithText("Сохранить").performClick()
    assertEquals(listOf(CHAPTER_1, CHAPTER_3, CHAPTER_2), saved)
}
```

Add a long-press drag test with `performTouchInput { down(center); advanceEventTime(600); moveTo(center.copy(y = center.y - 96f)); up() }`; assert the saved ID order. Add a mixed cached/uncached book and prove both rows reorder, while IDs and paths in callback objects do not change.

- [ ] **Step 2: Run Compose test compilation and record RED**

Run: `./gradlew compileDebugAndroidTestKotlin`

Expected: `onSaveOrder`, `Изменить порядок`, and semantic move actions are missing.

- [ ] **Step 3: Implement a saveable complete-spine ordering draft**

Create `ContentsReorderState.kt`:

```kotlin
@Stable
class ContentsReorderState private constructor(
    private val originalIds: List<String>,
    initialIds: List<String>,
) {
    var orderedChapterIds by mutableStateOf(initialIds)
        private set

    val changed: Boolean get() = orderedChapterIds != originalIds

    fun move(fromIndex: Int, toIndex: Int) {
        require(fromIndex in orderedChapterIds.indices && toIndex in orderedChapterIds.indices)
        if (fromIndex == toIndex) return
        orderedChapterIds = orderedChapterIds.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
    }

    fun cancel() { orderedChapterIds = originalIds }

    companion object {
        fun create(ids: List<String>): ContentsReorderState {
            require(ids.isNotEmpty() && ids.distinct().size == ids.size)
            return ContentsReorderState(ids.toList(), ids.toList())
        }

        fun saver(originalIds: List<String>) = Saver<ContentsReorderState, ArrayList<String>>(
            save = { ArrayList(it.orderedChapterIds) },
            restore = { restored ->
                require(restored.toSet() == originalIds.toSet() && restored.size == originalIds.size)
                ContentsReorderState(originalIds, restored)
            },
        )
    }
}
```

In `ContentsPanel`, add `onSaveOrder: (List<String>) -> Unit = {}`. `Изменить порядок` creates a state keyed by `bookId` and the exact current ID list via `rememberSaveable(saver = ContentsReorderState.saver(ids))`. `Отмена` calls `cancel()` and exits edit mode. `Сохранить` first requires `orderedChapterIds.toSet() == ids.toSet()` and equal size, then invokes the callback once and exits edit mode.

- [ ] **Step 4: Implement long-press drag with accessible semantic fallback**

Use only Compose foundation APIs; do not add a dependency. Give each row a drag handle and this pointer logic on the `LazyColumn`:

```kotlin
Modifier.pointerInput(editing, reorderState.orderedChapterIds) {
    if (!editing) return@pointerInput
    var draggedIndex: Int? = null
    detectDragGesturesAfterLongPress(
        onDragStart = { offset ->
            draggedIndex = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { offset.y.toInt() in it.offset until (it.offset + it.size) }
                ?.index
        },
        onDragCancel = { draggedIndex = null },
        onDragEnd = { draggedIndex = null },
        onDrag = { change, amount ->
            change.consume()
            val from = draggedIndex ?: return@detectDragGesturesAfterLongPress
            val y = change.position.y.toInt()
            val target = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { y in it.offset until (it.offset + it.size) }
                ?.index ?: return@detectDragGesturesAfterLongPress
            if (target != from) {
                reorderState.move(from, target)
                draggedIndex = target
            }
        },
    )
}
```

Each edit row adds custom actions exposed as clickable semantic nodes:

```kotlin
IconButton(
    onClick = { if (index > 0) reorderState.move(index, index - 1) },
    enabled = index > 0,
    modifier = Modifier.semantics { contentDescription = "Переместить ${chapter.title} вверх" },
) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = null) }
```

Add the equivalent down action. The drag handle's description is `Перетащить <title>`; selection remains disabled during edit mode so movement cannot navigate.

- [ ] **Step 5: Run the reorder Compose test and record GREEN**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.contents.ContentsReorderTest`

Expected: semantic move, drag, cancel, and save PASS. Without a device, report only successful compilation.

- [ ] **Step 6: Add repository RED tests for exact-set validation, exclusive lease, cached-only indexing, and one outbox row**

Extend `RoomYandexBookLibraryDataTest`:

```kotlin
@Test
fun reorderPreservesIdsPathsAndPublishesOneVerifiedManifestMutation() = runTest {
    installPartialBook(cachedPaths = setOf("one.md"), pendingPaths = setOf("two.md", "three.md"))
    val before = bookStore.readManifest(BOOK_ID)
    val ordered = listOf(CHAPTER_3, CHAPTER_1, CHAPTER_2)

    data.reorder(BOOK_ID, ordered)

    val after = bookStore.readManifest(BOOK_ID)
    assertEquals(ordered, after.chapters.map(ChapterEntry::id))
    assertEquals(before.chapters.associate { it.id to it.path }, after.chapters.associate { it.id to it.path })
    val outbox = syncDao.getOutbox(BOOK_ID).single { it.path == BookPaths.MANIFEST_NAME }
    assertEquals(mergeBase.sha256, outbox.baseSha256)
    assertEquals(BookManifest.encode(after).encodeToByteArray().sha256(), outbox.localSha256)
    assertEquals(listOf(CHAPTER_1), searchDao.indexedChapterIds(BOOK_ID))
    assertEquals(1, scheduler.requests.count { it.trigger == SyncTrigger.LOCAL_CHANGE })
}
```

Add tests rejecting missing, duplicate, and foreign IDs before writing; a shared cache publication held open while reorder waits; reorder holding the exclusive lease while a source publication waits; an outbox/local SHA mismatch producing `SyncConflict.MissingBase`; and process recreation reading the saved manifest order from Room/filesystem.

- [ ] **Step 7: Run repository tests and record RED**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.books.RoomYandexBookLibraryDataTest`

Expected: compilation fails because `BookLibraryData.reorder` is absent. Without a device, compilation is not concurrency/runtime proof.

- [ ] **Step 8: Add the reorder API and exclusive mutation implementation**

Add to `BookLibraryData` and controller:

```kotlin
suspend fun reorder(bookId: String, orderedChapterIds: List<String>)
```

Controller forwards inside `runCatchingIo`, then refreshes books while preserving the current Reader destination. Implement the data mutation entirely under the exclusive gate:

```kotlin
override suspend fun reorder(bookId: String, orderedChapterIds: List<String>) {
    reviewMutations.withBookExclusive(bookId) {
        val root = requireNotNull(books.getRoot(bookId)) { "Book is not registered" }
        val current = store.readManifest(bookId)
        val byId = current.chapters.associateBy(ChapterEntry::id)
        require(orderedChapterIds.size == current.chapters.size && orderedChapterIds.distinct().size == orderedChapterIds.size)
        require(orderedChapterIds.toSet() == byId.keys) { "Reorder must contain the complete unique spine" }

        val currentBytes = BookManifest.encode(current).encodeToByteArray()
        val currentOutbox = sync.getOutbox(bookId, BookPaths.MANIFEST_NAME)
        val baseSha = if (currentOutbox != null) {
            check(currentOutbox.localSha256 == currentBytes.sha256()) { "Manifest outbox no longer matches local base" }
            currentOutbox.baseSha256
        } else {
            val mergeBase = requireNotNull(sync.getMergeBase(bookId, BookPaths.MANIFEST_NAME))
            val durableBase = requireNotNull(baseStore.read(bookId, BookPaths.MANIFEST_NAME))
            check(mergeBase.sha256 == durableBase.sha256 && mergeBase.remoteRevision == durableBase.remoteRevision)
            mergeBase.sha256
        }
        val updated = current.copy(chapters = orderedChapterIds.map(byId::getValue))
        val revision = store.writeManifest(bookId, updated)
        transaction.run {
            sync.upsertOutbox(
                OutboxEntity(bookId, BookPaths.MANIFEST_NAME, revision.sha256, baseSha, OutboxState.PENDING),
            )
            val cached = loads.getFiles(bookId).filter { it.state == ProgressiveLoadFileState.CACHED }
                .associateBy(ProgressiveLoadFileEntity::chapterId)
            search.rebuildBook(bookId, updated.chapters.mapNotNull { chapter ->
                cached[chapter.id]?.let {
                    val bytes = store.readSource(bookId, chapter.path)
                    SearchChapterSource(chapter.id, ChapterTitleExtractor.extract(chapter.path, bytes).title, bytes)
                }
            })
        }
        contentChanges.changed(bookId, BookPaths.MANIFEST_NAME)
        contentChanges.bookChanged(bookId)
        root.remoteRootPath?.let { scheduler.enqueue(bookId, it, SyncTrigger.LOCAL_CHANGE) }
    }
}
```

Wrap base/outbox verification failures: call `conflicts.replace(bookId, SyncConflict.MissingBase(BookPaths.MANIFEST_NAME, "Основа манифеста изменилась"))`, leave the manifest untouched, and throw `BookLibraryUserError("Порядок не сохранён: сначала разрешите конфликт книги")`. Existing sync conflict UI is then the only resolution path; never overwrite an unverified base.

- [ ] **Step 9: Run repository tests and record GREEN**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.books.RoomYandexBookLibraryDataTest`

Expected: PASS for exact order/identity, one outbox mutation, cached-only index, conflict, recreation, and lease exclusion.

- [ ] **Step 10: Wire Contents save/cancel and add flow recreation coverage**

Pass from `PocketEditorRoot.ReaderDestination`:

```kotlin
onSaveOrder = { ids -> scope.launch { controller.reorder(destination.bookId, ids) } }
```

In `BookFlowTest`, enter edit mode with at least one uncached chapter, move it, recreate the activity before Save, reopen Contents, and assert the local draft order is restored. Then tap `Отмена` and assert durable order is unchanged. Repeat, Save, recreate, and assert the new manifest/Contents order persists. Inject a remote-base conflict before Save and assert the conflict card appears while the prior durable order remains.

- [ ] **Step 11: Run Task 5 focused verification and commit**

Run:

```bash
./gradlew testDebugUnitTest --tests "net.inkyquill.pocketeditor.ui.books.BookLibraryControllerTest"
./gradlew compileDebugAndroidTestKotlin
```

Expected: unit tests PASS and reorder instrumentation compiles.

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui/contents app/src/main/java/net/inkyquill/pocketeditor/ui/books/BookLibraryController.kt app/src/main/java/net/inkyquill/pocketeditor/ui/books/RoomYandexBookLibraryData.kt app/src/main/java/net/inkyquill/pocketeditor/ui/navigation/PocketEditorRoot.kt app/src/main/res/values/strings.xml app/src/test/java/net/inkyquill/pocketeditor/ui/books/BookLibraryControllerTest.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/contents/ContentsReorderTest.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/books/RoomYandexBookLibraryDataTest.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt
git commit -m "feat: reorder chapters from contents"
```

### Task 6: Remove the obsolete confirmation flow and verify the complete progressive path

**Files:**
- Delete: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/ImportConfirmationScreen.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/BooksScreen.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/BookLibraryController.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/RoomYandexBookLibraryData.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/navigation/PocketEditorRoot.kt`
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt`
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowScreenshotTest.kt`
- Modify: `docs/runbooks/yandex-e2e.md`
- Test: all `app/src/test/**` unit tests and all `app/src/androidTest/**` instrumentation sources; connected runtime only when an actual device/emulator is listed by `adb devices`.

**Interfaces:**
- Consumes: Tasks 1–5 complete progressive load, controls, pending reader, and reorder interfaces.
- Produces: one public first-load path (`startLoad`), no import-time chapter/title/order editor, a read-only 52-file `aria` evidence procedure, and a verification result that distinguishes build evidence from connected runtime.

- [ ] **Step 1: Add the final flow RED assertion that confirmation UI is absent**

Replace the old import-confirmation instrumentation case in `BookFlowTest` with:

```kotlin
@Test
fun choosingRawFolderStartsDeterministicLoadWithoutConfirmationEditor() {
    launchBookFlow(rawFolder(chapters = listOf("02.md", "01.md")))

    compose.onNodeWithText("Выбрать эту папку").performClick()

    compose.onNodeWithText("Проверьте книгу").assertDoesNotExist()
    compose.onNodeWithText("Название книги").assertDoesNotExist()
    compose.onNodeWithText("Исключить главу").assertDoesNotExist()
    compose.onNodeWithContentDescription("Загружено 0 из 2").assertIsDisplayed()
    assertEquals(listOf("01.md", "02.md"), fakeData.installedManifest.chapters.map(ChapterEntry::path))
}
```

Run: `./gradlew compileDebugAndroidTestKotlin`

Expected: RED while the old destination/screen callbacks still exist or the fake flow still enters confirmation.

- [ ] **Step 2: Remove every public old-flow symbol while retaining only migration readers**

Delete `ImportConfirmationScreen.kt`. Remove `ImportConfirmation`, `Importing`, and `InstallingExisting` from `BookDestination`; remove their root branches; remove `ImportChapterDraft`, `ImportDraft`, `ImportDraftSummary`, `ImportProgress`, and these `BookLibraryData` methods: `importDrafts`, `resumeImport`, `updateImport`, `discardImport`, `propose`, `installExisting`, and `import`. Remove `confirmImport`, draft discard state/actions, and the draft card/arguments from `BooksScreen`.

Keep `ImportDraftDocument`, `ImportDraftEntity`, `ImportDraftDao`, and `ImportDraftStore`: database v5 must still read a real v4 install and Task 3's `LegacyImportDraftAdapter` is their sole consumer. Delete `ImportDraftRepository.kt` after moving any remaining entity decode helper into `LegacyImportDraftAdapter.kt`.

- [ ] **Step 3: Prove no obsolete route remains and commit cleanup**

Run:

```bash
rg -n "ImportConfirmation|Importing|InstallingExisting|confirmImport|updateImport|resumeImport|onResumeDraft|discardDraft" app/src/main app/src/test app/src/androidTest
```

Expected: no matches. Then run `./gradlew testDebugUnitTest compileDebugAndroidTestKotlin`; expect PASS.

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui/books/ImportConfirmationScreen.kt app/src/main/java/net/inkyquill/pocketeditor/ui/books/BooksScreen.kt app/src/main/java/net/inkyquill/pocketeditor/ui/books/BookLibraryController.kt app/src/main/java/net/inkyquill/pocketeditor/ui/books/RoomYandexBookLibraryData.kt app/src/main/java/net/inkyquill/pocketeditor/ui/books/ImportDraftRepository.kt app/src/main/java/net/inkyquill/pocketeditor/ui/navigation/PocketEditorRoot.kt app/src/test/java/net/inkyquill/pocketeditor/ui/books app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowScreenshotTest.kt
git commit -m "refactor: remove blocking import confirmation"
```

- [ ] **Step 4: Run the complete JVM test suite**

Run: `./gradlew testDebugUnitTest`

Expected: PASS, including exact manifest order/IDs, normalized raw path order/stable IDs, initial three, one-file concurrency, restart skip, on-demand coalescing, unbounded retries, generation fencing, cancellation cleanup, and reorder base validation.

- [ ] **Step 5: Run lint and both application/instrumentation compilation gates**

Run:

```bash
./gradlew lintDebug assembleDebug compileDebugAndroidTestKotlin
```

Expected: PASS. This proves static checks and compilation only; it does not prove Compose runtime, WorkManager execution, database migration runtime, Android connectivity validation, process death, or real Yandex behavior.

- [ ] **Step 6: Detect connected runtime capability without guessing**

Run: `adb devices -l`

Expected with a usable target: exactly identify a line whose state is `device`. If no such line exists, record:

```text
Connected instrumentation: NOT RUN — no Android device/emulator in state `device`.
Runtime acceptance: UNVERIFIED; compileDebugAndroidTestKotlin is not runtime evidence.
```

Do not change `NOT RUN` to PASS based on `assembleDebug` or instrumentation compilation.

- [ ] **Step 7: Run connected automated instrumentation when a target exists**

Run: `./gradlew connectedDebugAndroidTest`

Expected: PASS for v4→v5 migration, DAO transactions, compact banner across Folder Browser/Books/Reader, 0→3 automatic opening, pending-body publication, pause/continue/cancel/sign-in/action semantics, reorder drag/cancel/save/recreation/conflict, and existing reader/review flows. If the device disconnects, report FAIL/BLOCKED with the observable device state; do not substitute compilation.

- [ ] **Step 8: Add the read-only `aria` procedure to the E2E runbook**

Append a “Progressive read-only `aria` load” section to `docs/runbooks/yandex-e2e.md` with this exact safety preamble and evidence table:

```markdown
## Progressive read-only `aria` load

Remote fixture: `Яндекс.Диск/writing/aria`. It contains private manuscript data.
This procedure may list and download only. It must not upload, delete, replace,
rename, reorder, acquire a write lock, or otherwise mutate this folder. Record
counts, redacted path basenames, timestamps, and hashes only; record no source text,
OAuth material, signed URLs, or raw response bodies.

| Gate | Required observation | Result |
| --- | --- | --- |
| Binder | Strict UTF-8 schema-v2 binder; 52 unique ID/path entries | NOT RUN |
| Initial | `0 из 52` advances to `3 из 52`; Reader opens chapter 1 | NOT RUN |
| Background | Count advances beyond 3 with max one active download | NOT RUN |
| Priority | A later uncached Contents row is the next downloaded path | NOT RUN |
| Resume | Connectivity/process interruption resumes without confirmed redownload | NOT RUN |
| Complete | `52 из 52`; all chapters open with connectivity disabled | NOT RUN |
| Write audit | Remote write request count for `aria` is exactly zero | NOT RUN |
```

The runbook must state that reorder is exercised only on a disposable folder and never against `aria`.

- [ ] **Step 9: Capture the remote baseline without mutation**

On the connected signed-in target, clear only the app's local copy if a fresh run is required; do not touch Yandex remote data. Select `Яндекс.Диск/writing/aria`, observe strict binder success and exactly 52 unique spine rows, and record a private before-snapshot of remote revision/hash metadata plus a redacted request-method count.

Expected: one paginated folder listing sequence, one binder download, zero uploads/deletes/locks, and `0 из 52` visible.

- [ ] **Step 10: Verify initial readiness and background continuation**

Observe progress `1 из 52`, `2 из 52`, then `3 из 52`. At `3 из 52`, verify Reader opens the first binder chapter while the compact card remains non-blocking. Return to Contents/Books and verify the count advances beyond 3 without a second simultaneous download.

Expected: Reader usable at three, exact binder order preserved, maximum one active Yandex source transfer.

- [ ] **Step 11: Verify on-demand priority**

Before full completion, open a later uncached row in Contents. Verify the Reader chrome/Contents remain usable, only the body shows the skeleton, and the redacted next downloaded basename equals the requested row. When publication arrives, verify rendered content replaces the skeleton; the following download returns to the earliest pending spine entry.

- [ ] **Step 12: Verify pause, Continue, Cancel, and durable process/network recovery**

Pause and verify scheduled work stops while cached chapters remain readable. Continue and verify immediate validated-network work. Disable connectivity during a file, verify `Нет сети · продолжим автоматически`, force-stop the process, restore connectivity, relaunch, and verify confirmed rows are not fetched again. Cancel, relaunch, verify no automatic continuation, then resume from Books and complete.

Expected: no permanent `DOWNLOADING` row, no stale-generation resurrection, no cache loss, and no parallel request.

- [ ] **Step 13: Verify 52/52 offline and audit remote immutability**

At `52 из 52`, disable connectivity and open the first, middle, and last chapters. Compare the post-run remote revision/hash snapshot and redacted method counts to the baseline.

Expected: all 52 chapters open offline; manifest/source/review revisions are unchanged; uploads, DELETEs, renames, reorder mutations, and cooperative-lock writes for `aria` are exactly zero.

- [ ] **Step 14: Record runtime truth and commit the runbook evidence shape**

Replace only the `Result` cells actually observed with dated PASS/FAIL/BLOCKED references. Leave unexecuted cells `NOT RUN`; never infer them from unit tests. Then commit:

```bash
git add docs/runbooks/yandex-e2e.md app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowScreenshotTest.kt
git commit -m "test: verify progressive Yandex loading"
```

- [ ] **Step 15: Run the final repository hygiene check**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; only intended feature/test/runbook changes are present. Preserve all unrelated pre-existing untracked skill links and `artifacts/`; do not stage or delete them.

---

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-15-progressive-yandex-book-loading.md`. Two execution options:

1. **Subagent-Driven (recommended)** — dispatch a fresh subagent per task and review between tasks.
2. **Inline Execution** — execute tasks in this session using `superpowers:executing-plans`, in batches with checkpoints.

Do not create a new worktree for either option; execute on the current branch as required by the approved design context.
