# Task 5 implementation report

Status: DONE_WITH_CONCERNS

Base: `9141735`

Head: `dd5714f`

## Outcome

- Added an anytime `Изменить порядок` mode to Contents. It owns a saveable complete-spine ID draft, supports accessible up/down actions plus long-press drag, disables chapter navigation while editing, and has explicit Save/Cancel behavior.
- Kept chapter IDs and paths immutable. Cached and uncached rows participate in the same draft; reorder never downloads a pending body.
- Added `BookLibraryData.reorder` and controller forwarding. Refreshes retain the destination that is current when the operation completes, so a delayed reorder cannot restore stale Reader navigation.
- Implemented reorder under the composition-root `ReviewMutationCoordinator.withBookExclusive` gate. Progressive shared publication and reorder exclude one another in both directions.
- Base verification accepts either an exact existing outbox/local pair or an exact merge-base/durable-base/observed-remote triple. Drift creates `SyncConflict.MissingBase`, leaves the manifest untouched, and returns the specified user-facing error.
- Manifest replacement uses the established staged full-book `repairSwap` journal. Its Room transaction publishes the one manifest outbox row, cached-only search index, and complete progressive spine indices/priorities together.
- Reordered pending rows receive priority from their new position, while explicit on-demand priority survives. Active path/claim identity are path-based and remain intact.
- Contents now surfaces the controller error in a compact dismissible error card; a base-conflict save therefore remains visible in Reader/Contents instead of disappearing behind an unchanged destination.
- Added component recreation coverage for draft Cancel and durable Save, including an uncached chapter.

## RED evidence

Command:

```text
./gradlew testDebugUnitTest \
  --tests net.inkyquill.pocketeditor.ui.contents.ContentsReorderStateTest \
  --tests 'net.inkyquill.pocketeditor.ui.books.BookLibraryControllerTest.reorder refreshes books while preserving current reader destination'
```

Observed RED: Kotlin compilation failed on missing `ContentsReorderState`, `BookLibraryData.reorder`, and `BookLibraryController.reorder`.

The repository and Compose acceptance tests are instrumentation tests. With no connected device, their runtime RED/GREEN transition could not be executed; only source compilation is claimed.

During verification, the first recreation-test attempt also exposed the Compose 1.11 v2 rule API mismatch (`StateRestorationTester` now consumes `ComposeUiTest`). The test was adapted to the rule's supported `cancelAndRecreateRecomposer` lifecycle seam and compilation then passed.

## GREEN evidence

Focused JVM state/controller suite:

```text
./gradlew testDebugUnitTest \
  --tests net.inkyquill.pocketeditor.ui.contents.ContentsReorderStateTest \
  --tests net.inkyquill.pocketeditor.ui.books.BookLibraryControllerTest
```

Result: BUILD SUCCESSFUL. The controller suite includes 24 passing tests after the stale-navigation regression was added.

Final required gate at `dd5714f` product/test contents (run immediately before the final commit, whose diff contains only the already-compiled Contents error-card integration and its instrumentation test):

```text
./gradlew testDebugUnitTest lintDebug compileDebugAndroidTestKotlin
```

Result: BUILD SUCCESSFUL. JVM runtime tests and lint passed; all instrumentation sources compiled.

Connected target check:

```text
adb devices -l
```

Result: empty device list. Connected instrumentation runtime is **NOT RUN**, not PASS.

## Instrumentation coverage added

- Semantic up/down, Cancel, Save, mixed cached/uncached identity preservation, and long-press drag.
- Verified manifest reorder, one outbox row, cached-only search, zero downloads, progressive index order, and one local-change schedule.
- Missing/duplicate/foreign exact-set rejection before filesystem mutation.
- Remote-base drift to `MissingBase` with unchanged durable order.
- Shared publication blocking reorder and exclusive reorder blocking shared publication.
- Recomposer recreation of an in-progress draft, Cancel without durable mutation, Save persistence, and visible conflict error with prior durable order.

## Commits

