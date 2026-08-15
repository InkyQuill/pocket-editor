# Durable Import, Compact Library, and Validated Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cache each Yandex chapter exactly once into a durable resumable import draft, promote that cache without downloading again, compact the library and confirmation UI, and block sync HTTP work until Android reports validated internet access.

**Architecture:** Add a Room-backed import-draft aggregate and a dedicated atomic filesystem store outside the registered-books root. Folder selection creates or resumes this durable snapshot; confirmation atomically promotes it into the existing registered-book protocol. Compose renders finished books and drafts as compact cards, while `SyncWorkerLogic` receives a small connectivity interface that gates `SyncEngine`.

**Tech Stack:** Kotlin 2.3, Jetpack Compose Material 3, Room 2.8, Kotlin serialization, coroutines, WorkManager 2.11, JUnit 5, AndroidX instrumentation tests.

## Global Constraints

- Selecting **Use this folder** is the only full chapter-download pass.
- Confirmation performs zero chapter downloads.
- Back and process recreation preserve the import draft and every cached source file.
- Only explicit **Удалить черновик и локальные файлы** confirmation deletes a draft cache.
- Canonical Markdown remains byte-for-byte read-only.
- Draft files live outside the registered-books root.
- Sync performs no Yandex request without both `NET_CAPABILITY_INTERNET` and `NET_CAPABILITY_VALIDATED`.
- Preserve all existing books, reviews, reading positions, sync state, drafts, and search rows through migration.
- Every new action has a minimum 48 dp touch target and a Russian accessibility label.
- Instrumentation commands use the dedicated `pocket-editor-instrumentation` AVD
  on `emulator-5556`; do not run them against the authenticated
  `pocket-editor-test` acceptance emulator on `emulator-5554`.
- Create and boot the dedicated AVD once before the first instrumentation step:

```bash
printf 'no\n' | /home/inky/.android/sdk/cmdline-tools/latest/bin/avdmanager create avd \
  --name pocket-editor-instrumentation \
  --package 'system-images;android-35;default;x86_64' \
  --device pixel_2 \
  --force
/home/inky/.android/sdk/emulator/emulator \
  -avd pocket-editor-instrumentation \
  -port 5556 \
  -no-snapshot-save
adb -s emulator-5556 wait-for-device
```

---

### Task 1: Durable import-draft model, Room migration, and atomic cache store

**Files:**
- Create: `app/src/main/java/net/inkyquill/pocketeditor/book/ImportDraftDocument.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/database/ImportDraftDao.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/storage/ImportDraftStore.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/database/Entities.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/database/PocketEditorDatabase.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/PocketEditorApp.kt`
- Test: `app/src/test/java/net/inkyquill/pocketeditor/book/ImportDraftDocumentTest.kt`
- Test: `app/src/test/java/net/inkyquill/pocketeditor/storage/ImportDraftStoreTest.kt`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/database/PocketEditorMigrationTest.kt`

**Interfaces:**
- Produces: `ImportDraftDocument`, `ImportDraftChapter`, and `ImportDraftPhase`.
- Produces: `ImportDraftDao.getAll()`, `getByBookId()`, `getByRemoteRoot()`, `upsert()`, and `delete()`.
- Produces: `ImportDraftStore.writeSource()`, `readSource()`, `hasMatchingSource()`, `promoteTo()`, and `delete()`.
- Consumes: existing strict UTF-8 and SHA-256 helpers.

- [ ] **Step 1: Write failing serialization, filesystem, and migration tests.**

```kotlin
@Test
fun `draft document round trips stable identity and remote revisions`() {
    val original = ImportDraftDocument(
        bookId = BOOK_ID,
        remoteRootPath = "disk:/growth-cheat/result/book01",
        title = "book01",
        phase = ImportDraftPhase.READY,
        chapters = listOf(
            ImportDraftChapter(
                id = CHAPTER_ID,
                path = "01-пролог.md",
                title = "Пролог",
                included = true,
                remoteRevision = "rev-1",
                sha256 = "abc",
                byteSize = 13,
            ),
        ),
    )
    assertEquals(original, ImportDraftDocument.decode(ImportDraftDocument.encode(original)))
}

