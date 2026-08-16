# Bidirectional Sync and Chapter Replacement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make remote binder and chapter changes authoritative when there is no local outbox, migrate the binder spine to `{id, path}`, retry transient failures indefinitely, and let a discovered Markdown file replace an existing chapter without losing its identity or review state.

**Architecture:** Keep the locked three-way merge in `SyncEngine`, but place a cheap revision probe in front of WorkManager and publish one book-level cache-change signal after a successful atomic sync. Derive chapter titles from cached source bytes through one extractor used by discovery, reader, summaries, and search. Implement replacement as one durable manifest mutation that preserves the chapter ID and copies the review sidecar to the new source path.

**Tech Stack:** Kotlin, coroutines and Flow, Room, WorkManager, kotlinx.serialization, commonmark-java, JUnit 5, AndroidX instrumentation.

## Global Constraints

- Work on `fix/review-issues-4-5`; do not create another branch or worktree.
- Binder schema v2 chapter entries contain exactly `id` and `path`.
- Read schema v1 without creating outbox work; encode schema v2 only after a real local binder mutation.
- Title precedence is YAML frontmatter `title`, first H1, then filename without `.md`.
- Probe every 60 seconds only while the app is foregrounded and a remote book is open.
- Retry network, timeout, server, rate-limit, delayed-visibility, and temporary-lock failures indefinitely with capped exponential backoff.
- Require user action only for authorization, invalid remote data, missing durable base, or a real merge conflict.
- Never modify `/Users/inkyquill/Yandex.Disk-dark13th.localized/writing/aria/`.

---

### Task 1: Binder schema v2 and one chapter-title extractor

**Files:**
- Create: `app/src/main/java/net/inkyquill/pocketeditor/book/ChapterTitleExtractor.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/book/BookManifest.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/book/BookDiscovery.kt`
- Modify: `app/src/test/resources/fixtures/manifest-v1.json`
- Test: `app/src/test/java/net/inkyquill/pocketeditor/book/BookManifestTest.kt`
- Test: `app/src/test/java/net/inkyquill/pocketeditor/book/BookDiscoveryTest.kt`

**Interfaces:**
- Produces: `data class ChapterEntry(val id: String, val path: String)`.
- Produces: `data class ChapterMetadata(val title: String, val number: Int?)`.
- Produces: `object ChapterTitleExtractor { fun extract(path: String, bytes: ByteArray): ChapterMetadata }`.

- [ ] **Step 1: Write failing schema migration tests**

```kotlin
@Test fun `schema v1 title is accepted but schema v2 encoding omits it`() {
    val decoded = BookManifest.decode(fixture("manifest-v1.json"))
    assertEquals("chapter-001.md", decoded.chapters.single().path)
    val encoded = BookManifest.encode(decoded.copy(title = "Changed"))
    assertTrue(encoded.contains("\"schema_version\": 2"))
    assertFalse(encoded.contains("\"title\": \"Chapter One\""))
}
```

- [ ] **Step 2: Run the focused test and confirm the v1-only model fails**

Run: `./gradlew testDebugUnitTest --tests '*BookManifestTest*schema v1 title*'`

Expected: FAIL because `ChapterEntry.title` is required and encoding still writes schema 1.

- [ ] **Step 3: Introduce wire DTOs and the title extractor**

```kotlin
@Serializable private data class ManifestWire(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("book_id") val bookId: String,
    val title: String,
    val chapters: List<ChapterWire>,
    @SerialName("ignored_files") val ignoredFiles: List<String> = emptyList(),
)

@Serializable private data class ChapterWire(
    val id: String,
    val path: String,
    val title: String? = null,
)

data class ChapterEntry(val id: String, val path: String)
```

Decode wire versions 1 and 2 into the title-free domain model. Reject every other version. Encode a `ManifestWire(schemaVersion = 2, chapters = chapters.map { ChapterWire(it.id, it.path) })` with `encodeDefaults = false` so chapter `title` is absent.