- `ea52d40 feat: edit chapter order from contents`
- `539a36c feat: publish verified chapter reorders`
- `1b0f7f3 test: cover contents drag reorder`
- `b70d356 fix: preserve newer navigation after reorder`
- `a2b886a test: preserve reorder state across recreation`
- `dd5714f fix: surface reorder conflicts in contents`

## Self-review notes

- The manifest write is not a naked `AtomicBookStore.writeManifest`: it uses the existing recoverable staged swap and commit marker, so filesystem/Room publication has the same crash protocol as chapter replacement.
- The exact-set check occurs before staging and is repeated for durable load rows inside the Room transaction.
- A raw binder with an initial `baseSha256 = null` is accepted only while merge base, durable base, and observed manifest remote revision are all absent. Once any remote base exists it must be verified exactly.
- No import/add confirmation behavior was removed here; that remains Task 6.
- No real Yandex folder was read or mutated by Task 5.

## Concerns / runtime gaps

- No Android device is connected. Room transaction/journal assertions, mutual-exclusion timing, Compose semantics/drag, and recomposer recreation are compiled but **NOT RUN**.
- `cancelAndRecreateRecomposer` exercises Compose saveable restoration at the rule-supported composition lifecycle boundary. Full Activity/process-death restoration remains part of connected Task 6 verification.

## Review remediation round 1

Commit: `8811fcc fix: make chapter reorder recovery actionable`

### Finding mapping

1. Removed the permanently invisible `MissingBase` blocker from reorder preflight. The controller retains the exact pending ID order and Contents exposes `Обновить основу и повторить`. Recovery downloads only the remote manifest under the exclusive book gate. An unchanged remote manifest rebuilds the exact durable/Room base and automatically retries; a changed remote manifest publishes a resolvable `SyncConflict.Manifest` for the normal conflict UI. Unauthorized recovery keeps explicit sign-in guidance and the retry action.
2. A non-null manifest base now requires exactly one observed manifest `RemoteRevisionEntity` whose revision and SHA match both merge-base and durable base. Missing or mismatched observation fails before staging or any Room/outbox mutation. The base-null raw-binder branch is unchanged.
3. The drag instrumentation test derives its movement from actual row bounds and asserts the exact persisted callback order `one, three, two`. The pure state test already asserts the same exact transition.
4. Added reorder-specific journal recovery tests at `FILESYSTEM_SWAPPED` before Room commit and `DATABASE_COMMITTED` after Room commit. They assert manifest/spine/outbox/search consistency, zero downloads, cleanup, and the correct rollback/keep side of the commit marker.
5. Moved move-up, move-down, drag, offline, refresh, and dismiss accessibility/action copy to Android string resources while preserving exact tested semantics.

### Round-1 RED / regression evidence

- The prior controller test had no recovery API/state and could not express fail → refresh → automatic retry.
- The prior remote-triple implementation accepted a missing observed manifest revision.
- The prior drag test asserted only the ID set, allowing an unchanged order to pass.
- The prior reorder suite had no crash boundary after the filesystem swap or after the Room commit marker.
- The first focused compile caught a duplicate pre-existing `available_offline` resource name; the new callsite now reuses the single canonical resource.

### Round-1 GREEN evidence

```text
./gradlew testDebugUnitTest --tests net.inkyquill.pocketeditor.ui.books.BookLibraryControllerTest \
  compileDebugAndroidTestKotlin
```

Result: BUILD SUCCESSFUL, including successful recovery/retry and unauthorized guidance JVM regressions plus compilation of exact-triple, exact-drag, and both crash-recovery instrumentation tests.

```text
./gradlew testDebugUnitTest lintDebug compileDebugAndroidTestKotlin
```

Result at the exact `8811fcc` contents: BUILD SUCCESSFUL.

Connected instrumentation remains **NOT RUN** because `adb devices -l` reports no attached target.

## Review remediation round 2

Commit: `6b63f23 fix: converge chapter reorder recovery`

### Finding mapping

