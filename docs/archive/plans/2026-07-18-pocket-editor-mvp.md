# Pocket Editor MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the approved local-first Android reader and editorial-overlay app for Markdown books stored on Yandex Disk.

**Architecture:** One Android `:app` module is organized by feature, with a pure-Kotlin core for deterministic files, anchors, Markdown mapping, and merges. App-private files are authoritative locally; Room is a disposable index/outbox, WorkManager synchronizes through a narrow Yandex gateway, and Compose sees use-case state rather than files, JSON, SQL, or HTTP.

**Tech Stack:** Kotlin/JVM 17, Android Gradle Plugin 9.2.1, Gradle 9.4.1, compile/target SDK 36, min SDK 26, Jetpack Compose BOM 2026.06.00, Navigation 3 1.1.4, Room 2.8.4, WorkManager 2.11.2, kotlinx.serialization JSON, coroutines/Flow, commonmark-java 0.28.0, OkHttp, Yandex ID SDK 3.1.3, JUnit 5, AndroidX Test, and Compose UI Test.

## Global Constraints

- The approved design at `docs/superpowers/specs/2026-07-18-pocket-editor-design.md` is authoritative.
- Application ID is exactly `net.inkyquill.pocketeditor`; formal author is `Pavel Obruchnikov <me@inkyquill.net>`.
- Pocket Editor never uploads or otherwise writes canonical `*.md` chapters.
- The only durable remote files it may create or replace are
  `.pocket-editor.json` and `*.review.json`; the only transient remote file is
  `.pocket-editor.sync.lock`.
- A completed review mutation is atomically durable locally before any network request.
- Review JSON is UTF-8, LF, two-space indented, trailing-newline terminated, strict, and deterministic.
- Invalid, stale, and ambiguous anchors never attach silently; edits never overlap; signals may overlap.
- A cached book remains readable, searchable, and reviewable without connectivity.
- Remote writes require verified ownership of `.pocket-editor.sync.lock`, a
  refreshed remote state, and any required explicit conflict decision.
- Raw HTML is inert text and is never executed.
- Use stable dependencies only; do not substitute alpha/beta artifacts.
- Before execution, the user-created standalone Git repository must exist at `/home/inky/Development/pocket-editor`.
- Each task ends in its stated verification and a focused commit.

## Milestones and file map

| Milestone | Deliverable | Primary packages |
|---|---|---|
| 1. Core | Deterministic documents, anchors, edit rules, merge | `book`, `review`, `anchor`, `merge` |
| 2. Storage/sync | Atomic cache, Room index/outbox, Yandex, recovery | `storage`, `database`, `yandex`, `sync` |
| 3. Reading engine | Markdown source maps, projection, source search | `markdown`, `reader`, `search` |
| 4. Product UI | Adaptive reader, setup/TOC, review tools, conflicts | `ui/*` |
| 5. Release | Privacy, CI, signed APK, Yandex E2E | `app`, `.github`, `docs/runbooks` |

The MVP stays in one module. Package interfaces provide isolation without premature Gradle-module complexity.

---