Implement `ChapterTitleExtractor.extract` with strict UTF-8 input, the existing frontmatter parsing rules, the first CommonMark H1, and `path.removeSuffix(".md")` fallback. Return the optional numeric frontmatter field for discovery ordering.

- [ ] **Step 4: Replace discovery's private metadata parser**

```kotlin
val ordered = ordinaryMarkdown
    .map { file -> file to ChapterTitleExtractor.extract(file.path, file.bytes) }

fun add(manifest: BookManifest, proposal: ChapterProposal, chapterId: String, order: Int): BookManifest =
    manifest.copy(chapters = manifest.chapters.toMutableList().apply {
        add(order, ChapterEntry(chapterId, proposal.path))
    }).validated()
```

Remove the editable `title` argument from `BookDiscovery.add`; proposals keep a derived display title only for previews.

- [ ] **Step 5: Run binder and discovery tests**

Run: `./gradlew testDebugUnitTest --tests '*BookManifestTest' --tests '*BookDiscoveryTest'`

Expected: PASS.

- [ ] **Step 6: Commit the schema boundary**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/book app/src/test/java/net/inkyquill/pocketeditor/book app/src/test/resources/fixtures/manifest-v1.json
git commit -m "feat: migrate binder spine to schema v2"
```

### Task 2: Derive titles in every cache consumer

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/reader/ReaderRepository.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/sync/SyncEngine.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/RoomYandexBookLibraryData.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/ImportDraftRepository.kt`
- Modify: `app/src/test/java/net/inkyquill/pocketeditor/reader/ReaderRepositoryTest.kt`
- Modify: `app/src/test/java/net/inkyquill/pocketeditor/sync/SyncEngineTest.kt`
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/books/RoomYandexBookLibraryDataTest.kt`

**Interfaces:**
- Consumes: `ChapterTitleExtractor.extract(path, bytes)` from Task 1.
- Produces: cached summaries, reader state, and search rows derived from the same source snapshot.

- [ ] **Step 1: Add failing title-precedence consumer tests**

```kotlin
@Test fun `open reader derives title from synchronized source`() = runBlocking {
    fixture.source("chapter.md", "---\ntitle: Frontmatter\n---\n# Heading\nBody")
    assertEquals("Frontmatter", fixture.reader().observeChapter(BOOK_ID, CHAPTER_ID, false).first().title)
}
```

Add an index assertion that the search chapter title equals the reader title after a remote source refresh.

- [ ] **Step 2: Run the focused tests and confirm stale manifest titles fail**

Run: `./gradlew testDebugUnitTest --tests '*ReaderRepositoryTest*derives title*' --tests '*SyncEngineTest*search title*'`

Expected: FAIL because current consumers read `chapter.title`.

- [ ] **Step 3: Replace all `chapter.title` reads at cache boundaries**

```kotlin
private fun chapterTitle(chapter: ChapterEntry, bytes: ByteArray): String =
    ChapterTitleExtractor.extract(chapter.path, bytes).title

val indexed = manifest.chapters.map { chapter ->
    val bytes = bookStore.readSource(bookId, chapter.path)
    IndexedChapter(chapter.id, chapterTitle(chapter, bytes), bytes)
}
```

Use the same pattern in `summaryFromCache`, install/import search rebuilds, `ReaderRepository.loadChapter`, discovery notices, and replacement previews. Do not add a title cache or database column.

- [ ] **Step 4: Run the title and import suites**

Run: `./gradlew testDebugUnitTest --tests '*ReaderRepositoryTest' --tests '*SyncEngineTest' --tests '*ImportDraftRepositoryTest'`

Expected: PASS.

- [ ] **Step 5: Commit the derived-title migration**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor app/src/test/java/net/inkyquill/pocketeditor app/src/androidTest/java/net/inkyquill/pocketeditor/ui/books/RoomYandexBookLibraryDataTest.kt
git commit -m "refactor: derive chapter titles from source"
```

