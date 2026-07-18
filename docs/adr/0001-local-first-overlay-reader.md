# ADR 0001: Local-First Overlay Reader for Markdown Books

## Status

Accepted. The compiled specification was approved by the user on 2026-07-18.
Implementation planning may proceed; implementation has not begun.

## Date

2026-07-18

## Context

Books already exist as ordinary folders of Markdown chapter files on cloud
storage. A representative folder is
`/home/inky/Yandex.Disk/writing/alchemist/chapters`, which currently contains an
`_index.md` chapter registry and numbered chapter files with YAML front matter.
Those features are examples, not requirements: other books may have no
surrounding project structure, registry, or consistent front matter.

The desired Android application must make such a project comfortable to read as
a book while supporting editorial work:

- a clean, distraction-free reading surface;
- a table of contents;
- multiple independently configured book roots;
- comments attached to a chapter or a precise passage;
- concrete edits shown as an overlay over the original Markdown;
- controls for hiding and showing editorial material;
- editing tools in a neat collapsible sidebar or equivalent compact panel;
- durable review artifacts stored beside the book and understandable to both
  people and AI agents.

This is not a request for a conventional Markdown editor. The original chapter
text must remain the stable source while comments and concrete overlay edits
form a separate review layer.

Pocket Editor is primarily a personal, human review tool. Its interaction model
must stay simple and direct. Robustness belongs in validation, anchoring, and
source safety; it must not create visible workflow machinery without a concrete
human need.

It is a single-user product for the book owner reviewing stories. Review JSON
does not model record authors, identities, replies, threads, mentions,
assignments, roles, or approvals. Agent-written records use the same flat model
as records created in the app.

## Decision

### Product boundary

Build a local-first Android reader and editorial overlay client for folder-based
Markdown books stored on Yandex Disk. The first version integrates directly
with the Yandex Disk API: it lists, downloads, uploads, caches, and reconciles
book files itself rather than relying on Yandex Disk to appear through Android's
Storage Access Framework. The application renders a book-oriented view and
writes review artifacts. It does not require a hosted Pocket Editor backend as
part of the product definition.

The application is a separate product from Hieronymus. Future integration may
let Hieronymus or other agents consume its review artifacts, but Pocket Editor
must not depend on a Hieronymus runtime or database to read and annotate a book.

The Android client authenticates through the official Yandex ID SDK and uses
the resulting OAuth token for Disk REST API requests. It requests only required
Disk permissions, keeps the token in Android-protected app storage, and handles
sign-out, expiry, and revocation explicitly. Users are never asked to paste a
personal OAuth token into a project file.

### Source authority

Original Markdown chapter files are authoritative and remain byte-for-byte
untouched by every Pocket Editor workflow. An "edit" in Pocket Editor is a
replacement, insertion, or deletion rendered on top of the source. Resolving or
re-anchoring an edit changes only the review sidecar. Pocket Editor never
applies overlay edits or writes any other change to canonical chapter Markdown.

An author or AI agent may incorporate an edit through an external writing
workflow and then remove its record from the sidecar. The sidecar describes only
the current overlay; Pocket Editor does not retain applied edit history or
automatically infer completion. The overlay can be hidden without changing the
rendered source text. Comments and edits can be reviewed independently.

### Portable project data

Book review data must be stored within or immediately beside the selected book
root, not only in an app-private database. The durable form must be:

- plain text and diff-friendly;
- deterministic enough for version control and cloud synchronization;
- readable without Pocket Editor;
- easy for an AI agent to enumerate and interpret;
- linked using more than fragile absolute line numbers, so normal source edits
  can be detected and reconciled rather than silently moving an annotation.

An app-private cache or index is allowed for performance, but it is disposable
and never authoritative.

Review data uses one structured sidecar beside each source chapter. The sidecar
does not duplicate the complete chapter, and free-form Markdown or a unified
diff is not the review data model. It must represent typed insertions,
replacements, deletions, passage-level comments, and chapter-level notes.
Active edit ranges must not overlap. The app rejects overlapping edits created
interactively and reports invalid overlap precisely when an agent writes it.

