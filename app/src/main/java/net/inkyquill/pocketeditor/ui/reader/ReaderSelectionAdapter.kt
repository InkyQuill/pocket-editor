package net.inkyquill.pocketeditor.ui.reader

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import net.inkyquill.pocketeditor.markdown.RawRange
import net.inkyquill.pocketeditor.markdown.TextRange
import net.inkyquill.pocketeditor.reader.ReaderDocument
import net.inkyquill.pocketeditor.reader.ReaderSourceSelection

internal data class ReaderTextLayout(
    val layout: TextLayoutResult,
    val coordinates: LayoutCoordinates,
)

internal object ReaderSelectionAdapter {
    private const val ProvenanceTag = "reader-selection-provenance"

    fun annotate(text: AnnotatedString, blockIndex: Int): AnnotatedString =
        AnnotatedString.Builder(text).apply {
            for (offset in text.indices) {
                addStringAnnotation(
                    tag = ProvenanceTag,
                    annotation = "$blockIndex:$offset:${offset + 1}",
                    start = offset,
                    end = offset + 1,
                )
            }
        }.toAnnotatedString()

    fun range(selected: List<AnnotatedString>, all: List<AnnotatedString>): TextRange? {
        if (selected.isEmpty() || all.isEmpty()) return null

        val document = all.filterNot(AnnotatedString::isEmpty).map(::documentBlock).takeIf { blocks ->
            blocks.all { it != null } && blocks.mapNotNull { it?.index }.distinct().size == blocks.size
        }?.filterNotNull() ?: return null
        val positions = document.mapIndexed { position, block -> block.index to position }.toMap()
        val allowed = document.flatMap { block -> block.characters }.toHashSet()

        val chosen = selected.flatMap { text ->
            text.indices.map { offset -> provenanceAt(text, offset) ?: return null }
        }
        if (chosen.isEmpty() || chosen.any { it !in allowed } || chosen.distinct().size != chosen.size) return null

        val selectedByBlock = chosen.groupBy(Provenance::blockIndex)
        val selectedPositions = selectedByBlock.keys.map { positions[it] ?: return null }.sorted()
        if (selectedPositions.zipWithNext().any { (first, second) -> second != first + 1 }) return null

        selectedByBlock.values.forEach { characters ->
            val offsets = characters.map(Provenance::start).sorted()
            if (offsets.zipWithNext().any { (first, second) -> second != first + 1 }) return null
        }

        val firstBlock = document[selectedPositions.first()]
        val lastBlock = document[selectedPositions.last()]
        val firstCharacters = selectedByBlock.getValue(firstBlock.index)
        val lastCharacters = selectedByBlock.getValue(lastBlock.index)
        return TextRange(
            startBlock = firstBlock.index,
            start = firstCharacters.minOf(Provenance::start),
            endBlock = lastBlock.index,
            end = lastCharacters.maxOf(Provenance::end),
        )
    }

    fun visibleEndpointBounds(
        range: TextRange,
        layouts: Map<Int, ReaderTextLayout>,
        viewport: Rect,
        preferEnd: Boolean = true,
    ): Rect? {
        val normalized = range.normalized()
        val endpoints = if (preferEnd) {
            listOf(normalized.endBlock to (normalized.end - 1), normalized.startBlock to normalized.start)
        } else {
            listOf(normalized.startBlock to normalized.start, normalized.endBlock to (normalized.end - 1))
        }
        return endpoints.firstNotNullOfOrNull { (blockIndex, offset) ->
            val entry = layouts[blockIndex] ?: return@firstNotNullOfOrNull null
            if (!entry.coordinates.isAttached || offset !in 0 until entry.layout.layoutInput.text.length) {
                return@firstNotNullOfOrNull null
            }
            val glyph = entry.layout.getBoundingBox(offset)
            val inRoot = Rect(
                topLeft = entry.coordinates.localToRoot(glyph.topLeft),
                bottomRight = entry.coordinates.localToRoot(glyph.bottomRight),
            )
            inRoot.takeIf { it.overlaps(viewport) }
        }
    }

    private fun documentBlock(text: AnnotatedString): DocumentBlock? {
        if (text.isEmpty()) return null
        val characters = text.indices.map { offset -> provenanceAt(text, offset) ?: return null }
        val blockIndex = characters.first().blockIndex
        if (characters.any { it.blockIndex != blockIndex } ||
            characters.map(Provenance::start) != text.indices.toList() ||
            characters.any { it.end != it.start + 1 }
        ) {
            return null
        }
        return DocumentBlock(blockIndex, characters)
    }

    private fun provenanceAt(text: AnnotatedString, offset: Int): Provenance? {
        val annotations = text.getStringAnnotations(ProvenanceTag, offset, offset + 1)
        if (annotations.size != 1) return null
        val parts = annotations.single().item.split(':')
        if (parts.size != 3) return null
        val blockIndex = parts[0].toIntOrNull() ?: return null
        val start = parts[1].toIntOrNull() ?: return null
        val end = parts[2].toIntOrNull() ?: return null
        if (blockIndex < 0 || start < 0 || end != start + 1) return null
        return Provenance(blockIndex, start, end)
    }

    private data class DocumentBlock(val index: Int, val characters: List<Provenance>)
    private data class Provenance(val blockIndex: Int, val start: Int, val end: Int)
}

internal fun ReaderDocument.sourceSelection(range: TextRange): ReaderSourceSelection? {
    val normalized = range.normalized()
    val first = blocks.firstOrNull { it.sourceIndex == normalized.startBlock } ?: return null
    val last = blocks.firstOrNull { it.sourceIndex == normalized.endBlock } ?: return null
    if (first === last) {
        val mapped = first.sourceSelection(normalized.start, normalized.end) ?: return null
        val bytes = sourceBytes ?: return mapped
        if (mapped.rawRange.endByte > bytes.size) return null
        return ReaderSourceSelection(
            rawRange = mapped.rawRange,
            selectedText = bytes.copyOfRange(mapped.rawRange.startByte, mapped.rawRange.endByte).decodeToString(),
        )
    }

    val rawStart = first.sourceSelection(normalized.start, normalized.start + 1)
        ?.rawRange?.startByte ?: return null
    val rawEnd = last.sourceSelection(normalized.end - 1, normalized.end)
        ?.rawRange?.endByte ?: return null
    if (rawEnd <= rawStart) return null
    val bytes = sourceBytes ?: return null
    if (rawEnd > bytes.size) return null
    val rawRange = RawRange(rawStart, rawEnd)
    return ReaderSourceSelection(
        rawRange = rawRange,
        selectedText = bytes.copyOfRange(rawRange.startByte, rawRange.endByte).decodeToString(),
    )
}