@Test
fun `matching cached source survives reopen and explicit delete removes only draft tree`() = runBlocking {
    val store = ImportDraftStore(File(root, "import-drafts"))
    store.writeSource(BOOK_ID, "01-пролог.md", BYTES, "rev-1")
    assertTrue(store.hasMatchingSource(BOOK_ID, "01-пролог.md", "rev-1", BYTES.sha256()))
    assertArrayEquals(BYTES, store.readSource(BOOK_ID, "01-пролог.md"))
    store.delete(BOOK_ID)
    assertFalse(store.directory(BOOK_ID).exists())
}
```

Extend `PocketEditorMigrationTest` to create schema version 2 with a registered
book, reading position, outbox, and review draft; migrate to version 3; assert
those rows remain and `import_drafts` exists empty.

- [ ] **Step 2: Run focused tests and verify missing types/table failures.**

Run:

```bash
./gradlew testDebugUnitTest --tests '*ImportDraftDocumentTest' --tests '*ImportDraftStoreTest'
ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.database.PocketEditorMigrationTest
```

Expected: unit compilation fails for missing draft types; migration assertion
fails because schema version 3 and `import_drafts` do not exist.

- [ ] **Step 3: Implement the serializable draft document and strict decoder.**

```kotlin
@Serializable
data class ImportDraftDocument(
    val schemaVersion: Int = 1,
    val bookId: String,
    val remoteRootPath: String,
    val title: String,
    val phase: ImportDraftPhase,
    val chapters: List<ImportDraftChapter>,
    val lastError: ImportDraftError? = null,
) {
    init {
        require(schemaVersion == 1)
        require(UUID.fromString(bookId).toString() == bookId)
        require(remoteRootPath.startsWith("disk:/"))
        require(chapters.map { it.path }.distinct().size == chapters.size)
    }

    companion object {
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
        fun encode(value: ImportDraftDocument): String = json.encodeToString(value)
        fun decode(value: String): ImportDraftDocument = json.decodeFromString(value)
    }
}

@Serializable
enum class ImportDraftPhase { DOWNLOADING, READY, PROMOTING, FAILED }
```

`ImportDraftChapter` contains exactly the fields used by the test.
`ImportDraftError` contains a safe category and retryable flag, not a remote
path or exception message.

- [ ] **Step 4: Add the Room entity, DAO, and exact 2→3 migration.**

```kotlin
@Entity(
    tableName = "import_drafts",
    indices = [Index(value = ["remote_root_path"], unique = true)],
)
data class ImportDraftEntity(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "remote_root_path") val remoteRootPath: String,
    @ColumnInfo(name = "local_directory") val localDirectory: String,
    @ColumnInfo(name = "document_json") val documentJson: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
```

Increase `@Database(version = 3)`, add `ImportDraftEntity`, expose
`importDraftDao()`, and create the table plus unique index in `MIGRATION_2_3`.
Register both migrations in `AppContainer`.

- [ ] **Step 5: Implement `ImportDraftStore` with validated direct-child paths and atomic replacement.**

`ImportDraftStore` must:

- require UUID book IDs and direct-child filenames using the same rules as
  `BookPaths`;
- write `.<name>.<uuid>.tmp`, `fd.sync()`, atomic-move, then directory-sync;
- persist a small sidecar containing revision, SHA-256, and byte size after the
  source succeeds;
- compare all three fields in `hasMatchingSource`;
- validate source bytes with `StrictUtf8` before reporting a match;
- reject promotion when destination exists;
- validate both roots before recursive deletion.

- [ ] **Step 6: Run tests and export the Room schema.**

Run:

```bash
./gradlew testDebugUnitTest --tests '*ImportDraftDocumentTest' --tests '*ImportDraftStoreTest' assembleDebug
```

Expected: PASS and
`app/schemas/net.inkyquill.pocketeditor.database.PocketEditorDatabase/3.json`
is generated.

- [ ] **Step 7: Commit the durable draft foundation.**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/book \
  app/src/main/java/net/inkyquill/pocketeditor/database \
  app/src/main/java/net/inkyquill/pocketeditor/storage \
  app/src/main/java/net/inkyquill/pocketeditor/PocketEditorApp.kt \
  app/src/test app/src/androidTest/java/net/inkyquill/pocketeditor/database \
  app/schemas
git commit -m "feat: persist offline import drafts"
```

