package net.inkyquill.pocketeditor

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.streams.toList
import org.commonmark.node.Code
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

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
        "docs/archive/README.md",
        "schemas/README.md",
    )

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
    fun `public and maintained documentation exists`() {
        (currentDocuments + "LICENSE").forEach { relativePath ->
            assertTrue(Files.isRegularFile(repoFile(relativePath)), "$relativePath must exist")
        }
    }

    @Test
    fun `archive index is protected by the current-document hygiene policy`() {
        assertTrue(currentDocuments.contains("docs/archive/README.md"))
    }

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
            "aria-read-only-2026-08-16",
            "1f9184a",
            "PASS (read-only `aria`)",
            "disposable write-capable fixture",
            "| 4 | Priority, pause, continue, cancel, and retry | IN PROGRESS |",
        ).forEach { expected -> assertTrue(runbook.contains(expected), expected) }
    }

    @Test
    fun `every relative markdown link resolves`() {
        val failures = markdownLinkFailures(markdownFiles())

        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `markdown link policy reports a broken inline file`(@TempDir fixture: Path) {
        val index = writeMarkdownFixture(fixture, "index.md", "[Missing](missing.md)")

        assertEquals(listOf("$index -> missing.md"), markdownLinkFailures(listOf(index)))
    }

    @Test
    fun `markdown link policy reports a broken reference target`(@TempDir fixture: Path) {
        val index = writeMarkdownFixture(
            fixture,
            "index.md",
            "[Missing chapter][chapter]\n\n[chapter]: missing.md",
        )

        assertEquals(listOf("$index -> missing.md"), markdownLinkFailures(listOf(index)))
    }

    @Test
    fun `markdown link policy validates github heading fragments anchor only links and explicit anchors`(
        @TempDir fixture: Path,
    ) {
        val target = writeMarkdownFixture(
            fixture,
            "target.md",
            """# Привет, мир! `v2`

# Привет, мир! `v2`

<a id="ручной-якорь"></a>

<a name="устаревший-якорь"></a>

<!-- <a id="comment-anchor"></a> -->

<script>
const example = '<a id="script-anchor"></a>';
</script>

<a title=' id="quoted-attribute-anchor"'></a>

<a id="valid-id-anchor" name="valid-name-anchor"></a>

`<a id="inline-code-anchor"></a>`

```html
<a id="fenced-code-anchor"></a>
```

<a data-id="data-id-anchor" data-name="data-name-anchor"></a>""",
        )
        val index = writeMarkdownFixture(
            fixture,
            "index.md",
            listOf(
                "# Index",
                "[Local](#index)",
                "[Broken local](#local-missing)",
                "[First](target.md#привет-мир-v2)",
                "[Encoded](target.md#%D0%BF%D1%80%D0%B8%D0%B2%D0%B5%D1%82-%D0%BC%D0%B8%D1%80-v2)",
                "[Duplicate](target.md#привет-мир-v2-1)",
                "[Explicit](target.md#ручной-якорь)",
                "[Named](target.md#устаревший-якорь)",
                "[Comment](target.md#comment-anchor)",
                "[Script](target.md#script-anchor)",
                "[Quoted attribute](target.md#quoted-attribute-anchor)",
                "[Valid id](target.md#valid-id-anchor)",
                "[Valid name](target.md#valid-name-anchor)",
                "[Inline code](target.md#inline-code-anchor)",
                "[Fenced code](target.md#fenced-code-anchor)",
                "[Data id](target.md#data-id-anchor)",
                "[Data name](target.md#data-name-anchor)",
                "[Missing](target.md#нет-такого-раздела)",
                "[External](https://example.com/missing.md#missing)",
                "[Email](mailto:docs@example.com)",
            ).joinToString("\n"),
        )

        assertEquals(
            listOf(
                "$index -> #local-missing (missing fragment)",
                "$index -> target.md#comment-anchor (missing fragment)",
                "$index -> target.md#script-anchor (missing fragment)",
                "$index -> target.md#quoted-attribute-anchor (missing fragment)",
                "$index -> target.md#inline-code-anchor (missing fragment)",
                "$index -> target.md#fenced-code-anchor (missing fragment)",
                "$index -> target.md#data-id-anchor (missing fragment)",
                "$index -> target.md#data-name-anchor (missing fragment)",
                "$index -> target.md#нет-такого-раздела (missing fragment)",
            ),
            markdownLinkFailures(listOf(index, target)),
        )
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

        currentDocuments.forEach { relativePath ->
            val text = read(relativePath)
            forbiddenLiteralMarkers.forEach { marker ->
                assertFalse(text.contains(marker), "$relativePath contains $marker")
            }
            assertFalse(stableEmulatorSerial.containsMatchIn(text), relativePath)
            assertFalse(containsAssignedProtectedValue(text), relativePath)
        }
    }

    @Test
    fun `secret hygiene detects shell and yaml protected values`() {
        listOf(
            "export YANDEX_CLIENT_ID=real-client-id",
            "cd app && POCKET_EDITOR_RELEASE_KEY_ALIAS=real-alias ./gradlew assembleRelease",
            "POCKET_EDITOR_RELEASE_STORE_PASSWORD: real-password",
        ).forEach { fixture ->
            assertTrue(containsAssignedProtectedValue(fixture), fixture)
        }
    }

    @Test
    fun `secret hygiene allows symbolic protected values`() {
        listOf(
            "export YANDEX_CLIENT_ID=${'$'}YANDEX_CLIENT_ID",
            "POCKET_EDITOR_RELEASE_KEY_ALIAS=${'$'}{POCKET_EDITOR_RELEASE_KEY_ALIAS}",
            "POCKET_EDITOR_RELEASE_STORE_PASSWORD: ${'$'}{{ secrets.POCKET_EDITOR_RELEASE_STORE_PASSWORD }}",
        ).forEach { fixture ->
            assertFalse(containsAssignedProtectedValue(fixture), fixture)
        }
    }

    @Test
    fun `release documentation matches repository release configuration`() {
        val readme = read("README.md")
        val development = read("docs/development.md")
        val release = read("docs/runbooks/release.md")
        val workflow = read(".github/workflows/android.yml")
        val version = read("version.txt").trim()

        assertTrue(version.matches(SEMANTIC_VERSION), "version.txt must contain a Semantic Version")
        assertTrue(readme.contains("./gradlew test lint assembleDebug compileDebugAndroidTestKotlin"))
        assertTrue(development.contains("compileSdk 37"))
        assertTrue(development.contains("targetSdk 36"))
        assertTrue(development.contains("minSdk 26"))
        assertTrue(development.contains("`version.txt` — единственный источник истины"))
        assertTrue(workflow.contains("./gradlew test lint assembleDebug assembleRelease"))
        assertTrue(workflow.contains("./gradlew connectedDebugAndroidTest"))
        assertTrue(workflow.contains("environment: release"))
        assertTrue(release.contains("version.txt"))
        assertTrue(release.contains("Release Please"))
        assertTrue(release.contains("app-release.apk.sha256"))
    }

    @Test
    fun `version file participates in Gradle provider tracking`() {
        val gradle = read("app/build.gradle.kts")

        assertTrue(gradle.contains("providers.fileContents(rootProject.layout.projectDirectory.file(\"version.txt\"))"))
        assertTrue(gradle.contains(".orElse(localVersionName)"))
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

    private fun markdownLinkFailures(markdownFiles: List<Path>): List<String> = buildList {
        markdownFiles.forEach { markdownFile ->
            relativeMarkdownTargets(Files.readString(markdownFile)).forEach { rawTarget ->
                val rawPath = rawTarget.substringBefore('#').substringBefore('?')
                val decodedPath = decodeUrlPart(rawPath)
                val resolved = if (decodedPath.isEmpty()) {
                    markdownFile
                } else {
                    markdownFile.parent.resolve(decodedPath).normalize()
                }
                if (!Files.exists(resolved)) {
                    add("$markdownFile -> $rawTarget")
                } else if (rawTarget.contains('#')) {
                    val fragment = decodeUrlPart(rawTarget.substringAfter('#'))
                    if (
                        fragment.isNotEmpty() &&
                        resolved.fileName.toString().endsWith(".md", ignoreCase = true) &&
                        fragment !in markdownAnchors(Files.readString(resolved))
                    ) {
                        add("$markdownFile -> $rawTarget (missing fragment)")
                    }
                }
            }
        }
    }

    private fun relativeMarkdownTargets(markdown: String): Sequence<String> {
        val targets = mutableListOf<String>()
        walkMarkdown(Parser.builder().build().parse(markdown)) { node ->
            val target = when (node) {
                is Link -> node.destination
                is Image -> node.destination
                else -> null
            }
            if (target != null && isRelativeTarget(target)) targets += target
        }
        return targets.asSequence()
    }

    private fun markdownAnchors(markdown: String): Set<String> = buildSet {
        val duplicateCounts = mutableMapOf<String, Int>()
        walkMarkdown(Parser.builder().build().parse(markdown)) { node ->
            if (node is Heading) {
                val base = githubHeadingSlug(headingText(node))
                val duplicateIndex = duplicateCounts.getOrDefault(base, 0)
                add(if (duplicateIndex == 0) base else "$base-$duplicateIndex")
                duplicateCounts[base] = duplicateIndex + 1
            }
        }
        walkMarkdown(Parser.builder().build().parse(markdown)) { node ->
            val html = when (node) {
                is HtmlBlock -> node.literal
                is HtmlInline -> node.literal
                else -> null
            }
            html?.let { addAll(explicitHtmlAnchors(it)) }
        }
    }

    private fun explicitHtmlAnchors(html: String): Sequence<String> = sequence {
        var cursor = 0
        while (cursor < html.length) {
            val tagStart = html.indexOf('<', cursor)
            if (tagStart < 0) break
            if (html.startsWith("<!--", tagStart)) {
                val commentEnd = html.indexOf("-->", tagStart + 4)
                if (commentEnd < 0) break
                cursor = commentEnd + 3
                continue
            }
            val tag = parseHtmlTag(html, tagStart)
            if (tag == null) {
                cursor = tagStart + 1
                continue
            }
            cursor = tag.endExclusive
            if (!tag.closing && !tag.selfClosing && tag.name.lowercase(Locale.ROOT) in RAW_TEXT_HTML_ELEMENTS) {
                val closingStart = html.indexOf("</${tag.name}", cursor, ignoreCase = true)
                if (closingStart < 0) break
                cursor = parseHtmlTag(html, closingStart)?.endExclusive ?: (closingStart + 2)
                continue
            }
            if (!tag.closing && tag.name.equals("a", ignoreCase = true)) {
                yieldAll(explicitAnchorAttributes(tag.attributes))
            }
        }
    }

    private fun parseHtmlTag(html: String, start: Int): ParsedHtmlTag? {
        if (html.getOrNull(start) != '<') return null
        var cursor = start + 1
        val closing = html.getOrNull(cursor) == '/'
        if (closing) cursor++
        val nameStart = cursor
        while (html.getOrNull(cursor)?.let { it.isLetterOrDigit() || it == '-' } == true) cursor++
        if (cursor == nameStart) return null
        val name = html.substring(nameStart, cursor)
        val attributesStart = cursor
        var quote: Char? = null
        while (cursor < html.length) {
            val character = html[cursor]
            when {
                quote != null && character == quote -> quote = null
                quote == null && (character == '\'' || character == '"') -> quote = character
                quote == null && character == '>' -> {
                    val attributes = html.substring(attributesStart, cursor)
                    return ParsedHtmlTag(
                        name = name,
                        attributes = attributes,
                        closing = closing,
                        selfClosing = attributes.trimEnd().endsWith('/'),
                        endExclusive = cursor + 1,
                    )
                }
            }
            cursor++
        }
        return null
    }

    private fun explicitAnchorAttributes(attributes: String): Sequence<String> = sequence {
        var cursor = 0
        while (cursor < attributes.length) {
            while (attributes.getOrNull(cursor)?.let { it.isWhitespace() || it == '/' } == true) cursor++
            val nameStart = cursor
            while (attributes.getOrNull(cursor)?.let { !it.isWhitespace() && it !in "=/<>" } == true) cursor++
            if (cursor == nameStart) {
                cursor++
                continue
            }
            val name = attributes.substring(nameStart, cursor)
            while (attributes.getOrNull(cursor)?.isWhitespace() == true) cursor++
            if (attributes.getOrNull(cursor) != '=') continue
            cursor++
            while (attributes.getOrNull(cursor)?.isWhitespace() == true) cursor++
            val quote = attributes.getOrNull(cursor)?.takeIf { it == '\'' || it == '"' }
            val value = if (quote != null) {
                cursor++
                val valueStart = cursor
                while (cursor < attributes.length && attributes[cursor] != quote) cursor++
                if (cursor >= attributes.length) break
                attributes.substring(valueStart, cursor).also { cursor++ }
            } else {
                val valueStart = cursor
                while (attributes.getOrNull(cursor)?.let { !it.isWhitespace() && it !in "<>`\"'=" } == true) cursor++
                attributes.substring(valueStart, cursor)
            }
            if (value.isNotEmpty() && (name.equals("id", true) || name.equals("name", true))) yield(value)
        }
    }

    private data class ParsedHtmlTag(
        val name: String,
        val attributes: String,
        val closing: Boolean,
        val selfClosing: Boolean,
        val endExclusive: Int,
    )

    private fun walkMarkdown(node: Node, visit: (Node) -> Unit) {
        visit(node)
        var child = node.firstChild
        while (child != null) {
            val next = child.next
            walkMarkdown(child, visit)
            child = next
        }
    }

    private fun headingText(heading: Heading): String = buildString {
        fun appendNode(node: Node) {
            when (node) {
                is Text -> append(node.literal)
                is Code -> append(node.literal)
                is SoftLineBreak, is HardLineBreak -> append(' ')
                is HtmlInline -> Unit
                else -> {
                    var child = node.firstChild
                    while (child != null) {
                        val next = child.next
                        appendNode(child)
                        child = next
                    }
                }
            }
        }
        appendNode(heading)
    }

    private fun githubHeadingSlug(heading: String): String = buildString {
        heading.lowercase(Locale.ROOT).forEach { character ->
            when {
                character.isLetterOrDigit() || character == '-' || character == '_' -> append(character)
                character.isWhitespace() -> append('-')
            }
        }
    }

    private fun isRelativeTarget(target: String): Boolean =
        !target.startsWith("//") && !URI_SCHEME.containsMatchIn(target)

    private fun decodeUrlPart(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)

    private fun containsAssignedProtectedValue(markdown: String): Boolean =
        (SHELL_PROTECTED_ASSIGNMENT.findAll(markdown) + YAML_PROTECTED_ASSIGNMENT.findAll(markdown))
            .any { match ->
                val name = match.groups[1]?.value.orEmpty()
                val value = match.groups[2]?.value.orEmpty()
                value.isNotBlank() && !isSymbolicProtectedValue(name, value)
            }

    private fun isSymbolicProtectedValue(name: String, rawValue: String): Boolean {
        val value = rawValue.trim().removeSurrounding("\"").removeSurrounding("'")
        return value.matches(Regex("\\$[A-Z][A-Z0-9_]*")) ||
            value.matches(Regex("\\$\\{[A-Z][A-Z0-9_]*}")) ||
            value.matches(Regex("\\$\\{\\{\\s*secrets\\.$name\\s*}}"))
    }

    private fun writeMarkdownFixture(root: Path, relativePath: String, content: String): Path =
        root.resolve(relativePath).also { path ->
            Files.createDirectories(path.parent)
            Files.writeString(path, content)
        }

    private fun read(relativePath: String): String =
        Files.readAllBytes(repoFile(relativePath)).toString(Charsets.UTF_8)

    private fun repoFile(relativePath: String): Path =
        sequenceOf(Path.of("."), Path.of(".."))
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
            .resolve(relativePath)
            .normalize()

    private companion object {
        private const val PROTECTED_NAME =
            "(YANDEX_CLIENT_ID|POCKET_EDITOR_RELEASE_(?:STORE_PASSWORD|KEY_PASSWORD|KEY_ALIAS|KEYSTORE_BASE64))"
        val SHELL_PROTECTED_ASSIGNMENT = Regex(
            "(?m)(?:^|[\\s;&|])(?:export\\s+)?$PROTECTED_NAME\\s*=\\s*([^\\s;&|]+)",
        )
        val YAML_PROTECTED_ASSIGNMENT = Regex(
            "(?m)^\\s*(?:-\\s*)?$PROTECTED_NAME\\s*:\\s*(\\S.*)?$",
        )
        val URI_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
        val RAW_TEXT_HTML_ELEMENTS = setOf("script", "style")
        val SEMANTIC_VERSION = Regex(
            "(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)" +
                "(?:-(?:0|[1-9]\\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(?:\\.(?:0|[1-9]\\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)?" +
                "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?",
        )
    }
}
