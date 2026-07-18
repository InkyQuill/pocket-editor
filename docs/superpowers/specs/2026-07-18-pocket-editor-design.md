# Pocket Editor: Android Markdown Story Review

Status: approved  
Date: 2026-07-18  
Author: Pavel Obruchnikov <me@inkyquill.net>

## Summary

Pocket Editor is a personal Android application for reading and reviewing
folder-based Markdown stories stored on Yandex Disk. It presents a clean,
book-like reader and a binary review overlay. The overlay contains concrete text
edits, chapter notes, and colored passage signals with optional comments.

Canonical chapter Markdown is always read-only. Pocket Editor writes only a
generated book manifest and one structured JSON review sidecar per chapter. The
files stay beside the book so AI agents can consume and update them without an
application database or proprietary service.

The application is native Kotlin with Jetpack Compose, fully usable offline,
and privately distributed as a signed APK. It has no Pocket Editor backend,
collaboration model, analytics, or Google Play release.

## Product Goals

- Make long Markdown stories comfortable to read on Android phones and tablets.
- Capture editorial changes without rewriting canonical chapter files.
- Attach semantic signals and optional comments to arbitrary prose selections.
- Keep chapter-level scratch notes immediately editable.
- Store all durable review state beside source files in strict, agent-readable
  JSON.
- Work reliably during travel with unstable or absent connectivity.
- Synchronize safely with Yandex Disk without silently overwriting changes made
  by another device or an AI agent.
- Keep the human workflow intentionally small and direct.

## Non-Goals

- Editing or formatting raw Markdown.
- Applying overlay edits to canonical chapters.
- Collaboration, accounts, authorship, threads, replies, mentions, assignments,
  roles, or approval workflows.
- Real-time synchronization.
- Public Google Play distribution.
- A Pocket Editor cloud service or authentication backend.
- Analytics, telemetry, advertising, or third-party crash reporting.
- Full ebook typography customization or paginated page turning.
- Searching or replacing review content.
- Executing raw HTML or supporting developer-document editing workflows.

## Product Invariants

1. Pocket Editor never writes canonical chapter Markdown.
2. A completed UI action saves locally before any network request.
3. Clearing app indexes must not lose durable review data.
4. Invalid or ambiguous anchors never attach silently.
5. Remote changes are never overwritten without a matching known revision or an
   explicit conflict decision.
6. Review off contains no review-derived content.
7. The application remains fully useful offline after a book is cached.
8. File formats contain no hidden workflow or collaboration state.

## Terminology

- **Book root**: a Yandex Disk folder whose direct children include the chapter
  Markdown files for one book.
- **Canonical chapter**: an original `.md` file. It is read-only to Pocket
  Editor.
- **Manifest**: `.pocket-editor.json`, generated after first import and
  authoritative for Pocket Editor TOC order.
- **Review sidecar**: `<chapter>.review.json`, stored beside its chapter.
- **Chapter note**: one autosaved plain-text scratchpad per chapter.
- **Signal**: a semantic colored highlight over selected prose, optionally with
  a comment.
- **Edit**: one non-overlapping `before` to `after` transformation over selected
  prose. It exists only in the review sidecar.
- **Anchor**: redundant source evidence that identifies a selected raw Markdown
  range.
- **Clean mode**: Review off; canonical rendered Markdown only.
- **Review mode**: Review on; the complete editorial overlay.

## Yandex Disk and Book Setup

Pocket Editor integrates directly with the Yandex Disk REST API. It does not
depend on Android Storage Access Framework or a desktop companion.

Authentication uses the official Yandex ID Android SDK. Pocket Editor is
registered as an Android authorization application with its stable application
ID and signing-certificate fingerprint. The OAuth token grants only the Disk
permissions required to list, download, and write files in user-selected book
folders.

### First import

1. The user signs in with Yandex.
2. Pocket Editor presents a Yandex Disk folder browser.
3. The selected folder itself becomes the book root.
4. Pocket Editor enumerates ordinary `*.md` files in that folder.
5. It proposes chapter inclusion, order, and display titles:
   - valid front-matter `number`, then natural filename order;
   - front-matter `title`, then first level-one heading, then filename.