---

### Task 2: Single-download proposal, resumable cache, and zero-download promotion

**Files:**
- Create: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/ImportDraftRepository.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/BookLibraryController.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/RoomYandexBookLibraryData.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/storage/InstallRecoveryJournal.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/PocketEditorApp.kt`
- Test: `app/src/test/java/net/inkyquill/pocketeditor/ui/books/ImportDraftRepositoryTest.kt`
- Test: `app/src/test/java/net/inkyquill/pocketeditor/ui/books/BookLibraryControllerTest.kt`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/books/RoomYandexBookLibraryDataTest.kt`

**Interfaces:**
- Consumes: Task 1 `ImportDraftDocument`, `ImportDraftDao`, and `ImportDraftStore`.
- Produces: `ImportDraftSummary` and `ImportProgress`.
- Produces: `BookLibraryData.importDrafts()`, `resumeImport(bookId)`, `updateImport(draft)`, `discardImport(bookId)`.
- Changes: `BookLibraryData.propose(path, onProgress)` persists before returning.
- Changes: `BookLibraryData.import(draft)` promotes cached bytes and never calls `gateway.download`.

- [ ] **Step 1: Write failing request-count, resume, persistence, Back, and deletion tests.**

```kotlin
@Test
fun `proposal downloads each chapter once and confirmation downloads none`() = runBlocking {
    val gateway = CountingGateway(files = mapOf("01.md" to "# One", "02.md" to "# Two"))
    val data = fixture(gateway)
    val draft = data.propose(ROOT) {}
    assertEquals(listOf("01.md", "02.md"), gateway.downloadedPaths)

    gateway.downloadedPaths.clear()
    data.import(draft)

    assertTrue(gateway.downloadedPaths.isEmpty())
    assertEquals(listOf("One", "Two"), data.books().single().chapters.map { it.title })
}

@Test
fun `retry reuses unchanged cached chapters and downloads only missing revision`() = runBlocking {
    val first = fixtureGateway(failPath = "02.md")
    assertThrows<YandexDiskError.Offline> { data(first).propose(ROOT) {} }
    val second = fixtureGateway()
    val resumed = data(second).propose(ROOT) {}
    assertEquals(listOf("02.md"), second.downloadedPaths)
    assertEquals(ImportDraftPhase.READY, resumed.phase)
}

@Test
fun `back keeps draft and explicit discard removes row and files`() = runBlocking {
    controller.openFolder(ROOT)
    controller.openBooks()
    assertEquals(1, data.importDrafts().size)
    controller.requestDiscardDraft(BOOK_ID)
    controller.confirmDiscardDraft()
    assertTrue(data.importDrafts().isEmpty())
}
```

- [ ] **Step 2: Run focused tests and verify current duplicate-download behavior fails.**

Run:

```bash
./gradlew testDebugUnitTest \
  --tests '*ImportDraftRepositoryTest' \
  --tests '*BookLibraryControllerTest'
```

Expected: FAIL because proposal state is not durable and `import()` invokes
`gateway.download` for every selected chapter.

- [ ] **Step 3: Implement `ImportDraftRepository.createOrResume`.**

Algorithm:

1. Normalize root and return an existing draft with the same root.
2. Create stable `bookId` and `DOWNLOADING` document before the first request.
3. List remote files once; for every ordinary Markdown entry compare its
   revision and cached SHA.
4. Reuse matching cache; otherwise download, strict-decode, atomically write,
   and persist document progress after each chapter.
