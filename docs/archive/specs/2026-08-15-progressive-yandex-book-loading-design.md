# Progressive Yandex Disk book loading design

## Context

Pocket Editor currently treats first load as an all-or-nothing operation. A
raw Markdown folder is downloaded completely before import confirmation, and a
folder with `.pocket-editor.json` is installed only after every tracked source
and review sidecar has been downloaded. The repository already persists each
successfully downloaded import chapter, but any transient error marks the draft
failed and returns control to the user. The existing progress callback is not
connected to controller or Compose state.

This makes a long book look frozen and turns one timeout, offline transition,
HTTP 429, or server error into a generic load failure. The later synchronization
worker cannot help because it is scheduled only after installation.

The target UX is progressive: build the spine from remote paths, cache the first
three chapters, open the book, and continue the remaining cache fill in the
background. Loading remains durable until it completes or the user explicitly
stops it.

## Goals

- Make a requested book usable after its first three chapters are cached.
- Continue downloading the remaining chapters after navigation and process
  restart without repeating already confirmed work.
- Show compact, observable progress instead of a blocking import screen.
- Retry transport, timeout, rate-limit, server, and temporary availability
  failures indefinitely with bounded backoff.
- Prioritize a chapter immediately when the reader opens it before it is cached.
- Build raw-folder order deterministically from paths and move manual reordering
  into Contents as an anytime book-editing function.
- Preserve exact remote binder order for manifest-backed books.
- Keep all Yandex operations sequential per book; progressive loading must not
  create a request burst.

## Non-goals

- Do not synchronize reading position.
- Do not add editable chapter titles or persist a title cache. Titles continue
  to derive from cached source bytes using `ChapterTitleExtractor`.
- Do not infer or promise a specific Yandex request quota. No captured failure
  establishes that a quota caused the original symptom.
- Do not download several chapters concurrently.
- Do not change chapter source or review files during a read-only installation
  of an existing manifest-backed book.

## Spine construction

Folder selection first performs one paginated listing and persists a load job
before downloading chapter bodies.

For a manifest-backed folder:

1. Download and strictly validate `.pocket-editor.json`.
2. Preserve its exact chapter IDs and order.
3. Resolve the tracked source entries from the folder listing.
4. Treat a missing tracked source, invalid binder, duplicate ID/path, or invalid
   UTF-8 binder as invalid remote data requiring user action.

For a raw Markdown folder:

1. Select ordinary direct-child `.md` files.
2. Sort by normalized relative path, using a locale-independent case-folded
   comparison with the original path as the deterministic tie-breaker.
3. Assign each path one UUID and persist it immediately. Resumption never
   regenerates chapter IDs.
4. Create schema-v2 binder state containing only `{id, path}` entries.
5. Use the filename fallback as the provisional display label until source bytes
   are cached; then use normal frontmatter-title, first-H1, filename precedence.

Raw import no longer asks the user to choose chapters, edit titles, or arrange
order. The generated binder is a normal local mutation and enters the existing
outbox after local installation has a valid durable base.

## Durable load model

Progressive loading uses Room as the source of truth. A book-level row records:

- book ID and normalized remote root;
- load phase;
- total and completed file counts;
- currently active path;
- retry attempt and `retryAt` timestamp;
- paused/cancelled state;
- the last classified error without secrets or signed URLs.

A per-file row records chapter ID, path, spine index, expected remote revision
and size, cache state, and priority. File states are `PENDING`, `DOWNLOADING`,
`CACHED`, or `ACTION_REQUIRED`. A completed file is accepted only when its
atomic cached bytes and persisted SHA-256/revision metadata agree.

The job and its files are created transactionally with the local book/spine.
The existing import-draft schema is migrated into this representation: cached
matching chapters become `CACHED`, incomplete chapters become `PENDING`, and a
ready draft can be promoted without a network request.

Only one progressive-load worker is active per book. Work uses a unique name and
a durable generation so pause, resume, priority changes, and retries cannot
resurrect superseded requests. A validated-network constraint prevents calls on
an unvalidated connection.

## Initial readiness and background continuation

The initial priority set is the first `min(3, chapterCount)` spine entries.
The folder-browser screen remains usable while they load and displays the same
compact progress surface used elsewhere. When all initial-priority chapters are
cached, the controller opens the first chapter automatically. The remaining
files continue through the unique background worker.

The worker downloads one file at a time. After every successful file it:

1. strictly validates UTF-8;
2. atomically writes source bytes and metadata;
3. derives the title and rebuilds the affected search entry;
4. marks the file `CACHED` in Room;
5. publishes path and book change notifications;
6. chooses the next highest-priority pending file.

Closing the screen or process does not change job intent. WorkManager resumes
from Room state. Matching cached chapters are never downloaded again.

## On-demand chapter priority

Contents always shows the complete spine, including uncached chapters. Selecting
an uncached chapter:

- persists that path as the highest pending priority;
- replaces delayed work with immediate validated-network work;
- navigates to the chapter shell;
- shows a skeleton only in the reader body, while reader chrome and Contents
  remain usable;
- opens the rendered chapter as soon as cache publication arrives.

The requested chapter outranks background order. After it is cached, the worker
returns to the earliest remaining spine entry. Repeated taps coalesce into one
priority update and never create parallel downloads.

## Retry and error classification

`Offline`, socket timeout/IO failure, HTTP 429, HTTP 5xx, delayed visibility,
and other explicitly transient transport failures remain active load states.
They never mark the book unavailable.