### Task 1: Bootstrap the Android application

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `gradle.properties`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/PocketEditorApp.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/MainActivity.kt`
- Create: `app/src/test/java/net/inkyquill/pocketeditor/BuildSmokeTest.kt`
- Create: `.gitignore`

**Interfaces:**
- Produces: `PocketEditorApp : Application`, `MainActivity : ComponentActivity`, and the build used by every later task.
- Consumes: no application code.

- [ ] **Step 1: Write the failing build-smoke test**

```kotlin
class BuildSmokeTest {
    @Test fun applicationId_isStable() {
        assertEquals("net.inkyquill.pocketeditor", BuildConfig.APPLICATION_ID)
    }
}
```

- [ ] **Step 2: Create the pinned build**

Use Gradle 9.4.1 and the versions in the plan header. Enable JVM 17, Compose, `buildConfig`, KSP, and kotlinx serialization; set `compileSdk/targetSdk = 36`, `minSdk = 26`.

Run: `./gradlew testDebugUnitTest`  
Expected: `BuildSmokeTest` passes.

- [ ] **Step 3: Add the application shell**

```kotlin
class PocketEditorApp : Application() {
    lateinit var container: AppContainer
        private set
    override fun onCreate() {
        super.onCreate()
        container = AppContainer.create(this)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { PocketEditorTheme { PocketEditorRoot() } }
    }
}
```

Create compile-safe empty `AppContainer`, `PocketEditorTheme`, and `PocketEditorRoot`; later tasks keep these names.

- [ ] **Step 4: Verify and commit**

Run: `./gradlew test lint assembleDebug`  
Expected: `BUILD SUCCESSFUL` and `app-debug.apk` exists.

```bash
git add .gitignore settings.gradle.kts build.gradle.kts gradle gradlew gradlew.bat app
git commit -m "build: bootstrap Pocket Editor Android app"
```

---

### Task 2: Implement manifest and review contracts

**Files:**
- Create: `app/src/main/java/net/inkyquill/pocketeditor/book/BookManifest.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/review/ReviewDocument.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/review/ReviewJson.kt`
- Create: `app/src/test/java/net/inkyquill/pocketeditor/book/BookManifestTest.kt`
- Create: `app/src/test/java/net/inkyquill/pocketeditor/review/ReviewJsonTest.kt`
- Create: `app/src/test/resources/fixtures/manifest-v1.json`
- Create: `app/src/test/resources/fixtures/review-v1.json`

**Interfaces:**
- Produces: `BookManifest`, `ChapterEntry`, `ReviewDocument`, `Signal`, `Edit`, `Anchor`, `SignalType`, and `ReviewJson.decode/encode`.
- Consumes: kotlinx.serialization only.

- [ ] **Step 1: Write strict fixture tests**

Test byte-identical round-trip; ID sorting; semantic chapter order; ignored-path sorting; LF/trailing newline; duplicate IDs/paths; traversal; unknown fields/version; empty/equal edit text; malformed hashes; and chapter/path mismatch.

```kotlin
@Test fun deterministicRoundTrip() {
    val input = fixture("review-v1.json")
    val document = ReviewJson.decode(input, chapterId, "chapter-01.md")
    assertEquals(input, ReviewJson.encode(document))
}
```

Run: `./gradlew testDebugUnitTest --tests '*ReviewJsonTest'`  
Expected: FAIL because `ReviewJson` does not exist.

- [ ] **Step 2: Define immutable serialized models**

```kotlin
@Serializable
data class ReviewDocument(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("chapter_id") val chapterId: String,
    @SerialName("source_path") val sourcePath: String,
    @SerialName("chapter_note") val chapterNote: String = "",
    val signals: List<Signal> = emptyList(),
    val edits: List<Edit> = emptyList(),
)

@Serializable
enum class SignalType {
    @SerialName("note") NOTE,
    @SerialName("change_required") CHANGE_REQUIRED,
    @SerialName("warning") WARNING,
    @SerialName("review") REVIEW,
}
```

Define every remaining field exactly as the approved schema. Validate UUID strings at the serialization boundary.

- [ ] **Step 3: Implement strict deterministic JSON**

Use `Json { ignoreUnknownKeys = false; explicitNulls = false; prettyPrint = true; prettyPrintIndent = "  " }`. Validate on decode and encode; sort record arrays by ID and ignored paths lexically; preserve chapter order and every text byte; append exactly one LF.

- [ ] **Step 4: Verify and commit**

Run: `./gradlew testDebugUnitTest --tests '*BookManifestTest' --tests '*ReviewJsonTest'`  
Expected: all valid/invalid fixture cases pass.

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/{book,review} app/src/test
git commit -m "feat: define deterministic review file contracts"
```

---

### Task 3: Add anchors, edits, diffs, and three-way merge

**Files:**
- Create: `app/src/main/java/net/inkyquill/pocketeditor/anchor/AnchorFactory.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/anchor/AnchorResolver.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/review/EditValidator.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/review/EditDiff.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/merge/ReviewMerge.kt`
- Test: matching files under `app/src/test/java/net/inkyquill/pocketeditor/`

