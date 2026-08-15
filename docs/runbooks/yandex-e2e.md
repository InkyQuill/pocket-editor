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

1. Authenticate client A and select the dedicated folder. Evidence: signed-in
   UI state and redacted authorization category, never the token.
2. Confirm import choices, generate `.pocket-editor.json`, finish full cache,
   and record fixture hashes plus stable TOC.
3. Disable all connectivity on client A and prove the app reports offline.
4. Offline, read multiple chapters, search source, create all four signal
   colors, optional/no-comment signals, a non-overlapping edit, and chapter
   note changes. Confirm clean mode contains only canonical rendered text.
5. Open an unsaved signal or edit draft, force-stop the process, relaunch, and
   confirm exact draft restoration before Save/Cancel.
6. From the external client, change one review JSON record and canonical
   Markdown in ways that exercise clean merge, conflict, and stale/re-anchor
   behavior. Record only hashes and fixture identifiers.
7. Restore connectivity on client A and wait for the scheduled refresh.
8. With no lock present, start acquisition simultaneously on clients A and B.
   Confirm exactly one returned lock nonce is re-read as owner and the loser
   performs zero guarded uploads. Release only after owner verification.
9. Complete owner upload/refresh. Verify clean review merge, explicit conflict
   choices, source refresh, exact/stale/ambiguous anchor behavior, and no silent
   overwrite.
10. Audit the redacted recording log: canonical `.md` upload count is exactly
    zero; writes are limited to the manifest, review sidecars, and transient
    cooperative lock. Confirm no sensitive values appear in the log.
11. Retain state on client A, install a newer APK signed by the same key using
    `adb install -r`, then verify cache, selected book/chapter/position, drafts,
    and authentication behavior before and after reconnect.

## Evidence table

PASS requires a dated evidence reference. FAIL/BLOCKED must name the observable
condition without secret values. Do not pre-fill PASS from automated tests.

| # | Gate | Status | Date | Evidence / blocker |
| --- | --- | --- | --- | --- |
| 1 | Authentication and import | PASS | 2026-07-20 | Signed release authenticated and selected the disposable two-chapter fixture |
| 2 | Manifest and full cache | PASS | 2026-07-20 | Two chapters imported and initial sync reached Saved; fixture hashes are recorded in `docs/HANDOFF.md` |
| 3 | Connectivity disabled | PASS | 2026-07-20 | Airplane mode enabled and Wi-Fi disabled; app reported Waiting to sync |
| 4 | Offline read/search/review | IN PROGRESS | 2026-07-20 | Read/search, chapter note, one blue passage note, and clean/review toggle passed; remaining colors, no-comment signal, and edit still required |
| 5 | Process-death draft restore | IN PROGRESS | 2026-07-20 | Saved chapter and passage notes survived force-stop; unsaved composer draft still required |
| 6 | External review/source changes | NOT RUN | 2026-07-19 | Dedicated test folder/client unavailable |
| 7 | Reconnect | NOT RUN | 2026-07-20 | Paused offline for workstation handoff |
| 8 | Two-client lock race | BLOCKED | 2026-07-19 | Two independent authenticated clients unavailable |
| 9 | Upload/merge/conflict/re-anchor | NOT RUN | 2026-07-19 | Real lock/fixture flow unavailable |
| 10 | Zero canonical uploads in log | NOT RUN | 2026-07-19 | Real recording-gateway session unavailable |
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

Exercise chapter reorder only against a disposable folder. Never reorder `aria`.
