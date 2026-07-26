# Durable import cache, compact library, and validated-network sync design

## Purpose

Make a large first import reliable, preserve its first successful download as
the offline source of truth, reduce the library and confirmation screens to
their essential actions, and prevent Yandex Disk requests when Android does not
report validated internet access.

The design is grounded in the reproduced
`disk:/growth-cheat/result/book01` failure:

- folder discovery found all 18 Markdown chapters;
- proposal downloaded and parsed all chapters successfully;
- confirmation downloaded the same chapters again;
- the second pass failed after about 30–40 seconds and the controller replaced
  the real failure with a generic message;
- no book or durable import draft was left in the library.

## Durable import cache

Selecting **Use this folder** is the only full source-download pass.

The data layer creates a stable import-draft ID before downloading and writes
each validated source file into a private durable draft directory under
`noBackupFilesDir`. It records the remote root, remote revision, SHA-256,
suggested title, chapter order, inclusion state, and edited chapter titles in a
Room import-draft record. Draft files live outside the registered-books root so
startup recovery cannot mistake an incomplete draft for a finished book.

Each downloaded file is written atomically. The draft becomes resumable only
after all ordinary Markdown files selected for proposal have downloaded and
validated as strict UTF-8. A partial failed attempt remains an explicit
retryable draft and reuses every already validated file whose recorded remote
revision still matches; it does not restart from chapter one.

Opening the confirmation screen reads only this durable local snapshot.
Changing titles, order, or inclusion updates draft metadata, never source
files. Confirming the book:

1. validates the cached snapshot and selected chapter metadata;
2. creates the final manifest with the draft's stable book ID;
3. atomically promotes the draft directory into the registered book cache;
4. registers Room metadata, search index, and manifest outbox state;
5. opens the first selected chapter.

Confirmation performs no chapter download. The manifest is the only new remote
write pending after confirmation. Canonical Markdown remains read-only.

Backing out or restarting the app keeps the draft and its cache. The library
shows it as **Настроить книгу**. Files are removed only after the user confirms
the destructive **Удалить черновик и локальные файлы** action. Selecting the
same remote folder resumes its existing draft instead of downloading a second
copy.

If a registered book already exists for the remote folder, existing-book
handling remains authoritative and no draft is created.

## Import errors and progress

Folder import exposes phase and count without leaking paths, tokens, or source
content:

- `Сохраняем главы · 7 из 18`;
- `Проверяем текст · 18 из 18`;
- `Сохранено на устройстве`.

Known Yandex failures map to bounded Russian messages: offline, authorization
required, resource missing, rate limited, and server unavailable. Unexpected
failures retain a safe generic user message and emit a redacted diagnostic with
exception type and import phase. HTTP logs never include authorization,
download URLs, remote paths, filenames, or bodies.

## Compact library

The library is a working screen, not a landing page:

- a compact top app bar is titled **Библиотека**;
- **Добавить книгу** is the primary top-level action;
- appearance and Yandex account actions use compact icon/overflow actions;
- the large product name and tagline do not repeat above every visit.

Each finished-book card is one clear click target. It contains:

- title;
- chapter count;
- offline availability and relink/recovery status;
- a trailing open affordance;
- an overflow menu for destructive or secondary actions.

**Забыть локальную копию** moves out of the card body into the overflow menu and
keeps its confirmation dialog.

Each import-draft card contains:

- proposed title or remote folder name;
- cached chapter count and any retryable error;
- **Настроить книгу** as the primary action;
- **Удалить черновик и локальные файлы** only in its overflow menu.

The empty state is short, centered, and contains one enabled action when signed
in. When signed out, cached finished books and import drafts remain visible;
only remote actions are disabled.

## Compact confirmation

The current large fields and vertically stacked reorder buttons are replaced
with:

- a compact top app bar titled **Проверьте книгу**;
- a status line such as **18 глав сохранены на устройстве**;
- one single-line book-title field;
- a dense `LazyColumn` of chapter rows;
- a compact sticky footer.

Each chapter row has a 48 dp minimum height and shows:

- inclusion checkbox;
- order number;
- editable title as the primary line;
- filename as a smaller secondary line;
- compact move-up/move-down actions at the trailing edge.

The footer shows **Выбрано 18 из 18** and one concise
**Добавить в библиотеку** button. During atomic promotion, controls are disabled
and the button reports **Добавляем…**. The former statement that nothing exists
before confirmation is removed because the offline draft cache already exists.

Back returns to the library and keeps the draft. Deletion is never implied by
Back.

## Validated-network sync

WorkManager retains its `NetworkType.CONNECTED` constraint as the first gate.
Immediately before invoking `SyncEngine`, the worker checks the active network
through an injected connectivity interface. A network is usable only when its
capabilities contain both:

- `NET_CAPABILITY_INTERNET`;
- `NET_CAPABILITY_VALIDATED`.

When the check fails, the worker performs no Yandex operation and returns a
retryable waiting outcome. Existing exponential backoff prevents polling. A
later validated connection, manual sync, local change, or book open can resume
normal scheduling. Tests use a fake connectivity interface and do not depend on
host networking.

The reader continues to describe pending local work as waiting to sync; it must
not briefly claim active synchronization before the validated-network gate
passes.

## Data integrity and recovery

- Draft cache files use atomic replace and strict UTF-8 validation.
- Room schema migration preserves every registered book, review draft, reading
  position, sync record, and search entry.
- Draft promotion uses the existing first-install journal semantics or an
  equivalent journaled atomic move.
- Startup recovery recognizes durable import drafts separately from registered
  books and never deletes them as orphans.
- Explicit draft deletion validates that the target is a direct child of the
  dedicated import-draft root before recursive removal.
- A crash during promotion resolves to exactly one recoverable state: resumable
  draft or complete registered book, never both and never neither.

## Verification

Automated tests prove:

- proposal downloads each remote chapter at most once;
- confirmation performs zero chapter downloads;
- repeated selection of the same folder resumes the durable draft;
- process recreation preserves cached files and edited confirmation metadata;
- retry reuses validated chapters and downloads only missing or changed files;
- Back keeps the draft; only explicit discard deletes its files and row;
- promotion is atomic across injected filesystem and database failures;
- migration from the current schema preserves existing data;
- sync does not call its runner without validated internet capability;
- compact library and confirmation layouts remain usable at phone, tablet, and
  supported large-font sizes;
- destructive actions remain in overflow menus with confirmation;
- accessibility labels and 48 dp touch targets cover every new action.

The real emulator acceptance pass imports
`disk:/growth-cheat/result/book01`, opens multiple chapters offline, restarts
the app, confirms the cache survives, disables connectivity, and verifies that
no Yandex request starts.