5. Run `BookDiscovery.propose` from cached bytes.
6. Preserve user-edited title/order/inclusion when retrying an existing draft;
   add new remote files at the end and retain missing cached files as a
   retryable error rather than silently dropping them.
7. Mark `READY` only after all proposed sources validate.

`onProgress(ImportProgress(completed, total, phase))` runs after each durable
checkpoint.

- [ ] **Step 4: Replace transient `ImportDraft` identity with stable draft identity.**

Add `bookId` and `phase` to the UI `ImportDraft`. Controller updates call
`data.updateImport(draft)` on the IO dispatcher before publishing the changed
state. `start()` loads draft summaries without auto-opening them. Opening the
same remote folder calls `resumeImport(existing.bookId)`.

- [ ] **Step 5: Implement zero-download atomic promotion.**

Build the final `BookManifest` from selected draft chapters. Read every source
from `ImportDraftStore`, validate SHA/UTF-8, write the manifest into the draft
tree, and journal the move to `BookPaths.bookDirectory(bookId)`. In one Room
transaction:

- register `BookRootEntity`;
- delete the `ImportDraftEntity`;
- insert manifest outbox;
- rebuild search entries from the promoted bytes.

Schedule `LOCAL_CHANGE` only after the transaction and move succeed. Recovery
must complete or roll back a `PROMOTING` draft without downloading.

- [ ] **Step 6: Map import failures to safe categories and redacted diagnostics.**

Add:

```kotlin
internal fun Throwable.toImportUserMessage(): String = when (this) {
    is YandexDiskError.Offline -> "Нет подключения к Яндекс Диску. Загруженные главы сохранены."
    is YandexDiskError.Unauthorized -> "Войдите в Яндекс Диск ещё раз."
    is YandexDiskError.NotFound -> "Папка или одна из глав больше недоступна."
    is YandexDiskError.RateLimited -> "Яндекс Диск временно ограничил запросы. Повторите позже."
    is YandexDiskError.ServerFailure -> "Яндекс Диск временно недоступен."
    else -> "Не удалось продолжить импорт. Загруженные главы сохранены."
}
```

Log only exception class, phase, completed count, and total count.

- [ ] **Step 7: Run focused unit and Room tests.**

Run:

```bash
./gradlew testDebugUnitTest \
  --tests '*ImportDraftRepositoryTest' \
  --tests '*BookLibraryControllerTest'
ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.books.RoomYandexBookLibraryDataTest
```

Expected: PASS; counting gateway proves zero confirmation downloads.

- [ ] **Step 8: Commit the single-pass import flow.**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui/books \
  app/src/main/java/net/inkyquill/pocketeditor/storage/InstallRecoveryJournal.kt \
  app/src/main/java/net/inkyquill/pocketeditor/PocketEditorApp.kt \
  app/src/test/java/net/inkyquill/pocketeditor/ui/books \
  app/src/androidTest/java/net/inkyquill/pocketeditor/ui/books
git commit -m "fix: reuse durable cache during book import"
```

---

### Task 3: Compact library with resumable drafts and secondary destructive actions

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/BooksScreen.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/navigation/PocketEditorRoot.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/RussianResources.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowScreenshotTest.kt`

**Interfaces:**
- Consumes: Task 2 `ImportDraftSummary`.
- Changes: `BooksScreen` receives `importDrafts`, resume/discard callbacks, and
  a single pending destructive-dialog model.
- Produces: semantic tags `library-list`, `book-card-<id>`, and
  `import-draft-card-<id>`.

- [ ] **Step 1: Write failing Compose behavior tests for hierarchy and actions.**