6. The user confirms included files, titles, and order.
7. Pocket Editor writes `.pocket-editor.json` and downloads the complete book.

No pre-existing `_index.md`, manifest, front matter, filename convention, or
surrounding project structure is required.

### Later folder changes

An unlisted `.md` file produces a quiet **New chapter found** notice:

- **Add** confirms title and TOC position before updating the manifest.
- **Ignore** persists the path in the manifest so the notice does not repeat.

A listed path that disappears becomes **Missing**. Cached chapter and review data
remain available. If exactly one new Markdown file has the same content hash,
Pocket Editor offers **Update path**. Otherwise it offers **Locate** and **Remove
from book**. These commands update Pocket Editor metadata only and never delete
a Yandex Disk file.

## Durable File Layout

Example:

```text
chapters/
├── .pocket-editor.json
├── chapter-01.md
├── chapter-01.review.json
├── chapter-02.md
└── chapter-02.review.json
```

For `chapter-01.md`, the only valid sidecar name is
`chapter-01.review.json`. A sidecar is created lazily when the first chapter note,
signal, or edit is saved.

All Pocket Editor JSON uses UTF-8, LF line endings, two-space indentation, a
trailing newline, schema-defined object-key order, lexicographically sorted
record arrays by stable ID, and strict versioned validation. Manifest chapter
array order is semantic and is never sorted. Ignored paths are sorted.

Unknown newer schema versions are opened read-only and are never rewritten.

## Manifest Schema

```json
{
  "schema_version": 1,
  "book_id": "2054f247-0f2e-4d7b-8c67-583526d51540",
  "title": "Алхимик",
  "chapters": [
    {
      "id": "0b4f1cad-c846-4551-a497-a745087f5de2",
      "path": "chapter-01.md",
      "title": "Прибытие и отказ"
    }
  ],
  "ignored_files": [
    "notes.md"
  ]
}
```

Rules:

- `schema_version` is the integer `1`.
- `book_id` and chapter `id` values are UUID strings.
- `path` is a normalized, relative, direct-child filename with no traversal.
- Chapter paths and IDs are unique.
- `chapters` order is authoritative TOC order.
- `ignored_files` and `chapters[].path` cannot contain the same path.
- Unknown properties are rejected.

## Review Sidecar Schema

```json
{
  "schema_version": 1,
  "chapter_id": "0b4f1cad-c846-4551-a497-a745087f5de2",
  "source_path": "chapter-01.md",
  "chapter_note": "Проверить ритм вступления.",
  "signals": [
    {
      "id": "77b2f145-faa5-4de8-8fb2-050dc805978e",
      "type": "warning",
      "selected_text": "Во второй раз приложил скриншоты",
      "anchor": {
        "source_sha256": "...",
        "selection_sha256": "...",
        "start_byte": 620,
        "end_byte": 689,
        "start_line": 17,
        "end_line": 17,
        "prefix": "...добавил тест-кейс. ",
        "suffix": ", потому что кое-кто..."
      },
      "comment": "Не повторяется ли здесь та же шутка?"
    }
  ],
  "edits": [
    {
      "id": "a65eef9e-7318-4777-b2a2-c58a169bfcf6",
      "before": "тикет снова открыли",
      "after": "тикет опять был открыт",
      "anchor": {
        "source_sha256": "...",
        "selection_sha256": "...",
        "start_byte": 1120,
        "end_byte": 1160,
        "start_line": 19,
        "end_line": 19,
        "prefix": "Теперь ",
        "suffix": ".\n\nНа красной метке"
      }
    }
  ]
}
```

Rules:

- `schema_version` is the integer `1`.
- `chapter_id` must match its manifest entry.
- `source_path` must match the source chapter paired with the sidecar.
- `chapter_note` is one plain string and may be empty.
- Record IDs are unique across `signals` and `edits` in one sidecar.
- Signal type is exactly one of `note`, `change_required`, `warning`, or
  `review`.