**Interfaces:**
- Produces: `AnchorFactory.create(ByteArray, Int, Int): Anchor`; `AnchorResolver.resolve(ByteArray, Anchor, String): AnchorResolution`; `EditValidator.validate(Edit, List<Edit>, ByteArray)`; `EditDiff.compute(String, String): List<DiffRun>`; `ReviewMerge.merge(base, local, remote): MergeResult`.
- Consumes: Task 2 models.

- [ ] **Step 1: Write UTF-8 and resolution tests**

```kotlin
@Test fun emojiUsesUtf8Offsets() {
    val source = "До 😀 после".encodeToByteArray()
    val start = "До ".encodeToByteArray().size
    val end = start + "😀".encodeToByteArray().size
    val anchor = AnchorFactory.create(source, start, end)
    assertEquals(Resolved(start, end), AnchorResolver.resolve(source, anchor, "😀"))
}
```

Add exact, unique relocation, full-context relocation, stale, ambiguous,
128-code-point context, intersecting/adjacent edits, overlapping signals,
identical change, delete-vs-change, and chapter-note singleton tests. Manifest
conflict handling belongs to Task 7 because manifests use a file-level choice,
not `ReviewMerge`.

- [ ] **Step 2: Implement exact anchors**

Hash and offset UTF-8 bytes; trim contexts by Unicode code points. Follow the approved seven-step resolver and return only `Resolved`, `Stale`, or `Ambiguous(candidates)`; never fuzzy match.

- [ ] **Step 3: Implement validation and display diff**

Reject empty/equal `before`, source mismatch, and half-open edit intersections; allow adjacency. Return deterministic `DiffRun(UNCHANGED|DELETED|ADDED, text)`; never persist runs.

- [ ] **Step 4: Implement record merge**

```kotlin
sealed interface MergeResult {
    data class Merged(val document: ReviewDocument) : MergeResult
    data class Conflicted(
        val partial: ReviewDocument,
        val conflicts: List<RecordConflict>,
    ) : MergeResult
}
```

Index by stable ID, implement all six approved rules, and model `chapter_note` internally as reserved ID `chapter-note` without serializing that ID.

- [ ] **Step 5: Verify and commit**

Run: `./gradlew testDebugUnitTest --tests '*Anchor*' --tests '*Edit*' --tests '*Merge*'`  
Expected: all matrices pass.

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/{anchor,review,merge} app/src/test
git commit -m "feat: add exact anchors and review merging"
```

---

### Task 4: Build Markdown source maps and review projection

**Files:**
- Create: `app/src/main/java/net/inkyquill/pocketeditor/markdown/MarkdownParser.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/markdown/RenderedDocument.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/markdown/SelectionMapper.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/reader/ReviewProjector.kt`
- Test: corresponding files and fixtures under `app/src/test/`

**Interfaces:**
- Produces: `MarkdownParser.parse(String): RenderedDocument`; `SelectionMapper.toRawRange(RenderedDocument, TextRange): RawRange?`; `ReviewProjector.project(RenderedDocument, ReviewDocument?, Boolean): ReaderDocument`.
- Consumes: commonmark-java source spans and Task 3.

- [ ] **Step 1: Write mapping fixtures**

Cover YAML front matter hiding, H1, paragraphs, emphasis, strong, Russian, emoji, links, quotes, lists, code, tables, and raw HTML. Valid selections map to one raw UTF-8 range; syntax-splitting selections return null; HTML renders inert.

- [ ] **Step 2: Parse with source evidence**

Use `includeSourceSpans(BLOCKS_AND_INLINES)`. Convert Java UTF-16 indices to UTF-8 through one precomputed index. Preserve front matter as a hidden source block instead of deleting it before offset calculation.

- [ ] **Step 3: Project clean/review modes**

Clean mode contains zero review-derived objects. Review mode resolves anchors, produces red strike/green diff runs, preserves intersecting signals, and places non-empty comments after their containing block in source order.

- [ ] **Step 4: Verify and commit**

Run: `./gradlew testDebugUnitTest --tests '*Markdown*' --tests '*SelectionMapper*' --tests '*ReviewProjector*'`  
Expected: every fixture and clean-mode invariant passes.

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/{markdown,reader} app/src/test
git commit -m "feat: map Markdown into reviewable reader blocks"
```