### Task 3: Unlimited retry classification

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/sync/SyncEngine.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/sync/SyncWorker.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/sync/SyncScheduler.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/yandex/YandexDiskGateway.kt`
- Test: `app/src/test/java/net/inkyquill/pocketeditor/sync/SyncWorkerTest.kt`
- Test: `app/src/test/java/net/inkyquill/pocketeditor/sync/SyncSchedulerTest.kt`
- Test: `app/src/test/java/net/inkyquill/pocketeditor/sync/SyncEngineTest.kt`

**Interfaces:**
- Produces: `sealed interface SyncFailureClass { Retryable; SignIn; InvalidRemote; Conflict }` internal to sync.
- Produces: `SyncWorkerCompletion.complete` that always enqueues `retryAttempt + 1` for retryable outcomes while the generation remains current.

- [ ] **Step 1: Replace the retry-limit test with an indefinite-retry test**

```kotlin
@Test fun `retry after the former attempt limit remains scheduled`() {
    completion.complete(BOOK_ID, ROOT, SyncWorkerOutcome.RETRY, retryAttempt = 50, retryGeneration = generation)
    assertEquals(51, queue.single().retryAttempt)
    assertEquals(WorkRequest.MAX_BACKOFF_MILLIS, queue.single().initialDelay.toMillis())
}
```

Add cases for `CandidateCleanupUnconfirmed`, `LockHeld`, `LockLost`, `UploadIncomplete`, `Offline`, `RateLimited`, and `ServerFailure` returning `WaitingToSync` without a lock-breaking prompt.

- [ ] **Step 2: Run the retry tests and confirm the terminal cutoff fails**

Run: `./gradlew testDebugUnitTest --tests '*SyncWorkerTest' --tests '*SyncSchedulerTest' --tests '*SyncEngineTest*lock*'`

Expected: FAIL at the current `MAX_RETRY_ATTEMPTS` branch and action-required lock cases.

- [ ] **Step 3: Remove the terminal attempt count and classify errors explicitly**

```kotlin
when (outcome) {
    SyncWorkerOutcome.RETRY,
    SyncWorkerOutcome.NO_VALIDATED_NETWORK -> SyncRetryLauncher(queue, generations).launch(
        bookId, remoteRootPath, retryAttempt + 1, retryGeneration,
    )
    SyncWorkerOutcome.SUCCESS,
    SyncWorkerOutcome.TERMINAL -> invalidateRetryGeneration()
    SyncWorkerOutcome.STALE -> Unit
}
```

Map temporary lock and delayed-visibility failures to `WaitingToSync`. Keep `Unauthorized` as `SignInRequired`; map only validation/base/conflict failures to `ActionRequired`. Preserve redacted attempt and phase logging.

- [ ] **Step 4: Run the full sync unit suite**

Run: `./gradlew testDebugUnitTest --tests 'net.inkyquill.pocketeditor.sync.*' --tests 'net.inkyquill.pocketeditor.yandex.*'`

Expected: PASS.

- [ ] **Step 5: Commit retry behavior**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/sync app/src/main/java/net/inkyquill/pocketeditor/yandex app/src/test/java/net/inkyquill/pocketeditor/sync
git commit -m "fix: retry transient sync failures indefinitely"
```

### Task 4: Revision probe, foreground monitor, and reactive library refresh

**Files:**
- Create: `app/src/main/java/net/inkyquill/pocketeditor/sync/RemoteRevisionProbe.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/sync/BookSyncMonitor.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/database/SyncDao.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/storage/ContentChangeNotifier.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/sync/NetworkConnectivityObserver.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/PocketEditorApp.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/navigation/PocketEditorRoot.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/BookLibraryController.kt`
- Create test: `app/src/test/java/net/inkyquill/pocketeditor/sync/RemoteRevisionProbeTest.kt`
- Create test: `app/src/test/java/net/inkyquill/pocketeditor/sync/BookSyncMonitorTest.kt`
- Create test: `app/src/test/java/net/inkyquill/pocketeditor/sync/NetworkConnectivityObserverTest.kt`
- Test: `app/src/test/java/net/inkyquill/pocketeditor/ui/books/BookLibraryControllerTest.kt`