```kotlin
compose.onNodeWithText("Библиотека").assertIsDisplayed()
compose.onNodeWithText("Pocket Editor").assertDoesNotExist()
compose.onNodeWithTag("book-card-book-a").assertHasClickAction()
compose.onNodeWithText("Забыть").assertDoesNotExist()
compose.onNodeWithContentDescription("Действия с книгой Alchemy of Rain").performClick()
compose.onNodeWithText("Забыть локальную копию").assertIsDisplayed()
compose.onNodeWithTag("import-draft-card-draft-a").assertIsDisplayed()
compose.onNodeWithText("Настроить книгу").performClick()
assertEquals("draft-a", resumedDraft)
```

Add a discard-menu test that requires a confirmation dialog and verifies
cancel does not call the discard callback.

- [ ] **Step 2: Run the focused UI test on the disposable AVD and verify failure.**

Run:

```bash
ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.BookFlowTest
```

Expected: FAIL because the large product header and visible `Забыть` buttons
remain and draft cards do not exist.

- [ ] **Step 3: Implement compact library scaffold and cards.**

Use `Scaffold` with a `TopAppBar(title = { Text("Библиотека") })`. Put Add,
Appearance, and account overflow actions in the app bar. Render one
`LazyColumn` for draft and finished cards.

Finished card layout:

```kotlin
ListItem(
    headlineContent = { Text(book.title, maxLines = 1) },
    supportingContent = { Text("$chapterCount · $availability") },
    trailingContent = {
        Row {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            BookOverflow(book, onForget)
        }
    },
    modifier = Modifier
        .testTag("book-card-${book.bookId}")
        .clickable(enabled = book.recoveryError == null, onClick = onOpen),
)
```

Draft cards use a tonal container, a downloaded/offline supporting label, a
compact `Настроить книгу` action, and overflow-only deletion.

- [ ] **Step 4: Replace the empty/sign-in landing composition.**

Keep the Yandex connection prompt only when signed out. Make the empty state
one short title, one sentence, and one add button. Finished books and drafts
remain visible while signed out.

- [ ] **Step 5: Capture before/after library screenshots on the disposable AVD.**

Run the screenshot scene with `screenshotName=library-compact-after`, pull it
from `Pictures/PocketEditorTask11`, and store the reviewed artifact under
`artifacts/screenshots/library-compact-after.png`. Confirm no clipped text at
360×640 dp and font scale 1.3.

- [ ] **Step 6: Run focused UI tests and commit.**

Run:

```bash
ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.BookFlowTest,net.inkyquill.pocketeditor.ui.BookFlowScreenshotTest
```

Then:

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui/books/BooksScreen.kt \
  app/src/main/java/net/inkyquill/pocketeditor/ui/navigation/PocketEditorRoot.kt \
  app/src/main/java/net/inkyquill/pocketeditor/ui/RussianResources.kt \
  app/src/main/res/values/strings.xml \
  app/src/androidTest/java/net/inkyquill/pocketeditor/ui
git commit -m "feat: compact the book library"
```

---

### Task 4: Compact durable-cache confirmation screen

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/ImportConfirmationScreen.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/navigation/PocketEditorRoot.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowScreenshotTest.kt`

**Interfaces:**
- Consumes: Task 2 ready/failed draft phase and progress.
- Produces: tags `import-book-title`, `import-chapter-list`,
  `import-chapter-<path>`, and `confirm-import`.
- Back calls `onBackKeepDraft`; it never calls discard.

- [ ] **Step 1: Write failing compact-layout and cache-copy tests.**

```kotlin
compose.onNodeWithText("18 глав сохранены на устройстве").assertIsDisplayed()
compose.onNodeWithText("До подтверждения ничего не будет создано").assertDoesNotExist()
compose.onNodeWithTag("import-chapter-list").assert(hasScrollAction())
compose.onNodeWithTag("import-chapter-01-пролог.md").assertHeightIsAtLeast(48.dp)
compose.onNodeWithText("Добавить в библиотеку").assertIsDisplayed()
compose.onNodeWithText("Создать книгу для чтения без сети").assertDoesNotExist()
```

At 360×640 dp and font scale 1.3, assert the sticky footer and at least three
chapter rows can be reached without horizontal clipping.

- [ ] **Step 2: Run focused UI tests and verify current bulky layout fails.**