Each source chapter has at most one authoritative sidecar named
`<chapter>.review.json`, for example `chapter-01.review.json`. Pocket Editor and
AI agents create and consume the file; direct human editing is not a design
constraint. A versioned JSON Schema defines and validates the document. Files
use UTF-8, deterministic formatting, and a trailing newline so agent changes
and synchronized revisions remain inspectable.

Passage-level items use redundant, content-addressed anchors. Each anchor stores
the source chapter hash observed at creation, the exact selected raw source text
and its hash, bounded prefix and suffix context, and source-position hints.
Insertion anchors capture context on both sides of the insertion point.

Anchor resolution is deterministic. Saved positions are authoritative only
while the chapter hash matches. After a source change, exact text and context
may reattach an item only when they identify one defensible range. Zero matches
produce a `stale` anchor; multiple plausible matches produce an `ambiguous`
anchor. Neither state may be silently attached or applied.

Chapter notes have no passage anchor. Passage comments target an anchored range
without changing text. An edit stores one anchored `before` selection and its
edited `after` text. Pocket Editor derives insertion, replacement, and deletion
segments for display rather than exposing or storing separate user-selected
operation modes. The remaining schema fields and coordinate conventions will be
specified during discovery.

Edits may target any contiguous raw source-text range within one chapter.
The edit surface is a plain-text field prefilled with the selected prose. Saving
compares the resulting text with the anchored selection. Clearing it produces a
deletion; changing it produces a replacement; adding text before or after the
copied selection produces insertion segments. Complete lines and paragraphs
require no separate operation types.

The user selects rendered prose rather than raw Markdown. Pocket Editor permits
an edit only when that selection maps to one contiguous raw-source range without
splitting a Markdown structure such as an emphasis delimiter or link. A complete
formatted span may be selected. Formatting-only edits and raw Markdown editing
remain outside Pocket Editor.

### Book discovery and table of contents

Users can register multiple book roots and switch between them. A book root is
the Yandex Disk folder that directly contains its Markdown chapter texts.
Pocket Editor must not require or inspect a surrounding project layout, and it
must work without `_index.md` or a Pocket Editor manifest.

Book discovery enumerates supported chapter files within that selected folder.
It must distinguish source chapters from Pocket Editor review sidecars or other
non-chapter Markdown. On first import it proposes candidates, ordering, and
titles from available front matter, headings, and natural filename order, then
lets the user confirm inclusion, order, and display titles. It persists the
confirmed TOC and book identity in a generated root-level
`.pocket-editor.json`. No manifest is required before first import; the generated
manifest becomes authoritative for later Pocket Editor loads.

On launch, Pocket Editor resumes the last opened book, chapter, and scroll
position. A clear book switcher in the Contents/menu surface opens a small Books
screen for selecting, adding, or removing configured roots. Books is the initial
screen only when no root is configured or the previous root is unavailable.

Reading position is app-private, device-local state. Each device frequently
persists and restores its own current book, chapter, and scroll anchor. Pocket
Editor does not synchronize reading position or write it into the book manifest;
review data and TOC metadata remain synchronized normally.

After import, unlisted ordinary Markdown files are never added silently. Pocket
Editor shows a quiet `New chapter found` notice. **Add** confirms the derived
title and TOC position before updating the manifest; **Ignore** records the path
so it is not repeatedly offered.

If a listed chapter path disappears, its TOC entry becomes `Missing` and Pocket
Editor retains the cached chapter and review sidecar. A unique new file with the
same content hash may be offered as a likely rename through **Update path**;
otherwise the user can **Locate** or **Remove from book**. These actions update
Pocket Editor metadata only. Pocket Editor never deletes a Yandex Disk chapter
or cached review data as an inferred consequence of a missing path.

### Interaction model

The default chapter view prioritizes reading. Editorial chrome is hidden or
collapsed until requested. The reader supports at least these conceptual modes:

1. **Clean**: rendered canonical Markdown with editorial marks hidden.
2. **Review**: the complete editorial overlay is visible in context.
3. **Focus**: a selected comment or edit opens its details and editing
   controls in a collapsible side panel, bottom sheet, or device-appropriate
   equivalent.

The top-level Review control is binary. Review on shows all edits, passage
highlights, inline comments, and chapter notes. Review off shows only clean
canonical chapter text. There are no per-layer visibility filters.

The precise phone/tablet navigation and panel behavior will be designed after
the content and editing workflows are settled. The accepted responsive direction
is reader-first and adaptive:

- phones use a full-width reader, contextual selection controls, and modal
  bottom sheets for TOC and review surfaces;
- tablet landscape uses a centered reading column with independently
  collapsible left Contents and right Review sidebars;
- tablet portrait keeps Contents hidden behind a menu and opens Review as a
  right-side overlay without permanently narrowing the reading column.

Each visible tablet sidebar owns its collapse control. A clear icon button lives
in the sidebar header; sidebar controls do not float in the global top bar, and
subtle standalone chevrons are insufficient. When collapsed, each sidebar leaves
a discoverable edge control for reopening it.

Chapters use continuous vertical scrolling on phones and tablets. Pocket Editor
loads one chapter at a time, remembers the reading position, and provides TOC
plus explicit previous/next chapter navigation. It does not provide paginated
page-turning mode.

Light and dark themes are both required. Dark theme is a primary usage mode,
not a later color inversion: reading surfaces, chrome, selection backgrounds,
diff colors, and all four passage-signal colors require accessible dark-theme
tokens designed for long reading sessions.

Reader appearance exposes only theme (`Light`, `Dark`, presented as a two-state
switch) and simple text size decrease/reset/increase controls that respect
Android accessibility font scaling. Pocket Editor uses one designed book serif,
a line height fixed as a ratio to text size so it scales with font-size
settings rather than a fixed constant, and responsive content measure. It does
not initially expose font family, margins, justification, or paragraph spacing
settings.

### Markdown rendering

Pocket Editor parses YAML front matter for optional metadata and excludes it
from the reading body. The reading surface safely renders ordinary CommonMark
prose structures: headings, paragraphs, emphasis and strong emphasis,
blockquotes, lists, thematic breaks, and links. It never executes or injects raw
HTML. Specialized documentation extensions such as tables and fenced code are
not initial review requirements.

The representative Alchemist chapters currently require only front matter, one
chapter heading, paragraphs, and inline emphasis. Review selection remains
restricted to rendered prose ranges that map cleanly to contiguous raw source.

### Search

The TOC surface includes one local full-text search field for canonical source
prose in the current book. Search operates entirely on the offline cache.
Results show chapter title and a short matching excerpt and open the exact
passage. Pocket Editor does not initially search review JSON, expose filters, or
provide replacement.

### Offline operation

Pocket Editor caches every chapter, the generated root manifest, and every
review sidecar for each configured book. Reading, navigation, chapter notes,
passage signals, and edits work without a network connection. Every change is
saved durably to the local cache first and queued for later synchronization;
the UI never blocks a review save on Yandex Disk availability.

When connectivity returns, Pocket Editor refreshes and synchronizes in the
background with visible compact status and retry affordances. Offline support is
a core product requirement because travel with unstable or absent connectivity
is an expected primary environment, not an edge case.

Review sidecars merge by stable record ID during synchronization. Changes to
different records merge automatically. If local and remote versions both
changed the same record from their shared base, Pocket Editor presents that
record's two versions and requires **Keep mine** or **Keep Yandex Disk** before
uploading. It never guesses or silently overwrites the remote change.

Canonical Markdown is download-only. When it changes remotely, Pocket Editor
refreshes the cached chapter and re-resolves existing anchors. Stale and
ambiguous anchors follow the already defined visible failure states.