---

### Task 5: Add atomic cache and disposable Room state

**Files:**
- Create: `app/src/main/java/net/inkyquill/pocketeditor/storage/BookPaths.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/storage/AtomicBookStore.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/storage/RecoveryScanner.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/database/PocketEditorDatabase.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/database/Entities.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/database/BookDao.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/database/SyncDao.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/database/DraftDao.kt`
- Test: unit/instrumented storage and migration tests
- Create: `app/schemas/`

**Interfaces:**
- Produces: `BookStore`, internal-only `SourceCache`, Room flows for registrations/revisions/bases/outbox/position/drafts, and `RecoveryScanner.reconcile(): RecoveryReport`.
- Consumes: Task 2 serializers.

- [ ] **Step 1: Write forbidden-path and interruption tests**

Assert traversal rejection, validation-before-replace, interruption preserves old data, and only manifest/review names have public write methods.

- [ ] **Step 2: Implement the narrow store**

```kotlin
interface BookStore {
    suspend fun readSource(bookId: String, path: String): ByteArray
    suspend fun readManifest(bookId: String): BookManifest
    suspend fun writeManifest(bookId: String, value: BookManifest): LocalRevision
    suspend fun readReview(bookId: String, path: String): ReviewDocument?
    suspend fun writeReview(bookId: String, path: String, value: ReviewDocument): LocalRevision
}

internal interface SourceCache {
    suspend fun replaceDownloadedSource(
        bookId: String,
        path: String,
        bytes: ByteArray,
    ): LocalRevision
}
```

Only the sync package receives `SourceCache`; UI and review use cases receive
`BookStore`. Review/manifest writes serialize, validate, write sibling temp,
fsync when supported, atomically rename, and return SHA-256.

- [ ] **Step 3: Implement Room v1**

Tables: `book_roots`, `remote_revisions`, `merge_bases`, `outbox`, `reading_positions`, `drafts`. Export schemas. Store no manuscript or review document text.

- [ ] **Step 4: Implement recovery**

On startup compare deterministic file hashes to merge bases/outbox. Recreate missing pending work. Without a trustworthy base, mark `NEEDS_REMOTE_COMPARE`, never upload. Test deleting Room loses no saved review data.

- [ ] **Step 5: Verify and commit**

Run: `./gradlew testDebugUnitTest connectedDebugAndroidTest`  
Expected: atomic, migration, rebuild, and boundary tests pass.

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/{storage,database} app/src/test app/src/androidTest app/schemas
git commit -m "feat: persist books with atomic files and rebuildable indexes"
```

---

### Task 6: Connect safely to Yandex Disk

**Files:**
- Create: `app/src/main/java/net/inkyquill/pocketeditor/yandex/YandexAuth.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/yandex/YandexDiskApi.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/yandex/YandexDiskGateway.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/yandex/RedactingHttpLogger.kt`
- Create: `app/src/test/java/net/inkyquill/pocketeditor/yandex/YandexDiskGatewayTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: `AuthSession`, `RemoteEntry`, `RemoteFile`, `SyncLock`;
  `listFolder`, `download`, `tryAcquireLock`, `readLock`, `uploadGuarded`, and
  `releaseOwnedLock`; Task 7 extends this boundary with
  `breakObservedLock(rootPath, observedLock)` for explicit stale-lock recovery.
- Consumes: Yandex ID token provider and OkHttp; raw HTTP types never escape.

- [ ] **Step 1: Write MockWebServer contracts**

Cover pagination, encoded paths, URL indirection, revision extraction,
competing `overwrite=false` lock acquisition, nonce verification, guarded
upload, owned release, 401/404/409/429/5xx, invalid JSON, cancellation, log
redaction, and pre-request rejection of canonical upload paths.

- [ ] **Step 2: Wrap Yandex ID**

```kotlin
interface YandexAuth {
    val session: StateFlow<AuthSession>
    suspend fun signIn(activity: ComponentActivity): AuthSession.SignedIn
    suspend fun signOut()
    suspend fun accessToken(): SecretToken
}
```

