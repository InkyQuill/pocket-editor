# Import feedback and inline annotation design

## Purpose

Make selecting a Yandex Disk folder feel responsive and understandable, and
separate creation of a note or edit on selected prose from the chapter-level
Review workspace.

## Folder browser

The selected folder is always previewed before import. The browser shows:

- a **Folders** group with folder names;
- a **Markdown chapters** group with its count, the first 5–8 filenames, and
  an overflow summary when more exist; and
- an **Other files** group with a count only, not a noisy list of unsupported
  filenames.

Tapping **Use this folder** immediately disables the action and changes its
label to **Reading files…**, with a progress indicator. This is a local
`FolderBrowserScreen` state, not a new navigation destination: the folder
preview remains visible while discovery, validation, and caching run. The next
state must be visibly one of:

- import confirmation with the discovered chapters;
- a recoverable error with Retry and Back; or
- an empty/invalid-folder explanation when the folder changed after preview.

The interface must never appear inert while discovery, validation, or caching
is running.

A folder with no Markdown chapters is already invalid at preview time: **Use
this folder** remains disabled and the preview says **No Markdown files in this
folder**, with the existing path navigation available to choose another folder.

## Selected-text annotation flow

The platform selection toolbar (Copy/Select all) remains available. The app
selection flyout is clamped horizontally to the reader viewport and cannot
leave the screen.

Tapping a signal colour or Pencil creates a draft and opens a separate,
selection-scoped composer. It is not the chapter Review panel.

### Phone

Render the composer as a small card adjacent to the selected text, preferably
below it. It contains the signal type where relevant, one text field, Save,
and Cancel. The card must remain in the visible viewport: flip above the
selection when the lower placement cannot fit; use a bottom sheet only when
neither adjacent placement can fit.

### Tablet

Use the same adjacent card where space permits. Otherwise use a compact,
independent modal, including in a narrow split-screen window. Do not use a
bottom sheet or open the Review drawer/sidebar to create a selected text
annotation.

The composer supports Signal comments and Edit replacements. Save and Cancel
operate on the existing draft controller; dirty drafts retain the existing
explicit-save-or-cancel protection.

## Chapter Review workspace

The Review panel is an overview of the chapter only:

- chapter note;
- navigation/listing of saved inline signals and edits;
- existing review actions such as locating or managing those records.

It does not render an active Signal or Edit composer. Selecting an existing
record may navigate to its text but does not conflate the chapter overview
with creation of a new annotation.

## Accessibility and interaction

- Every new action has a 44 dp minimum touch target and a content description.
- Composer focus moves to its text field when it opens; Back cancels only when
  the current draft can safely be dismissed, preserving existing dirty-draft
  safeguards.
- The anchored card must not cover the selected range or extend beyond the
  reader viewport.

## Verification

Instrumented tests prove:

- folder preview, in-progress feedback, success, empty, and error states;
- selecting a signal or Pencil opens the inline composer without opening
  or requiring the Review drawer/sheet;
- horizontal clamping and vertical fallback keep flyout and composer visible;
- Save and Cancel work, and the Review panel shows chapter overview rather
  than an active composer;
- platform selection actions remain available.
