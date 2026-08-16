# Pocket Editor Discovery Q&A

This is the chronological decision log for the Pocket Editor specification.
Each question is registered when asked. Its answer, rationale, and affected
documents are added immediately after Pavel responds.

## Initial brief

- Read folder-based Markdown books comfortably on Android.
- Treat existing Markdown as canonical and show proposed edits as an overlay.
- Attach comments to passages or whole chapters.
- Store comments and edits near the source in a form that AI agents can read.
- Support a TOC, multiple book roots, clean reading, collapsible editorial
  tools, and visibility controls for edits.
- Write the base ADR before requirements grilling; ask one question at a time
  and recommend an answer.

## Q-001: Android access to cloud-backed book folders

**Status:** Answered

**Question:** What storage boundary should the first version use to reach a book
folder on Android?

**Recommended answer:** Use Android's Storage Access Framework (SAF) and let the
user grant persistent access to a provider-backed folder. Treat Yandex Disk as
the first real compatibility target, but keep Pocket Editor provider-agnostic.
If Yandex Disk cannot expose a usable document tree through SAF, add a narrow
Yandex Disk connector as a follow-up rather than making its API the core storage
model.

**Alternatives:**

- Integrate directly with the Yandex Disk API in version one. This offers more
  control over sync but introduces authentication, network, caching, conflict,
  and vendor-specific behavior immediately.
- Use a desktop companion service that exposes the Linux folder to Android.
  This can reuse the exact desktop path but makes reading depend on another
  running device and service.

**Answer:** Direct Yandex Disk API integration.

**Rationale:** Yandex Disk is the actual storage service the first version must
work with. Pocket Editor will own remote listing, download, upload, caching, and
conflict behavior rather than depending on Yandex Disk to appear as an Android
document provider. Provider-independent storage can remain a future extension,
but it must not dilute or delay reliable Yandex Disk support.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future storage-access section of the product specification

## Q-002: Yandex account authentication

**Status:** Resolved by official documentation; not asked

**Question:** How should the Android app authenticate with Yandex for direct
Disk API access?

**Decision:** Register Pocket Editor as a Yandex OAuth mobile authorization app
and use the official Yandex ID SDK for Android to obtain an OAuth token with
only the Disk permissions Pocket Editor requires. Store credentials only in
Android-protected app storage and support explicit sign-out and revoked-token
recovery.

**Evidence:**

