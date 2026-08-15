package net.inkyquill.pocketeditor

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReleaseWorkflowPolicyTest {
    private val workflow = readWorkflow()
    private val signedJob = workflow.substringAfter("  signed-release:")

    @Test
    fun `compose bom supports public selection state`() {
        val catalog = listOf(
            Path.of("..", "gradle", "libs.versions.toml"),
            Path.of("gradle", "libs.versions.toml"),
        ).first(Files::exists)

        val catalogText = Files.readAllBytes(catalog).toString(Charsets.UTF_8)
        assertTrue(catalogText.contains("compose-bom = \"2026.08.00\""))
    }

    @Test
    fun `third party actions are pinned to immutable commits`() {
        val actionReferences = Regex("uses: [^\\s]+@([^\\s]+)(?:\\s+#\\s+(.+))?")
            .findAll(workflow)
            .toList()

        assertTrue(actionReferences.isNotEmpty())
        actionReferences.forEach { match ->
            assertTrue(match.groupValues[1].matches(Regex("[0-9a-f]{40}")), match.value)
            assertTrue(match.groupValues[2].matches(Regex("v\\d+(?:\\.\\d+)*")), match.value)
        }
    }

    @Test
    fun `protected secrets are scoped only to validation and build steps`() {
        val jobEnvironment = signedJob.substringBefore("    steps:")
        val secretNames = listOf(
            "POCKET_EDITOR_RELEASE_STORE_PASSWORD",
            "POCKET_EDITOR_RELEASE_KEY_ALIAS",
            "POCKET_EDITOR_RELEASE_KEY_PASSWORD",
            "YANDEX_CLIENT_ID",
            "POCKET_EDITOR_RELEASE_KEYSTORE_BASE64",
        )

        secretNames.forEach { assertFalse(jobEnvironment.contains(it), "Job-level secret leaked: $it") }
        assertFalse(signedJob.substringBefore("- name: Decode protected signing keystore").contains("secrets."))
        assertFalse(
            signedJob.substringAfter("- name: Verify signature and checksum")
                .substringBefore("- name: Remove decoded keystore")
                .contains("secrets."),
        )
    }

    @Test
    fun `signature and portable checksum are verified before upload`() {
        assertTrue(signedJob.contains("find \"${'$'}ANDROID_SDK_ROOT/build-tools\""))
        assertTrue(signedJob.contains("test -x \"${'$'}apksigner\""))
        assertTrue(signedJob.contains("\"${'$'}apksigner\" verify --verbose"))
        assertTrue(signedJob.contains("cd app/build/outputs/apk/release"))
        assertTrue(signedJob.contains("sha256sum app-release.apk > app-release.apk.sha256"))
        assertTrue(signedJob.contains("sha256sum --check app-release.apk.sha256"))
    }

    @Test
    fun `release please uses the root simple manifest contract`() {
        val manifest = readRootFile(".release-please-manifest.json")
        val config = readRootFile("release-please-config.json")

        assertTrue(manifest.contains("\".\": \"0.1.0\""))
        assertTrue(config.contains("\"release-type\": \"simple\""))
        assertTrue(config.contains("\"version-file\": \"version.txt\""))
        assertTrue(config.contains("\"changelog-path\": \"CHANGELOG.md\""))
        assertEquals("0.1.0", readRootFile("version.txt").trim())
    }

    @Test
    fun `pull requests validate conventional commit titles without shell interpolation`() {
        assertTrue(workflow.contains("pull_request:"))
        assertTrue(workflow.contains("branches: [main]"))
        assertTrue(workflow.contains("actions/github-script@ed597411d8f924073f98dfc5c65a23a2325f34cd # v8.0.0"))
        assertTrue(workflow.contains("context.payload.pull_request?.title"))
        assertTrue(workflow.contains("feat|fix|perf|refactor|docs|test|build|ci|chore|revert"))
        assertFalse(workflow.contains("${'$'}{{ github.event.pull_request.title }}"))
    }

    @Test
    fun `release publication is gated on release please and exact released sha`() {
        val releasePleaseJob = workflow.substringAfter("  release-please:").substringBefore("  signed-release:")
        val verificationJob = workflow.substringAfter("  verify:").substringBefore("  emulator:")

        assertTrue(releasePleaseJob.contains("googleapis/release-please-action@5c625bfb5d1ff62eadeeb3772007f7f66fdcf071 # v4.4.1"))
        assertTrue(releasePleaseJob.contains("release_created"))
        assertTrue(releasePleaseJob.contains("tag_name"))
        assertTrue(releasePleaseJob.contains("version"))
        assertTrue(releasePleaseJob.contains("sha"))
        assertTrue(signedJob.contains("needs.release-please.outputs.release_created == 'true'"))
        assertTrue(signedJob.contains("ref: ${'$'}{{ needs.release-please.outputs.sha }}"))
        assertTrue(signedJob.contains("fetch-depth: 0"))
        assertFalse(signedJob.contains("git fetch --tags origin"))
        assertTrue(signedJob.contains("RELEASE_TAG\" =~ ${'$'}release_tag_pattern"))
        assertTrue(signedJob.contains("RELEASE_SHA\" =~ ^[0-9a-f]{40}${'$'}"))
        assertTrue(signedJob.contains("git rev-parse --verify \"refs/tags/${'$'}RELEASE_TAG^{commit}\""))
        assertTrue(signedJob.contains("gh release view \"${'$'}RELEASE_TAG\""))
        assertTrue(signedJob.contains("gh release upload \"${'$'}RELEASE_TAG\""))
        assertTrue(signedJob.contains("--clobber"))
        assertFalse(signedJob.contains("github.event_name == 'workflow_dispatch'"))
        assertTrue(verificationJob.contains("YANDEX_CLIENT_ID: ci-release-verification"))
        assertTrue(verificationJob.contains("./gradlew test lint assembleDebug assembleRelease"))
    }

    @Test
    fun `runbook records the release please github token prerequisite and ci limitation`() {
        val runbook = readRootFile("docs/runbooks/release.md")

        assertTrue(
            runbook.contains(
                "Settings → Actions → General → “Allow GitHub Actions to create and approve pull requests”",
            ),
        )
        assertTrue(runbook.contains("One-time prerequisite"))
        assertTrue(runbook.contains("GITHUB_TOKEN-created Release PR"))
        assertTrue(runbook.contains("does not trigger pull_request workflows"))
    }

    @Test
    fun `release signing is secret-scoped and verifies a signed apk before idempotent upload`() {
        assertTrue(signedJob.contains("permissions:\n      contents: write"))
        assertTrue(workflow.contains("release-please:\n    if: github.event_name == 'push' && github.ref == 'refs/heads/main'"))
        assertTrue(workflow.contains("pull-requests: write"))
        assertFalse(workflow.substringAfter("  release-please:").substringBefore("  signed-release:").contains("issues: write"))
        assertTrue(signedJob.contains("chmod 600 \"${'$'}POCKET_EDITOR_RELEASE_STORE_FILE\""))
        assertTrue(signedJob.contains("base64 --decode > \"${'$'}POCKET_EDITOR_RELEASE_STORE_FILE\" <<< \"${'$'}POCKET_EDITOR_RELEASE_KEYSTORE_BASE64\""))
        assertTrue(signedJob.contains("if: always()"))
        assertTrue(signedJob.contains("POCKET_EDITOR_VERSION_NAME: ${'$'}{{ needs.release-please.outputs.version }}"))
        assertTrue(signedJob.contains("POCKET_EDITOR_VERSION_CODE: ${'$'}{{ github.run_number }}"))
        assertTrue(signedJob.contains("POCKET_EDITOR_VERSION_NAME\" =~ ${'$'}semver_pattern"))
        assertTrue(signedJob.contains("POCKET_EDITOR_VERSION_CODE <= 2100000000"))
        assertTrue(signedJob.contains("verify --verbose --print-certs"))
        assertTrue(signedJob.contains("test ! -e app/build/outputs/apk/release/app-release-unsigned.apk"))
        assertFalse(signedJob.contains("actions/upload-artifact@"))
    }

    @Test
    fun `gradle validates release version overrides and appearance shows the generated version`() {
        val gradle = readRootFile("app/build.gradle.kts")
        val appearance = readRootFile("app/src/main/java/net/inkyquill/pocketeditor/ui/settings/AppearanceScreen.kt")
        val strings = readRootFile("app/src/main/res/values/strings.xml")

        assertTrue(gradle.contains("POCKET_EDITOR_VERSION_NAME"))
        assertTrue(gradle.contains("POCKET_EDITOR_VERSION_CODE"))
        assertTrue(gradle.contains("version.txt"))
        assertTrue(gradle.contains("Version code must be a positive Android-safe integer"))
        assertTrue(appearance.contains("BuildConfig.VERSION_NAME"))
        assertTrue(appearance.contains("BuildConfig.VERSION_CODE"))
        assertTrue(appearance.contains("R.string.app_version"))
        assertTrue(strings.contains("name=\"app_version\">Версия %1${'$'}s (%2${'$'}d)</string>"))
    }

    private fun readWorkflow(): String {
        val candidates = listOf(
            Path.of("..", ".github", "workflows", "android.yml"),
            Path.of(".github", "workflows", "android.yml"),
        )
        val path = candidates.firstOrNull(Files::exists) ?: error("Android workflow not found")
        return Files.readAllBytes(path).toString(Charsets.UTF_8)
    }

    private fun readRootFile(relativePath: String): String {
        val candidates = listOf(Path.of("..", relativePath), Path.of(relativePath))
        val path = candidates.firstOrNull(Files::exists) ?: error("$relativePath not found")
        return Files.readAllBytes(path).toString(Charsets.UTF_8)
    }
}