1. Base recovery now keeps remote and local byte identities distinct. The durable base, merge base, and observed revision store the exact downloaded remote bytes/SHA/revision. The current local manifest SHA comes from the actual local file and is reconciled into a PENDING outbox whenever a semantically identical remote manifest differs only in whitespace/key order. Automatic retry then writes the reordered canonical manifest with `localSha256` for those new local bytes and `baseSha256` for the verified non-canonical remote bytes. Existing null/stale-base outbox state is replaced coherently, so the refresh/retry converges once instead of looping.
2. Reorder recovery now has a monotonic generation and same-generation single-flight guarded by a mutex/`CompletableDeferred`. Parallel Retry taps share one refresh and one reorder. A newer order supersedes a suspended recovery; stale work checks ownership after each gateway suspension and before conflict/base publication, removes a just-published stale conflict by identity, and cannot clear the newer order/error. Contents disables the recovery action and announces `Обновляем основу…` while the single-flight is active.

### Round-2 regression evidence

- The repository instrumentation fixture serves a decoded-identical manifest with different key order and compact whitespace, preloads an obsolete manifest outbox base, then asserts one refresh download, one reorder, exact remote durable/base SHA, exact reordered local SHA, and no retry loop.
- Controller JVM tests suspend base refresh and launch two Retry taps. They assert one refresh call, one reorder call, loading-state cleanup, and no error. A separate newer-order test supersedes the suspended generation and proves only the newer exact ID order is published.

### Round-2 GREEN evidence

```text
./gradlew testDebugUnitTest --tests net.inkyquill.pocketeditor.ui.books.BookLibraryControllerTest
```

Result: BUILD SUCCESSFUL, including parallel-tap coalescing and newer-generation fencing.

```text
./gradlew testDebugUnitTest lintDebug compileDebugAndroidTestKotlin
```

Result at the exact `6b63f23` contents: BUILD SUCCESSFUL.

Connected instrumentation remains **NOT RUN** because no Android target is attached.

## Review remediation round 3

Commit: `f0b69cb fix: serialize reorder base publication`

### Finding mapping

1. Reorder generation replacement and the complete refresh/conflict/Room/reorder publication now share one controller operation mutex. A newer Save cannot change ownership while an older recovery is inside conflict replacement or the metadata transaction; it queues and runs afterward. Same-generation Retry taps still coalesce through the existing single-flight completion. The generation callback remains a defensive repository boundary, but correctness no longer depends on check-then-act timing.
2. Refresh now accepts a downloaded sync base only when `directorySyncStatus == SYNCED`. An unsupported directory sync triggers a durable restoration of the prior base before any merge base, observed revision, or outbox transaction. A failed durable restoration is also exposed as an actionable retry error instead of publishing authoritative metadata.

### Round-3 regression evidence

- The controller JVM regression suspends recovery publication, submits a newer exact order, proves the newer Save remains queued, then asserts the older order publishes first and the newer order owns the final state.
- Repository/controller instrumentation regressions block at `BEFORE_CONFLICT_REPLACE` and `BEFORE_METADATA_COMMIT`. They prove a concurrent newer Save cannot interleave, cannot be cleared by older work, and either remains recoverable after the serialized conflict or owns the final manifest/outbox after serialized metadata publication.
- The durability instrumentation regression scripts one `UNSUPPORTED` base write followed by a durable restoration. It asserts the prior base bytes/SHA/revision, merge base, observed revisions, outbox, and manifest are unchanged; recovery stays actionable and no automatic reorder is published.

### Round-3 GREEN evidence

```text
./gradlew testDebugUnitTest --tests 'net.inkyquill.pocketeditor.ui.books.BookLibraryControllerTest.*reorder*'
```

Result: BUILD SUCCESSFUL, including queued-newer-order and parallel Retry single-flight JVM regressions.

```text
./gradlew testDebugUnitTest lintDebug compileDebugAndroidTestKotlin
```

Result at `f0b69cb` product/test contents: BUILD SUCCESSFUL. JVM runtime tests and lint passed; all new Room/controller concurrency and durability instrumentation sources compiled.

Connected instrumentation remains **NOT RUN** because no Android target is attached.
