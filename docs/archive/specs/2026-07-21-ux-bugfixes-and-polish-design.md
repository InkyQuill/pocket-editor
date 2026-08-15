# UX bug fixes and design polish

**Date:** 2026-07-21
**Status:** Proposed

## Purpose

Fix defects found during a live emulator walkthrough of the current build
(rebuilt from a clean checkout, signed in for real against Yandex Disk, folder
`PocketEditor-E2E-2026-07-19`) and tighten several layout details. Every item
below is either a regression against an already-approved design
(`2026-07-20-review-mobile-gestures-design.md`,
`2026-07-20-import-feedback-and-inline-annotation-design.md`) or a new,
narrowly scoped addition. This spec does not reopen decisions those documents
already settled.

## 1. Local builds silently ship a broken Yandex client ID

**Regression / build correctness.** `.env` at the repo root holds the real
Yandex client ID, but nothing loads it. `app/build.gradle.kts:30-33` falls
back to the literal string `"unset"` when neither a Gradle property nor an OS
env var is set:

```kotlin
manifestPlaceholders["YANDEX_CLIENT_ID"] = providers.gradleProperty("YANDEX_CLIENT_ID")
    .orElse(providers.environmentVariable("YANDEX_CLIENT_ID"))
    .orElse("unset")
    .get()
```

A plain `./gradlew :app:assembleDebug` on a fresh checkout produces an APK
with `com.yandex.auth.CLIENT_ID=unset` and OAuth redirect scheme
`yxunset://` — sign-in opens a real Yandex login page but can never complete
the redirect back into the app. Confirmed by decompiling the manifest
(`aapt2 dump xmltree`) before and after sourcing `.env`.

Fix:

- Add a small `.env` loader at the top of `app/build.gradle.kts`: if
  `rootProject.file(".env")` exists, parse `KEY=VALUE` lines (ignore blank
  lines and `#` comments) and seed them as Gradle project properties before
  the existing lookup chain runs, e.g.:

  ```kotlin
  val dotEnv = rootProject.file(".env").takeIf { it.exists() }?.readLines()
      ?.mapNotNull { line ->
          val trimmed = line.trim()
          if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
          val (key, value) = trimmed.split("=", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
          key.trim() to value.trim()
      }?.toMap() ?: emptyMap()

  fun envOrProperty(key: String): Provider<String> =
      providers.gradleProperty(key)
          .orElse(providers.environmentVariable(key))
          .orElse(providers.provider { dotEnv[key] })
  ```

  Use `envOrProperty("YANDEX_CLIENT_ID")` in place of the current chain at
  line 30-32. Keep the existing env-var-first behavior intact — CI
  (`.github/workflows/android.yml:59,73`) supplies `YANDEX_CLIENT_ID` purely
  via `secrets.YANDEX_CLIENT_ID` as an environment variable and must keep
  working with no `.env` file present in the runner checkout (it isn't
  committed).
- Replace the final silent `"unset"` fallback with a build failure for
  release-facing tasks: `error("YANDEX_CLIENT_ID is not set. Add it to .env, pass -PYANDEX_CLIENT_ID=..., or set the env var.")`
  when the resolved value is blank **and** the requested task is
  `assembleRelease`/`bundleRelease`/`signed-release`. For `assembleDebug`,
  keep a working build but emit `logger.warn("YANDEX_CLIENT_ID unset — Yandex sign-in will not work in this build")`
  so local UI-only work isn't blocked but the gap is never silent.
- `.gitignore` already covers `.env` (line 15) — no change needed there.
- The parser above is intentionally simplistic (naive `KEY=VALUE` split, no
  quoting/escaping support) — it is not a general-purpose dotenv
  implementation, just enough to read a flat `YANDEX_CLIENT_ID=...` line.
  Document this in a code comment so nobody expands it into a full parser
  without cause.
- To be unambiguous about the release-build guard: the build fails **only
  when the resolved value is blank**, regardless of which task is running;
  the task-name check exists purely to decide whether a blank value is fatal
  (`assembleRelease`/`bundleRelease`/`signed-release`) or a warning
  (`assembleDebug`). A working, non-blank `.env` value must build
  successfully for every task, local or CI.

## 2. Selection annotation flyout overlaps body text and the platform menu

**Regression against the "cannot leave the screen" requirement in
`2026-07-20-import-feedback-and-inline-annotation-design.md`.**

