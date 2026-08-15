# Real Yandex Disk E2E runbook

This release-blocking runbook must use real Yandex services. Automated fakes,
MockWebServer, or local lock tests cannot turn a row into PASS. Record no OAuth
tokens, manuscript excerpts, search queries, full remote paths, credentials, or
private filesystem paths in evidence.

## Prerequisites

- A release APK signed by the stable key, with its SHA-256 verified.
- A Yandex Android OAuth application registered for
  `net.inkyquill.pocketeditor` and that release certificate fingerprint.
- A dedicated Yandex test account and disposable test folder containing short,
  non-private Markdown fixtures plus expected review JSON.
- Two independent clients using the same test folder: preferably two physical
  Android devices, otherwise two independently installed/emulated clients with
  separate app data and stable device identities.
- A controlled way to edit canonical Markdown and review JSON externally.
- Network capture at the Pocket Editor recording-gateway boundary with bodies,
  headers, query values, and full paths disabled. Retain only method, approved
  host, redacted endpoint category, status/response metadata, client label, and
  timestamp.

Before starting, copy the fixture hashes and expected TOC into a private test
record. Confirm the request log is empty and both clients have synchronized
clocks.

## Eleven approved steps

1. Authenticate client A and select the dedicated folder. Folder selection must
   immediately create durable discovery work; there is no intermediate title,
   inclusion, or ordering screen. Evidence: signed-in UI state and redacted
   authorization category, never the token.
2. Verify discovery lists the folder once, derives the spine in exact manifest
   order or (for a raw folder) normalized path order, and shows a compact durable
   progress card beginning at `0 из N`. The library must remain usable.
3. Verify chapters download sequentially. Reader opens after the first
   `min(3, N)` chapters are cached while the compact card remains visible and the
   remaining chapters continue in the background with at most one active
   download. Record fixture hashes and the stable TOC without source text.
4. From Contents, open a later uncached chapter. Verify its body shows a loading
   skeleton, that chapter becomes the next download, and work then resumes from
   the earliest pending spine entry. Pause, continue, cancel, and retry after a
   transient offline response; confirmed cached rows must not download again.
5. After progress reaches `N из N`, disable all connectivity on client A and
   prove every chapter opens offline. Search source, create all four signal
   colors, optional/no-comment signals, a non-overlapping edit, and chapter
   note changes. Confirm clean mode contains only canonical rendered text.
6. Reconnect, reorder chapters only from the separate Contents action, and
   verify that it changes the stored spine without downloading source again.
   Never exercise reorder against the private `aria` fixture.
7. Open an unsaved signal or edit draft, force-stop the process, relaunch, and
   confirm exact draft restoration before Save/Cancel. Also interrupt an active
   progressive load and verify it resumes from durable progress after relaunch.
8. From the external client, change one review JSON record and canonical
   Markdown in ways that exercise clean merge, conflict, and stale/re-anchor
   behavior. Record only hashes and fixture identifiers.
9. With no lock present, start acquisition simultaneously on clients A and B.
   Confirm exactly one returned lock nonce is re-read as owner and the loser
   performs zero guarded uploads. Release only after owner verification.
10. Complete owner upload/refresh. Verify clean review merge, explicit conflict
   choices, source refresh, exact/stale/ambiguous anchor behavior, and no silent
   overwrite. Audit the redacted recording log: canonical `.md` upload count is
   exactly zero; writes are limited to the manifest, review sidecars, and transient
    cooperative lock. Confirm no sensitive values appear in the log.
11. Retain state on client A, install a newer APK signed by the same key using
    `adb install -r`, then verify cache, selected book/chapter/position, drafts,
    and authentication behavior before and after reconnect.

## Evidence table

PASS requires a dated evidence reference. FAIL/BLOCKED must name the observable
condition without secret values. Do not pre-fill PASS from automated tests.

| # | Gate | Status | Date | Evidence / blocker |
| --- | --- | --- | --- | --- |
| 1 | Authentication and direct folder selection | NOT RUN | 2026-08-15 | The 2026-07-20 session predates the direct progressive flow and cannot prove this gate |
| 2 | Durable discovery, order, and compact progress | NOT RUN | 2026-08-15 | Real authenticated progressive run unavailable |
| 3 | Initial readiness and sequential background load | NOT RUN | 2026-08-15 | Real authenticated progressive run unavailable |
| 4 | Priority, pause, continue, cancel, and retry | NOT RUN | 2026-08-15 | Real authenticated progressive run unavailable |
| 5 | Complete offline read/search/review | IN PROGRESS | 2026-07-20 | Historical two-chapter read/search, chapter note, one blue passage note, and clean/review toggle passed; progressive completion plus remaining review cases are required |
| 6 | Separate Contents reorder | NOT RUN | 2026-08-15 | Disposable authenticated fixture unavailable; private `aria` must not be reordered |
| 7 | Process-death draft and load resume | IN PROGRESS | 2026-07-20 | Historical saved notes survived force-stop; unsaved composer and progressive load resume remain required |
| 8 | External review/source changes | NOT RUN | 2026-07-19 | Dedicated test folder/client unavailable |
| 9 | Two-client lock race | BLOCKED | 2026-07-19 | Two independent authenticated clients unavailable |
| 10 | Upload/merge/conflict/re-anchor and zero canonical uploads | NOT RUN | 2026-07-19 | Real recording-gateway session unavailable |
| 11 | Signed in-place upgrade | NOT RUN | 2026-07-20 | Stable signing identity is available; Samsung in-place upgrade test remains |

## Cleanup

1. Confirm no client still owns `.pocket-editor.sync.lock`; an owner verifies
   its nonce immediately before delete. Never delete an unverified foreign lock.
2. Export only the redacted evidence summary, fixture hashes, and request counts.
3. Remove the disposable Yandex folder and external fixtures from the dedicated
   account after evidence retention.
4. Sign out both clients and confirm the local token vault is cleared.
5. Clear each test app's data only after upgrade evidence is captured.
6. Delete local packet captures or raw logs; they are not release artifacts.

## Progressive read-only `aria` load

Remote fixture: `Яндекс.Диск/writing/aria`. It contains private manuscript data.
This procedure may list and download only. It must not upload, delete, replace,
rename, reorder, acquire a write lock, or otherwise mutate this folder. Record
counts, redacted path basenames, timestamps, and hashes only; record no source text,
OAuth material, signed URLs, or raw response bodies.

| Gate | Required observation | Result |
| --- | --- | --- |
| Binder | Strict UTF-8 schema-v2 binder; 52 unique ID/path entries | NOT RUN |
| Initial | `0 из 52` advances to `3 из 52`; Reader opens chapter 1 | NOT RUN |
| Background | Count advances beyond 3 with max one active download | NOT RUN |
| Priority | A later uncached Contents row is the next downloaded path | NOT RUN |
| Resume | Connectivity/process interruption resumes without confirmed redownload | NOT RUN |
| Complete | `52 из 52`; all chapters open with connectivity disabled | NOT RUN |
| Write audit | Remote write request count for `aria` is exactly zero | NOT RUN |

The local read-only preflight found a schema-v1 binder. Its 52 unique chapter
IDs and paths, exact references, normalized path order, and strict UTF-8 content
are statically valid, but that does not satisfy the required schema-v2 Binder
gate. No authenticated app/device run was available, so every `aria` runtime row
remains `NOT RUN`.

Exercise chapter reorder only against a disposable folder. Never reorder `aria`.
