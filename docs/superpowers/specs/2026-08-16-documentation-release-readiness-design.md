# Pocket Editor Documentation and Release-Readiness Design

**Date:** 2026-08-16

**Status:** Approved

**Primary language:** Russian, with a short English overview in the root README

## Context

Pocket Editor has reached a release-ready implementation after several feature
plans and runtime fix waves. The repository contains the decisions needed to
understand the product, but they are spread across a chronological discovery
log, an obsolete handoff, eleven design specifications, thirteen implementation
plans, a large ADR, and two operational runbooks. There is no root README or
license.

The documentation cleanup must make the current product understandable without
discarding the historical reasoning that produced it. It must also leave a
clear path from the public project page to installation, development,
architecture, testing, and release operations.

## Goals

- Add a useful, public-facing root README with Russian primary content and a
  concise English overview.
- Add an MIT license attributed to `Pavel Obruchnikov`.
- Replace stale handoff/specification navigation with a small set of current,
  task-oriented documents.
- Preserve old plans and original specifications in an explicit archive.
- Fold durable product and architecture decisions from the specifications into
  current documentation instead of requiring readers to reconstruct the
  product from chronological files.
- Keep secret handling, Yandex E2E safety, release signing, Conventional
  Commits, Release Please, and CI behavior accurate.
- Validate links, Markdown structure, build commands, and repository hygiene
  before publication.

## Non-goals

- Rewriting the Android implementation or changing product behavior.
- Publishing private credentials, OAuth data, manuscript contents, local
  absolute paths, or screenshots containing sensitive information.
- Replacing the existing ADR with a new architecture decision format.
- Deleting historical plans/specifications solely because Git retains history.
- Introducing a documentation generator or hosted documentation site.

## Chosen approach

Use layered current documentation backed by a historical archive.

The active documentation surface remains deliberately small:

```text
README.md
LICENSE
docs/
├── user-guide.md
├── architecture.md
├── development.md
├── testing.md
├── adr/
│   └── 0001-local-first-overlay-reader.md
├── runbooks/
│   ├── release.md
│   └── yandex-e2e.md
└── archive/
    ├── plans/
    ├── specs/
    └── project-history/
```

The original plans and specifications remain byte-for-byte useful historical
records after moving, but no active document may link to them as the current
source of truth. The current documents link to the ADR and runbooks instead.

## Document responsibilities

### `README.md`

The README is the project entry point, not an exhaustive manual. It contains:

1. project name and one-sentence purpose;
2. short English overview;
3. current feature summary;
4. privacy/local-first guarantees and Yandex Disk boundary;
5. supported Android baseline;
6. installation through GitHub Releases and sideloading;
7. a compact first-use flow;
8. development and verification commands;
9. release/CI summary;
10. links to the four current documents, ADR, schemas, and runbooks;
11. MIT license attribution.

It must not claim Play Store distribution, backend services, automatic remote
Markdown editing, or runtime gates that were not actually completed.

### `docs/user-guide.md`

This document explains the product from a reader/editor perspective:

- Yandex sign-in and selecting a Markdown folder;
- deterministic initial chapter order by path;
- compact progressive loading, first readable chapters, background completion,
  pause/continue/cancel/retry, and offline state;
- reader navigation, Contents, search, appearance, and book switching;
- review mode, passage selection, signals, comments, edits, drafts, and
  conflicts;
- separate chapter reordering workflow;
- local-copy removal and its guarantee not to delete Yandex Disk data;
- recovery guidance for unauthorized, offline, unavailable, and conflict
  states.

The guide uses current Russian UI labels and avoids implementation details.

### `docs/architecture.md`

This is the maintained technical overview distilled from the ADR and design
specifications. It documents:

- local-first boundaries and canonical Markdown policy;
- Yandex REST/OAuth gateway and remote-root model;
- Room, app-private book cache, exact sync bases, search index, and WorkManager;
- manifest, review sidecar, sync lock, outbox, conflict, and durable publication
  protocols;
- progressive loading and install/recovery/forget coordination;
- Markdown rendering, source-byte mapping, multi-block selection, anchors, and
  editorial overlay;
- mutation gates and crash-recovery invariants;
- security/privacy boundary and intentional non-goals.

The ADR remains the detailed decision record; this document is the current map
of the implemented system.

### `docs/development.md`

This document is the contributor/developer entry point:

- JDK 17, Android SDK, compile/target/min SDK values, and project layout;
- local `.env` and `local.properties` setup without secret values;
- Yandex public mobile client ID boundary;
- debug build, JVM tests, lint, Android-test compilation, emulator execution,
  and release build commands;
