package net.inkyquill.pocketeditor.anchor

import net.inkyquill.pocketeditor.review.Anchor

sealed interface AnchorResolution

data class Resolved(val startByte: Int, val endByte: Int) : AnchorResolution

data object Stale : AnchorResolution

data class Ambiguous(val candidates: List<Resolved>) : AnchorResolution

object AnchorResolver {
    fun resolve(source: ByteArray, anchor: Anchor, selectedText: String): AnchorResolution {
        val selected = selectedText.encodeToByteArray()
        if (selected.isEmpty() || selected.sha256() != anchor.selectionSha256) return Stale

        if (source.sha256() == anchor.sourceSha256) {
            val saved = anchor.savedRangeOrNull(source.size) ?: return Stale
            return if (
                saved.endByte - saved.startByte == selected.size &&
                source.matchesAt(selected, saved.startByte)
            ) {
                saved
            } else {
                Stale
            }
        }

        val occurrences = source.exactOccurrences(selected)
        if (occurrences.isEmpty()) return Stale
        if (occurrences.size == 1) return occurrences.single()

        val prefix = anchor.prefix.encodeToByteArray()
        val suffix = anchor.suffix.encodeToByteArray()
        val contextual = occurrences.filter { occurrence ->
            source.hasPrefixContext(prefix, occurrence.startByte) &&
                source.hasSuffixContext(suffix, occurrence.endByte)
        }

        return when (contextual.size) {
            1 -> contextual.single()
            0 -> Ambiguous(occurrences)
            else -> Ambiguous(contextual)
        }
    }

    private fun Anchor.savedRangeOrNull(sourceSize: Int): Resolved? {
        if (startByte < 0 || endByte <= startByte || endByte > sourceSize) return null
        if (startByte > Int.MAX_VALUE || endByte > Int.MAX_VALUE) return null
        return Resolved(startByte.toInt(), endByte.toInt())
    }

    private fun ByteArray.exactOccurrences(selection: ByteArray): List<Resolved> {
        if (selection.size > size) return emptyList()
        val source = this
        return buildList {
            for (start in 0..source.size - selection.size) {
                if (matchesAt(selection, start)) add(Resolved(start, start + selection.size))
            }
        }
    }

    private fun ByteArray.matchesAt(value: ByteArray, offset: Int): Boolean =
        offset >= 0 && offset + value.size <= size && value.indices.all { index ->
            this[offset + index] == value[index]
        }

    private fun ByteArray.hasPrefixContext(prefix: ByteArray, start: Int): Boolean =
        prefix.size <= start && matchesAt(prefix, start - prefix.size)

    private fun ByteArray.hasSuffixContext(suffix: ByteArray, end: Int): Boolean =
        matchesAt(suffix, end)
}
