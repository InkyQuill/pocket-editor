# CodeRabbit reader/review/contents validation

Date: 2026-08-16

Scope: `book/**`, `reader/**`, `review/**`, `ui/reader/**`, `ui/review/**`,
`ui/contents/**`, their focused tests, and verified `docs/user-guide.md` wording.

CodeRabbit findings were treated as untrusted issue reports. The containing commit is the
coherent reader-cluster fix; its SHA is reported in the task handoff because a commit cannot
embed its own final SHA.

## Disposition

| Candidate | Result | Evidence |
|---|---|---|
| New `ReviewDraft.Signal` type change with an empty comment may not mark the draft dirty | REJECTED | `ReviewDraftSession.isDirty` treats every signal with `savedType == null` as dirty. `ReviewDraftStateMachineTest.signal choice opens persistent optional comment draft and color remains changeable` changes `NOTE` to `REVIEW` with `comment == ""` and asserts `blocksDismissal`. Saved records retain independent `savedType` and `savedComment` comparisons. RED: N/A; the reported failure is already regression-covered. GREEN: focused unit suite passes. |
| Missing reading-position block falls to the final visible block | FIXED | `ReadingPositionClamp` now selects the exact visible block, otherwise the nearest visible block at or before the stored index, otherwise the first visible block. RED: the new hidden-front-matter regression failed at `ReadingPositionClampTest.kt:34` against the old final-block fallback. GREEN: focused unit suite passes. |
| `ReviewProjector.locateSlices` can return a negative local start | FIXED | A range beginning inside a UTF-8 code point produced `LocalRange(start = -1, ...)`. The locator now requires `start >= 0`. RED: the new UTF-8 boundary test failed at `ReviewProjectorTest.kt:46` without the guard. GREEN: focused unit suite passes. |
| Pending missing chapter uses a generic exception; duplicate availability restarts work | FIXED | Pending lookup now throws the existing `OpenChapterRemoved`, matching cached refresh semantics. `distinctUntilChanged()` prevents identical availability emissions from cancelling/restarting chapter lookup. RED: duplicate-state regression failed at `ReaderRepositoryTest.kt:91` without deduplication. GREEN: focused unit suite passes. |
| Stale Contents save can throw after the canonical spine changes | FIXED | `ContentsReorderState.orderForSave` validates the complete current ID set and returns `null` for a stale draft; `ContentsPanel` ignores such a stale click without `require`/`requireNotNull`. RED: the test-first batch failed compilation because the safe save contract did not exist. GREEN: stale/current order regression and focused suite pass. |
| Contents should suppress current-chapter auto-scroll while editing | REJECTED | The effect is intentionally keyed to book, current chapter, and canonical chapter IDs. A canonical-spine update recreates the reorder state, while `ContentsReorderStateTest.restore discards a draft when the canonical spine changed` protects identity. Adding an editing exception would regress the reviewed canonical-spine/current-chapter contract. RED: N/A. |
| Footnote `Popup` should be focusable | REJECTED | `focusable = false` is intentional reader interaction behavior from `fix: restore reader selection interactions`. `ReviewInteractionTest.forwardEndHandleCrossesOneCharacterSeamAndExcludesReaderChrome` opens the footnote, performs a cross-block selection, saves a signal, and asserts that the popup remains present throughout. Focus capture would break this proven gesture path; the popup already has an explicit Close action. RED: N/A. |
| Installation/selection performance and style micro-refactors | REJECTED | No correctness or measured performance failure was supplied. The affected reader selection path has extensive gesture regressions, so broad refactoring is outside this bounded fix. RED: N/A. |
| `ReviewMutationCoordinator` registry grows for process lifetime | FIXED (DOCUMENTED) | Lifetime retention is intentional: removing an apparently idle mutex can race an existing waiter and create two locks for one key. KDoc now records the safety rationale and the bounded locally-known book/review key set. Unsafe eviction was not added. RED: N/A (documentation-only concurrency invariant). |
| User guide uses stale navigation and progressive-load wording | FIXED | The guide now names `Управление книгами`, states that priority is followed by the first remaining pending chapter in book order, and clarifies that Cancel preserves downloaded cache while Continue resumes remaining chapters. GREEN: `DocumentationPolicyTest` and the focused suite pass. |
| Inline-string/localization cleanup | REJECTED | No scoped correctness defect required new runtime copy. UI strings and localization resources were left unchanged; verified wording changes are confined to the user guide. RED: N/A. |
| `BookDiscovery.replace` with the active path breaks manifest disjointness | FIXED | Same-path replacement now returns the manifest unchanged before ignored-path mutation. RED: the new idempotence test threw `IllegalArgumentException` at `BookDiscoveryTest.kt:98` against the old code. GREEN: focused unit suite passes. |

## Verification

Focused GREEN command:

```text
./gradlew testDebugUnitTest \
  --tests '*ReadingPositionClampTest' \
  --tests '*ReviewProjectorTest' \
  --tests '*ReaderRepositoryTest' \
  --tests '*ContentsReorderStateTest' \
  --tests '*BookDiscoveryTest' \
  --tests '*DocumentationPolicyTest'
```

Result: `BUILD SUCCESSFUL`.

Final repository gates are recorded in the task handoff after the shared multi-agent worktree
finishes compiling.