`SelectionFlyout` (`app/src/main/java/net/inkyquill/pocketeditor/ui/review/SelectionFlyout.kt:36-67`)
is positioned from `ReaderScreen.kt:432-457` at a fixed `annotationGapPx`
(8dp, line 322) below the selection's bottom edge, using an *estimated*
width (220dp, line 317) until the real width is known via
`onGloballyPositioned` (line 449). There is no check against
`readerColumnBounds`/viewport bottom, unlike the composer's
`annotationPlacement` (`ReaderScreen.kt:747-760`), which does check
available space before choosing `Below`/`Above`/`PhoneSheet`/`TabletModal`.

Reproduced twice on-device: the flyout renders on top of the next paragraph,
and Android's native `Copy / Select all / Read aloud` menu renders on top of
the flyout, obscuring both the flyout icons and the paragraph beneath.

Fix:

- Give `SelectionFlyout`'s placement the same collision check
  `annotationPlacement` already does for the composer: if the flyout's
  height (once measured) plus `annotationGapPx` would extend past
  `readerColumnBounds.bottom`, flip it above the selection instead of below,
  reusing the existing `Below`/`Above` logic path rather than inventing a
  second one.
- Re-clamp vertically once the real flyout size is known via
  `onGloballyPositioned` (line 449), the same way horizontal re-clamping
  already happens for the composer card — do not rely solely on the
  pre-measurement 220dp estimate for the final placement decision.
- Increase `annotationGapPx` from 8dp to 16dp, and treat the top 56dp of the
  reader viewport (roughly the height of Android's default floating
  selection toolbar) as **unavailable space in the placement decision
  itself** while a selection is active — not just a passive clamp applied
  after the fact. Concretely, the collision check ported from
  `annotationPlacement` should test against
  `readerColumnBounds.top + 56.dp` as the effective top bound, so that if
  flipping the flyout above the selection would land inside that reserved
  band, the logic keeps it below (or falls back to a sheet on very small
  viewports) instead of flipping into a second collision. This is porting
  the *collision-check concept* from `annotationPlacement`
  (`ReaderScreen.kt:747-760`) to the flyout's own placement code — it is not
  a literal call to `annotationPlacement`, which is parameterized for the
  composer's `viewport`/`composerHeightPx` inputs, not the flyout's.

## 3. Search results don't navigate on tap

**Confirm-before-fix.** Code review shows `SearchScreen.kt:61` already wires
`Surface(onClick = { onResultSelected(hit.toNavigation()) })`, and the
callback chain — `ContentsPanel`'s `onSearchResult` param →
`PocketEditorRoot.kt:315-329` → `controller.openChapter(...)` — is intact.
Live testing (`adb shell input tap` on the result card, followed immediately
by a `uiautomator` accessibility-tree dump) found no clickable node at all at
the result card's bounds, and no navigation occurred, reproduced once.

Fix (investigation first, since the obvious wiring already looks correct):

- Add a Compose UI test that renders `SearchScreen` with a non-empty
  `results` list and calls
  `composeTestRule.onNodeWithText(hit.title).performClick()`, asserting
  `onResultSelected` fires with the expected `SearchNavigation`. This
  isolates whether the bug is in `SearchScreen` itself or in how it's hosted.
- If that test passes, instrument the live repro path: check whether the
  outer `ModalBottomSheet`/`ContentsPanel` scroll or drag-handle gesture
  detector is consuming the initial pointer-down before it reaches the
  nested `LazyColumn`'s `Surface.onClick` (a known category of Compose
  nested-scrollable-inside-sheet interaction bug). If confirmed, wrap the
  result `LazyColumn` with `Modifier.nestedScroll(rememberNestedScrollInteropConnection())`
  or move it out of the currently-dragging sheet's direct gesture area.
- Do not ship a UI redesign for this item until the root cause is confirmed;
  the existing click-through-to-navigate design is correct per spec.

## 4. "New passage signal" / "Edit passage" card has no horizontal padding and isn't edge-clamped

**Regression against the adjacent-card placement design in
`2026-07-20-import-feedback-and-inline-annotation-design.md`** ("card must
remain in the visible viewport"). The *placement strategy* (adjacent card,
flip above/below, sheet/modal fallback) is correct and already implemented in
`InlineAnnotationComposer.kt:26,68-91`; the bug is in the card's own chrome
for the `Below`/`Above` branch, not its placement logic.