Keep tokens in SDK/Keystore-backed private storage; exclude backup; redact authorization, query, excerpt, and full path; delete credentials on sign-out.

- [ ] **Step 3: Implement gateway/error mapping**

Map `Offline`, `Unauthorized`, `NotFound`, `LockHeld`, `LockLost`,
`RateLimited`, `InvalidRemote`, and `ServerFailure`. `uploadGuarded` verifies the
remote lock nonce before requesting an `overwrite=true` upload URL. It accepts
only manifest/review paths; lock creation alone uses `overwrite=false`, and lock
release verifies ownership immediately before delete.

- [ ] **Step 4: Verify and commit**

Run: `./gradlew testDebugUnitTest --tests '*Yandex*'`  
Expected: all HTTP and canonical-upload prevention tests pass.

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/yandex app/src/test app/src/main/AndroidManifest.xml
git commit -m "feat: connect safely to Yandex Disk"
```

---

### Task 7: Implement discovery and offline sync

**Files:**
- Create: `app/src/main/java/net/inkyquill/pocketeditor/book/BookDiscovery.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/sync/SyncEngine.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/sync/SyncWorker.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/sync/SyncScheduler.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/sync/ConflictRepository.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/sync/SyncBaseStore.kt`
- Test: matching files under `app/src/test/`

**Interfaces:**
- Produces: `BookDiscovery.propose`, `SyncEngine.syncBook`, `SyncScheduler.enqueue`, observable `SyncStatus`.
- Consumes: Tasks 3 and 6 plus Task 5's `BookStore` and internal `SourceCache`;
  no Compose types.

- [ ] **Step 1: Write state-machine tests**

Cover title priority/natural order/confirmation, ignored files, Add/Ignore,
missing/same-hash rename/Locate/Remove, full cache, offline outbox, reconnect,
source download-only, lock acquisition/loss/stale breaking,
auto-merge/conflicts, invalid remote preservation, backoff, and revoked token.

- [ ] **Step 2: Implement discovery**

```kotlin
data class ChapterProposal(
    val path: String,
    val suggestedTitle: String,
    val suggestedOrder: Int,
)
```

Inspect direct-child ordinary `.md` only. Apply front-matter number/title, H1, filename fallbacks exactly. Never include without confirmation.

- [ ] **Step 3: Implement sync**

Local save only enqueues. Acquire and verify the cooperative book lock, refresh
metadata/content, download source changes, three-way merge review, block
unresolved files, guarded-upload sidecars/manifests, release the owned lock, and
retain last valid cache on parse failure. Never auto-expire a lock;
user-confirmed Break lock forces full refresh and reacquisition before writes.
Persist the exact last-confirmed remote manifest/review documents atomically in
the app-private `SyncBaseStore`; Room holds their hashes/revisions. Missing or
mismatched base content blocks upload instead of degrading to a two-way merge.
Breaking a foreign lock re-reads the observed nonce immediately before delete.

- [ ] **Step 4: Schedule WorkManager**

One unique connected-network chain per book, exponential backoff, triggered by open/reconnect/debounced change/Sync now. Fake-scheduler tests prove UI saves never await background work.

- [ ] **Step 5: Verify and commit**

Run: `./gradlew testDebugUnitTest --tests '*Discovery*' --tests '*Sync*' --tests '*Conflict*'`  
Expected: all discovery/sync matrices pass.

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/{book,sync} app/src/test
git commit -m "feat: synchronize offline reviews without silent overwrite"
```

---

### Task 8: Add source search and reader use cases

**Files:**
- Create: `app/src/main/java/net/inkyquill/pocketeditor/search/SearchEntity.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/search/SearchDao.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/search/SourceSearch.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/reader/ReaderRepository.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/reader/ReaderState.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/database/PocketEditorDatabase.kt`
- Test: corresponding files

**Interfaces:**
- Produces: `SourceSearch.query(bookId, query): Flow<List<SearchHit>>`; `ReaderRepository.observeChapter(bookId, chapterId, reviewEnabled): Flow<ReaderState>`; atomic mutation methods.
- Consumes: Tasks 4, 5, and 7.

