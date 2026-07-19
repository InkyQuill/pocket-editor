package net.inkyquill.pocketeditor

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReleaseWorkflowPolicyTest {
    private val workflow = readWorkflow()
    private val signedJob = workflow.substringAfter("  signed-release:")

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
        assertFalse(signedJob.substringBefore("- name: Validate protected signing inputs").contains("secrets."))
        assertFalse(
            signedJob.substringAfter("- name: Verify signature and checksum")
                .substringBefore("uses: actions/upload-artifact@")
                .contains("secrets."),
        )
        assertFalse(signedJob.substringAfter("uses: actions/upload-artifact@").contains("secrets."))
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

    private fun readWorkflow(): String {
        val candidates = listOf(
            Path.of("..", ".github", "workflows", "android.yml"),
            Path.of(".github", "workflows", "android.yml"),
        )
        val path = candidates.firstOrNull(Files::exists) ?: error("Android workflow not found")
        return Files.readAllBytes(path).toString(Charsets.UTF_8)
    }
}