Root cause: `InlineAnnotationComposer.kt:44-50` wraps content in a
`Surface(shape = large, tonalElevation = 8.dp, shadowElevation = 8.dp)` for
every placement, but only `PhoneSheet` (line 79:
`.padding(horizontal = 16.dp, vertical = 8.dp)`) and `TabletModal` (line 86:
`Box(...).padding(24.dp)` plus `widthIn(max = 420.dp)`) add outer padding.
`Below`/`Above` (line 73: `content(modifier)`) adds none, and the inner
`SignalComposer` (`SignalComposer.kt:34-36`) only applies
`padding(vertical = 4.dp)` — **zero horizontal padding** — so chips and text
touch the card's rounded edges, and when the card is positioned near the
left edge of the screen (selection near column start), it visually touches
the screen edge with the chapter title bleeding through behind it. Confirmed
on-device and by reading `SignalComposer.kt`/`EditComposer.kt` directly.

Fix:

- `SignalComposer.kt:34-36` and the identical `Column` in
  `EditComposer.kt:29-31` (same `modifier.fillMaxWidth().padding(vertical = 4.dp)`
  pattern in both files): change `padding(vertical = 4.dp)` to `padding(16.dp)`
  (all sides), matching the padding already used for `PhoneSheet`/`TabletModal`.
  Both composers call `.fillMaxWidth()` on the incoming modifier, so this
  padding is *inside* the card's clamped width, not additive to it — no
  overflow risk on its own, but see the next bullet for how it interacts
  with the outer clamp.
- `InlineAnnotationComposer.kt:71-74` (`Below`/`Above` branch): clamp the
  card's horizontal position so it never touches the screen edge — the
  offset math in `ReaderScreen.kt:478-503` should constrain the card's left
  edge to `readerColumnBounds.left + 12.dp` and its right edge to
  `readerColumnBounds.right - 12.dp`, using the card's real measured width
  (available post `onGloballyPositioned`, same pattern as item 2's fix). The
  clamp must treat the new 16dp-per-side composer padding as part of that
  measured width (32dp total is already included once the width is measured
  post-layout, since padding is inside the card) — the only thing to verify
  is that the FlowRow of signal-type chips (`SignalComposer.kt`) still wraps
  correctly at the clamped width; it uses `FlowRow`, which reflows instead of
  overflowing, so this should be a non-issue, but include it in manual
  verification (see Verification section).
- No scrim is needed. Set the `Surface` in `InlineAnnotationComposer.kt:44-50`
  to `color = MaterialTheme.colorScheme.surfaceContainerHigh` explicitly
  (it currently sets no `color` at all, so it falls back to `Surface`'s
  default, which is `colorScheme.surface` — not distinct enough from the
  reader background behind it). The existing `shadowElevation = 8.dp` plus
  this explicit color gives enough visual separation from the text
  underneath without turning this into a modal, which the approved design
  explicitly avoids for `Below`/`Above`.

## 5. Review FAB renders top-left instead of bottom-right

**Regression against `2026-07-20-review-mobile-gestures-design.md`**: "A
circular Review FAB sits at the lower right while Review is enabled... The
FAB has a 44dp minimum touch target... Remove the phone EdgeControl; the FAB
is the only phone shortcut to the Review panel."

Currently implemented in `AdaptiveReaderScaffold.kt:86-102`:

```kotlin
TooltipBox(
    state = reviewTooltipState,
    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
    tooltip = { PlainTooltip { Text("Open review panel") } },
    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
) {
    FloatingActionButton(
        onClick = onExpandReview,
        modifier = Modifier.size(48.dp).semantics { contentDescription = "Open review panel" },
    ) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}
```

On-device this renders at the **top-left**, directly over the Contents
hamburger button, confirmed and reproduced by toggling Review on (via the
top-bar pill) without opening the panel and observing the chevron button
appear top-left instead of bottom-right.

**Root cause: hypothesis, not confirmed.** The working theory is that
`TooltipBox` measures itself against the space it's given for its own
tooltip-popup positioning in a way that defeats the outer
`Modifier.align(Alignment.BottomEnd)` on the visible anchor content. This
has not been verified by stepping through `TooltipBox`'s own layout code —
a plain reading of the M3 `TooltipBox` source suggests it should just wrap
content in a `Box` and respect the outer alignment, so the actual cause
could instead be a `BoxScope`-alignment-propagation quirk one level up, or
something specific to how the `Popup` used internally by `TooltipBox`
interacts with layout on this Compose version. This distinction matters
less than it might: the fix below is a full replacement (drop `TooltipBox`,
rebuild the entry point) rather than a targeted patch, so it resolves the
symptom regardless of which exact mechanism is at fault. If a future
targeted fix is ever preferred over the replacement, confirm the mechanism
first by reproducing the misplacement in isolation (a minimal
`TooltipBox`-wrapped `FloatingActionButton` inside a plain
`Box(Modifier.fillMaxSize())`) before trusting this hypothesis.

Rather than patch the `TooltipBox` positioning in place, replace the whole
review-entry-point per this session's design update (single FAB, Lucide
icons):