- [ ] **Step 1: Write search/repository tests**

Source-only index excludes front matter, delimiters, notes, comments, and edits. Russian results include title/excerpt/raw range and work offline. Review off exposes no review content. Mutations complete after file write and before scheduling sync.

- [ ] **Step 2: Implement Room FTS**

Store display prose and compact rendered-to-raw block offsets. Return `SearchHit(chapterId, title, excerpt, rawStartByte, rawEndByte)`. Add no review search/replacement.

- [ ] **Step 3: Implement reader use cases**

Expose immutable Flow plus note autosave, signal/edit save, delete/undo, re-anchor, navigation, switching, position, and Sync now. Never expose DAOs/files/JSON/gateway to Compose.

- [ ] **Step 4: Verify and commit**

Run: `./gradlew testDebugUnitTest --tests '*Search*' --tests '*ReaderRepository*'`  
Expected: source-only search and local-first timing pass.

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/{search,reader,database} app/src/test
git commit -m "feat: expose offline search and reader state"
```

---

### Task 9: Build the visual system and adaptive shell

**Files:**
- Create: `app/src/main/java/net/inkyquill/pocketeditor/ui/theme/Color.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/ui/theme/Type.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/ui/theme/Theme.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/ui/navigation/PocketEditorRoot.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/AdaptiveReaderScaffold.kt`
- Create: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/AdaptiveReaderTest.kt`

**Interfaces:**
- Produces: navigation and phone/tablet shell.
- Consumes: `ReaderState` through a ViewModel only.

- [ ] **Step 1: Write adaptive semantic tests**

At phone, portrait tablet, landscape tablet, both themes, and font scales 1.0/1.5/2.0 assert approved panels, readable measure, panel-owned controls, previous/next, and Review toggle semantics.

- [ ] **Step 2: Implement tokens**

Warm surfaces; accessible blue/red/yellow/violet signals; green additions; red strike deletions; subdued chrome; bundled book serif; line-height ratio to `sp`. Labels/semantics carry meaning beyond color.

- [ ] **Step 3: Implement adaptive layout**

Phone: full reader + modal Contents/Review bottom sheets. Landscape: independent sidebars. Portrait: menu TOC + right review overlay. Panel controls live in headers. Review is a two-state toggle button, not a settings switch.

- [ ] **Step 4: Verify and commit**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.AdaptiveReaderTest`  
Expected: size/theme/font-scale assertions pass.

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui app/src/androidTest
git commit -m "feat: add the adaptive book reader shell"
```

---

### Task 10: Implement review rendering and editors

**Files:**
- Create: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderDocument.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/SelectionFlyout.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/SignalComposer.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/EditComposer.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/ChapterNote.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/ConflictResolver.kt`
- Create: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt`

**Interfaces:**
- Produces: approved review interactions.
- Consumes: Task 8 ViewModel state/events and Task 9 shell.

- [ ] **Step 1: Write interaction tests**

Test selection→color→comment→Save, color change, empty comment, edit, overlap error, outside tap, system Back, scroll, rotation, process recreation, Cancel, note debounce/focus save, delete+Undo, comment order, overlapping signals, re-anchor, and both conflict choices.

- [ ] **Step 2: Render source-mapped prose**

Render selectable Compose blocks. Map selection through `SelectionMapper`; explain disabled action for syntax-splitting selection. Clean mode is canonical only. Never use WebView.

- [ ] **Step 3: Implement durable drafts**

Persist draft type/text/selection via ViewModel/Room. Dirty composers intercept scrim and system Back; survive scroll/config/process. Save atomically writes the record; Cancel restores saved values or discards new draft.

- [ ] **Step 4: Add notes/deletion/re-anchor/conflicts**

Chapter note has no tools/buttons and shows Saved/Waiting. Delete immediately with Undo. Conflict upload stays blocked until every record has Keep mine/Keep Yandex Disk.

- [ ] **Step 5: Verify and commit**