- `selected_text`, `before`, and `after` contain raw source text, not rendered
  HTML.
- `comment` may be empty; empty comments create no rendered comment block.
- Edit `before` is non-empty because the human edit flow begins with a selection.
- An edit whose `after` equals `before` is invalid.
- Half-open source ranges for edits must not intersect. Adjacent edits are valid.
- Signal ranges may intersect or coincide.
- Unknown properties are rejected.

The schema contains no authors, timestamps, statuses, replies, threads,
assignments, approval state, or history. Anchor resolution is derived and is not
persisted.

## Anchor Model

Every signal and edit stores:

- SHA-256 of the complete source bytes observed at creation;
- SHA-256 of the exact selected bytes;
- zero-based, half-open UTF-8 byte offsets;
- one-based start and end line hints;
- exact raw prefix and suffix context, each limited to 128 Unicode code points;
- exact selected text in `selected_text` or `before`.

Hashes use exact UTF-8 source bytes without Unicode normalization or line-ending
conversion.

### Resolution algorithm

1. If `source_sha256` matches the current chapter, validate that the byte range
   equals the selected text and selection hash. If so, resolve at saved offsets.
2. If the chapter changed, ignore saved offsets and find exact occurrences of
   the selected raw text.
3. Zero occurrences produce `Stale`.
4. One occurrence resolves uniquely.
5. Multiple occurrences are filtered by exact stored prefix and suffix context.
6. Exactly one full-context match resolves.
7. Otherwise the anchor is `Ambiguous`.

No fuzzy or semantic guess is allowed. Stale and ambiguous records remain in the
sidecar and are visible in Review with an explicit re-anchor action.

The UI selects rendered prose, but an operation is permitted only if it maps to
one contiguous raw-source range without splitting a Markdown delimiter, link,
or other syntax node. A whole formatted span is valid. Formatting-only editing
is not supported.

## Review Semantics

### Edits

The reviewer selects arbitrary contiguous prose and presses **Edit**. Pocket
Editor opens a plain-text field prefilled with the selected text. On Save it
stores `before`, `after`, and the anchor.

There is no Insert/Replace/Delete mode picker:

- empty `after` displays as a deletion;
- changed `after` displays a replacement;
- text added before or after the copied selection displays as an insertion;
- a mixed change displays the derived red/green diff.

Edits are concrete overlay edits, not proposals or alternatives. They cannot
overlap. Pocket Editor never applies them to canonical Markdown. After an
external author or agent incorporates an edit, that record is removed from the
sidecar. No applied history is retained.

### Passage signals

Signal semantics are authoritative; colors are theme tokens:

| Type | Light/dark semantic | Meaning |
|---|---|---|
| `note` | blue | Something to keep in mind |
| `change_required` | red | The passage needs changing |
| `warning` | yellow | Something seems strange or puzzling |
| `review` | violet | Recheck on a hunch; the issue is not yet known |

Signals may overlap. Each stays an independent record. A single signal uses its
semantic background. Intersecting signals use a combined indicator without
discarding records. In Review mode, all non-empty comment blocks remain visible.

The selected characters are highlighted. A non-empty comment renders after the
containing Markdown paragraph or block, never between device-dependent wrapped
lines. Multiple comment blocks under one block stack in source-range order.

### Chapter note

Each chapter has one plain-text note. It is an immediate scratchpad with no
formatting controls, authorship, or item list. It autosaves locally after a short
typing debounce and on focus loss. The only status is quiet `Saved` or `Waiting
to sync` UI.

## Application Architecture

Pocket Editor is a native Kotlin Android application using Jetpack Compose.
Logical package boundaries are required; a multi-module Gradle build is optional
and should be introduced only if it reduces build or ownership complexity.

### Compose UI

Owns screens, responsive layouts, theme tokens, accessibility semantics, and
transient interaction state. UI calls use cases and observes state. It never
parses or writes JSON, queries Yandex directly, or mutates SQLite directly.