**Interfaces:**
- Produces: `suspend fun RemoteRevisionProbe.shouldSync(bookId: String, remoteRootPath: String): Boolean`.
- Produces: `BookSyncMonitor.activate(bookId, rootPath)`, `foreground(Boolean)`, and `trigger(SyncTrigger)`.
- Produces: `ContentChangeNotifier.bookVersions: StateFlow<Map<String, Long>>` and `bookChanged(bookId)`.
- Produces: `BookLibraryData.bookChanges(): Flow<String>` backed by notifier version changes.
- Produces: `NetworkConnectivityObserver.connected: Flow<Unit>` for validated-network restoration.

- [ ] **Step 1: Write failing probe and coalescing tests**

```kotlin
@Test fun `changed remote binder revision requests full sync`() = runTest {
    metadata.remote += RemoteRevisionEntity(BOOK_ID, MANIFEST_PATH, "old", null)
    gateway.entries += remoteEntry(MANIFEST_PATH, revision = "new")
    assertTrue(probe.shouldSync(BOOK_ID, ROOT))
}

@Test fun `overlapping foreground and timer triggers enqueue once`() = runTest {
    monitor.activate(BOOK_ID, ROOT)
    monitor.foreground(true)
    monitor.trigger(SyncTrigger.CHAPTER_CHANGE)
    advanceUntilIdle()
    assertEquals(1, scheduler.requests.size)
}
```

Cover unchanged revisions with empty outbox (false), tracked source/review deletion (true), untracked Markdown addition (false), non-empty outbox (true), and the 60-second virtual-time tick.

- [ ] **Step 2: Run the new tests and confirm the types are absent**

Run: `./gradlew testDebugUnitTest --tests '*RemoteRevisionProbeTest' --tests '*BookSyncMonitorTest'`

Expected: FAIL because probe and monitor do not exist.

- [ ] **Step 3: Implement the lock-free revision comparison**

```kotlin
val tracked = buildSet {
    add(BookPaths.MANIFEST_NAME)
    manifest.chapters.forEach { chapter ->
        add(chapter.path)
        add(chapter.path + BookPaths.REVIEW_SUFFIX)
    }
}
if (metadata.outbox(bookId).isNotEmpty()) return true
val remote = gateway.listFolder(remoteRootPath).filter { it.type == "file" }.associateBy { it.name }
return tracked.any { path -> confirmed[path]?.remoteRevision != remote[path]?.revision }
```

Add direct DAO reads for confirmed revisions and per-book outbox. The probe never acquires a lock, downloads bytes, writes metadata, or treats untracked files as sync changes.

- [ ] **Step 4: Implement foreground monitoring and immediate triggers**

```kotlin
while (currentCoroutineContext().isActive) {
    delay(60.seconds)
    probeAndSchedule(SyncTrigger.PERIODIC_PROBE)
}
```

Extend `SyncTrigger` with `FOREGROUND`, `CHAPTER_CHANGE`, and `PERIODIC_PROBE`. Guard per-book probing with a `Mutex`; record one pending trigger while a probe runs.

Every trigger first calls `shouldSync`; enqueue full work only when a tracked revision changed or outbox work exists. If that condition is true, `SYNC_NOW`, local outbox, navigation, and reconnection replace a delayed retry generation with immediate active work.

Feed foreground state from `ProcessLifecycleOwner` in `PocketEditorRoot`, activate the current remote book, rewire **Sync now** to `monitor.trigger(SyncTrigger.SYNC_NOW)`, and send `CHAPTER_CHANGE` after navigation. Register one `ConnectivityManager.NetworkCallback` in `NetworkConnectivityObserver`; emit only when the active network regains `NET_CAPABILITY_VALIDATED`, then call `monitor.trigger(SyncTrigger.RECONNECT)`.

- [ ] **Step 5: Refresh the library only after published cache changes**

```kotlin
init {
    scope.launch {
        data.bookChanges().collect { changedBookId ->
            if ((state.value.destination as? BookDestination.Reader)?.bookId == changedBookId) {
                refreshBooksAndDiscovery(changedBookId)
            }
        }
    }
}
```