Run:

```bash
ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.BookFlowTest
```

- [ ] **Step 3: Implement the compact scaffold and dense chapter rows.**

Use a small `TopAppBar`, a status row, a single book-title field, weighted
`LazyColumn`, and one footer. Replace each full outlined chapter field with a
compact editable basic text field inside `ListItem`; keep filename in
`supportingContent`. Trailing up/down `IconButton`s remain 48 dp touch targets
but no longer form a separate full-height column.

Footer:

```kotlin
Surface(tonalElevation = 3.dp) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Выбрано $includedCount из ${draft.chapters.size}", Modifier.weight(1f))
        Button(
            onClick = onConfirm,
            enabled = canConfirm,
            modifier = Modifier.testTag("confirm-import").heightIn(min = 48.dp),
        ) { Text(if (importing) "Добавляем…" else "Добавить в библиотеку") }
    }
}
```

- [ ] **Step 4: Make Back persist current metadata and return to library.**

`PocketEditorRoot` calls the controller's `openBooks()` after any pending
`updateImport` write completes. No deletion dialog is shown for Back.

- [ ] **Step 5: Capture and inspect the real-size confirmation screenshot.**

Use the existing confirmation screenshot scene with the 18-chapter Russian
fixture, save `artifacts/screenshots/import-confirmation-compact-after.png`,
and visually compare it with `artifacts/screenshots/import-after-wait.png`.

- [ ] **Step 6: Run focused tests and commit.**

```bash
ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.BookFlowTest,net.inkyquill.pocketeditor.ui.BookFlowScreenshotTest
git add app/src/main/java/net/inkyquill/pocketeditor/ui/books/ImportConfirmationScreen.kt \
  app/src/main/java/net/inkyquill/pocketeditor/ui/navigation/PocketEditorRoot.kt \
  app/src/main/res/values/strings.xml \
  app/src/androidTest/java/net/inkyquill/pocketeditor/ui
git commit -m "feat: compact book confirmation"
```

---

### Task 5: Validated-internet sync gate

**Files:**
- Create: `app/src/main/java/net/inkyquill/pocketeditor/sync/NetworkAvailability.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/sync/SyncWorker.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/PocketEditorApp.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/net/inkyquill/pocketeditor/sync/SyncWorkerTest.kt`

**Interfaces:**
- Produces: `fun interface NetworkAvailability { fun hasValidatedInternet(): Boolean }`.
- Produces: `AndroidNetworkAvailability(Context)`.
- Changes: `SyncWorkerLogic` consumes `NetworkAvailability`.
- Adds: `SyncWorkerOutcome.NO_VALIDATED_NETWORK`, handled like bounded retry
  without invoking `SyncBookRunner`.

- [ ] **Step 1: Write the failing runner-call gate test.**

```kotlin
@Test
fun `worker never invokes sync runner without validated internet`() = runBlocking {
    var calls = 0
    val logic = SyncWorkerLogic(
        runner = SyncBookRunner { _, _ -> calls++; SyncStatus.Saved },
        network = NetworkAvailability { false },
    )
    assertEquals(SyncWorkerOutcome.NO_VALIDATED_NETWORK, logic.run(BOOK_ID, ROOT))
    assertEquals(0, calls)
}
```

Add the inverse test with `NetworkAvailability { true }` and assert one runner
call.

- [ ] **Step 2: Run the focused unit test and verify the missing gate.**

Run:

```bash
./gradlew testDebugUnitTest --tests '*SyncWorkerTest'
```

Expected: compilation failure for missing `NetworkAvailability`.

- [ ] **Step 3: Implement Android validated-network detection.**

```kotlin
class AndroidNetworkAvailability(context: Context) : NetworkAvailability {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun hasValidatedInternet(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
```

Ensure `ACCESS_NETWORK_STATE` exists in the manifest. Inject this instance from
`AppContainer` into `SyncWorkerFactory`.

- [ ] **Step 4: Gate before stale/retry runner execution and preserve bounded backoff.**