Synchronization runs on app open, restored connectivity, a short delay after
local changes, and explicit **Sync now**. Uploads are conditional on the last
known remote revision. Independent record changes three-way merge; a same-record
conflict blocks that file's upload until **Keep mine** or **Keep Yandex Disk** is
chosen.

Network absence leaves all cached workflows available and marks pending work
`Waiting to sync`. Revoked authentication preserves the cache and requests
sign-in for sync only. Rate limits and server errors preserve the outbox and use
bounded backoff. Invalid remote JSON or overlapping edits never replace the last
valid cache or get overwritten automatically; diagnostics identify the file,
record IDs, and validation issue. Unknown newer schema versions are read-only.
Normal UI shows only compact sync state; detailed error surfaces appear only for
actionable failures.

Review content has three distinct surfaces:

1. **Edits** render inline when their overlay is enabled. Deletions are
   red and struck through; additions are green; replacements show both.
2. **Chapter notes** use a separate drawer with an immediate plain-text editor
   and no formatting instruments.
3. **Passage signals** anchor to selected original text, highlight its character
   background, and may insert a comment block below the relevant passage. The
   optional-comment signal types are `note` (blue, keep in mind),
   `change_required` (red, needs changing), `warning` (yellow, strange or
   puzzling), and `review` (violet, recheck on a hunch).

The stored semantic type, not its color, is authoritative so accessible themes
can vary presentation without losing meaning. Edits and red
`change_required` signals remain separate concepts: the former contains a
concrete text operation; the latter may only indicate editorial intent.

Chapter notes autosave durably to the local cache after a short typing debounce
and when focus leaves the field. The surface has no Save or Cancel buttons and
shows only quiet `Saved` or `Waiting to sync` state.

Passage signals may overlap. Each remains an independent record in the review
sidecar. A single signal uses its semantic background color; overlapping signals
use a combined indicator that reveals every associated signal and optional
comment. Pocket Editor never merges or discards signal records merely to
simplify rendering.

When Review is on, a non-empty passage comment renders immediately after the
containing Markdown paragraph or block, never between device-dependent wrapped
lines. Multiple comment blocks under one source block stack in source-range
order. A signal with no comment renders only its highlight and creates no empty
comment block.

Creating a passage signal is a direct five-step interaction: select rendered
text, choose one of the four semantic colors from a contextual flyout, optionally
write a comment, then press Save or Cancel. Choosing a color opens the comment
composer. Once that field is active, outside taps and system back navigation
never dismiss it or discard its draft; cancellation is explicit. The committed
comment renders beneath its anchored passage.

The comment composer retains all four semantic color controls and clearly marks
the current choice, allowing the signal type to be corrected without restarting
the interaction. Save commits the selected type and comment together. When an
existing signal is edited, Cancel restores both its previously saved type and
comment.

Saved passage signals and edits delete immediately without a confirmation
dialog. Pocket Editor shows a brief Undo snackbar and keeps the removal locally
recoverable until that window closes. Once committed, synchronization retains a
deletion marker sufficient to prevent an older remote record from reappearing.

### System decomposition

The complete specification will treat the product as four bounded subsystems:

1. **Storage access**: authenticate with Yandex, access one or more Yandex Disk
   book folders through its API, and handle caching, offline work, and
   conflicts.
2. **Book model**: discover chapter texts directly within a configured folder,
   derive table-of-contents order, and support an agreed Markdown subset without
   depending on surrounding project files.
3. **Review overlay**: create, anchor, render, update, resolve, and export
   comments and proposed edits without mutating source chapters.
4. **Reader experience**: render chapters, preserve reading position, navigate
   the TOC, and reveal editorial controls progressively.

Each subsystem requires its own detailed decisions, but they belong to one
product specification because their boundaries and data contracts must agree.

## Architectural Invariants

- A review action must never silently rewrite canonical chapter Markdown.
- Pocket Editor must never write canonical chapter Markdown, even through an
  explicit accept or apply action.