### 5a. Remove the top-bar "Review" pill

Delete `ReviewToggle` (`ReaderScreen.kt:588-609`) and its call site inside
`ReaderTopBar` (line 583). The top bar keeps: Contents hamburger (left),
title/sync status (center), sync/lock icons (right, unchanged), and a new
small review-visibility toggle (5b).

### 5b. Review-visibility toggle moves to the top bar as an icon button

The existing `reviewEnabled`/`onToggleReview` wiring (controls only whether
annotations render inline — must stay independent of panel visibility, per
`2026-07-20-review-mobile-gestures-design.md`'s "State independence"
section) gets a new, smaller control in the top bar where the pill used to
be: an `IconButton` (44dp min touch target) using Lucide `Eye`/`EyeOff` (icon
swaps with `reviewEnabled` state), `contentDescription = if (enabled) "Review mode on" else "Review mode off"`,
same semantics as the current `ReviewToggle` (`ReaderScreen.kt:596-602`)
minus the pill chrome.

### 5c. One Review FAB, phone and tablet portrait, bottom-right

New `ReviewFab` composable in `AdaptiveReaderScaffold.kt`, replacing:

- the broken `TooltipBox`/`FloatingActionButton` block (lines 86-102), and
- the `TABLET_PORTRAIT` `EdgeControl("Expand review panel", EdgeSide.RIGHT, onExpandReview)`
  (line 186), unifying phone and tablet-portrait onto the same control per
  this session's request (tablet-landscape keeps its always-visible side
  panel and `SideRailControl`, unchanged, since the approved gestures spec
  says the FAB "is not required on tablet landscape").

```kotlin
val showFab = reviewEnabled && (policy.mode != ReaderLayoutMode.TABLET_PORTRAIT || !contentsExpanded)
if (showFab) {
    FloatingActionButton(
        onClick = { if (reviewExpanded) onDismissReview() else onExpandReview() },
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(16.dp)
            .size(56.dp)
            .semantics {
                contentDescription = if (reviewExpanded) "Close review panel" else "Open review panel"
            },
    ) {
        Icon(
            imageVector = if (reviewExpanded) LucideIcons.X else LucideIcons.MessageSquareText,
            contentDescription = null,
        )
    }
}
```

- Drop the `TooltipBox` wrapper entirely — a FAB with a clear icon, a
  content description, and a 56dp (Material default) touch target does not
  need a hover-only tooltip on a touch device, and removing it removes the
  measurement bug at its source rather than working around it.
- 56dp matches Material's standard `FloatingActionButton` size and clears
  the spec's 44dp minimum with room to spare.
- Icon doubles as open/close affordance by swapping on `reviewExpanded`, so
  users don't need a separate close control to find the FAB again.
- **Behavioral note vs. the control it replaces:** the old
  `EdgeControl("Expand review panel", EdgeSide.RIGHT, onExpandReview)`
  (`AdaptiveReaderScaffold.kt:186`) only rendered when
  `reviewEnabled && !contentsExpanded` on `TABLET_PORTRAIT`, i.e. it hid
  itself while Contents was open. The `showFab` guard above preserves that
  exact behavior for `TABLET_PORTRAIT` (so the FAB doesn't visually compete
  with the open Contents panel there) while imposing no such restriction on
  `PHONE`, where Contents opens as a full-screen `ModalBottomSheet` that
  already covers the FAB's position — so no `!contentsExpanded` check is
  needed there. This is a deliberate, not accidental, difference between
  the two modes; call it out in the PR description when this ships.

