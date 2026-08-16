package net.inkyquill.pocketeditor.yandex

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ManifestRecoveryArtifactNameTest {
    @Test
    fun `content authenticated recovery artifacts are internal through maximum transition index`() {
        val transaction = "31b46db6d72373418460992b"
        val digest = "11507a0e2f5e69d5dfa40a62"

        assertTrue(isManifestRecoveryArtifactName(".pocket-editor.manifest.previous.$transaction"))
        assertTrue(isManifestRecoveryArtifactName(".pocket-editor.manifest.next.$transaction"))
        assertTrue(isManifestRecoveryArtifactName(".pocket-editor.manifest.retired.$transaction.$digest.2147483647"))
        assertTrue(isManifestRecoveryArtifactName(".pocket-editor.manifest.provisional.$transaction.2147483647"))
        assertTrue(
            isManifestRecoveryArtifactName(
                ".pocket-editor.manifest.transition.$transaction.$digest.2147483647",
            ),
        )
    }

    @Test
    fun `unauthenticated or out of range transition names remain user visible`() {
        val transaction = "31b46db6d72373418460992b"
        val digest = "11507a0e2f5e69d5dfa40a62"

        assertFalse(isManifestRecoveryArtifactName(".pocket-editor.manifest.transition.$transaction.0"))
        assertFalse(isManifestRecoveryArtifactName(".pocket-editor.manifest.retired.$transaction.$digest"))
        assertFalse(isManifestRecoveryArtifactName(".pocket-editor.manifest.provisional.$transaction.2147483648"))
        assertFalse(
            isManifestRecoveryArtifactName(
                ".pocket-editor.manifest.transition.$transaction.$digest.2147483648",
            ),
        )
        assertFalse(isManifestRecoveryArtifactName(".pocket-editor.manifest.transition.$transaction.$digest.-1"))
    }

    @Test
    fun `maximum artifact generation or transition index fails closed instead of overflowing`() {
        assertThrows(YandexDiskError.UploadIncomplete::class.java) {
            nextManifestArtifactIndex(listOf(MAX_MANIFEST_ARTIFACT_INDEX))
        }
    }
}