After stale-generation rejection and before `runner.syncBook`, return
`NO_VALIDATED_NETWORK` when the gate is false. `SyncWorkerCompletion` schedules
the existing bounded retry path for this outcome. It must not publish an active
sync status before the gate.

- [ ] **Step 5: Run sync unit suite and commit.**

```bash
./gradlew testDebugUnitTest --tests 'net.inkyquill.pocketeditor.sync.*'
git add app/src/main/java/net/inkyquill/pocketeditor/sync \
  app/src/main/java/net/inkyquill/pocketeditor/PocketEditorApp.kt \
  app/src/main/AndroidManifest.xml \
  app/src/test/java/net/inkyquill/pocketeditor/sync
git commit -m "fix: gate sync on validated internet"
```

---

### Task 6: Full verification and real `book01` emulator acceptance

**Files:**
- Modify if evidence changes: `docs/runbooks/yandex-e2e.md`
- Modify if evidence changes: `docs/HANDOFF.md`
- Keep local-only: `artifacts/screenshots/*.png`

**Interfaces:**
- Consumes: all Tasks 1–5.
- Produces: verified debug APK and screenshot/evidence paths.

- [ ] **Step 1: Run formatting, unit, lint, and debug build checks.**

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Expected: all tasks PASS.

- [ ] **Step 2: Run the full instrumentation suite on a disposable AVD.**

```bash
ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest
```

Expected: PASS with only pre-existing explicitly skipped screenshot/release
fixtures. Never point this command at the authenticated acceptance emulator.

- [ ] **Step 3: Install in place on the authenticated emulator and preserve data.**

```bash
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
```

Verify Yandex remains signed in before proceeding.

- [ ] **Step 4: Import the exact real folder and inspect durable state.**

Select `disk:/growth-cheat/result/book01`. Confirm:

- progress reaches 18/18 once;
- confirmation immediately says all 18 chapters are saved locally;
- Back returns to a visible draft card;
- reopening the draft causes zero download requests;
- confirmation opens the reader without another chapter download;
- local manifest contains 18 selected chapters.

Capture `artifacts/screenshots/book01-library-after.png` and
`artifacts/screenshots/book01-confirmation-after.png`.

- [ ] **Step 5: Verify offline survival and no-request sync gate.**

Force-stop the app, disable airplane-independent transports, relaunch, and open
at least chapters 1, 13, and 17 from cache. Clear logcat immediately before
opening the book; trigger local review work; verify no Yandex HTTP diagnostic
appears and sync remains waiting. Restore network and verify the pending
manifest sync completes.

- [ ] **Step 6: Update runbook evidence without recording secrets or source text.**

Record date, folder path, chapter count, offline pass, sync-gate pass, automated
commands, and screenshot paths. Do not record token, download URL, phone number,
or manuscript excerpts.

- [ ] **Step 7: Run final diff and repository checks.**

```bash
git diff --check
git status --short --branch
git log --oneline -8
```

Confirm `.agents/`, `.claude/`, `.env`, and local `artifacts/` remain untracked
and uncommitted unless the user separately requests otherwise.

- [ ] **Step 8: Commit verification documentation.**

```bash
git add docs/runbooks/yandex-e2e.md docs/HANDOFF.md
git commit -m "docs: record durable import verification"
```

## Plan self-review

- Task 1 covers durable schema, storage, migration, path safety, and restart
  persistence.
- Task 2 covers exactly-once downloads, resumable partial cache, no-download
  confirmation, atomic promotion, safe errors, and explicit deletion.
- Tasks 3–4 cover compact library/confirmation, draft recovery, secondary
  destructive actions, Russian accessibility, large fonts, and screenshots.
- Task 5 covers the validated-network preflight and zero runner calls offline.
- Task 6 covers all automated checks plus the real 18-chapter online/offline
  acceptance flow without clearing authenticated emulator data.
- Signatures and names used by later tasks match the interfaces introduced in
  earlier tasks; no task relies on an undefined placeholder.