### 5d. Reader bottom padding so the FAB never blocks content or selection

Per this session's remediation idea: add real scrollable bottom padding to
the reader's content, not just a visual inset, so the last paragraph can
scroll fully clear of the FAB for selection. In `ReaderPane`'s content
container (`ReaderScreen.kt`, inside the scrollable/lazy column that renders
`ReaderDocumentBlock`s), add trailing space:
`Spacer(Modifier.height(96.dp))` (or equivalent `contentPadding = PaddingValues(bottom = 96.dp)`
if it's a `LazyColumn`) whenever `reviewEnabled` is true and the FAB is
visible for the current layout mode (phone or tablet portrait); `0.dp`
otherwise. 96dp covers the 56dp FAB plus 16dp margin plus a comfortable
24dp buffer above it.

### 5e. Icon library

No non-Material icon set exists today (`gradle/libs.versions.toml:26-27`
list only `androidx-compose-material-icons-core`/`-extended`). Library choice
is locked now, not deferred to the implementer:

- **Decision:** `com.composables:icons-lucide` (the Lucide-for-Compose port
  most commonly used in Compose projects today). Do not evaluate other
  Lucide ports at implementation time — the choice of *library* is settled
  by this spec; only the exact patch version is a mechanical detail.
- `gradle/libs.versions.toml`: add a `[versions]` entry
  (`icons-lucide = "<latest stable at implementation time>"`) and a
  `[libraries]` alias `icons-lucide = { module = "com.composables:icons-lucide", version.ref = "icons-lucide" }`.
  Pin to whatever is the current latest stable release when this is
  implemented — check Maven Central at that time; do not hold up
  implementation waiting for a specific version number written into this
  document, since it will be stale by the time this ships.
- `app/build.gradle.kts`: add `implementation(libs.icons.lucide)` in the
  `dependencies {}` block.
- Scope this pass to: the Review FAB icon (5c), the review-visibility toggle
  icon (5b), and the five `SelectionFlyout` icons
  (`SelectionFlyout.kt:94-107`, currently Material icons for Note/Change
  needed/Warning/Review/Edit). Do not migrate unrelated icons in this pass.
- **File overlap warning:** both this item and item 2 (flyout overlap fix)
  touch `SelectionFlyout.kt` — item 2 changes placement/positioning logic,
  this item changes only the `Icon(...)` calls' `imageVector` arguments.
  Sequence or coordinate these two changes to avoid a merge conflict; the
  icon swap is the lower-risk, mechanical half and can land either before or
  after the positioning fix without depending on it.

### 5f. FAB close is gated by the same `blocksDismissal` check as the panel X button

The FAB's `onClick` (5c) calls `onDismissReview()` when closing, which is
the exact same function gated by `reviewUiState.draftSession.blocksDismissal`
that item 6 addresses for the panel's own X button
(`PanelColumn`, `ReaderScreen.kt:818-820`). This means the FAB has the same
potential dead-button symptom: if it renders showing the close (`X`) icon
while a Signal/Edit draft is dirty, tapping it will silently no-op. Item 6's
fix (confirm `onDismissReview` always succeeds when there is no dirty inline
draft, independent of `NoteSaveStatus`) covers the FAB's close path too,
since both call the same function — implement and test them together, not
as two separate fixes. Add the FAB-close case explicitly to item 6's test
list (see item 6 and Verification below).

## 6. Review panel close (X) button unresponsive during a save failure; sync-lock error surfaces mid-flow

**New defect**, found by: opening Review, adding then deleting a test
annotation, leaving the chapter-note field untouched/empty, then trying to
close the panel.

`PanelColumn`'s close button (`ReaderScreen.kt:818-820`,
`FilledTonalIconButton(onClick = onClose)`) did not dismiss on tap while an
*empty* chapter-note field's autosave was showing "Save failed — retry".
Falling back to the system Back gesture instead additionally surfaced a
sync-lock conflict ("Action required — Lock bdbc5b63-487…") that resolved
itself on retry.

`onClose` traces to `onDismissReview` (`ReaderScreen.kt:193`):

```kotlin
onDismissReview = { if (!reviewUiState.draftSession.blocksDismissal) reviewExpanded = false },
```

