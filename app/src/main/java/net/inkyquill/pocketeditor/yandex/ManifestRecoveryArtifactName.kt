package net.inkyquill.pocketeditor.yandex

internal const val MAX_MANIFEST_ARTIFACT_INDEX: Int = Int.MAX_VALUE

internal sealed interface ManifestRecoveryArtifactName {
    val transactionId: String

    data class Previous(override val transactionId: String) : ManifestRecoveryArtifactName
    data class Next(override val transactionId: String) : ManifestRecoveryArtifactName
    data class Retired(
        override val transactionId: String,
        val contentDigest: String,
        val generation: Int,
    ) : ManifestRecoveryArtifactName
    data class Provisional(
        override val transactionId: String,
        val generation: Int,
    ) : ManifestRecoveryArtifactName
    data class Transition(
        override val transactionId: String,
        val contentDigest: String,
        val index: Int,
    ) : ManifestRecoveryArtifactName
}

internal fun parseManifestRecoveryArtifactName(name: String): ManifestRecoveryArtifactName? {
    SIMPLE_PATTERN.matchEntire(name)?.let { match ->
        val transactionId = match.groupValues[2]
        return when (match.groupValues[1]) {
            "previous" -> ManifestRecoveryArtifactName.Previous(transactionId)
            "next" -> ManifestRecoveryArtifactName.Next(transactionId)
            else -> null
        }
    }
    RETIRED_PATTERN.matchEntire(name)?.let { match ->
        return ManifestRecoveryArtifactName.Retired(
            match.groupValues[1],
            match.groupValues[2],
            match.groupValues[3].toIntOrNull() ?: return null,
        )
    }
    PROVISIONAL_PATTERN.matchEntire(name)?.let { match ->
        return ManifestRecoveryArtifactName.Provisional(
            match.groupValues[1],
            match.groupValues[2].toIntOrNull() ?: return null,
        )
    }
    TRANSITION_PATTERN.matchEntire(name)?.let { match ->
        return ManifestRecoveryArtifactName.Transition(
            transactionId = match.groupValues[1],
            contentDigest = match.groupValues[2],
            index = match.groupValues[3].toIntOrNull() ?: return null,
        )
    }
    return null
}

internal fun isManifestRecoveryArtifactName(name: String): Boolean =
    parseManifestRecoveryArtifactName(name) != null

internal fun nextManifestArtifactIndex(existing: Collection<Int>): Int = when (val maximum = existing.maxOrNull()) {
    null -> 0
    MAX_MANIFEST_ARTIFACT_INDEX -> throw YandexDiskError.UploadIncomplete()
    else -> maximum + 1
}

private val SIMPLE_PATTERN = Regex("^\\.pocket-editor\\.manifest\\.(previous|next)\\.([0-9a-f]{24})$")
private val RETIRED_PATTERN = Regex(
    "^\\.pocket-editor\\.manifest\\.retired\\.([0-9a-f]{24})\\.([0-9a-f]{24})\\.([0-9]+)$",
)
private val PROVISIONAL_PATTERN = Regex(
    "^\\.pocket-editor\\.manifest\\.provisional\\.([0-9a-f]{24})\\.([0-9]+)$",
)
private val TRANSITION_PATTERN = Regex(
    "^\\.pocket-editor\\.manifest\\.transition\\.([0-9a-f]{24})\\.([0-9a-f]{24})\\.([0-9]+)$",
)