- [Yandex ID mobile SDK](https://yandex.ru/dev/id/doc/en/mobileauthsdk/about)
  is the documented OAuth path for Android and iOS applications.
- [Yandex OAuth token guidance](https://yandex.com/dev/id/doc/en/access)
  recommends the Yandex ID SDK for mobile apps and says tokens must not be
  stored in browsers or open configuration files.
- [Yandex Disk REST API](https://yandex.com/dev/disk/rest/) explicitly supports
  mobile applications and uses an OAuth access token.

**Rationale:** This follows Yandex's supported native mobile flow, avoids asking
users to paste tokens, and does not introduce a Pocket Editor authentication
backend.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future security and authentication section of the product specification

## Q-003: Meaning of a book root

**Status:** Answered

**Question:** Should a configured book root point to the whole book project,
such as `writing/alchemist/`, or directly to its chapter directory, such as
`writing/alchemist/chapters/`?

**Recommended answer:** Select the whole book project as the root. Add a small,
plain-text Pocket Editor manifest that identifies the chapter directory and TOC
source. Store portable review data under that project root. This leaves room for
agents and future features to understand nearby plot, character, glossary, and
style material without making the Android reader load all of it initially.

**Alternatives:**

- Select the chapter directory directly and infer its contents. Setup is
  simpler, but book identity and storage layout become implicit.
- Select one library directory containing several books and auto-discover them.
  This reduces setup clicks but makes discovery heuristics and access scope much
  broader.

**Answer:** A book root points directly to the folder containing the chapter
texts, such as `writing/alchemist/chapters/`. Pocket Editor needs only book
texts. It must not assume that a surrounding project has any particular
structure or that an `_index.md` file exists.

**Rationale:** The chapter folder is the only stable common boundary across
different book projects. Requiring a project manifest, plot files, character
files, or a chapter registry would couple the reader to one author's current
project layout.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future book-discovery section of the specification

## Q-004: Durable review document model

**Status:** Answered; sidecar accepted here, encoding resolved by Q-005

**Question:** Should Pocket Editor store review state in a custom extended
document such as `chapter-01.mdq`, containing the chapter text plus embedded
edits, or in a Markdown sidecar such as `chapter-01.edits.md` beside the
unchanged `chapter-01.md`?

**Recommended answer:** Use `chapter-01.edits.md` as a sidecar. Keep the source
chapter as the sole copy of canonical prose. Give the sidecar a small,
documented, machine-parseable Markdown dialect containing source identity,
anchors, comments, and unified replacement/insertion/deletion proposals. An AI
agent can read the source and sidecar together without proprietary tooling.

**Trade-offs:**

- A custom `.mdq` document can display source and review material as one file,
  but it either duplicates the source text and can drift out of sync, or embeds
  nonstandard syntax into prose and requires every consumer to understand a new
  parser.
- A per-chapter `.edits.md` sidecar keeps source ownership unambiguous and
  localizes conflicts, but agents and tools must open two neighboring files to
  reconstruct the reviewed view.
- A structured JSON or YAML sidecar would be simpler for machines, but is less
  pleasant for people and conflicts with the requirement that review artifacts
  be naturally readable as documentation.

**Answer:** Use a separate sidecar beside each source chapter. Do not use an
extended document that incorporates a second copy of the chapter. The
`.edits.md` extension and Markdown encoding are not accepted yet.

**Rationale:** A sidecar is the most viable ownership model, but edits require a
robust structure for insertions, replacements, deletions, line- or passage-level
comments, and chapter-level notes. Markdown does not enforce such a structure,
and a unified diff cannot represent the full review domain or resilient
anchoring semantics.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Review-file format section of the specification

## Q-005: Sidecar serialization and filename

**Status:** Answered

**Question:** Should the structured per-chapter sidecar use YAML, JSON, TOML, or
another serialization, and what should its suffix communicate?

**Recommended answer:** Use `<chapter>.review.yaml`, for example
`chapter-01.review.yaml`. Define a versioned application schema and validate it
strictly on every read and write. YAML block scalars keep multiline prose,
comments, and replacement text readable to people and AI agents, while the
schema—not the filename—enforces required fields, allowed operation types,
stable IDs, statuses, and anchor shapes. `review` covers chapter notes,
passage comments, and proposed edits more accurately than `edits`.

**Alternatives:**

- `<chapter>.review.json` has the strictest widely supported syntax and simplest
  schema tooling, but escaped multiline prose is noisy for people and agents.
- `<chapter>.review.toml` is readable and handles multiline strings, but deeply
  nested arrays of heterogeneous annotations and anchors become cumbersome.
- A custom extension such as `.mdq` or `.review` hides the underlying encoding
  and requires custom editor/tool associations without adding structural
  guarantees.

**Answer:** Use JSON. The sidecar is created and consumed by Pocket Editor and
AI agents; it does not need to optimize for direct human editing. The filename
is `<chapter>.review.json`, for example `chapter-01.review.json`.

**Rationale:** JSON has strict, widely supported parsing and mature schema
validation. Its weaker presentation of multiline prose is acceptable because
people interact through the app. `review` accurately includes proposed edits,
passage comments, and chapter notes. YAML remains a possible interchange format
but offers no decisive advantage for the authoritative file.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future review-file schema section of the specification

## Q-006: Resilient passage anchoring

**Status:** Answered

**Question:** Should each passage-level review item use a redundant,
content-addressed anchor rather than treating line numbers or offsets as its
identity?

**Recommended answer:** Yes. Store several independent signals:

- the source chapter content hash observed when the item was created;
- the exact selected source text and its hash;
- bounded prefix and suffix context around the selection;
- start/end UTF-8 or Unicode offsets and line/column positions as fast hints;
- for insertion points, both the preceding and following text context.

Resolve anchors deterministically: use saved offsets only when the chapter hash
still matches; otherwise search for the exact selection, disambiguate it using
prefix/suffix context, and attach only when there is one defensible match. If
there is no match or several equally plausible matches, mark the item `stale`
or `ambiguous` and require explicit re-anchoring. Never guess silently.

Chapter notes have chapter scope and no passage anchor. Passage comments target
a range without changing text. Edit proposals use typed `insert`, `replace`, or
`delete` operations over an anchor.

**Alternatives:**

- Line numbers plus columns are compact but drift after unrelated edits.
- Character offsets are precise only for the exact source snapshot.
- Exact quoted text alone fails when prose repeats or the selected text itself
  changes.
- A full diff provides context for a patch but does not model comments,
  chapter notes, stable identities, or ambiguous reattachment.

**Answer:** Adopt the recommended redundant, content-addressed anchor model.

**Rationale:** No single coordinate system survives source changes reliably.
Combining snapshot identity, exact source text, surrounding context, and
location hints permits deterministic fast-path resolution while ensuring stale
or ambiguous annotations fail visibly instead of moving to unrelated prose.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future anchor resolution and review-operation sections of the specification

## Q-007: Canonical chapter mutation boundary

**Status:** Answered

**Question:** Should Pocket Editor remain strictly overlay-only, or should it
offer an action that applies an accepted edit proposal to the canonical chapter
Markdown?

**Recommended answer:** Keep Pocket Editor overlay-only for the first product.
It reads chapter Markdown and writes only `<chapter>.review.json`. Review items
can be `open`, `resolved`, `rejected`, or `applied_externally`, but the Android
app never rewrites the source chapter. An author or AI agent can incorporate a
proposal through the existing writing workflow; Pocket Editor then detects the
source revision and lets the user confirm that the item was applied.

**Alternatives:**

- Apply individual accepted proposals directly from the app using the saved
  anchor and a guarded compare-and-swap upload. This is convenient, but turns
  Pocket Editor into a canonical editor and greatly raises data-loss and
  conflict risk.
- Generate a patch or revised chapter as a separate export. This avoids direct
  mutation but introduces another artifact and duplicates text without being
  necessary for an agent-readable sidecar workflow.

**Answer:** Pocket Editor is strictly overlay-only. It must not edit original
chapter files.

**Rationale:** Canonical prose remains under the author's existing writing and
agent workflows. Pocket Editor is a reader and review-capture tool; separating
those responsibilities prevents an annotation action, status change, or anchor
resolution from becoming an accidental source rewrite.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future review lifecycle and source-safety sections of the specification

## Q-008: Table-of-contents discovery and ordering

**Status:** Answered

**Question:** Without an `_index.md`, how should Pocket Editor decide which
Markdown files are chapters, what order they appear in, and which title to
display?

**Recommended answer:** On first import, enumerate ordinary `*.md` files while
excluding recognized Pocket Editor sidecars, then present a confirmation screen
where the user can include/exclude files, reorder them, and edit display titles.
Seed that screen deterministically:

1. order by a valid front-matter `number` when present, then natural filename
   order;
2. derive titles from front-matter `title`, then the first level-one heading,
   then the filename;
3. persist the confirmed TOC in a generated root-level
   `.pocket-editor.json`, never in a chapter file.

The generated file contains only book identity, chapter paths, order, and
display titles. It is optional before first import but becomes authoritative
for Pocket Editor afterward, making TOC behavior stable across devices and
visible to agents.

**Alternatives:**

- Infer the TOC on every load. This creates no metadata file but can reorder the
  book unexpectedly when filenames or front matter change.
- Keep confirmed ordering only in app-private storage. This avoids another
  Yandex Disk file but loses the TOC on reinstall and makes devices disagree.
- Require a pre-existing manifest. This is deterministic but violates the
  requirement that a plain folder of chapter texts is enough to begin.

**Answer:** Generate the root-level `.pocket-editor.json` TOC manifest using the
recommended first-import discovery and confirmation flow.

**Rationale:** A plain chapter folder remains sufficient for initial import,
while the generated manifest gives subsequent loads and other devices stable,
explicit inclusion, ordering, titles, and book identity without modifying any
chapter.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future book discovery and TOC sections of the specification

## Q-009: Review surfaces and visual semantics

**Status:** Answered from volunteered requirements

**Question:** What distinct editorial surfaces and signals must the product
model and reading UI support?

**Answer:** There are three primary surfaces:

1. **Actual edit proposals.** They appear inline only when the edit overlay is
   enabled. Deleted source is red with strikethrough; added prose is green. A
   replacement displays both its deleted and added portions.
2. **Chapter notes.** They live in a separate drawer with an immediate plain-text
   editing surface and no formatting instruments.
3. **Line or passage signals.** The user selects original text. The selected
   characters receive a background highlight and an optional comment block is
   inserted below the relevant line or passage. Four semantic signal types are
   required:
   - `note` — blue; something to keep in mind;
   - `change_required` — red; this text needs changing, with an optional note
     suggesting what should change;
   - `warning` — yellow; something seems strange or puzzling, optionally
     explained in the note;
   - `review` — pink; a hunch that the text needs rechecking even when the
     editor cannot identify the problem.

The explanatory note is optional for every line/passage signal. The semantic
type is stored independently of its rendered color so themes and accessibility
modes can preserve meaning.

**Rationale:** Edits, chapter notes, and signals serve different editorial
purposes and should not be collapsed into one generic comment UI. Signal type
must remain useful even when the author leaves no prose note.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future review schema and reader interaction sections of the specification

## Q-010: Overlapping passage signals

**Status:** Answered

**Question:** May two line/passage signals overlap the same source characters,
and if so, how should the reader represent them?

**Recommended answer:** Permit overlapping anchors in the JSON model because a
person and an agent may independently flag intersecting text. With one signal,
use its semantic background color. With overlapping signals, keep every record
but show one combined highlight with a small count/multi-signal indicator;
tapping it opens all associated comment blocks. Do not silently merge or discard
records. The exact combined visual can be refined during UI design.

**Alternatives:**

- Reject any overlap and require the user to edit the existing signal. This is
  visually simple but makes independent agent contributions brittle.
- Render stacked or striped background colors. This exposes every category at a
  glance but can make prose difficult to read, especially on a phone.
- Choose a fixed color priority such as red over yellow over pink over blue.
  This stays visually simple but hides lower-priority meanings unless the user
  opens the annotation.

**Answer:** Allow overlapping passage signals using the recommended combined
presentation while preserving each signal as an independent record.

**Rationale:** User and agent annotations may legitimately intersect. Data must
not be discarded or silently merged merely to simplify phone rendering.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future overlap-resolution and annotation-rendering sections of the specification

## Q-011: Overlapping edit proposals

**Status:** Answered; recommendation rejected

**Question:** What should happen when two actual edit proposals modify
overlapping source ranges?

**Recommended answer:** Preserve both as competing proposals, but never compose
them automatically. Group intersecting edits into a conflict set. The reader
shows one selected proposal at a time and clearly indicates that alternatives
exist; the user can switch between them and mark proposals `preferred`,
`rejected`, or `superseded`. Agents must also treat unresolved conflict sets as
alternatives, not sequential patches.

**Alternatives:**

- Reject creation of any overlapping edit. This simplifies rendering but
  prevents agents or users from proposing legitimate alternatives.
- Render every overlapping edit simultaneously. This produces text that may
  correspond to none of the intended proposals.
- Automatically prioritize the newest edit. This silently hides review history
  and makes clock/order differences affect meaning.

**Answer:** Edits must not overlap. An edit is a concrete edit, not one of
several competing proposals over the same source.

**Rationale:** Simultaneous edits need one unambiguous overlay result. Alternative
or overlapping patches introduce a proposal-selection workflow that is outside
the product model.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future edit-conflict and overlay-rendering sections of the specification

## Q-012: Completed edit retention

**Status:** Answered; recommendation rejected

**Question:** After an author or agent incorporates an overlay edit into the
canonical chapter outside Pocket Editor, should its sidecar record be retained
as history or removed from the review file?

**Recommended answer:** Retain it with `status: applied_externally` and hide it
from the normal reading overlay. Pocket Editor may detect that the source now
contains the edited result and offer to mark it applied, but must not change the
status silently. This preserves an agent-readable audit trail and explains why
the original anchor no longer matches. Provide an explicit cleanup action later
if accumulated history becomes noisy.

**Alternatives:**

- Delete the record when it is incorporated. This keeps sidecars small but
  removes useful editorial history and makes accidental deletion hard to
  distinguish from completion.
- Move completed records to a separate history file. This keeps active state
  clean but doubles the number of synchronization artifacts and complicates
  atomic transitions.

**Answer:** Do not complicate the model with retained edit history. The review
file represents the current overlay. When an external workflow incorporates an
edit into the canonical chapter, remove that edit record from the sidecar.

**Rationale:** Applied-status tracking, automatic detection, and separate
history are unnecessary workflow machinery. Version control or cloud file
history can provide recovery outside Pocket Editor if needed.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future review lifecycle and history sections of the specification

## Q-013: Edit selection granularity

**Status:** Answered

**Question:** Must edits operate on complete Markdown lines, or may they target
any contiguous text selection inside a line or paragraph?

**Recommended answer:** Allow arbitrary contiguous source-text ranges.
`replace` and `delete` act on the selected range; `insert` acts at a caret or
boundary. Whole-line and whole-paragraph edits are ordinary cases of the same
model. This supports small wording corrections without forcing an entire prose
line to appear deleted and re-added.

**Alternative:** Restrict edits to complete lines. Anchoring and display become
simpler, but Markdown prose often stores an entire paragraph on one physical
line, so changing one word would turn into a noisy paragraph replacement.

**Answer:** Edits may target arbitrary contiguous text.

**Rationale:** Pocket Editor should support precise human review without turning
a small correction into a noisy whole-line or whole-paragraph replacement.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future edit operation and selection sections of the specification

## Q-014: Collaboration and discussion features

**Status:** Answered

**Question:** Do review records need authors, replies, discussion threads, or
multi-reviewer assignment?

**Recommended answer:** No. Treat Pocket Editor as a single-owner human review
tool. Each passage signal has one optional plain-text note; each chapter has one
plain-text notes surface; each edit is one concrete operation. Agents may create
or update the same records through JSON, but Pocket Editor does not model users,
replies, mentions, assignments, or comment threads.

**Alternative:** Add optional authors and threaded replies. This supports
collaborative review but expands the schema, conflict handling, and UI far beyond
the current personal workflow.

**Answer:** No collaboration features. Pocket Editor is only for Pavel reviewing
stories.

**Rationale:** This is a personal review tool, not a collaboration platform.
Authorship, replies, threads, mentions, assignments, and roles add no value to
the intended workflow.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future non-goals and review schema sections of the specification

## Q-015: Selection across Markdown formatting

**Status:** Answered

**Question:** Should Pocket Editor allow an edit selection to cut through or
directly modify Markdown syntax such as emphasis markers, links, or headings?

**Recommended answer:** No. Selection happens on rendered prose. Pocket Editor
maps it to a contiguous raw-source range and permits an edit only when that range
can be replaced without splitting Markdown syntax. It may include a complete
formatted span, but not half of a delimiter or link. Formatting-only changes
remain outside this simple review tool and can be made later by an agent or
ordinary Markdown editor.

**Alternative:** Expose raw Markdown selection/editing. This provides full
control but turns the reading surface into a source editor and makes it easy to
create malformed markup.

**Answer:** Adopt the recommendation: edits operate on rendered prose and may
only target source ranges that do not split Markdown structures.

**Rationale:** This preserves the simple reading-oriented interaction and keeps
Pocket Editor from generating malformed Markdown operations.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future Markdown mapping and edit interaction sections of the specification

## Q-016: Primary chapter reading motion

**Status:** Answered

**Question:** Should a chapter use continuous vertical scrolling or paginated
ebook-style screens?

**Recommended answer:** Use continuous vertical scrolling, loading one chapter
at a time and remembering its reading position. Navigate between chapters from
the TOC and explicit previous/next controls. Continuous layout keeps Android
text selection natural and accommodates inline deletions/additions and inserted
comment blocks without repaginating the rest of a chapter after every overlay
change.

**Alternative:** Paginated screens feel more like an ebook and bound each view,
but selection across page boundaries is awkward and every visible review block
changes pagination.

**Answer:** Use continuous vertical scrolling on both phones and tablets. There
is no need for page-turning or paginated reading.

**Rationale:** Scrolling is the preferred reading behavior on both target device
classes and works naturally with selection, overlay edits, and inserted comment
blocks whose heights change dynamically.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future reader layout and navigation sections of the specification

## Q-017: Responsive review workspace

**Status:** Answered; adaptive layout selected

**Question:** How should the TOC and editorial surfaces adapt between a phone
and a tablet without compromising the clean reading view?

**Recommended answer:** Use an adaptive reader-first layout. On phones, the
chapter occupies the full viewport; TOC and review tools open as polished modal
bottom sheets, while text selection produces a small contextual action bar. On
tablets, center a bounded reading column and expose independently collapsible
left TOC and right review sidebars. The right sidebar hosts chapter notes and
the focused edit/signal details. Both device classes use the same semantic
controls and overlay rendering.

**Alternative:** Use the same single overlay drawer on phones and tablets. This
is structurally simpler but wastes tablet width and requires repeated context
switching during longer review sessions.

**Answer:** Select the adaptive reader workspace (option A). It is the correct
finished-product direction, with these refinements:

- design dark theme as a first-class experience because it will be used most;
- in tablet landscape, the left Contents and right Review sidebars are
  independently collapsible so either or both can disappear during reading;
- in tablet portrait, Contents becomes a hidden menu opened on demand;
- in tablet portrait, Review is an overlay sidebar that opens from the right
  over the reading view rather than permanently narrowing the text column.

**Rationale:** The adaptive layout uses wide screens productively during review
without forcing persistent chrome during reading. Orientation-specific behavior
preserves a comfortable text measure instead of squeezing the prose between two
fixed panels.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future responsive layout and interaction sections of the specification

## Q-018: Dark theme and tablet orientation refinement

**Status:** Answered; direction confirmed with control-placement refinement

**Question:** Does the refined dark presentation correctly realize the selected
adaptive layout across phone, tablet landscape, and tablet portrait?

**Recommended answer:** Use a warm near-black reading canvas rather than pure
black, high-contrast warm-white prose, subdued chrome, and semantic review
colors tuned separately for dark backgrounds. Preserve the selected tablet
collapse and overlay behavior exactly as specified in Q-017.

**Answer:** The dark adaptive direction is excellent. In tablet landscape,
however, sidebar controls must belong visually to their sidebars. Replace the
easy-to-miss chevrons and detached top-bar controls with clear icon buttons in
the Contents and Review panel headers.

**Rationale:** Controls for a block should be located on that block. This makes
the collapse affordance discoverable and preserves clear ownership.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future themes and responsive layout sections of the specification

## Q-019: Passage-signal creation flow

**Status:** Answered from volunteered requirements

**Question:** What is the exact human interaction for creating a colored
passage signal and optional comment?

**Answer:**

1. Select rendered text.
2. Pocket Editor shows a small flyout with the four semantic color buttons.
3. Pressing a color creates the signal and opens its comment field.
4. Enter an optional comment.
5. Press **Save** to commit the record or **Cancel** to discard it.

Once the comment field has been activated, tapping outside must not dismiss the
composer or lose draft text. Cancellation is always explicit through the Cancel
button.

**Rationale:** The flow is quick for signals without notes, remains obvious for
signals with explanations, and prevents accidental loss caused by stray taps
during phone or tablet text entry.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future passage-signal interaction and draft-safety sections of the specification

## Q-020: Final interaction refinement

**Status:** Answered; approved with color-editing refinement

**Question:** Do the corrected sidebar-owned icon controls and persistent inline
comment composer match the intended finished interaction?

**Recommended answer:** Use explicit panel-collapse icon buttons in panel
headers. Render the comment composer inline beneath the selected passage so the
text remains in context; after a color is chosen, keep the draft open until Save
or Cancel regardless of outside taps.

**Answer:** The corrected sidebar controls and persistent comment composer are
approved. The comment editor must additionally allow changing the selected
signal color before saving.

**Rationale:** A mistaken initial color should be correctable in the same editor
without cancelling and recreating the signal.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future responsive controls and passage-signal interaction sections

## Q-021: Signal color changes in the comment editor

**Status:** Answered from volunteered requirement

**Question:** Can the signal category/color be changed after the initial flyout
choice?

**Answer:** Yes. The comment editor contains the same four semantic color
buttons, with the current choice visibly selected. The user may change it before
Save. When editing an existing saved signal, Save commits color and comment
together; Cancel restores both previous values.

**Rationale:** Color is semantic data, not a one-time creation shortcut. Keeping
it editable in the same surface makes correction obvious and avoids destructive
recreation.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future passage-signal editor section of the specification

## Q-022: Final signal-editor visual

**Status:** Answered; approved

**Question:** Is the inline four-color selector in the persistent comment editor
the correct finished placement and hierarchy?

**Recommended answer:** Keep a compact labeled color row above the plain-text
field, show the selected color with both a ring and semantic label, and commit
color plus comment atomically through Save.

**Answer:** The inline four-color selector above the comment field is approved.

**Rationale:** It keeps semantic correction visible and immediate without adding
a separate settings or recreation flow.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future signal-editor visual specification

## Q-023: Simplified actual-edit interaction

**Status:** Answered

**Question:** Should insertion, replacement, and deletion use one simple edit
surface rather than separate operation modes?

**Recommended answer:** Yes. Select any contiguous rendered text and press
**Edit**. Pocket Editor opens a plain-text field prefilled with that selection.
The user changes the copy and saves it; the app stores the selected `before`
text and resulting `after` text, then derives the red deletions and green
additions for display. Clearing the field is a deletion; adding text before or
after the selected copy creates an insertion as part of the same edit. No
Replace/Delete/Insert mode picker is needed.

**Alternative:** Ask the user to choose `insert`, `replace`, or `delete` before
editing. This maps directly to separate schema operations but adds a decision
the app can derive from before/after text.

**Answer:** Adopt the single before/after edit surface. Do not expose separate
Insert, Replace, and Delete modes.

**Rationale:** All concrete text changes can be expressed by editing a copy of
the selected text. Pocket Editor can derive visual additions and deletions
without asking the reviewer to classify the operation first.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future edit schema and interaction sections of the specification

## Q-024: Review overlay visibility controls

**Status:** Answered; recommendation simplified

**Question:** Should edits and colored passage signals always appear together
under one Review switch, or should their visibility be independently
controllable?

**Recommended answer:** Keep one prominent master **Review** switch for normal
reading versus reviewing. Inside the Review panel, provide two remembered
visibility toggles: **Edits** and **Signals**. Chapter notes are visible only
when their drawer/panel is open. This keeps the top bar simple while allowing a
clean pass focused only on edits or only on editorial signals.

**Alternative:** One switch always shows or hides everything. This is simpler,
but dense chapters may make concrete edits difficult to inspect among many
colored signals and comment blocks.

**Answer:** Use one binary switch only. **Review on** shows the complete
editorial overlay: all highlights, edits, passage comments, and notes. **Review
off** shows the clean original chapter text. Do not add independent visibility
filters.

**Rationale:** The product needs a clean reading state and a complete review
state, not a configurable layer system. Additional filters would complicate a
personal review tool without serving the core workflow.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future review visibility and toolbar sections of the specification

## Q-025: Offline book caching

**Status:** Answered; mandatory requirement

**Question:** When a book root is added, should Pocket Editor cache the entire
book and all review sidecars for offline reading and review, or download chapters
only as they are opened?

**Recommended answer:** Cache the complete selected book. Markdown chapters and
JSON sidecars are small, and full caching makes TOC navigation, searching,
reading, and review reliable without a network connection. Synchronize changed
files with Yandex Disk when connectivity returns; images or other large linked
assets can remain a later, separately bounded concern.

**Alternative:** Cache on demand. Initial setup is faster, but unopened chapters
become unavailable offline and review state is less predictable.

**Answer:** Cache the complete selected book. Full offline reading and review are
mandatory because the primary usage includes travel with unstable or absent
internet access.

**Rationale:** The review workflow must never depend on a live connection.
Local changes save immediately and synchronize later; network availability is a
background delivery concern, not a prerequisite for work.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future offline cache and synchronization sections of the specification

## Q-026: Review-file synchronization conflicts

**Status:** Answered

**Question:** If a locally edited `<chapter>.review.json` also changed on Yandex
Disk while the device was offline, how should Pocket Editor reconcile it?

**Recommended answer:** Automatically merge independent review records by their
stable IDs. If both versions changed the same record, do not guess: show one
simple conflict screen with **Keep mine** and **Keep Yandex Disk** previews for
that record. Upload only after every same-record conflict is resolved. Never
overwrite a changed remote file silently.

Canonical chapter Markdown follows a simpler rule because Pocket Editor never
writes it: download the new source, retain local review records, and run normal
anchor re-resolution, surfacing stale or ambiguous anchors as already decided.

**Alternative:** Treat the entire JSON file as one conflict and choose one copy.
This is simpler internally but can discard unrelated notes and edits created on
another device or by an agent.

**Answer:** Adopt record-level automatic merging with explicit resolution for
same-record conflicts. Never overwrite remote review changes silently.

**Rationale:** Independent notes and edits should synchronize without user
involvement, while genuinely competing changes require a small, safe human
choice rather than a guessed merge.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future synchronization, conflict, and source-refresh sections

## Q-027: Chapter-note saving behavior

**Status:** Answered

**Question:** Should the plain-text chapter-notes drawer autosave as the user
types, or require explicit Save and Cancel actions?

**Recommended answer:** Autosave locally after a short debounce and whenever the
field loses focus. Show a quiet `Saved` / `Waiting to sync` status, but no Save
or Cancel buttons. The drawer is a persistent scratchpad, unlike creation of a
new anchored signal where explicit Save or Cancel prevents an accidental record.

**Alternative:** Require Save and Cancel. This is consistent with the signal
composer but adds friction and creates a question about what closing the drawer
should do with unsaved chapter notes.

**Answer:** Autosave the chapter note locally during typing and on focus loss.
Use quiet save/synchronization status and no Save or Cancel buttons.

**Rationale:** The chapter note is a persistent scratchpad. Explicit commit
controls add friction without protecting creation of a discrete anchored record.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future chapter-notes interaction and persistence sections

## Q-028: Deleting saved review items

**Status:** Answered

**Question:** When deleting a saved passage signal or edit, should Pocket Editor
ask for confirmation first or delete immediately with an Undo action?

**Recommended answer:** Delete immediately and show a brief **Undo** snackbar.
Keep the deletion recoverable locally until the undo window closes, then queue
it for synchronization. Avoid repetitive confirmation dialogs. Internally,
synchronized deletions must carry enough record identity to prevent an older
remote copy from being resurrected during merge.

**Alternative:** Show a confirmation dialog before every deletion. This is
safer against a single mistaken tap but slows routine cleanup and makes the
personal review flow feel heavier.

**Answer:** Delete saved signals and edits immediately with a brief Undo
snackbar. Do not require a confirmation dialog.

**Rationale:** Undo protects against accidental taps without slowing routine
cleanup. Synchronization identity prevents a deleted record from reappearing
from an older remote revision.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future review-item editing, deletion, and synchronization sections

## Q-029: Reader appearance controls

**Status:** Answered

**Question:** Which appearance controls should the reader expose beyond the
responsive layout and required light/dark themes?

**Recommended answer:** Keep only two user-facing settings:

- theme: **System**, **Light**, or **Dark**;
- text size: simple decrease/reset/increase controls that respect Android's
  accessibility font scale.

Use one carefully selected book serif, a designed default line height, and an
automatic readable content width. Do not expose font-family, margins,
justification, paragraph spacing, or line-height controls initially.

**Alternative:** Provide a full ebook typography panel. This offers extensive
personalization but contradicts the simple personal-review focus and increases
the number of layouts the annotation UI must handle.

**Answer:** Expose only System/Light/Dark theme selection and simple text-size
controls. Keep all other typography designed and automatic.

**Rationale:** These two settings cover environmental comfort and accessibility
without turning the personal review reader into a configurable ebook engine.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future appearance, accessibility, and reading typography sections

## Q-030: Newly discovered chapter files

**Status:** Answered

**Question:** After `.pocket-editor.json` exists, what should happen when a new
ordinary Markdown file appears in the book folder but is not listed in the TOC?

**Recommended answer:** Show a quiet `New chapter found` notice with **Add** and
**Ignore** actions. Add opens a small confirmation surface for display title and
TOC position, prefilled using the normal metadata/heading/filename fallbacks.
Ignore records the path so the notice does not return unless explicitly reset.
Never insert an unknown file into the reading order silently.

**Alternative:** Automatically append every new `.md` file. This is frictionless
for newly written chapters but can accidentally include notes, readmes, or other
Markdown stored in the same folder.

**Answer:** Notify with Add and Ignore. Never add an unlisted Markdown file to
the TOC silently.

**Rationale:** Explicit confirmation keeps the manifest authoritative and avoids
mistaking neighboring Markdown notes or readmes for chapters.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future book refresh and TOC maintenance sections

## Q-031: Missing or renamed chapter files

**Status:** Answered

**Question:** What should Pocket Editor do when a chapter listed in
`.pocket-editor.json` no longer exists at its recorded path?

**Recommended answer:** Mark the TOC entry `Missing` and retain its cached
chapter and review sidecar. If exactly one new Markdown file has the same content
hash, offer **Update path** as a likely rename; do not change it automatically.
Otherwise offer **Locate** and **Remove from book**. Removing a TOC entry must
not delete any Yandex Disk file or cached review data without a separate,
explicit cleanup action.

**Alternative:** Remove missing chapters automatically. This keeps the TOC tidy
but risks losing access to offline review work during a temporary sync issue,
rename, or external reorganization.

**Answer:** Keep missing chapters and cached review data, offer guarded rename
recovery, and never delete remote files automatically.

**Rationale:** Missing paths can result from temporary sync state, external
renames, or reorganization. Preserving cached work and requiring explicit TOC
actions avoids destructive guesses.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future missing-file and TOC maintenance sections

## Q-032: Markdown rendering baseline

**Status:** Resolved by source exploration; not asked

**Question:** What Markdown surface must the reader support initially?

**Evidence:** The 15 representative `alchemist/chapters/chapter-*.md` files use
YAML front matter, one chapter heading, paragraphs, and inline emphasis. They do
not currently use blockquotes, Markdown lists, fenced code, tables, links,
images, strong emphasis, or raw HTML.

**Decision:** Use a safe CommonMark prose renderer with YAML front matter parsed
for metadata and hidden from the reading body. Support ordinary headings,
paragraphs, emphasis/strong emphasis, blockquotes, lists, thematic breaks, and
links. Do not execute or inject raw HTML. More specialized extensions are not
MVP requirements. Review selection remains limited to cleanly mappable rendered
prose ranges as decided in Q-015.

**Rationale:** This covers the real book and standard prose Markdown without
designing review interactions for developer-document constructs the product does
not need.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future Markdown rendering and security sections

## Q-033: Full-text book search

**Status:** Answered

**Question:** Should the first version support searching source prose across all
chapters in the current book?

**Recommended answer:** Yes, but keep it small: one search field in the TOC
surface, matching clean source prose from the offline cache. Results show chapter
title plus a short excerpt and open the exact passage. Do not search review JSON,
add filters, or build replace functionality.

**Alternative:** Omit search initially. This reduces scope, but full-book cache
already makes a basic local source search inexpensive and useful when reviewing
long stories.

**Answer:** Include the recommended minimal full-text search across source prose
in the current book.

**Rationale:** Offline caching makes local source search inexpensive, and exact
passage navigation is useful during long-story review without requiring review
filters or replacement features.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future search and TOC interaction sections

## Q-034: Launch destination and book switching

**Status:** Answered

**Question:** With multiple configured book roots, should Pocket Editor launch
into the last reading position or always open a Books/library screen?

**Recommended answer:** Resume the last opened book, chapter, and scroll position
immediately. The Contents/menu surface includes a clear book switcher that opens
a simple Books screen for adding, removing, or selecting roots. If no book is
configured or the last root is unavailable, open Books instead.

**Alternative:** Always launch into Books. This makes root switching prominent
but adds a navigation step to the overwhelmingly common continue-reading flow.

**Answer:** Resume the last book, chapter, and scroll position immediately. Use
a Books screen only for initial setup, switching, or root management.

**Rationale:** Continuing review is the dominant action; routine launch should
not stop at a library intermediary.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future app launch, library, and reading-position sections

## Q-035: Reading-position synchronization

**Status:** Answered

**Question:** Should the current chapter and scroll position synchronize through
Yandex Disk between phone and tablet, or remain local to each device?

**Recommended answer:** Keep reading position device-local. Persist it
frequently and restore it reliably on that device, but do not write scroll state
into `.pocket-editor.json`. This avoids remote writes during ordinary scrolling,
sync conflicts, and one device unexpectedly moving another device's position.
Review content and TOC still synchronize normally.

**Alternative:** Synchronize the latest reading position across devices. This
supports seamless device switching but needs throttled metadata uploads and a
rule for competing positions from offline devices.

**Answer:** Keep reading position local to each device. No special cross-device
position synchronization is needed.

**Rationale:** Device-local continuation is sufficient and avoids remote writes
and conflicts for ephemeral scroll state.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future local state and synchronization-boundary sections

## Q-036: Placement of passage comment blocks

**Status:** Answered

**Question:** Since visual line wrapping differs by device and text size, where
should the comment block for selected prose be inserted?

**Recommended answer:** Insert it immediately after the rendered paragraph or
block containing the selection, with the selected characters still highlighted
and a subtle visual association to the comment. Do not insert between wrapped
screen lines. If a paragraph has several signals with comments, stack their
blocks beneath it in source-range order; Review on shows all of them.

**Alternative:** Insert directly after the wrapped visual line containing the
selection. This appears closer to short selections but is unstable when the
screen rotates, text size changes, or the selected range spans lines.

**Answer:** Place comments after the containing Markdown paragraph/block, not
after a wrapped visual line. Stack multiple comment blocks in source order and
show all of them when Review is on.

**Rationale:** Block placement remains stable across phone/tablet widths,
orientation changes, and text-size settings while retaining clear association
through the highlighted source range.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future annotation layout and responsive rendering sections

## Q-037: Android distribution target

**Status:** Answered

**Question:** Is Pocket Editor a personal sideloaded application, or should the
first release be prepared for public Google Play distribution?

**Recommended answer:** Treat the first product as a privately distributed,
consistently signed APK for personal installation, with release artifacts from
the repository CI. Register the production package name and signing-certificate
fingerprint with Yandex OAuth. Defer Play Store listing, policy work, analytics,
and public support until the personal workflow is proven.

**Alternative:** Target Google Play from the start. This simplifies mainstream
updates later but adds store policy, signing, listing, testing-track, privacy,
and support work unrelated to validating the personal review tool.

**Answer:** Pocket Editor is a personal sideloaded application. Google Play
publication is not planned.

**Rationale:** Private signed APK releases cover the intended single-user
workflow without store policy, listing, analytics, or public support scope.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future packaging, signing, OAuth registration, and release sections

## Q-038: Android implementation architecture

**Status:** Answered

**Question:** Which application stack should implement the approved Android-only
reader and review workflow?

**Recommended answer:** Native Kotlin with Jetpack Compose.

### A. Native Kotlin + Jetpack Compose — recommended

- One Android runtime and one application language for UI, Yandex ID SDK,
  networking, protected credentials, offline storage, background sync, text
  selection, IME behavior, and responsive phone/tablet layouts.
- Compose provides native text selection and custom layout primitives; Android
  WorkManager handles reliable constrained synchronization.
- The disposable local cache/index can use Room/SQLite while JSON files on
  Yandex Disk remain authoritative.
- Main cost: less reuse of Pavel's Svelte experience and careful custom work for
  rendered-Markdown-to-source selection mapping.

### B. Svelte 5 + Tauri 2 + Rust

- Strong fit for Pavel's frontend skills and the approved CSS-like visual
  direction; Rust could own parsing, anchors, and JSON validation.
- Tauri 2 supports Android, but Yandex ID and Android background behavior still
  require Kotlin mobile plugin code and a bridge among Svelte, Rust, and Kotlin.
- WebView selection, IME behavior, and native lifecycle integration add risk at
  exactly the product's most important interaction boundary.

### C. Flutter

- One cohesive UI toolkit with strong custom rendering and Android support.
- Introduces Dart and still needs a native integration boundary for Yandex ID.
- Cross-platform leverage does not justify a third ecosystem for an explicitly
  Android-only personal application.

**Answer:** Use native Kotlin with Jetpack Compose.

**Rationale:** The product is Android-only and depends heavily on native text
selection, input behavior, Yandex ID SDK integration, protected credentials,
responsive phone/tablet layouts, and reliable offline synchronization. One
native stack is simpler and lower risk than bridging Svelte, Rust, and Kotlin.

**Evidence:**

- Android Compose documentation supports selection across composable text
  layouts and stateful text editing.
- Android WorkManager is the platform-supported API for reliable deferrable
  asynchronous work under constraints.
- Tauri's official mobile plugin model implements Android integrations as
  Kotlin plugin classes callable from Rust/JavaScript.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Future architecture, component, and implementation-boundary sections

## Q-039: Component architecture and data flow

**Status:** Answered; design section approved

**Question:** Does the proposed component architecture and authoritative data
flow match the product decisions?

**Proposed section:** Compose UI over a pure-Kotlin book core, atomic local file
store, disposable Room/SQLite index, Yandex gateway, and WorkManager-based sync
engine. User changes validate and persist locally before entering the outbox;
reading composes cached Markdown and review JSON through source mapping and
anchor resolution.

**Answer:** Approved.

**Rationale:** The boundaries preserve file authority, offline-first behavior,
read-only canonical Markdown, replaceable indexing, and testable domain logic.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Final Pocket Editor design specification

## Q-040: Durable JSON data model

**Status:** Answered; design section approved

**Question:** Does the proposed minimal manifest, review document, and anchor
schema capture every accepted workflow without unnecessary state?

**Proposed section:** A generated `.pocket-editor.json` owns book identity and
ordered TOC entries. Each chapter has one flat `<chapter>.review.json` with a
single chapter note, signal records, edit records, and redundant anchors. The
model contains no collaboration, workflow, history, or derived resolution
state.

**Answer:** Approved.

**Rationale:** The model captures every accepted review surface while leaving
anchor resolution, diff rendering, conflict bases, and transient state inside
the application.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Final Pocket Editor design specification

## Q-041: Reader and review interaction contract

**Status:** Answered; design section approved

**Question:** Does the consolidated reader, responsive layout, and review
interaction section match the approved visual mockups and workflows?

**Proposed section:** Continuous reader with binary clean/review mode; adaptive
phone/tablet navigation; full overlay rendering; persistent signal and edit
composers; autosaved chapter note; TOC, book switcher, source search, minimal
appearance controls, and Undo deletion.

**Answer:** Approved.

**Rationale:** The interaction contract matches the approved high-fidelity dark
mockups and preserves the simple personal-review principle.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Final Pocket Editor design specification

## Q-042: Offline synchronization and error behavior

**Status:** Answered; design section approved

**Question:** Does the proposed sync lifecycle, conflict handling, and fail-safe
error behavior match the offline-first requirement?

**Proposed section:** Full offline cache and local-first atomic saves; automatic
and manual WorkManager sync; remote-revision checks; record-level three-way
merge; explicit same-record conflicts; source refresh and re-anchoring; fail-safe
handling for auth, network, invalid JSON, unknown schema, missing chapters, and
overlapping edits.

**Answer:** Approved.

**Rationale:** The section makes travel/offline work primary while preventing
silent remote overwrite, source mutation, invalid-file propagation, or data loss.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Final Pocket Editor design specification

## Q-043: Security, privacy, and distribution boundary

**Status:** Answered; design section approved

**Question:** Does the proposed personal-app security and release boundary cover
the real risks without introducing a backend or public-product machinery?

**Proposed section:** No Pocket Editor backend or telemetry; Yandex ID SDK with
least privilege; Keystore-backed token storage; private cache excluded from
backup; strict log/content redaction; safe Markdown rendering; signed private
APK; and a write boundary limited to manifest and review JSON.

**Answer:** Approved.

**Rationale:** The boundary protects credentials and manuscript content while
remaining proportionate to a personal sideloaded application.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Final Pocket Editor design specification

## Q-044: Verification and MVP completion criteria

**Status:** Answered; design section approved

**Question:** Does the proposed test strategy and concrete definition of MVP
completion provide enough evidence to begin implementation planning afterward?

**Proposed section:** Pure Kotlin schema/anchor/diff/merge tests; storage and sync
integration tests; responsive Compose UI and accessibility tests; a real Yandex
test-account offline/reconnect E2E; and explicit release/data-safety acceptance
criteria.

**Answer:** Approved.

**Rationale:** Completion requires evidence that offline work, process recovery,
remote conflict handling, source immutability, responsive UI, and signed release
behavior all work together—not merely isolated unit tests.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- Final Pocket Editor design specification

## Q-045: Non-color signal markers and the `review` hue

**Status:** Answered; recommendation partially rejected

**Question:** Following an HIG-grounded design review, should each passage
signal carry a non-color tell (an underline pattern or small glyph) in the
highlight itself, beyond color, label, and accessibility semantics? Separately,
is pink the right hue for `review`?

**Recommended answer:** Add a distinct underline pattern or corner glyph per
signal type to the highlight rendering, since two of the four hues can read as
close for some forms of color vision deficiency, and a sighted reader scanning
a page shouldn't need to open a comment to tell them apart.

**Answer:** No additional non-color marker. A highlight is already the signal,
the way it is in a paper manuscript; four colors are few enough to learn once
and hold in memory without a secondary glyph system. Recolor `review` from pink
to violet, since violet reads as more distinct from the other three than pink
did.

**Rationale:** The existing commitment — that signal meaning is also exposed
through labels and accessibility semantics, never color alone — already covers
assistive technology. A fifth visual channel for four learnable colors is
workflow machinery the personal review tool does not need. Violet resolves the
one real closeness (pink sitting too near red) without adding a marker system.

**Affected documents:**

- `docs/superpowers/specs/2026-07-18-pocket-editor-design.md` (signal color
  table)
- `docs/adr/0001-local-first-overlay-reader.md` (signal type list)

## Q-046: Combined-signal indicator touch target

**Status:** Answered; deferred

**Question:** Should the combined-signal indicator shown for overlapping
passage signals get an enlarged or independently anchored tap target now, since
a single short highlighted word can fall under the ~48dp Android touch-target
minimum?

**Answer:** Not now. Overlapping signals on a span too short to comfortably tap
are a rare case. Fix it if real usage shows it causes friction, rather than
building hit-slop handling speculatively.

**Rationale:** Personal single-user tool; the cost of a missed tap is
re-tapping, not data loss. No specification change is needed until the edge
case is observed in practice.

**Affected documents:** None.

## Q-047: Review control widget shape

**Status:** Answered

**Question:** Should the top-level Review control render as an Android
`Switch` (the standard list-row toggle widget) or a two-state toggle button?

**Recommended answer:** A two-state toggle button. `Switch` is conventionally a
list-row control on both Android and iOS; the Review control lives in the top
app bar, not a list row.

**Answer:** Agreed — a two-state toggle button, not a `Switch`.

**Rationale:** Matches the control's location. The binary Review model itself
is unchanged; only the widget shape is clarified for implementation.

**Affected documents:**

- `docs/superpowers/specs/2026-07-18-pocket-editor-design.md` (Reader
  Experience top bar wording)
- `docs/adr/0001-local-first-overlay-reader.md` (Review control wording)

## Q-048: Draft persistence must cover system back navigation

**Status:** Answered

**Question:** The signal and edit composers must never lose a draft to an
outside tap. On Android, does that invariant also need to explicitly cover the
system back gesture/button, since it dismisses a modal presentation by default
the same way a scrim tap does?

**Answer:** Yes. Extend the non-dismissible draft behavior to explicitly cover
back navigation, not only outside taps.

**Rationale:** Back navigation is a first-class Android dismissal path with no
iOS equivalent; leaving it unstated risks an implementation that silently
discards a draft on back-press while correctly guarding against outside taps.

**Affected documents:**

- `docs/superpowers/specs/2026-07-18-pocket-editor-design.md` (signal/edit
  editor sections, MVP acceptance criteria)
- `docs/adr/0001-local-first-overlay-reader.md` (interaction model, reader and
  review interactions)

## Q-049: Line-height scaling behavior

**Status:** Answered

**Question:** Should the reader's designed line height be a fixed absolute
value, or a fixed ratio relative to text size?

**Recommended answer:** A fixed ratio relative to text size. An absolute line
height paired with Android's font-scaling support will start colliding with
glyphs once a reader raises text size toward the largest accessibility steps.

**Answer:** Agreed — line height is a fixed ratio to text size, not a fixed
constant.

**Rationale:** Keeps the "designed line height" intent while making it actually
compatible with the font-scaling requirement already in the specification.

**Affected documents:**

- `docs/superpowers/specs/2026-07-18-pocket-editor-design.md`
- `docs/adr/0001-local-first-overlay-reader.md`

## Q-050: Appearance theme control simplified to Light/Dark

**Status:** Answered; recommendation narrowed

**Question:** Should Appearance keep the three-way `System`/`Light`/`Dark`
theme picker, given that a literal reading of Apple's HIG discourages an
app-specific appearance override (a bullet judged inapplicable to Android in
Q-029's review)?

**Answer:** Simplify to `Light`/`Dark` only; drop `System`. Present it as a
two-state switch. Unlike the top-level Review control, this one is a
settings-style control in a list row, which is exactly where a `Switch` is the
right widget.

**Rationale:** Two states are enough for this app's actual usage, and the
list-row Appearance setting is precisely the context Android/HIG both reserve
for a genuine switch — consistent with, not contradicting, Q-047's reasoning
for why the top-bar Review control is a button instead.

**Affected documents:**

- `docs/superpowers/specs/2026-07-18-pocket-editor-design.md` (Appearance
  section)
- `docs/adr/0001-local-first-overlay-reader.md` (reader appearance wording)

## Q-051: Final compiled specification approval

**Status:** Answered; approved

**Question:** After the section decisions and final clarifications were folded
into the compiled design, is the complete Pocket Editor specification approved
as the implementation source of truth?

**Answer:** Yes. The user reviewed the changed documents and approved the
specification.

**Rationale:** Product discovery is complete. Implementation planning may use
the approved specification without reopening settled product decisions.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- `docs/superpowers/specs/2026-07-18-pocket-editor-design.md`
- `docs/superpowers/plans/2026-07-18-pocket-editor-mvp.md`

## Q-052: Safe Yandex Disk writes without conditional upload

**Status:** Answered

**Question:** The official Yandex Disk REST upload API supports only
`overwrite=false` or unconditional `overwrite=true`, not revision-bound
conditional replacement. Should Pocket Editor weaken its no-silent-overwrite
guarantee to a best-effort metadata preflight, or add a cooperative book lock?

**Recommended answer:** Add `.pocket-editor.sync.lock`, atomically requested
with `overwrite=false`. Verify a random nonce, refresh and merge remote state
while holding it, verify the nonce before every overwrite, and release only the
owned lock. Require AI-agent writers to follow the same protocol. Never
auto-expire a lock; breaking a stale lock is explicit and forces full refresh
and reacquisition.

**Answer:** Use the recommended cooperative lock.

**Rationale:** A metadata preflight has an unavoidable check/upload race and can
silently replace a concurrent change. The lock adds one transient service file
but preserves the safety guarantee for all cooperative writers using supported
Yandex operations.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- `docs/superpowers/specs/2026-07-18-pocket-editor-design.md`
- `docs/superpowers/plans/2026-07-18-pocket-editor-mvp.md`

## Q-053: Durable merge bases and explicit stale-lock breaking

**Status:** Answered

**Question:** Task 7 needs the complete base review document for a real
three-way merge after process death, but Room currently stores only its hash and
revision. It also needs an operation for user-confirmed removal of a foreign
stale lock. Which minimal interfaces preserve the approved safety invariants?

**Answer:** Add an app-private atomic `SyncBaseStore` containing the exact last
confirmed remote manifest/review documents, paired with Room hash/revision
metadata. If the base is missing or mismatched, block upload instead of guessing.
Add `breakObservedLock`, which re-reads the exact observed nonce immediately
before deletion; after breaking, require full refresh and new acquisition before
any upload.

**Rationale:** Hashes alone cannot reconstruct the third input to
`ReviewMerge.merge`. A two-way fallback would silently choose data. Ordinary
owned-lock release cannot remove a confirmed stale foreign lock, while deleting
without re-observation could target state the user never approved.

**Affected documents:**

- `docs/superpowers/specs/2026-07-18-pocket-editor-design.md`
- `docs/superpowers/plans/2026-07-18-pocket-editor-mvp.md`

## Q-054: Bundled typography, Markdown hierarchy, and search matches

**Status:** Answered; approved

**Question:** How should Pocket Editor correct the oversized, weakly
differentiated typography seen on the real Android emulator, ensure the intended
fonts actually ship with the app, represent Markdown heading levels, and make a
search hit identifiable at first glance?

**Recommended answer:** Bundle Literata for book content and Manrope for
application chrome, keep the in-app size control limited to book content, add a
designed H1-H6/prose scale, preserve heading levels in the renderer, give the
top-bar chapter and sync status distinct sizes, and highlight the matching
substring in every search excerpt as well as at the navigation destination.

**Answer:** Approved. Literata was selected after a Cyrillic comparison against
Lora and Vollkorn because it read more softly and clearly in both the prose test
and the screen specimen. Use Manrope for all UI. The user size control affects
only rendered book text. Support the prose Markdown set only; tables and fenced
code remain out of scope.

**Rationale:** The previous assets were actually DejaVu Serif despite their
generic filenames, Manrope was absent, every Markdown heading collapsed to one
oversized style, and the top-bar title and sync status shared one style. The
approved families have suitable Cyrillic coverage and OFL distribution terms.
Explicit hierarchy improves long-form reading without turning Appearance into a
general ebook typography editor. Highlighting the exact query match removes the
need to scan an excerpt to discover why it was returned.

**Affected documents:**

- `docs/adr/0001-local-first-overlay-reader.md`
- `docs/superpowers/specs/2026-07-18-pocket-editor-design.md`
- `docs/superpowers/specs/2026-07-19-reader-typography-search-design.md`