- Losing the app or clearing its data must not lose synced review artifacts.
- An agent must be able to understand review state by reading project files.
- A stale anchor must be surfaced as stale or ambiguous; it must never attach
  silently to unrelated prose.
- Cloud conflicts must be visible and recoverable.
- Reading must remain useful offline for content already available locally.
- The UI must remain a pleasant reader when all editorial overlays are hidden.

## Non-Goals for Initial Discovery

- Replacing the author's desktop Markdown workflow.
- Storing the canonical book in a proprietary database.
- Building collaborative real-time editing.
- Adding accounts, roles, assignments, approval workflows, or other project
  management behavior without a demonstrated personal-review need.
- Requiring an AI service to create, display, or preserve annotations.
- Requiring a Pocket Editor server to exchange or retain Yandex credentials.
- Designing implementation details before storage and review semantics are
  agreed.
- Publishing through Google Play, adding public analytics, or operating a public
  support channel.

### Distribution

Pocket Editor is a personal sideloaded Android application. Repository CI
produces a consistently signed APK. The production application ID and signing
certificate fingerprint are registered with Yandex OAuth. Release signing keys
must remain stable and protected because changing them affects both Android
updates and Yandex authorization. Google Play distribution is not planned.

### Security and privacy

Pocket Editor has no application backend, analytics, telemetry, advertising, or
third-party crash reporting. Yandex ID SDK obtains the least-privilege Disk
token, which is stored only in Android Keystore-backed app-private storage,
excluded from backup, redacted from logs, and removed on sign-out. Revocation
blocks sync but never locks the offline cache.

The manuscript cache is app-private and excluded from Android cloud backup.
Diagnostics contain neither tokens nor manuscript excerpts, search queries, or
full remote paths. Markdown raw HTML is never executed. Network access is
restricted to configured Yandex authorization and Disk endpoints.

Pocket Editor writes only `.pocket-editor.json` and `*.review.json` in selected
book roots. Personal release APKs are built by CI, signed with one protected key
kept outside the repository, accompanied by a checksum, and update only an
installation signed by that same key. Application ID and signing fingerprint
are registered with Yandex OAuth. Custom PIN, biometric lock, and additional
manuscript encryption are not initial requirements.

### Verification and completion

Pure Kotlin tests cover schemas, deterministic serialization, Markdown source
maps, UTF-8 anchors, re-anchoring outcomes, before/after diffing, edit overlap,
signal overlap, three-way merge, deletion, conflict detection, and TOC
discovery. Storage/sync integration tests cover atomic writes, process recovery,
index rebuild, offline outbox behavior, retry/backoff, invalid remote data,
revoked auth, and missing/renamed chapters. Compose tests cover responsive
layouts, themes, Review modes, panel controls, draft persistence, color changes,
Save/Cancel, autosave, Undo, and accessibility scaling.

A release-blocking E2E uses a dedicated Yandex test account and exercises full
import, offline review, forced process death, external source/review changes,
reconnection, merge, conflict UI, and re-anchoring. It verifies that Pocket
Editor never uploads canonical Markdown. MVP completion also requires rebuild
from file data after deleting the Room index, responsive UI regression coverage,
and a signed upgrade-compatible APK that authorizes successfully through
Yandex.

### Implementation architecture

Pocket Editor is a native Android application written in Kotlin with Jetpack
Compose. Native Android owns text selection and input, responsive phone/tablet
UI, Yandex ID SDK integration, protected credentials, offline persistence, and
background synchronization. A Svelte/Tauri bridge or Flutter runtime is not part
of the architecture.

The implementation has five boundaries:

1. **Compose UI** owns screens and transient interaction state but never writes
   JSON, SQLite, or Yandex Disk directly.
2. **Book core** is pure Kotlin domain logic for manifest parsing, Markdown AST
   and source maps, review validation, anchor resolution, before/after diffing,
   search projection, and overlap enforcement.
3. **Local book store** holds the complete offline file cache and performs
   validated atomic writes through temporary-file replacement. Canonical
   Markdown is read-only.
