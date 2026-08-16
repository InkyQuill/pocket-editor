# Documentation Release Readiness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the repository's release-ready behavior and verified runtime evidence into a small maintained documentation surface, preserve historical plans and specifications in an explicit archive, and enforce documentation integrity with an executable policy test.

**Architecture:** `README.md` is the public entry point and routes readers to four task-oriented maintained documents, the active ADR, schemas, and two operational runbooks. Historical discovery records, plans, specifications, and status documents move unchanged into `docs/archive/`, while `DocumentationPolicyTest` checks the boundary between current instructions and archived context, validates relative links, and prevents stale or sensitive content from returning to active documentation.

**Tech Stack:** Markdown, standard MIT license text, Kotlin/JUnit 5 repository policy tests, Gradle, GitHub Actions, Release Please, Android/Jetpack Compose project metadata.

## Global Constraints

- Primary language: Russian, with a short English overview in the root README.
- Do not rewrite the Android implementation or change product behavior.
- Do not publish private credentials, OAuth data, manuscript contents, local absolute paths, or screenshots containing sensitive information.
- Do not replace the existing ADR with a new architecture decision format.
- Do not delete historical plans or specifications; move them into the explicit archive.
- Do not introduce a documentation generator or hosted documentation site.
- No current task may require opening a plan or old specification.
- Never copy secret values from `.env`, `~/.keys`, GitHub environments, Android shared preferences, or Yandex OAuth storage into documentation.
- Refer to private paths symbolically (`~/.keys/...`) only where the existing runbook contract requires it.
- Refer to `aria` as a read-only verification fixture; never instruct users to reorder or mutate it remotely.
- Do not publish local emulator serials as stable prerequisites.
- Current code, workflow configuration, and verified runtime evidence govern whenever a historical claim conflicts with them.
- Broken relative links, links from current documentation to `docs/superpowers/`, placeholder markers, and obsolete branch instructions are release blockers.
- The standard MIT text must contain `Copyright (c) 2026 Pavel Obruchnikov`.
- Keep `docs/adr/0001-local-first-overlay-reader.md` active; correct stale status wording without rewriting its decision history.
- Keep `docs/runbooks/release.md` authoritative for secret-safe release operations and `docs/runbooks/yandex-e2e.md` authoritative for real-service verification.
- Work in the current branch. Do not create another branch or worktree.
- Preserve unrelated untracked `.agents/skills/`, `.claude/skills/`, and `artifacts/` content exactly as found.
- No product or CI behavior changes are part of this documentation task.

## File map

### Public and maintained documentation

- Create `README.md`: concise public project entry point, Russian first, with a short English overview and links into maintained documentation.
- Create `LICENSE`: standard MIT license attributed to Pavel Obruchnikov for 2026.
- Create `docs/user-guide.md`: user-facing product flow using current Russian UI labels and no implementation detail.
- Create `docs/architecture.md`: current technical map distilled from the active ADR and implemented code.
- Create `docs/development.md`: contributor setup, repository layout, build commands, project policies, and known limitations.
- Create `docs/testing.md`: verification layers, safe emulator separation, known-good evidence, and change-specific regression requirements.
- Create `app/src/test/java/net/inkyquill/pocketeditor/DocumentationPolicyTest.kt`: executable repository documentation contract.

### Active operational and decision records

- Modify `docs/adr/0001-local-first-overlay-reader.md`: mark the accepted decision as implemented, remove the private absolute example path, and replace obsolete discovery links with current/archive navigation.
- Modify `docs/runbooks/release.md`: remove stale MVP status rows and align commands, version injection, signing, Release Please, checksum, and upload descriptions with the existing workflow.
- Modify `docs/runbooks/yandex-e2e.md`: record the verified read-only 52-chapter `aria` load and deletion-residue audit while preserving the disposable-fixture boundary for all remote mutations.

### Archive

- Create `docs/archive/README.md`: explain archive purpose and its non-authoritative status.
- Move every pre-2026-08-16 file from `docs/superpowers/plans/` to `docs/archive/plans/`, preserving filenames.
- Move every pre-2026-08-16 file from `docs/superpowers/specs/` to `docs/archive/specs/`, preserving filenames.
- Move `docs/HANDOFF.md`, `docs/qa.md`, `docs/backlog.md`, and `docs/superpowers/2026-07-20-final-review-fix-report.md` to `docs/archive/project-history/`, preserving filenames.
- In the final task, move this plan and `docs/superpowers/specs/2026-08-16-documentation-release-readiness-design.md` to their matching archive directories, then remove the empty `docs/superpowers/` tree.

---

### Task 1: Add the public entry point and maintained documentation

**Files:**
- Create: `README.md`
- Create: `LICENSE`
- Create: `docs/user-guide.md`
- Create: `docs/architecture.md`
- Create: `docs/development.md`
- Create: `docs/testing.md`
- Create: `app/src/test/java/net/inkyquill/pocketeditor/DocumentationPolicyTest.kt`
- Read for facts only: `app/build.gradle.kts`
- Read for facts only: `gradle/libs.versions.toml`
- Read for facts only: `version.txt`
- Read for facts only: `schemas/README.md`
- Read for facts only: `docs/adr/0001-local-first-overlay-reader.md`
- Read for facts only: `docs/runbooks/release.md`
- Read for facts only: `docs/runbooks/yandex-e2e.md`

**Interfaces:**
- Consumes: repository-root discovery through `settings.gradle.kts`; Android values `compileSdk = 37`, `targetSdk = 36`, `minSdk = 26`; local version `0.1.0`; known-good evidence of 565 JVM tests and a connected run completing 225 tests with five intentional opt-in skips and zero failures.
- Produces: `DocumentationPolicyTest.repoFile(relativePath: String): Path`, `DocumentationPolicyTest.read(relativePath: String): String`, and the maintained-document list later tasks extend without changing names.

- [ ] **Step 1: Write the failing repository documentation contract**