`blocksDismissal` (`ui/review/ReviewDraft.kt:42-48`) is `isDirty` on an
**inline annotation draft** (a Signal/Edit being actively composed) — it has
nothing to do with chapter-note save state, which is tracked separately via
`NoteSaveStatus` (`ui/review/ReviewUiState.kt`).

**Stated invariant (this is the actual desired behavior, not just a test to
add):** the panel's X button, and the FAB's close action (item 5f), must
always dismiss the Review panel whenever there is no dirty Signal/Edit
draft — regardless of `NoteSaveStatus`, including `NoteSaveStatus.ERROR`. A
failed note save must surface only as the existing inline "Save failed —
retry" text; it must never block the user from closing the panel. This is
the concrete behavior the fix below has to guarantee, not merely "investigate
and see."

**Root cause: two distinct pathways set `NoteSaveStatus.ERROR`, and the
observed repro more likely hit the second one, not the first.**
Reading `EditorialReviewController.kt` end to end:

1. `chapterNoteFocusLost()` (lines 162-165) unconditionally calls
   `saveChapterNote(...)` on focus loss, whose failure path
   (`serialized(...)`, lines ~388-396) sets `NoteSaveStatus.ERROR` only if
   the save call itself throws.
2. Separately, `updateChapterContext(...)` (lines 165-179) derives
   `noteSaveStatus` from the reader's *ambient* `ReaderSyncState` via
   `ReaderSyncState.noteStatus()` (lines 410-414) —
   `SIGN_IN_REQUIRED` and **`ACTION_REQUIRED` both map to
   `NoteSaveStatus.ERROR`**, independent of whether a note save was ever
   attempted.

The on-device repro happened moments before an observed sync-lock conflict
(`ReaderSyncState.ACTION_REQUIRED`, "Lock bdbc5b63-487…") that resolved on
retry — almost certainly the local Yandex.Disk desktop client and the app
racing on the same synced folder. That strongly suggests pathway 2: the
empty chapter-note field's `noteSaveStatus` went to `ERROR` because the
*surrounding sync state* briefly went `ACTION_REQUIRED`, not because
`chapterNoteFocusLost()`'s own save call failed on an empty string. The two
fixes below address different things — do not treat either alone as fixing
the specific observed repro:

Fix:

- **Fixes the stated invariant, and directly fixes the observed repro:**
  confirm the X button's `onClose` and the FAB's close action are both
  wired to `onDismissReview` with no gate beyond
  `draftSession.blocksDismissal`, and that this gate is `false` whenever
  there is no active Signal/Edit draft — regardless of `NoteSaveStatus` or
  `ReaderSyncState`. Add a Compose test: open Review, drive
  `noteSaveStatus` to `ERROR` via the `ACTION_REQUIRED` pathway (not just a
  mocked save failure — cover both pathway 1 and pathway 2 above), tap the
  X, assert the panel closes; repeat for the FAB.
- **General robustness improvement, not proven to be the repro's cause:**
  stop autosaving a chapter note that's empty and was already empty —
  `chapterNoteFocusLost()` (`EditorialReviewController.kt:162-165`) should
  no-op when the current text is blank and the previously-saved text was
  also blank, instead of unconditionally calling `saveChapterNote(...)`.
  This removes one class of spurious save attempts, but on its own would
  **not** have prevented the observed repro if pathway 2 (ambient sync
  state) was the actual cause — ship it as a good independent improvement,
  not as the fix for this bug report.

## 7. Text-size preview doesn't visually change

**New defect.** `AppearanceScreen.kt:76` renders the sample sentence with
plain `MaterialTheme.typography.bodyLarge`, ignoring `appearance.textScale`
entirely:

```kotlin
Text("The quick brown fox crossed the moonlit courtyard.", style = MaterialTheme.typography.bodyLarge)
```

The percentage label just below it (lines 89-93) does update correctly on
each +/-/reset tap; the sample text does not, even though the real reader
text does scale correctly via `LocalReaderTypography` (`Theme.kt:145`,
`DefaultReaderTypography.scaled(textScale.coerceIn(.8f, 1.3f))`). Confirmed
by tapping "+" five times (100% → 130%) with no visible change to the sample
sentence.