4. **Disposable Room/SQLite index** owns search indexes, sync metadata, remote
   revisions, outbox state, and device-local reading positions. It can be
   rebuilt entirely from cached and remote files.
5. **Yandex gateway and sync engine** own Yandex ID tokens, Disk REST calls,
   background refresh/upload, record-level three-way merge, retries, and
   conflict handoff to the UI.

User mutations flow through domain validation, atomic local JSON persistence,
immediate UI state, outbox enqueueing, and later background sync—in that order.
Reading flows from cached Markdown plus review JSON through parsing, source
mapping, anchor resolution, and clean/review rendering. Network availability is
never in the synchronous save path.

### Durable JSON model

The root `.pocket-editor.json` contains `schema_version`, stable `book_id`,
editable title, ordered chapter entries (`id`, `path`, `title`), and ignored
unlisted paths. Array order is TOC order.

Each source chapter has at most one `<chapter>.review.json` containing
`schema_version`, stable `chapter_id`, `source_path`, one plain
`chapter_note` string, a `signals` array, and an `edits` array. Signal records
contain stable ID, semantic type, exact selected text, redundant anchor, and an
optional comment string. Edit records contain stable ID, exact `before` text,
edited `after` text, and redundant anchor.

Anchors store the source SHA-256, selection SHA-256, zero-based half-open UTF-8
byte offsets, one-based start/end line hints, and at most 128 Unicode code points
of exact prefix and suffix context. Hashes use exact source bytes without
Unicode normalization.

The schema contains no authors, timestamps, statuses, threads, history, or
persisted anchor-resolution state. Stable IDs are UUIDs. Deleting an item removes
it from the JSON; the disposable sync index retains the three-way merge base.
Files use UTF-8, deterministic formatting, and strict versioned JSON Schema
validation. An unknown newer schema version is never rewritten and opens
read-only with a clear compatibility error.

### Reader and review interactions

Pocket Editor resumes the last device-local position and displays one chapter
as a continuous vertical reader. The top-level Review control — a two-state
toggle button, not a list-style switch — is the only overlay visibility
control. Off renders canonical source alone; on renders all edits,
signal highlights, non-empty passage comments, and chapter notes.

Phones use a full-width reader with Contents and Review bottom sheets. Tablet
landscape uses independently collapsible Contents and Review sidebars with
icon-buttons in each sidebar header. Tablet portrait hides Contents behind a
menu and opens Review as a right overlay.

Selecting prose opens a contextual flyout with the four signal colors and Edit.
A signal color opens a persistent inline optional-comment composer with the
four-color selector, Save, and Cancel. Outside taps and back navigation never
dismiss an active draft. Edit opens a plain-text field prefilled with the
selection and uses the
same explicit Save/Cancel behavior; diff presentation derives from before/after
text. Existing records reopen these editors.

Non-empty signal comments render after the containing Markdown block; multiple
comments stack in source order. Chapter notes autosave without commit buttons.
Saved signals and edits delete immediately with Undo. Contents owns the TOC,
book switcher, and current-book source search. Appearance exposes only theme and
text size.

## Consequences

- Overlay data needs a resilient anchoring scheme and explicit stale-anchor
  handling.
- Direct Yandex Disk API access is an architectural dependency and makes OAuth,
  caching, rate/error handling, upload safety, and conflict recovery first-class
  requirements.
- A portable sidecar format trades some compactness for transparency and agent
  usability.
- The sidecar requires schema validation; a recognizable extension alone cannot
  guarantee valid review data.
- The reading renderer and overlay engine must share a stable mapping between
  source Markdown ranges and rendered passages.
- Implementation planning and prototyping must wait until the compiled
  specification receives final user approval.

## Discovery Record

Questions and answers are recorded in [`../qa.md`](../qa.md). The resulting
complete design is
[`../superpowers/specs/2026-07-18-pocket-editor-design.md`](../superpowers/specs/2026-07-18-pocket-editor-design.md).