### Pure Kotlin book core

Owns:

- manifest and review models;
- JSON Schema validation and deterministic serialization;
- Markdown AST and raw-source maps;
- safe rendered-selection mapping;
- anchor creation and resolution;
- before/after diff generation;
- edit-overlap validation;
- signal ordering and projection;
- record-level three-way merge;
- source-text projection for search.

This boundary has no Android UI or network dependencies and receives exhaustive
JVM tests.

### Local book store

Owns the complete app-private offline file cache. Writes use:

```text
serialize
→ validate
→ write temporary sibling
→ fsync as supported
→ atomic replacement
→ enqueue outbox revision
```

Canonical Markdown has no write method in this boundary.

### Disposable Room/SQLite index

Owns:

- book-root registrations;
- Yandex remote paths and revisions;
- last valid merge bases;
- sync outbox and retry state;
- full-text source search index;
- cached anchor-resolution projection;
- ignored discovery state mirrored from manifests;
- device-local last book, chapter, and scroll anchor;
- active unsaved comment/edit drafts.

It is not authoritative for book or saved review content. Rebuilding it from
cached and remote files may discard only device-local preferences and unsaved
drafts; it cannot discard a saved chapter note, signal, or edit.

### Yandex gateway

Owns OAuth token access, folder listing, metadata/revision lookup, download,
conditional upload, and error mapping. It exposes domain results rather than raw
HTTP responses.

### Sync engine

Uses Android background work for refresh, outbox upload, bounded retry, and
connectivity constraints. It never blocks a UI save.

## Primary Data Flows

### Local review mutation

```text
User action
→ validate domain operation
→ update and validate review document
→ atomic local JSON write
→ update UI immediately
→ persist merge base/outbox metadata
→ schedule sync
```

On startup, a recovery scan compares deterministic local file hashes with the
recorded merge bases and outbox. If a process stopped after the atomic JSON
write but before the metadata transaction, the scan reconstructs pending work.
If no trustworthy merge base remains, Pocket Editor downloads the remote file
and requires conflict resolution instead of uploading blindly.

### Chapter rendering

```text
cached Markdown + cached review JSON
→ safe Markdown parse and source map
→ anchor resolution
→ clean or complete review projection
→ Compose reader
```

### External source update

```text
remote revision change
→ download canonical Markdown
→ atomically replace cached source
→ rebuild Markdown/source index
→ re-resolve all review anchors
→ show resolved, Stale, or Ambiguous state
```

## Reader Experience

Pocket Editor opens the last book, chapter, and device-local scroll position.
If none exists or the root is unavailable, it opens Books.

Chapters use continuous vertical scrolling and explicit previous/next chapter
navigation. There is no pagination mode.

The top bar contains Contents, chapter identity, and one binary **Review**
control, presented as a two-state toggle button rather than a list-style
switch:

- **Review off**: canonical rendered Markdown only.
- **Review on**: all edits, signal highlights, passage comments, and access to
  chapter notes.

There are no independent layer filters.

### Responsive layout

- **Phone**: full-width reader; Contents and Review are modal bottom sheets;
  selection opens a compact contextual flyout.
- **Tablet landscape**: centered reading column; independently collapsible left
  Contents and right Review sidebars.
- **Tablet portrait**: Contents is hidden behind a menu; Review is a right-side
  overlay that does not permanently narrow the text column.

Each visible sidebar owns a prominent icon-button in its own header. Collapsed
panels leave discoverable edge controls. Global toolbar controls never own panel
collapse.

### Signal creation and editing

```text
select prose
→ choose one of four semantic colors
→ persistent inline optional-comment editor
→ Save or Cancel
```

The editor retains all four labeled colors, previews changes, and keeps draft
text open through outside taps, system back navigation, scrolling, focus
changes, configuration changes, and process recreation. Save commits type and
comment atomically. Cancel restores the previous saved values or discards a new
record. Empty comments are valid.