Fix: change the sample `Text` at `AppearanceScreen.kt:76` to read from
`LocalReaderTypography.current` (the same reader body style used for actual
chapter text) so it inherits the live-scaled style already produced by
`Theme.kt:145`, instead of a hardcoded, unscaled Material style. Verify with
a Compose test that asserts the sample `Text`'s resolved `fontSize` changes
between two different `textScale` values.

## 8. Tighten Contents drawer chapter list

Current: `ContentsPanel.kt:118-134`'s `LazyColumn` has no
`verticalArrangement` (default 0dp inter-item spacing), but every row reads
as individually boxed and gapped because each `Surface`
(`heightIn(min = 48.dp)`, lines 121-125) carries its own background color
(`primaryContainer` when current, `surface` otherwise) against the drawer's
`background` color, plus internal
`Text(...).padding(horizontal = 14.dp, vertical = 12.dp)` (lines 127-131).
`ChapterRow` in `ReaderScreen.kt:627-668` repeats the same pattern for the
in-reader chapter list.

Design change — rows should read as one continuous list, with only the
current chapter visually set apart:

- Unselected rows: drop the per-row `Surface`/`shape` background entirely;
  render as a plain `Row`/`Text` against the drawer's own background.
- Current-chapter row only: keep the rounded `primaryContainer` pill —
  this is the one row that should still look boxed, since it's the "you are
  here" signal.
- Reduce row vertical padding from `12.dp` to `10.dp` (still clears the
  48dp `heightIn(min = 48.dp)` touch target).
