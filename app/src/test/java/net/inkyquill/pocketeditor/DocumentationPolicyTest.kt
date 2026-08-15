package net.inkyquill.pocketeditor

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        ).forEach { expected -> assertTrue(runbook.contains(expected), expected) }
    }

    private fun read(relativePath: String): String =
        Files.readAllBytes(repoFile(relativePath)).toString(Charsets.UTF_8)

    private fun repoFile(relativePath: String): Path =
        sequenceOf(Path.of("."), Path.of(".."))
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
            .resolve(relativePath)
            .normalize()
}