- Honor a valid `Retry-After` value from both API and transfer hosts.
- Otherwise use capped exponential backoff with jitter.
- Retry attempts have no terminal count.
- Reconnection and an explicit Continue action replace delayed work with an
  immediate request while preserving generation fencing.
- Progress reports the retry category and next retry time, not signed URLs,
  tokens, or raw server bodies.

The transfer-download response path must reuse the normal Yandex HTTP classifier
so transfer-host 429 and 5xx remain `RateLimited` and `ServerFailure` instead of
becoming `InvalidRemote`.

Authorization failure pauses the job and exposes Sign in. Invalid binder/source
data, a tracked file that remains missing after a confirming relist, or a real
durable-base conflict exposes Action required. These are the only terminal UI
states. After the user resolves the condition, Continue resumes from durable
file state.

## Compact progress surface

Progress is a small persistent banner/card hosted by `PocketEditorRoot`, not a
full-screen destination. It can appear over Folder Browser, Books, Contents, or
Reader without taking over navigation.

The primary text is one of:

- `Готовим книгу…`
- `Загружено 7 из 52`
- `Загружаем chapter-008-v2.md`
- `Нет сети · продолжим автоматически`
- `Лимит Яндекс Диска · повтор через 18 с`
- `Нужно войти в Яндекс Диск`
- `Книга доступна без сети`

The surface includes determinate file progress when the total is known. Byte
progress is optional and displayed only when all relevant remote sizes are
known; file count remains canonical. Accessibility semantics announce phase and
count changes without announcing every byte.

Actions are contextual:

- `Приостановить` cancels scheduled work but retains job and cache.
- `Продолжить` resumes immediately when constraints allow.
- `Отменить` stops automatic continuation but keeps the installed partial book
  and cached chapters; the Books screen can resume it later.
- Removing the book remains the separate destructive operation that deletes
  cache and load state.

## Reordering as a book function

Contents gains an explicit `Изменить порядок` action available at any time.
Edit mode displays the current spine with drag handles. The user may reorder
only chapter entries; IDs and paths do not change.

- `Сохранить` validates the complete unique spine and persists one schema-v2
  manifest mutation against the exact current durable base.
- The mutation enters the normal outbox, publishes a book change, and requests
  immediate synchronization.
- `Отмена` discards the local ordering draft.
- A remote base change or real merge conflict uses the existing conflict flow;
  the editor never overwrites an unverified base.
- Reordering does not wait for every chapter to be cached.

The previous add/import-time order and title editor is removed. Adding a book
and editing its order are separate user intentions.

## Concurrency and atomicity

Progressive cache publication participates in the existing per-book shared/
exclusive mutation gate. A single-file cache write uses a shared book lease;
binder creation and reordering use an exclusive lease. The established lock
order remains book lease before review-path mutex.

File bytes, revision/SHA metadata, search state, and load-state transitions are
published atomically enough that a crash produces either a matching cached file
or a pending file to redownload. Cache-change publication is durably journaled
before confirmed revisions can suppress work on retry.

Work cancellation restores invariants non-cancellably. It must not leave a file
permanently `DOWNLOADING`, leak a generation, or block the exclusive book gate.

## Testing

### Unit tests

- Manifest-backed jobs preserve exact binder order and IDs.
- Raw jobs sort by normalized path and keep generated IDs across resume.
- Initial readiness waits for exactly the first three chapters, or all chapters
  when the book is shorter.
- One chapter downloads at a time.
- Cached matching chapters are skipped after restart.
- Selecting an uncached chapter raises it above background entries and coalesces
  repeated requests.
- Timeout, Offline, transfer/API 429 with `Retry-After`, and 5xx schedule
  indefinite retries without terminal failure.
- Unauthorized and invalid remote data expose their exact action states.
- Pause, Continue, Cancel, reconnection, generation fencing, and cancellation
  cleanup preserve durable intent.
- Reorder preserves IDs/paths and creates one manifest outbox mutation against
  the current base.

### Compose and instrumentation tests

- Folder Browser shows compact `0 из N`, advances through `3 из N`, and opens
  Reader without a full-screen import confirmation.
- The banner remains visible but non-blocking in Books and Reader.
- Opening an uncached Contents row shows a body skeleton, reprioritizes it, and
  renders it after publication.
- Pause/Continue/Cancel and Sign in/Action required controls expose correct
  semantics.
- Reorder drag, cancel, save, process recreation, and conflict handling preserve
  the spine.

### Real-folder verification

Use `Яндекс.Диск/writing/aria` as a read-only 52-chapter validation fixture. It
contains `.pocket-editor.json`, so the test must:

1. list and validate the binder;
2. observe the first three cached chapters and automatic Reader opening;
3. observe background progress beyond chapter three;
4. open a later uncached chapter and verify priority promotion;
5. interrupt connectivity/process and verify durable continuation;
6. reach `52 из 52` and confirm offline access.

The verification must not upload, delete, replace, reorder, or otherwise modify
the remote `aria` folder. Reorder is covered against disposable fixtures only.

## Acceptance criteria

- A transient Yandex or network failure never turns an active requested load
  into a generic unavailable-book error.
- A 52-chapter manifest-backed book opens after the first three cached chapters
  and completes in the background.
- Progress remains visible, determinate by file count, and non-blocking.
- Process restart resumes without redownloading confirmed chapters.
- An uncached chapter selected by the user is downloaded next.
- Raw-folder spine order is deterministic by path; reordering is a separate
  anytime Contents action.
- Full unit/lint/instrumentation compilation passes, and connected runtime is
  reported separately from compilation evidence.