Create `app/src/test/java/net/inkyquill/pocketeditor/DocumentationPolicyTest.kt` with the following initial contract. Use containment assertions for durable responsibilities instead of asserting complete prose, so wording can improve without weakening required content.

```kotlin
package net.inkyquill.pocketeditor

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DocumentationPolicyTest {
    private val currentDocuments = listOf(
        "README.md",
        "docs/user-guide.md",
        "docs/architecture.md",
        "docs/development.md",
        "docs/testing.md",
        "docs/adr/0001-local-first-overlay-reader.md",
        "docs/runbooks/release.md",
        "docs/runbooks/yandex-e2e.md",
        "schemas/README.md",
    )

    @Test
    fun `public and maintained documentation exists`() {
        (currentDocuments + "LICENSE").forEach { relativePath ->
            assertTrue(Files.isRegularFile(repoFile(relativePath)), "$relativePath must exist")
        }
    }

    @Test
    fun `readme is a Russian entry point with a concise English overview`() {
        val readme = read("README.md")

        listOf(
            "# Pocket Editor",
            "## English overview",
            "## Возможности",
            "## Приватность и данные",
            "## Требования",
            "## Установка",
            "## Первый запуск",
            "## Разработка и проверка",
            "## Релизы и CI",
            "## Документация",
            "## Лицензия",
            "docs/user-guide.md",
            "docs/architecture.md",
            "docs/development.md",
            "docs/testing.md",
            "docs/adr/0001-local-first-overlay-reader.md",
            "docs/runbooks/release.md",
            "docs/runbooks/yandex-e2e.md",
            "schemas/README.md",
        ).forEach { expected -> assertTrue(readme.contains(expected), expected) }
    }

    @Test
    fun `maintained documents cover the implemented product`() {
        val userGuide = read("docs/user-guide.md")
        listOf(
            "Войти через Яндекс",
            "Выбрать папку",
            "по пути",
            "Пауза",
            "Продолжить",
            "Отменить",
            "Повторить",
            "Содержание",
            "Поиск",
            "Оформление",
            "Рецензирование",
            "Изменить порядок глав",
            "Удалить с устройства",
        ).forEach { expected -> assertTrue(userGuide.contains(expected), expected) }

        val architecture = read("docs/architecture.md")
        listOf(
            "локальные данные",
            "канонический Markdown",
            "Yandex Disk REST API",
            "Room",
            "WorkManager",
            "merge base",
            "outbox",
            "sync lock",
            "прогрессивная загрузка",
            "source-byte mapping",
        ).forEach { expected -> assertTrue(architecture.contains(expected), expected) }

        val development = read("docs/development.md")
        listOf(
            "JDK 17",
            "compileSdk 37",
            "targetSdk 36",
            "minSdk 26",
            "local.properties",
            "YANDEX_CLIENT_ID",
            "./gradlew test",
            "./gradlew lint",
            "./gradlew assembleDebug",
            "./gradlew compileDebugAndroidTestKotlin",
            "./gradlew connectedDebugAndroidTest",
            "./gradlew assembleRelease",
            "Conventional Commits",
        ).forEach { expected -> assertTrue(development.contains(expected), expected) }

        val testing = read("docs/testing.md")
        listOf(
            "565 JVM",
            "225",
            "5",
            "0 ошибок",
            "инструментальный AVD",
            "авторизованный Yandex AVD",
            "скриншот",
            "удаления локальной копии",
            "подписанного обновления",
        ).forEach { expected -> assertTrue(testing.contains(expected), expected) }
    }

    @Test
    fun `license is the standard MIT license for Pavel Obruchnikov`() {
        val license = read("LICENSE")
        assertEquals("MIT License", license.lineSequence().first())
        assertTrue(license.contains("Copyright (c) 2026 Pavel Obruchnikov"))
        assertTrue(license.contains("Permission is hereby granted, free of charge"))
        assertTrue(license.contains("THE SOFTWARE IS PROVIDED \"AS IS\""))
    }

    private fun read(relativePath: String): String =
        Files.readAllBytes(repoFile(relativePath)).toString(Charsets.UTF_8)

    private fun repoFile(relativePath: String): Path =
        sequenceOf(Path.of("."), Path.of(".."))
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
            .resolve(relativePath)
            .normalize()
}
```

- [ ] **Step 2: Run the focused test and verify the RED state**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests net.inkyquill.pocketeditor.DocumentationPolicyTest
```

Expected: FAIL in `public and maintained documentation exists` because `README.md`, `LICENSE`, and the four maintained documents do not exist yet. If JUnit reports a different first failure, keep the test and fix only repository-root discovery before proceeding.

- [ ] **Step 3: Write `README.md` and `LICENSE`**

Write `README.md` in Russian, except for a 2–4 sentence `## English overview`. Use the exact tested headings. Keep the entry point compact and include these facts:

- Pocket Editor is a local-first Android reader and editorial overlay for Markdown books on Yandex Disk.
- Initial chapter order for a raw folder is deterministic normalized path order; users reorder later through the separate Contents action.
- The first three chapters become readable while the remaining chapters download sequentially in the background with a compact progress card.
- Canonical Markdown is never changed by the app; the app writes only the manifest, review sidecars, and a transient cooperative lock.
- Cached books remain readable offline, and removing a local copy does not delete Yandex Disk data.
- Android 8.0/API 26 is the minimum, GitHub Releases is the installation source, and distribution is sideload-only rather than Google Play.
- The first-use flow is sign in, select a Markdown folder, open the first cached chapters, then let background loading finish.
- Show `./gradlew test lint assembleDebug compileDebugAndroidTestKotlin` as the compact local gate and link detailed commands to `docs/development.md` and `docs/testing.md`.
- Explain that PR titles use Conventional Commits, Release Please creates release PRs/tags, and CI attaches a signed APK plus SHA-256 checksum.
- Link all paths asserted by the test and attribute the MIT license to Pavel Obruchnikov.

Write `LICENSE` as this exact standard MIT license text:

```text
MIT License

Copyright (c) 2026 Pavel Obruchnikov

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

- [ ] **Step 4: Write `docs/user-guide.md`**

Use this exact information architecture and current UI vocabulary:

```markdown
# Руководство пользователя Pocket Editor

## Вход и добавление книги
## Первая загрузка
## Чтение и навигация
## Рецензирование
## Изменение порядка глав
## Работа без сети
## Удаление локальной копии
## Восстановление после ошибок
```

Under these headings, explain deterministic path ordering; a compact `0 из N` progress card; the first `min(3, N)` readable chapters; sequential background completion; priority loading for an opened uncached chapter; `Пауза`, `Продолжить`, `Отменить`, and `Повторить`; Contents, book switching, source search, Appearance, and reading position; clean/review modes; passage signals, optional comments, edits, chapter notes, drafts, and visible conflicts; separate chapter reordering; offline limits; and the guarantee that `Удалить с устройства` removes the complete local cache/index/work state but never remote Yandex data. Recovery guidance must distinguish authorization required, no validated network, temporarily unavailable service, invalid remote data, and explicit sync conflict.

- [ ] **Step 5: Write `docs/architecture.md`**

Use this exact information architecture:

```markdown
# Архитектура Pocket Editor

## Границы системы
## Источники истины и локальные данные
## Yandex Disk и удалённый корень
## Хранение и фоновые задачи
## Форматы и протокол синхронизации
## Прогрессивная установка и удаление книги
## Markdown, выделение и редакторский слой
## Инварианты мутаций и восстановление после сбоя
## Безопасность и приватность
## Осознанные ограничения
## Подробное решение
```

State the implemented boundaries without copying chronological design prose. Cover canonical read-only Markdown; the Yandex ID/OAuth and Disk REST gateway; a normalized remote-root identifier; app-private cached book files; Room metadata/search/revisions/outbox; exact sync bases; WorkManager; manifest, one review sidecar per chapter, transient sync lock, guarded publication and explicit conflict handoff; progressive discovery, initial-ready threshold, durable resume, and serialized install/recovery/forget coordination; Markdown rendering and UTF-8 source-byte mapping; multi-block selection, resilient anchors, and editorial overlays; mutation gates that reject stale publishers and prevent forgotten-book resurrection; no backend/analytics/telemetry; and the active ADR as the detailed rationale.

- [ ] **Step 6: Write `docs/development.md`**

Use this exact information architecture:

```markdown
# Разработка Pocket Editor

## Требования
## Структура проекта
## Локальная конфигурация
## Сборка и быстрые проверки
## Room и схемы данных
## Правила изменений
## Conventional Commits и pull request
## Известные ограничения
## Связанные документы
```

Record JDK 17, compile SDK 37, target SDK 36, minimum SDK 26, Gradle wrapper use, the `app/src/main`, `app/src/test`, `app/src/androidTest`, `app/schemas`, `schemas`, and `.github/workflows` responsibilities, and secret-safe setup. Explain that `local.properties` points to the Android SDK; `.env` is ignored and supports the public mobile `YANDEX_CLIENT_ID`; signing secrets remain outside Git; no value from `.env` or `~/.keys` belongs in documentation. Provide each asserted Gradle command and what it proves. Require Room schema exports and migration tests for database changes. Preserve canonical Markdown and private fixtures, use disposable remote fixtures for write tests, keep `aria` read-only, and require Conventional Commit titles for pull requests to `main`. Move the still-actionable lint-cleanup backlog item into `## Известные ограничения` without preserving the stale numeric warning count.

- [ ] **Step 7: Write `docs/testing.md`**

Use this exact information architecture:

```markdown
# Проверка Pocket Editor

## Уровни проверки
## Быстрый локальный набор
## Инструментальные тесты на эмуляторе
## Визуальная и UX-проверка
## Реальный Yandex Disk E2E
## Offline, перезапуск и удаление локальной копии
## Подписанное обновление
## Что перезапускать после изменений
## Последний подтверждённый результат
```

Document JVM tests, lint, debug/release assembly, Android-test compilation, connected tests, screenshots, real Yandex E2E, offline/restart, deletion-residue inspection, and signed upgrade checks as distinct evidence layers. Name the disposable instrumentation AVD and authenticated Yandex AVD as separate roles, never as fixed serial numbers. Explain opt-in screenshot/minified fixtures and that skipped opt-in cases are intentional only when their required fixture is absent. Map storage/sync changes to unit plus connected persistence tests and real-service checks; reader selection/UI changes to focused connected tests plus screenshots; release configuration to workflow-policy tests, release assembly, signing verification, checksum, and in-place upgrade. Record the known-good 2026-08-16 result exactly: 565 JVM tests; connected run completed 225 tests, five intentional opt-in screenshot/minified skips, zero failures; real read-only `aria` load completed 52 chapters; deletion returned local counts to the pre-import baseline with no orphan work or files.

- [ ] **Step 8: Run the focused contract and existing release policy tests**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests net.inkyquill.pocketeditor.DocumentationPolicyTest \
  --tests net.inkyquill.pocketeditor.ReleaseWorkflowPolicyTest