Expose a flow from `ContentChangeNotifier.bookVersions` through `BookLibraryData.bookChanges()`. During sync, collect changed source and review paths without notifying observers. After manifest, sources, merge bases, revisions, and search index are durable, publish their path changes together and call `bookChanged(bookId)`. Remove the immediate `refreshDiscoveryQuietly` calls that follow asynchronous `opened`; discovery now runs after publication or after a local binder mutation.

Update `ConflictCardMapper` and its tests so manifest conflicts summarize added, removed, reordered, and repointed chapter IDs and paths instead of showing only the book title. Remove the stale-lock break prompt and callback from `ReaderScreen`, `ReaderCallbacks`, and `PocketEditorRoot`, because temporary lock conditions now remain in the quiet retry state.

```kotlin
data class ManifestSpineDiff(
    val added: List<ChapterEntry>,
    val removed: List<ChapterEntry>,
    val repointed: List<Pair<ChapterEntry, ChapterEntry>>,
    val orderChanged: Boolean,
)
```

- [ ] **Step 6: Add the `aria` regression shape without reading or writing the real folder**

```kotlin
@Test fun `remote v2 spine replaces cached v1 without upload`() = runBlocking {
    val remote = manifest((1..28).map { chapter(it, "chapter-%03d-v2.md".format(it)) })
    fixture.remote.put(MANIFEST_PATH, encode(remote))
    remote.chapters.forEach { fixture.remote.put(it.path, "# ${it.path}".encodeToByteArray()) }
    assertEquals(SyncStatus.Saved, fixture.engine.syncBook(BOOK_ID, ROOT))
    assertEquals(remote, fixture.cache.manifest)
    assertTrue(fixture.remote.uploads.isEmpty())
}
```

- [ ] **Step 7: Run monitor, controller, and sync tests**

Run: `./gradlew testDebugUnitTest --tests '*RemoteRevisionProbeTest' --tests '*BookSyncMonitorTest' --tests '*BookLibraryControllerTest' --tests '*SyncEngineTest'`

Expected: PASS.

- [ ] **Step 8: Commit remote monitoring**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor app/src/test/java/net/inkyquill/pocketeditor
git commit -m "feat: monitor remote book revisions"
```

### Task 5: Identity-preserving chapter replacement

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/book/BookDiscovery.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/BookLibraryController.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/RoomYandexBookLibraryData.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/contents/ContentsPanel.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/contents/DiscoveryPanel.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/navigation/PocketEditorRoot.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/reader/ReadingPositionClamp.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/net/inkyquill/pocketeditor/book/BookDiscoveryTest.kt`
- Test: `app/src/test/java/net/inkyquill/pocketeditor/ui/books/BookLibraryControllerTest.kt`
- Create test: `app/src/test/java/net/inkyquill/pocketeditor/reader/ReadingPositionClampTest.kt`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/books/RoomYandexBookLibraryDataTest.kt`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt`

**Interfaces:**
- Produces: `BookDiscovery.replace(manifest, chapterId, newPath): BookManifest`.
- Produces: `BookLibraryData.add(bookId, path, position)`, `BookLibraryData.replace(bookId, chapterId, path)`, and matching controller methods without editable chapter titles.
- Produces: `DiscoveryPanel(..., currentChapterId, chapters, onReplace)`.
- Produces: `ReadingPositionClamp.clamp(position, rendered): ReadingPositionEntity`.

- [ ] **Step 1: Write failing domain and persistence tests**

```kotlin
@Test fun `replace keeps id and ignores old path`() {
    val replaced = discovery.replace(manifest, CHAPTER_ID, "chapter-v2.md")
    assertEquals(ChapterEntry(CHAPTER_ID, "chapter-v2.md"), replaced.chapters.single())
    assertTrue("chapter-v1.md" in replaced.ignoredFiles)
    assertFalse("chapter-v2.md" in replaced.ignoredFiles)
}
```