- Add a 1dp `HorizontalDivider` (`MaterialTheme.colorScheme.outlineVariant`)
  **between rows only** — after each item except the last one, inside the
  `items(...)` lambda in `ContentsPanel.kt:119-134` (e.g. `if (chapter != book?.chapters?.lastOrNull()) HorizontalDivider(...)`
  after each row's `Surface`). Do not add a divider above the first chapter
  row or below the last one: the existing `HorizontalDivider` before the
  "Chapters" label (`ContentsPanel.kt:115`, separating the search section
  from the chapter list) already serves as the top boundary, and the list
  visually ends at "Manage books" (item 9's fix removes the gap there) with
  no divider needed in between. This divider, not `Arrangement.spacedBy`, is
  what removes the "gap" impression while keeping each row distinctly
  tappable.
- Apply the same treatment (drop per-row background for unselected rows,
  reduce padding, add inter-row dividers per the rule above) to `ChapterRow`
  in `ReaderScreen.kt:627-668` so the drawer and any in-reader chapter list
  look consistent. Verify this composable's exact current structure before
  editing — it was confirmed to exist and follow the same
  `Surface`/`heightIn(min = 48.dp)`/`padding(horizontal = 14.dp, vertical = 12.dp)`
  pattern as `ContentsPanel.kt`, but was not re-read line-by-line for this
  spec beyond that confirmation.

## 9. Reduce dead vertical space

- **Home/library screen** (`BooksScreen.kt`): `EmptyBooks` (~lines 206-212)
  uses `Arrangement.Center` inside a `weight(1f)` column. That's correct for
  a genuinely empty book library, but the effect on a signed-out screen
  (sign-in card already shown above it) is that "A quiet place for your
  stories" ends up vertically centered in a disproportionate amount of
  leftover space on tall phones. Change: give the empty-state block
  `Modifier.weight(1f, fill = false)` plus top alignment, with a fixed
  `Spacer(Modifier.height(48.dp))` immediately above it, so it sits 48dp
  below whatever content precedes it in the column — the sign-in card (plus
  its own trailing spacer) when signed out, or the top app bar directly
  when signed in with zero books. Either way, any genuine leftover vertical
  space ends up below the empty-state block instead of split evenly around
  it via `Arrangement.Center`.
- **Appearance screen** (`AppearanceScreen.kt:44-45`): root column is
  `Modifier.fillMaxSize()...verticalScroll(...)` with no bottom anchor, so
  short content (two cards) leaves a large trailing gap on tall screens.
  Change the root modifier from `fillMaxSize()` to `fillMaxWidth()`, keeping
  `verticalScroll` for accessibility/large-font overflow cases, so the
  column's height is intrinsic to its content rather than always filling
  the viewport.
- **Contents drawer** (`ContentsPanel.kt:118`): the chapter `LazyColumn` has
  `Modifier.weight(1f)`, reserving all remaining drawer height even for a
  2-chapter book — this pushes "Manage books" to the very bottom with a
  large empty gap above it. Drop `weight(1f)`, use `Modifier.fillMaxWidth()`
  only, so the list sizes to its content and "Manage books" sits directly
  below the chapters.

## Cross-cutting notes

**Suggested implementation order**, roughly most user-impact-and-risk first:

1. Item 1 (`.env` loader) — blocks sign-in entirely on any fresh local
   build; trivial and self-contained, do it first.
2. Item 3 (search click) — needs its investigation test written before
   anything else in this list depends on search working, and may turn out
   to be unrelated to all other items.
3. Item 5 + item 6 together (Review FAB replacement, and the shared
   `blocksDismissal`/dismissal-invariant fix from 5f) — these two are
   coupled (5f explicitly depends on 6's fix) and are the largest
   user-visible change in this spec; land them as one unit of work.
4. Item 2 and item 5e (both touch `SelectionFlyout.kt` — see the file-overlap
   note in 5e) — sequence these two changes deliberately rather than in
   parallel.
5. Item 4 (composer card padding/clamp) — independent, low-risk.
6. Items 7, 8, 9 — independent visual polish, any order, can be parallelized
   across different people/sessions since they touch disjoint files
   (`AppearanceScreen.kt`, `ContentsPanel.kt`/`ReaderScreen.kt`,
   `BooksScreen.kt`/`AppearanceScreen.kt` respectively).

**On `blocksDismissal` and the "state independence" guarantee (item 5f, 6):**
`2026-07-20-review-mobile-gestures-design.md` states `reviewExpanded`
controls only Review-panel visibility and must stay independent of other
toggles. `blocksDismissal` doesn't violate that principle directly, but it
does mean `reviewExpanded` cannot always be set to `false` on demand — a
dirty inline draft can keep the panel open against a direct user dismiss
action. That's very likely correct product behavior (silently discarding an
in-progress note or edit on an accidental tap would be worse), so this spec
does not propose relaxing `blocksDismissal`. What this spec does insist on:
that gate must depend only on inline-draft dirtiness, never on unrelated
state like `NoteSaveStatus`/`ReaderSyncState` (items 6, 5f) — so the
tension is between "dismiss can be legitimately blocked by a dirty draft"
(keep) and "dismiss must never be blocked by something the user can't see
or address from the panel" (fix). If a future session wants an explicit UX
treatment for the dirty-draft case (e.g. "You have unsaved changes — Discard
/ Keep editing" on the X tap instead of a silent no-op), that's new scope,
not covered here.

## Verification

- Rebuild locally with `.env` present and absent (both `assembleDebug` and a
  release-flavored dry run) to confirm the loader and the loud-failure path
  both work as specified (item 1).
- New Compose UI tests:
  - selection flyout and composer card never render past
    `readerColumnBounds` in a chapter with a short paragraph near the top
    and bottom of the viewport, and the composer's `FlowRow` of signal-type
    chips reflows rather than overflows at the clamped width (items 2, 4);
  - search-result click fires `onResultSelected` (item 3);
  - Review FAB renders at `Alignment.BottomEnd`, icon swaps with
    `reviewExpanded`, appears on phone and tablet-portrait only, and
    respects the `!contentsExpanded` guard on tablet-portrait specifically
    (item 5);
  - both the panel X button and the FAB's close action dismiss the Review
    panel while `NoteSaveStatus.ERROR` is active (via both the direct
    save-failure pathway and the `ReaderSyncState.ACTION_REQUIRED` pathway)
    and no Signal/Edit draft is open, and that dismissal is correctly
    blocked when a draft *is* dirty (items 6, 5f);
  - Appearance sample text's resolved font size changes with `textScale`
    (item 7).
- Manual on-device pass: last paragraph of a chapter scrolls fully clear of
  the FAB for selection (item 5d); Contents drawer and in-reader chapter
  list read as one continuous list with only the current chapter boxed,
  with dividers appearing only between rows (item 8).
- Screenshot diffs, before/after: Contents drawer and in-reader chapter list
  (item 8); Home/Appearance screens (item 9); the new Review FAB and top-bar
  visibility toggle in both open and closed states, phone and
  tablet-portrait (item 5); the selection flyout and composer card
  positioned near the top and bottom of a chapter (items 2, 4).