```

Expected: PASS. The documentation test proves every new file and durable responsibility; the release policy test protects the existing workflow facts quoted by the docs.

- [ ] **Step 9: Review the public surface for unsupported claims**

Run:

```bash
rg -n "Play Store|Pocket Editor backend|автоматически изменяет Markdown|uploads canonical Markdown|runtime gate complete" README.md docs/user-guide.md docs/architecture.md docs/development.md docs/testing.md
```

Expected: no output. Replace any unsupported distribution, backend, canonical-write, or unverified-gate claim with the exact implemented boundary.

- [ ] **Step 10: Commit the maintained documentation**

```bash
git add README.md LICENSE docs/user-guide.md docs/architecture.md docs/development.md docs/testing.md app/src/test/java/net/inkyquill/pocketeditor/DocumentationPolicyTest.kt
git diff --cached --check
git commit -m "docs: add release-ready project documentation"
```

Expected: the staged diff contains only the seven new public/current files and `DocumentationPolicyTest.kt`; the commit succeeds without staging unrelated untracked content.

---

### Task 2: Align the ADR and operational runbooks with verified behavior

**Files:**
- Modify: `app/src/test/java/net/inkyquill/pocketeditor/DocumentationPolicyTest.kt`
- Modify: `docs/adr/0001-local-first-overlay-reader.md:3-17`
- Modify: `docs/adr/0001-local-first-overlay-reader.md:435-558`
- Modify: `docs/runbooks/release.md:1-195`
- Modify: `docs/runbooks/yandex-e2e.md:1-124`
- Modify: `docs/development.md`
- Read for facts only: `.github/workflows/android.yml`
- Read for facts only: `release-please-config.json`
- Read for facts only: `.release-please-manifest.json`
- Read for facts only: `version.txt`
- Read for facts only: `app/build.gradle.kts`
- Read for facts only: `app/src/main/java/net/inkyquill/pocketeditor/load/ProgressiveBookLoader.kt`
- Read for facts only: book-removal/recovery implementations and their focused tests under `app/src/main/` and `app/src/test/`

**Interfaces:**
- Consumes: `DocumentationPolicyTest.read(relativePath: String): String` and the maintained documents created by Task 1.
- Produces: active ADR/runbooks with no stale status, exact release workflow claims, an explicit read-only `aria` evidence record, and a current limitation recorded in `docs/development.md` before the old backlog is archived.

- [ ] **Step 1: Add failing accuracy checks for active decision and runbook documents**

Add these tests to `DocumentationPolicyTest`:

```kotlin
@Test
fun `active decision and release runbook describe the current implementation`() {
    val adr = read("docs/adr/0001-local-first-overlay-reader.md")
    assertTrue(adr.contains("Accepted and implemented"))
    assertTrue(adr.contains("../architecture.md"))
    assertTrue(adr.contains("../testing.md"))

    val runbook = read("docs/runbooks/release.md")
    listOf(
        "release-please-action",
        "v4.4.1",
        "release",
        "version.txt",
        "POCKET_EDITOR_VERSION_NAME",
        "POCKET_EDITOR_VERSION_CODE",
        "verify --verbose --print-certs",
        "app-release.apk.sha256",
        "gh release upload",
        "--clobber",
    ).forEach { expected -> assertTrue(runbook.contains(expected), expected) }
    assertFalse(runbook.contains("## MVP acceptance trace"))
}

@Test
fun `real service runbook preserves the aria read only boundary and verified evidence`() {
    val runbook = read("docs/runbooks/yandex-e2e.md")
    listOf(
        "52",
        "3 из 52",
        "последовательно",
        "Пауза",
        "Продолжить",
        "Отменить",
        "без сети",
        "ровно ноль",
        "не изменяет данные на Yandex Disk",
        "исходному состоянию",
    ).forEach { expected -> assertTrue(runbook.contains(expected), expected) }
}
```

Also add `import org.junit.jupiter.api.Assertions.assertFalse` and use `assertFalse` for negative assertions in the final implementation.

- [ ] **Step 2: Run the focused test and verify the RED state**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests net.inkyquill.pocketeditor.DocumentationPolicyTest
```

Expected: FAIL because the ADR still says implementation has not begun, the release runbook retains the stale MVP acceptance trace, and the `aria` table still reports the completed runtime gates as not run.

- [ ] **Step 3: Correct the active ADR without rewriting its decision history**

Make these bounded edits:

- Change the status paragraph to `Accepted and implemented. The compiled specification was approved by the user on 2026-07-18; this ADR remains the governing decision record for the current application.`
- Replace the private absolute manuscript path in Context with a generic representative Yandex Disk folder containing Markdown chapters and an optional manifest.
- Keep historical decision and consequence sections intact, but change future-tense implementation-completion language where it directly contradicts the current implementation.
- Replace `## Discovery Record` with `## Связанные документы` linking to `../architecture.md`, `../testing.md`, and `../archive/README.md`. Do not link to a current plan or specification.

- [ ] **Step 4: Align `docs/runbooks/release.md` with the existing workflow**

Retain its secret-safe signing procedure and symbolic `~/.keys/pocket-editor-release.jks` reference. Make these exact corrections:

- State that `.github/workflows/android.yml` verifies pull-request titles, runs `./gradlew test lint assembleDebug assembleRelease`, runs `connectedDebugAndroidTest` in the emulator job, and invokes `googleapis/release-please-action` v4.4.1 on pushes to `main`.
- Keep the one-time GitHub setting and `GITHUB_TOKEN` limitation already enforced by `ReleaseWorkflowPolicyTest`.
- State that the signed job uses the protected `release` environment, checks out the exact Release Please SHA/tag, injects `POCKET_EDITOR_VERSION_NAME` and `POCKET_EDITOR_VERSION_CODE`, rejects invalid versions, decodes the JKS only in the runner temporary directory, and removes it in an `always()` cleanup step.
- Use the exact verification spelling emitted by the workflow: `verify --verbose --print-certs`, `app-release.apk.sha256`, and idempotent `gh release upload ... --clobber`.
- Keep local signed build/upgrade instructions, but clearly distinguish them from the unsigned CI verification build.
- Remove the dated `## MVP acceptance trace` table. Link verification requirements to `../testing.md` and real-service steps to `yandex-e2e.md`.

- [ ] **Step 5: Update the real Yandex E2E evidence without authorizing remote writes**

Retain the eleven-step disposable-fixture procedure for mutation, lock, conflict, reorder, and signed-upgrade gates. Rewrite `## Progressive read-only aria load` as a dated 2026-08-16 evidence section:

- Identify `aria` only by its symbolic Yandex Disk location and as private/read-only.
- Replace the obsolete schema-v2 binder requirement with raw-folder discovery: 52 unique Markdown chapter paths and a deterministic normalized-path spine.
- Mark initial readiness PASS at `3 из 52`, sequential single-active-download background completion PASS, pause/continue/cancel/resume PASS, full 52-chapter offline opening PASS, and remote write audit PASS at exactly zero mutations.
- State that opening an uncached later chapter gives it priority and then resumes the earliest pending spine entry only if that behavior was captured in the verified run; otherwise leave this row as a separately named unverified gate rather than converting it to PASS.
- Add the local forget audit: after `Удалить с устройства`, book files, sync bases, search rows, revisions, outbox/import/pending rows, and active WorkManager jobs for the imported book were absent; global counts returned to the pre-import baseline; the existing cached book and OAuth session remained.
- State explicitly that the removal action affects local state only and does not mutate Yandex Disk.
- Keep chapter reorder prohibited for `aria`; direct reorder testing to a disposable remote fixture.

- [ ] **Step 6: Preserve the actionable backlog item in current development guidance**

In `docs/development.md` under `## Известные ограничения`, keep one actionable item: reduce Android lint warnings in focused changes without mixing dependency upgrades. Do not state a warning/hint count, because the report is generated and the old count can drift.

- [ ] **Step 7: Run focused documentation and release workflow tests**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests net.inkyquill.pocketeditor.DocumentationPolicyTest \
  --tests net.inkyquill.pocketeditor.ReleaseWorkflowPolicyTest
```

Expected: PASS. The documentation test sees implemented ADR status, exact release markers, and the verified `aria` boundary; the existing workflow-policy test still passes unchanged.

- [ ] **Step 8: Check the active files for stale status and sensitive local context**

Run:

```bash
rg -n "implementation has not begun|MVP acceptance trace|/home/inky/|feat/pocket-editor-mvp|fix/review-issues-4-5" docs/adr docs/runbooks docs/development.md
```

Expected: no output. Symbolic `~/.keys/...` references in the release runbook remain permitted.

- [ ] **Step 9: Commit the active-document alignment**

```bash
git add app/src/test/java/net/inkyquill/pocketeditor/DocumentationPolicyTest.kt docs/adr/0001-local-first-overlay-reader.md docs/runbooks/release.md docs/runbooks/yandex-e2e.md docs/development.md
git diff --cached --check
git commit -m "docs: align architecture and release runbooks"
```

Expected: the commit contains only the active ADR/runbook/development corrections and their policy assertions.

---

### Task 3: Archive historical plans, specifications, and project records

**Files:**
- Modify: `app/src/test/java/net/inkyquill/pocketeditor/DocumentationPolicyTest.kt`
- Create: `docs/archive/README.md`
- Move: `docs/HANDOFF.md` → `docs/archive/project-history/HANDOFF.md`
- Move: `docs/qa.md` → `docs/archive/project-history/qa.md`
- Move: `docs/backlog.md` → `docs/archive/project-history/backlog.md`
- Move: `docs/superpowers/2026-07-20-final-review-fix-report.md` → `docs/archive/project-history/2026-07-20-final-review-fix-report.md`
- Move: `docs/superpowers/plans/2026-07-18-pocket-editor-mvp.md` → `docs/archive/plans/2026-07-18-pocket-editor-mvp.md`
- Move: `docs/superpowers/plans/2026-07-19-reader-typography-search.md` → `docs/archive/plans/2026-07-19-reader-typography-search.md`
- Move: `docs/superpowers/plans/2026-07-20-import-feedback-and-inline-annotation.md` → `docs/archive/plans/2026-07-20-import-feedback-and-inline-annotation.md`
- Move: `docs/superpowers/plans/2026-07-20-review-panel-controls.md` → `docs/archive/plans/2026-07-20-review-panel-controls.md`
- Move: `docs/superpowers/plans/2026-07-21-ux-bugfixes-and-polish.md` → `docs/archive/plans/2026-07-21-ux-bugfixes-and-polish.md`
- Move: `docs/superpowers/plans/2026-07-22-russian-only-interface.md` → `docs/archive/plans/2026-07-22-russian-only-interface.md`
- Move: `docs/superpowers/plans/2026-07-23-review-record-cards.md` → `docs/archive/plans/2026-07-23-review-record-cards.md`
- Move: `docs/superpowers/plans/2026-07-26-durable-import-library-sync.md` → `docs/archive/plans/2026-07-26-durable-import-library-sync.md`
- Move: `docs/superpowers/plans/2026-07-27-stable-review-composer.md` → `docs/archive/plans/2026-07-27-stable-review-composer.md`
- Move: `docs/superpowers/plans/2026-08-14-bidirectional-sync-and-chapter-replacement.md` → `docs/archive/plans/2026-08-14-bidirectional-sync-and-chapter-replacement.md`
- Move: `docs/superpowers/plans/2026-08-14-contents-and-markdown-rendering.md` → `docs/archive/plans/2026-08-14-contents-and-markdown-rendering.md`
- Move: `docs/superpowers/plans/2026-08-14-multi-block-reader-selection.md` → `docs/archive/plans/2026-08-14-multi-block-reader-selection.md`
- Move: `docs/superpowers/plans/2026-08-15-progressive-yandex-book-loading.md` → `docs/archive/plans/2026-08-15-progressive-yandex-book-loading.md`
- Move: all eleven pre-2026-08-16 design files from `docs/superpowers/specs/` to the same filename under `docs/archive/specs/`
- Leave for Task 4: `docs/superpowers/plans/2026-08-16-documentation-release-readiness.md`
- Leave for Task 4: `docs/superpowers/specs/2026-08-16-documentation-release-readiness-design.md`

**Interfaces:**
- Consumes: current documentation from Tasks 1–2, which no longer uses plans/specifications as source-of-truth links.
- Produces: `docs/archive/README.md` plus historical plan/spec/project-history trees; only this implementation plan and its approved design remain temporarily under `docs/superpowers/` for the final execution task.

- [ ] **Step 1: Add failing archive-completeness checks**

Add these members and test to `DocumentationPolicyTest`:

```kotlin
private val historicalPlans = setOf(
    "2026-07-18-pocket-editor-mvp.md",
    "2026-07-19-reader-typography-search.md",
    "2026-07-20-import-feedback-and-inline-annotation.md",
    "2026-07-20-review-panel-controls.md",
    "2026-07-21-ux-bugfixes-and-polish.md",
    "2026-07-22-russian-only-interface.md",
    "2026-07-23-review-record-cards.md",
    "2026-07-26-durable-import-library-sync.md",
    "2026-07-27-stable-review-composer.md",
    "2026-08-14-bidirectional-sync-and-chapter-replacement.md",
    "2026-08-14-contents-and-markdown-rendering.md",
    "2026-08-14-multi-block-reader-selection.md",
    "2026-08-15-progressive-yandex-book-loading.md",
)