### Edit creation and editing

```text
select prose
→ Edit
→ persistent prefilled plain-text editor
→ Save or Cancel
```

It has the same non-dismissible draft behavior. Save validates non-overlap and
commits the before/after edit. Cancel restores or discards.

### Deletion

Saved signals and edits delete immediately and show a brief **Undo** snackbar.
After the undo window, the deletion enters the sync outbox. The three-way merge
base is sufficient to prevent an unchanged stale copy from resurrecting it.

### TOC, books, search, and appearance

Contents contains ordered chapters, current-book source search, and a book
switcher. Books allows adding, selecting, and forgetting roots. Forgetting a
root removes local registration/cache after explicit confirmation but never
deletes Yandex files.

Search indexes canonical prose only, entirely offline. Results contain chapter
title and excerpt and navigate to the exact passage. There are no review filters
or replace actions.

Appearance exposes:

- `Light` and `Dark` themes, presented as a two-state switch;
- decrease, reset, and increase text-size controls respecting Android font
  scaling.

Pocket Editor uses one designed book serif, responsive readable measure, and a
line height held at a fixed ratio to text size so it scales with Android
font-size settings rather than a fixed constant. Dark mode uses warm near-black
surfaces, bright
warm-white prose, subdued chrome, and separately tuned semantic colors. Signal
meaning is also exposed through labels and accessibility semantics, never color
alone.

## Offline Operation and Synchronization

Pocket Editor downloads all chapters, manifest, and review sidecars for every
configured book. Reading, TOC, search, chapter notes, signals, and edits work
offline.

Sync triggers:

- app open;
- connectivity restored;
- a short delay after local changes;
- explicit **Sync now**.

The normal UI shows a compact state such as `Saved`, `Waiting to sync`,
`Syncing`, or `Action required`. Details appear only for actionable failures.

### Review three-way merge

Uploads use the last known remote revision as a condition. When remote changed,
the sync engine compares base, local, and remote documents.

- Different record IDs merge automatically.
- Same record changed on one side only uses the changed version.
- Record deleted on one side and unchanged on the other remains deleted.
- Record deleted on one side and changed on the other is a conflict.
- Same record changed differently on both sides is a conflict.
- Identical changes coalesce.
- `chapter_note` behaves as one reserved singleton record.

Each conflict presents local and Yandex Disk versions with **Keep mine** and
**Keep Yandex Disk**. The file is not uploaded until every conflict is resolved.

### Manifest conflicts

Manifest order is semantic, so concurrent manifest changes are not reordered or
merged heuristically. If both local and remote manifest changed from the same
base, Pocket Editor presents a manifest-level **Keep mine / Keep Yandex Disk**
choice. Chapter content and review files remain accessible during that decision.

### Error behavior

| Condition | Behavior |
|---|---|
| No network | Continue from cache; retain outbox; show `Waiting to sync` |
| Token revoked | Keep cache available; require sign-in only for sync |
| Rate limit/server error | Retain outbox; bounded exponential backoff; manual retry |
| Invalid remote JSON | Keep last valid cache; do not overwrite remote; show exact diagnostic |
| Unknown newer schema | Open affected book/review read-only |
| Overlapping remote edits | Reject sidecar projection; identify conflicting IDs |
| Missing chapter | Preserve cache/review; offer Locate, Update path, Remove from book |
| Stale anchor | Keep record; show re-anchor action |
| Ambiguous anchor | Keep record; show candidate-aware re-anchor action |
| Local disk write failure | Do not update UI as saved or enqueue sync; preserve prior valid file |

## Security and Privacy

Pocket Editor has no application backend. Yandex OAuth tokens live only in
Android Keystore-backed app-private storage, are excluded from backup, are
redacted from diagnostics, and are removed on sign-out.

The manuscript cache is app-private and excluded from Android cloud backup.
Logs contain no OAuth token, manuscript excerpt, search query, or full remote
path. Network access is restricted to Yandex authorization and Disk endpoints.
Raw Markdown HTML is never executed.