Run: `./gradlew test connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.ReviewInteractionTest`  
Expected: persistence, accessibility, clean/review, and conflict tests pass.

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui app/src/androidTest
git commit -m "feat: add persistent editorial review tools"
```

---

### Task 11: Complete setup, TOC, search, and appearance

**Files:**
- Create: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/BooksScreen.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/FolderBrowserScreen.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/ImportConfirmationScreen.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/ui/contents/ContentsPanel.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/ui/search/SearchScreen.kt`
- Create: `app/src/main/java/net/inkyquill/pocketeditor/ui/settings/AppearanceScreen.kt`
- Create: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt`

**Interfaces:**
- Produces: complete setup/navigation/settings journey.
- Consumes: Tasks 7–9.

- [ ] **Step 1: Write fake-gateway book-flow test**

Cover sign-in, folder browser, direct root, inclusion/title/order confirmation, full cache, roots, resume, switch, Add/Ignore, missing/rename, forget confirmation, exact search navigation, Light/Dark switch, and −/reset/+ text size.

- [ ] **Step 2: Implement Books/import**

Browse Yandex folders without SAF. Always confirm proposals before manifest creation. Forget local cache only and never remote-delete.

- [ ] **Step 3: Implement Contents/search**

Contents owns ordered TOC, book switcher, and current-book source search. Results show chapter/excerpt and navigate by raw range. No review filters/replace.

- [ ] **Step 4: Implement appearance/resume**

A list-row Light/Dark `Switch`; text-size −/reset/+; launch resumes local book/chapter/scroll, opening Books only without a usable root.

- [ ] **Step 5: Verify and commit**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.BookFlowTest`  
Expected: fake-gateway journey passes on phone/tablet.

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui app/src/androidTest
git commit -m "feat: complete book setup and navigation flows"
```

---

### Task 12: Harden and verify the signed release

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/backup_rules.xml`
- Create: `app/src/main/res/xml/data_extraction_rules.xml`
- Create: `app/proguard-rules.pro`
- Create: `.github/workflows/android.yml`
- Create: `docs/runbooks/yandex-e2e.md`
- Create: `docs/runbooks/release.md`
- Create: `app/src/androidTest/java/net/inkyquill/pocketeditor/PrivacyBoundaryTest.kt`

**Interfaces:**
- Produces: checked release APK/checksum and release-blocking E2E evidence.
- Consumes: complete app.

- [ ] **Step 1: Add privacy tests**

Assert cache/database/credentials excluded from backup; logs omit token/excerpt/query/path; cleartext disabled; no telemetry SDK packaged; recording gateway observes zero canonical uploads.

- [ ] **Step 2: Configure release**

Enable backup exclusions, R8/resource shrinking, network restrictions, and signing from local/CI secrets. Never commit keys, passwords, OAuth secrets, or test credentials.

- [ ] **Step 3: Add CI**

Run `test lint assembleRelease` and emulator tests when available. Protected release job signs and emits `sha256sum app-release.apk > app-release.apk.sha256`.

- [ ] **Step 4: Execute the Yandex runbook**

Record dated evidence for the approved eleven steps: auth/import/cache, offline
review, process-death draft, external source/review changes, a real-Yandex
two-client lock race with exactly one verified owner, reconnect/merge/conflict/
re-anchor, no-source-upload request log, and signed in-place upgrade.

- [ ] **Step 5: Run final gate**

```bash
./gradlew clean test lint connectedDebugAndroidTest assembleRelease
sha256sum app/build/outputs/apk/release/app-release.apk
```

Expected: `BUILD SUCCESSFUL`; all suites pass; APK/checksum exist; every Yandex E2E step has PASS evidence.

- [ ] **Step 6: Commit**

```bash
git add app .github docs/runbooks
git commit -m "build: verify signed Pocket Editor release"
```

---

## Final acceptance gate

Trace each bullet in the approved `MVP Acceptance Criteria` to a passing automated test or dated Yandex E2E step. Confirm `git status --short` is clean, inspect the release dependency inventory for forbidden telemetry, and retain the APK SHA-256 beside the artifact. Missing evidence keeps the MVP incomplete.