private val historicalSpecs = setOf(
    "2026-07-18-pocket-editor-design.md",
    "2026-07-19-reader-typography-search-design.md",
    "2026-07-20-import-feedback-and-inline-annotation-design.md",
    "2026-07-20-review-mobile-gestures-design.md",
    "2026-07-21-ux-bugfixes-and-polish-design.md",
    "2026-07-22-russian-only-interface-design.md",
    "2026-07-23-review-record-cards-design.md",
    "2026-07-26-library-import-offline-sync-design.md",
    "2026-07-27-stable-review-composer-design.md",
    "2026-08-14-bidirectional-book-sync-and-reader-selection-design.md",
    "2026-08-15-progressive-yandex-book-loading-design.md",
)

@Test
fun `historical project records are preserved in the archive`() {
    historicalPlans.forEach { assertTrue(Files.isRegularFile(repoFile("docs/archive/plans/$it")), it) }
    historicalSpecs.forEach { assertTrue(Files.isRegularFile(repoFile("docs/archive/specs/$it")), it) }
    listOf(
        "docs/archive/README.md",
        "docs/archive/project-history/HANDOFF.md",
        "docs/archive/project-history/qa.md",
        "docs/archive/project-history/backlog.md",
        "docs/archive/project-history/2026-07-20-final-review-fix-report.md",
    ).forEach { assertTrue(Files.isRegularFile(repoFile(it)), it) }

    historicalPlans.forEach {
        assertFalse(Files.exists(repoFile("docs/superpowers/plans/$it")), it)
    }
    historicalSpecs.forEach {
        assertFalse(Files.exists(repoFile("docs/superpowers/specs/$it")), it)
    }
    listOf("docs/HANDOFF.md", "docs/qa.md", "docs/backlog.md").forEach {
        assertFalse(Files.exists(repoFile(it)), it)
    }
}
```

- [ ] **Step 2: Run the focused test and verify the RED state**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests net.inkyquill.pocketeditor.DocumentationPolicyTest
```

Expected: FAIL because `docs/archive/README.md` and the archive destinations do not exist.

- [ ] **Step 3: Create the archive directories and move history with Git**

Run these explicit, reviewable moves:

```bash
mkdir -p docs/archive/plans docs/archive/specs docs/archive/project-history
git mv docs/HANDOFF.md docs/archive/project-history/HANDOFF.md
git mv docs/qa.md docs/archive/project-history/qa.md
git mv docs/backlog.md docs/archive/project-history/backlog.md
git mv docs/superpowers/2026-07-20-final-review-fix-report.md docs/archive/project-history/2026-07-20-final-review-fix-report.md
for file in docs/superpowers/plans/2026-07-18-pocket-editor-mvp.md docs/superpowers/plans/2026-07-19-reader-typography-search.md docs/superpowers/plans/2026-07-20-import-feedback-and-inline-annotation.md docs/superpowers/plans/2026-07-20-review-panel-controls.md docs/superpowers/plans/2026-07-21-ux-bugfixes-and-polish.md docs/superpowers/plans/2026-07-22-russian-only-interface.md docs/superpowers/plans/2026-07-23-review-record-cards.md docs/superpowers/plans/2026-07-26-durable-import-library-sync.md docs/superpowers/plans/2026-07-27-stable-review-composer.md docs/superpowers/plans/2026-08-14-bidirectional-sync-and-chapter-replacement.md docs/superpowers/plans/2026-08-14-contents-and-markdown-rendering.md docs/superpowers/plans/2026-08-14-multi-block-reader-selection.md docs/superpowers/plans/2026-08-15-progressive-yandex-book-loading.md; do
  git mv "$file" docs/archive/plans/
done
for file in docs/superpowers/specs/2026-07-18-pocket-editor-design.md docs/superpowers/specs/2026-07-19-reader-typography-search-design.md docs/superpowers/specs/2026-07-20-import-feedback-and-inline-annotation-design.md docs/superpowers/specs/2026-07-20-review-mobile-gestures-design.md docs/superpowers/specs/2026-07-21-ux-bugfixes-and-polish-design.md docs/superpowers/specs/2026-07-22-russian-only-interface-design.md docs/superpowers/specs/2026-07-23-review-record-cards-design.md docs/superpowers/specs/2026-07-26-library-import-offline-sync-design.md docs/superpowers/specs/2026-07-27-stable-review-composer-design.md docs/superpowers/specs/2026-08-14-bidirectional-book-sync-and-reader-selection-design.md docs/superpowers/specs/2026-08-15-progressive-yandex-book-loading-design.md; do
  git mv "$file" docs/archive/specs/
done
```

Expected: `git status --short` reports renames, not deletions plus newly authored replacements. The only files left under `docs/superpowers/` are this plan and its approved design spec.

- [ ] **Step 4: Write `docs/archive/README.md`**

Use this exact information architecture:

```markdown
# Архив документации Pocket Editor

## Назначение
## Состав архива
## Как пользоваться архивом
## Текущая документация
```

State that archived files preserve chronological reasoning and may contain stale commands, branches, paths, status, or assumptions; they are not current instructions. Describe `plans/`, `specs/`, and `project-history/`. Route current product, development, architecture, testing, operational, and decision questions back to `../../README.md`, `../user-guide.md`, `../development.md`, `../architecture.md`, `../testing.md`, `../runbooks/`, and `../adr/0001-local-first-overlay-reader.md`.