The application writes only `.pocket-editor.json` and `*.review.json` under a
selected root. No custom PIN, biometric gate, or extra manuscript encryption is
required for MVP; device security and Yandex security are the protection
boundary.

## Distribution

Pocket Editor is a personal sideloaded APK. Google Play publication is not
planned.

- Application ID: `net.inkyquill.pocketeditor`.
- CI produces a release APK and SHA-256 checksum.
- The release signing key is stable, protected, and absent from the repository.
- Yandex OAuth registers the application ID and signing-certificate fingerprint.
- Upgrade verification installs a new APK over the previous signed release and
  preserves local cache and authentication behavior.

## Verification Strategy

### Pure Kotlin tests

- Both JSON schemas and unknown-version behavior.
- Deterministic serialization and invalid unknown fields.
- UTF-8 byte offsets and Russian prose fixtures.
- Markdown AST/source-map selection boundaries.
- Exact, stale, and ambiguous anchor resolution.
- Before/after diff presentation.
- Edit adjacency and overlap rejection.
- Signal overlap preservation and ordering.
- Review three-way merge, deletion, and conflict cases.
- Manifest discovery, title fallbacks, natural sorting, ignored paths, and
  missing/rename detection.

### Storage and sync integration tests

- Atomic writes and simulated interruption.
- Room/index deletion and complete rebuild.
- Outbox persistence across process death.
- Offline create/edit/delete and later upload.
- Remote revision mismatch and conflict resolution.
- Invalid remote JSON and last-valid-cache preservation.
- Auth revocation, rate limit, server error, and backoff.
- Source refresh without source upload.

### Compose UI and accessibility tests

- Phone, tablet portrait, and tablet landscape.
- Light and dark themes.
- Android font scaling.
- Binary Review mode.
- Sidebar-owned collapse controls.
- Signal flyout and persistent comment draft.
- Color correction, Save, and Cancel.
- Persistent edit draft and non-overlap error.
- Chapter-note autosave state.
- Undo deletion.
- Search navigation and book switching.
- TalkBack semantics for signal types, buttons, sync state, and conflicts.

### Release-blocking Yandex E2E

Using a dedicated Yandex test account and folder:

1. Authenticate and import a folder.
2. Generate the manifest and complete full cache.
3. Disable connectivity.
4. Read, search, create signals and edits, and modify chapter notes.
5. Force process death with an active unsaved draft and restore it.
6. Modify review JSON and canonical Markdown externally.
7. Restore connectivity.
8. Verify upload, merge, conflict UI, source refresh, and re-anchoring.
9. Verify from request logs that no canonical Markdown upload occurred.
10. Install a newly signed release over the old APK and verify retained state.

## MVP Acceptance Criteria

MVP is complete only when:

- a user can authenticate, select multiple roots, and generate stable TOCs;
- every configured book is fully usable offline;
- clean reading, full review overlay, search, and responsive layouts match the
  approved design direction;
- chapter notes, four signal types, optional comments, and non-overlapping edits
  round-trip through JSON sidecars;
- comments and edit drafts survive outside taps, system back navigation,
  rotation, and process death;
- external source and review changes reconcile without silent overwrite;
- stale and ambiguous anchors are visible and never guessed;
- deleting the Room database loses no durable review data;
- Pocket Editor writes no file except the manifest and review sidecars;
- automated test suites and the Yandex E2E pass;
- the signed APK upgrades a prior installation and authenticates successfully.

## Approved Scope Decomposition

Implementation planning should preserve these bounded areas:

1. Domain models, schemas, deterministic files, anchors, and merges.
2. Yandex authentication, gateway, local cache, outbox, and synchronization.
3. Markdown parsing, source maps, diff projection, and offline search.
4. Reader, responsive navigation, review editors, themes, and accessibility.
5. Packaging, signing, release verification, and end-to-end tests.

This is one product design but should become multiple implementation milestones.
No unresolved product or architecture decisions remain in this specification.