The Room test must also assert unchanged reading-position chapter ID, a byte/block position clamped into the replacement source, a copied review with `source_path = chapter-v2.md`, a pending v2 sidecar outbox item, a pending binder outbox item against the current merge base, and no remote delete call.

- [ ] **Step 2: Run focused replacement tests and confirm failure**

Run: `./gradlew testDebugUnitTest --tests '*BookDiscoveryTest*replace*' --tests '*BookLibraryControllerTest*replace*'`

Expected: FAIL because replacement APIs are absent.

- [ ] **Step 3: Implement the atomic replacement mutation**

```kotlin
fun replace(manifest: BookManifest, chapterId: String, newPath: String): BookManifest {
    val old = manifest.chapters.single { it.id == chapterId }
    require(manifest.chapters.none { it.id != chapterId && it.path == newPath })
    return manifest.copy(
        chapters = manifest.chapters.map { if (it.id == chapterId) it.copy(path = newPath) else it },
        ignoredFiles = (manifest.ignoredFiles - newPath + old.path).distinct(),
    ).validated()
}
```

In `RoomYandexBookLibraryData.replace`, download and strict-UTF-8 validate the new source before mutation, copy the old review to the new sidecar with its source path changed, preserve the old cached files, write the manifest outbox against the exact current base, rebuild search, publish a book change, and enqueue immediate sync. Let the normal three-way pass detect a remote base change before upload.

Clamp the saved position against `MarkdownParser.parse(newSource)`: retain the chapter ID, choose the requested block when it exists or the nearest last visible block, and clamp the byte offset to that block's `[rawRange.startByte, rawRange.endByte]` interval.

```kotlin
val source = StrictUtf8.decode(download.bytes, "Replacement source $path")
val rendered = MarkdownParser.parse(source)
store.replaceDownloadedSource(bookId, path, download.bytes)
existingReview?.copy(sourcePath = path)?.let { copied ->
    val local = store.writeReview(bookId, path + BookPaths.REVIEW_SUFFIX, copied)
    sync.upsertOutbox(OutboxEntity(bookId, path + BookPaths.REVIEW_SUFFIX, local.sha256, null, OutboxState.PENDING))
}
books.getReadingPosition(bookId)?.let { books.upsertReadingPosition(ReadingPositionClamp.clamp(it, rendered)) }
persistManifestMutation(root, discovery.replace(manifest, chapterId, path))
```

- [ ] **Step 4: Add the three-action discovery UI**

```kotlin
NewFileCard(
    notice = notice,
    onAdd = { addDraft = notice },
    onReplace = { replaceDraft = notice },
    onIgnore = onIgnore,
)
```

`ReplaceChapterDialog` lists current chapters by derived title, preselects `currentChapterId`, and confirms `(chapterId, notice.path)`. Remove the editable title field from `AddChapterDialog`; show the derived preview title as read-only text.

```kotlin
interface BookLibraryData {
    suspend fun add(bookId: String, path: String, position: Int)
    suspend fun replace(bookId: String, chapterId: String, path: String)
}
```

- [ ] **Step 5: Run replacement UI and Room tests**

Run: `./gradlew testDebugUnitTest --tests '*BookDiscoveryTest' --tests '*BookLibraryControllerTest' && ./gradlew compileDebugAndroidTestKotlin`

Expected: PASS.

- [ ] **Step 6: Commit replacement behavior**

```bash
git add app/src/main app/src/test app/src/androidTest
git commit -m "feat: replace chapters from discovered files"
```

### Task 6: Slice verification

**Files:**
- Verify only; do not edit the `aria` fixture.

- [ ] **Step 1: Run all unit tests and lint**

Run: `./gradlew testDebugUnitTest lintDebug`

Expected: BUILD SUCCESSFUL with zero test failures and zero lint errors.

- [ ] **Step 2: Compile instrumentation tests**

Run: `./gradlew compileDebugAndroidTestKotlin`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run replacement instrumentation on an available emulator**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.BookFlowTest`

Expected: PASS. If no emulator is connected, record that runtime verification is pending; do not report compilation as runtime proof.