- [ ] **Step 5: Verify archive identity and temporary active-plan boundary**

Run:

```bash
git diff --summary
find docs/superpowers -type f -print | sort
```

Expected: summary shows the listed historical files as renames; the `find` output contains exactly:

```text
docs/superpowers/plans/2026-08-16-documentation-release-readiness.md
docs/superpowers/specs/2026-08-16-documentation-release-readiness-design.md
```

- [ ] **Step 6: Run the archive contract**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests net.inkyquill.pocketeditor.DocumentationPolicyTest
```

Expected: PASS. Historical files exist at the exact archive destinations and no longer exist at their old active paths.

- [ ] **Step 7: Commit the historical archive**

```bash
git add docs/archive docs/superpowers docs/HANDOFF.md docs/qa.md docs/backlog.md app/src/test/java/net/inkyquill/pocketeditor/DocumentationPolicyTest.kt
git diff --cached --check
git commit -m "docs: archive historical project records"
```

Expected: the commit contains archive navigation, exact renames, and archive policy assertions; it does not yet move the currently executing design/plan pair.

---

### Task 4: Enforce link, hygiene, secret, and final archive policy

**Files:**
- Modify: `app/src/test/java/net/inkyquill/pocketeditor/DocumentationPolicyTest.kt`
- Move: `docs/superpowers/plans/2026-08-16-documentation-release-readiness.md` → `docs/archive/plans/2026-08-16-documentation-release-readiness.md`
- Move: `docs/superpowers/specs/2026-08-16-documentation-release-readiness-design.md` → `docs/archive/specs/2026-08-16-documentation-release-readiness-design.md`
- Modify only if a policy test reveals a defect: `README.md`
- Modify only if a policy test reveals a defect: `docs/user-guide.md`
- Modify only if a policy test reveals a defect: `docs/architecture.md`
- Modify only if a policy test reveals a defect: `docs/development.md`
- Modify only if a policy test reveals a defect: `docs/testing.md`
- Modify only if a policy test reveals a defect: `docs/adr/0001-local-first-overlay-reader.md`
- Modify only if a policy test reveals a defect: `docs/runbooks/release.md`
- Modify only if a policy test reveals a defect: `docs/runbooks/yandex-e2e.md`
- Modify only if a policy test reveals a defect: `docs/archive/README.md`

**Interfaces:**
- Consumes: `DocumentationPolicyTest.currentDocuments`, `repoFile(relativePath: String)`, all current docs, active runbooks/ADR, and the archive produced by Tasks 1–3.
- Produces: `DocumentationPolicyTest.markdownFiles(): List<Path>` and `DocumentationPolicyTest.relativeMarkdownTargets(markdown: String): Sequence<String>`; a repository with no active `docs/superpowers/` tree and a complete current-design/current-plan archive.

- [ ] **Step 1: Generate this task's subagent brief before moving the plan**

The coordinating agent must generate and retain the Task 4 brief before the first file move, because this step archives the plan itself. Reviewers must use `docs/archive/plans/2026-08-16-documentation-release-readiness.md` after the move.

- [ ] **Step 2: Add the final failing policy tests**

Add these imports:

```kotlin
import java.net.URI
import kotlin.streams.toList
```

Add these tests and helpers. The placeholder-marker strings are assembled so this plan and test source do not themselves contain active placeholder markers.

```kotlin
@Test
fun `every relative markdown link resolves`() {
    markdownFiles().forEach { markdownFile ->
        relativeMarkdownTargets(Files.readString(markdownFile)).forEach { rawTarget ->
            val decoded = URI(null, null, rawTarget.substringBefore('#'), null).path
            if (decoded.isNotEmpty()) {
                val resolved = markdownFile.parent.resolve(decoded).normalize()
                assertTrue(Files.exists(resolved), "$markdownFile -> $rawTarget")
            }
        }
    }
}

@Test
fun `current documentation contains no stale navigation or private machine data`() {
    val forbiddenLiteralMarkers = listOf(
        "TO" + "DO",
        "TB" + "D",
        "/home/inky/",
        "feat/pocket-editor-mvp",
        "fix/review-issues-4-5",
        "docs/superpowers/",
    )
    val stableEmulatorSerial = Regex("emulator-[0-9]+")
    val assignedProtectedValue = Regex(
        "(?m)^\\s*(YANDEX_CLIENT_ID|POCKET_EDITOR_RELEASE_(STORE_PASSWORD|KEY_PASSWORD|KEY_ALIAS|KEYSTORE_BASE64))\\s*=\\s*\\S+",
    )

    currentDocuments.forEach { relativePath ->
        val text = read(relativePath)
        forbiddenLiteralMarkers.forEach { marker ->
            assertFalse(text.contains(marker), "$relativePath contains $marker")
        }
        assertFalse(stableEmulatorSerial.containsMatchIn(text), relativePath)
        assertFalse(assignedProtectedValue.containsMatchIn(text), relativePath)
    }
}

@Test
fun `release documentation matches repository release configuration`() {
    val readme = read("README.md")
    val development = read("docs/development.md")
    val release = read("docs/runbooks/release.md")
    val workflow = read(".github/workflows/android.yml")
    val version = read("version.txt").trim()

    assertEquals("0.1.0", version)
    assertTrue(readme.contains("./gradlew test lint assembleDebug compileDebugAndroidTestKotlin"))
    assertTrue(development.contains("compileSdk 37"))
    assertTrue(development.contains("targetSdk 36"))
    assertTrue(development.contains("minSdk 26"))
    assertTrue(workflow.contains("./gradlew test lint assembleDebug assembleRelease"))
    assertTrue(workflow.contains("./gradlew connectedDebugAndroidTest"))
    assertTrue(workflow.contains("environment: release"))
    assertTrue(release.contains("version.txt"))
    assertTrue(release.contains("Release Please"))
    assertTrue(release.contains("app-release.apk.sha256"))
}