- Room schema export/migration expectations;
- Conventional Commit and PR-title rules;
- rules for preserving canonical Markdown and private fixtures;
- links to testing and operational runbooks.

### `docs/testing.md`

This document describes verification layers and evidence expectations:

- unit, lint, debug/release assembly, Android-test compile, connected emulator,
  screenshots, real Yandex E2E, offline/restart, deletion-residue audit, and
  signed upgrade checks;
- the current known-good local result: 565 JVM tests and a final connected run
  completing 225 tests with five intentional opt-in screenshot/minified skips
  and zero failures;
- safe separation of the disposable instrumentation AVD from the authenticated
  Yandex AVD;
- how to enable opt-in screenshot/minified smoke fixtures;
- what must be re-run for changes to storage, sync, reader selection, UI, and
  release configuration.

This replaces `docs/qa.md` as the active QA entry point. The discovery Q&A moves
to project history unchanged.

### Existing runbooks and ADR

- `docs/runbooks/release.md` remains the authoritative secret-safe local and
  GitHub release procedure. It must reflect the existing workflow, release
  environment secrets, Release Please behavior, version injection, signing,
  signature verification, checksum, and release asset upload.
- `docs/runbooks/yandex-e2e.md` remains the authoritative real-service procedure
  and must distinguish disposable remote fixtures from read-only `aria`.
- `docs/adr/0001-local-first-overlay-reader.md` remains active because its core
  decision still governs the implementation. Stale implementation-status
  wording may be corrected, but its decision history is not rewritten.

## Archive mapping

Move, preserving filenames:

- `docs/superpowers/plans/*` → `docs/archive/plans/`
- all prior design specs in `docs/superpowers/specs/*` →
  `docs/archive/specs/`, including this design after its implementation plan is
  complete;
- `docs/HANDOFF.md`, `docs/qa.md`, the historical final-review report, and any
  obsolete discovery/status document → `docs/archive/project-history/`.

`docs/backlog.md` remains active only if every item is still actionable and
accurate. Otherwise its useful items move into an explicit “Known limitations”
section in `docs/development.md`, and the old file is archived.

Add `docs/archive/README.md` explaining that archived files are historical,
may contain stale paths or commands, and are not current instructions.

## Data flow for documentation maintenance

1. A user starts at `README.md`.
2. User tasks route to `user-guide.md`; contributor tasks route to
   `development.md`; system questions route to `architecture.md`; verification
   questions route to `testing.md`.
3. Operationally sensitive procedures route to a runbook.
4. Rationale routes to the ADR.
5. Historical reconstruction routes to `docs/archive/`.

No current task should require opening a plan or old specification.

## Safety and error handling

- Never copy secret values from `.env`, `~/.keys`, GitHub environments, Android
  shared preferences, or Yandex OAuth storage into documentation.
- Refer to private paths symbolically (`~/.keys/...`) only where the existing
  runbook contract requires it.
- Refer to `aria` as a read-only verification fixture; never instruct users to
  reorder or mutate it remotely.
- Do not publish local emulator serials as stable prerequisites.
- If a historical claim conflicts with current code/workflows, current code and
  verified runtime evidence govern; record the current behavior in active docs
  and leave history untouched in the archive.
- Broken relative links, links to active `docs/superpowers/`, placeholder text,
  and references to obsolete branches are release blockers.

## Verification

The documentation task is complete only when:

- all expected archive moves are present and no plan/spec remains in the active
  `docs/superpowers/` tree;
- every relative Markdown link resolves;
- current docs contain no `TODO`, `TBD`, obsolete feature branch instruction,
  secret value, or private absolute machine path;
- README commands match Gradle and GitHub workflow configuration;
- release documentation matches `.github/workflows/android.yml`,
  `release-please-config.json`, `.release-please-manifest.json`, and
  `version.txt`;
- `LICENSE` is the standard MIT text with `Copyright (c) 2026 Pavel
  Obruchnikov`;
- `./gradlew test lint assembleDebug compileDebugAndroidTestKotlin` remains
  green after file moves;
- a fresh reviewer verifies information architecture, technical accuracy,
  archive completeness, link integrity, and secret hygiene.

## Implementation boundaries

The cleanup should be split into reviewable commits:

1. add current README, LICENSE, and maintained documentation;
2. update ADR/runbooks and cross-links;
3. archive plans/specifications/history and add archive navigation;
4. validate and fix links/hygiene.

No product or CI behavior changes are part of this documentation task.