@Test
fun `the completed documentation design and plan are archived`() {
    assertTrue(
        Files.isRegularFile(
            repoFile("docs/archive/plans/2026-08-16-documentation-release-readiness.md"),
        ),
    )
    assertTrue(
        Files.isRegularFile(
            repoFile("docs/archive/specs/2026-08-16-documentation-release-readiness-design.md"),
        ),
    )
    assertFalse(Files.exists(repoFile("docs/superpowers")))
}

private fun markdownFiles(): List<Path> = buildList {
    add(repoFile("README.md"))
    add(repoFile("schemas/README.md"))
    Files.walk(repoFile("docs")).use { paths ->
        addAll(paths.filter { path ->
            Files.isRegularFile(path) && path.fileName.toString().endsWith(".md")
        }.toList())
    }
}

private fun relativeMarkdownTargets(markdown: String): Sequence<String> =
    Regex("!?\\[[^]]*]\\(([^)]+)\\)")
        .findAll(markdown)
        .map { it.groupValues[1].trim().removeSurrounding("<", ">") }
        .filterNot { target ->
            target.startsWith("#") ||
                target.startsWith("http://") ||
                target.startsWith("https://") ||
                target.startsWith("mailto:")
        }
```

- [ ] **Step 3: Run the focused test and verify the RED state**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests net.inkyquill.pocketeditor.DocumentationPolicyTest
```

Expected: FAIL in `the completed documentation design and plan are archived` because the pair still exists under `docs/superpowers/`. Link or hygiene failures may also appear and must remain visible until corrected.

- [ ] **Step 4: Archive the completed design and implementation plan**

Run:

```bash
git mv docs/superpowers/plans/2026-08-16-documentation-release-readiness.md docs/archive/plans/2026-08-16-documentation-release-readiness.md
git mv docs/superpowers/specs/2026-08-16-documentation-release-readiness-design.md docs/archive/specs/2026-08-16-documentation-release-readiness-design.md
rmdir docs/superpowers/plans docs/superpowers/specs docs/superpowers
```

Expected: `test ! -e docs/superpowers` succeeds. Both files retain their original filenames under `docs/archive/`.

- [ ] **Step 5: Fix every policy failure at its source**

Run the focused test repeatedly:

```bash
./gradlew :app:testDebugUnitTest --tests net.inkyquill.pocketeditor.DocumentationPolicyTest
```

Expected: PASS after all relative links resolve; current documents contain no placeholder markers, private absolute machine paths, fixed emulator serials, obsolete branch instructions, active-plan navigation, or assigned protected values; README/runbook statements match current Gradle/workflow/version configuration; and the final design/plan pair is archived. Do not edit historical archive contents merely to remove stale prose; only relative links are global integrity requirements, while stale wording is forbidden in current documents.

- [ ] **Step 6: Run direct Markdown and secret-hygiene diagnostics**

Run:

```bash
test ! -e docs/superpowers
rg -n "(/home/inky/|feat/pocket-editor-mvp|fix/review-issues-4-5|docs/superpowers/)" README.md docs/user-guide.md docs/architecture.md docs/development.md docs/testing.md docs/adr docs/runbooks docs/archive/README.md
rg -n "^(YANDEX_CLIENT_ID|POCKET_EDITOR_RELEASE_(STORE_PASSWORD|KEY_PASSWORD|KEY_ALIAS|KEYSTORE_BASE64))=[^[:space:]]+" README.md docs/user-guide.md docs/architecture.md docs/development.md docs/testing.md docs/adr docs/runbooks docs/archive/README.md
```

Expected: both `rg` commands produce no output. The first `test` exits successfully. The archive's historical files are intentionally excluded from stale/private-prose scanning because `docs/archive/README.md` declares them non-authoritative.

- [ ] **Step 7: Run the complete JVM suite and release-document policy tests**

Run:

```bash
./gradlew test
```

Expected: PASS with all JVM tests, including `DocumentationPolicyTest` and `ReleaseWorkflowPolicyTest`. No existing release-policy assertion is weakened to make documentation pass.

- [ ] **Step 8: Run the final Gradle release-readiness gate**

Run:

```bash
./gradlew test lint assembleDebug compileDebugAndroidTestKotlin
```

Expected: BUILD SUCCESSFUL. The JVM suite runs again as part of the exact release-readiness gate; lint may report known non-fatal warnings described generically in `docs/development.md`, but no task fails.

- [ ] **Step 9: Review archive completeness and repository scope**

Run:

```bash
find docs/archive/plans -maxdepth 1 -type f -name '*.md' | sort
find docs/archive/specs -maxdepth 1 -type f -name '*.md' | sort
find docs/archive/project-history -maxdepth 1 -type f -name '*.md' | sort
git status --short
git diff --check
```

Expected: 14 archived plans, 12 archived specs, and four project-history files; `docs/superpowers/` is absent; `git diff --check` is silent; unrelated untracked `.agents/skills/`, `.claude/skills/`, and `artifacts/` remain unmodified and unstaged.

- [ ] **Step 10: Commit the final documentation policy and archive pair**

```bash
git add README.md LICENSE docs app/src/test/java/net/inkyquill/pocketeditor/DocumentationPolicyTest.kt
git diff --cached --check
git commit -m "test: enforce documentation release policy"
```

Expected: the commit includes the final design/plan archive moves, the completed link/hygiene/secret policy, and only source fixes required by that policy.

- [ ] **Step 11: Request an independent final documentation review**

Give a fresh reviewer the range from the parent of `docs: add release-ready project documentation` through `test: enforce documentation release policy`. Require explicit findings for:

1. information architecture and Russian-first/short-English README balance;
2. user-flow and technical accuracy against current code;
3. release workflow/version/signing accuracy;
4. archive completeness and non-authoritative labeling;
5. relative-link integrity;
6. private-path, credential, OAuth, manuscript, and emulator-identifier hygiene;
7. adherence to the no-product/no-CI-change boundary.

Expected: APPROVED with no unresolved finding. Apply every valid finding, rerun Steps 5–9, and commit the correction with a narrowly scoped Conventional Commit message before publication.
