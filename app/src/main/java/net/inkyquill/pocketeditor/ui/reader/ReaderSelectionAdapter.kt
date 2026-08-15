package net.inkyquill.pocketeditor.ui.reader

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import java.security.MessageDigest
import net.inkyquill.pocketeditor.markdown.RenderedDocument
import net.inkyquill.pocketeditor.markdown.SelectionMapper
import net.inkyquill.pocketeditor.markdown.TextRange
import net.inkyquill.pocketeditor.reader.ReaderDocument
import net.inkyquill.pocketeditor.reader.ReaderSourceSelection

internal data class ReaderTextLayout(
    val layout: TextLayoutResult,
    val coordinates: LayoutCoordinates,
)

internal enum class ReaderSelectionEndpoint { Start, End }

internal data class ReaderSelectionGlyph(val blockIndex: Int, val visualOffset: Int)

internal data class ReaderSelectionResult(
    val range: TextRange,
    val activeEndpoint: ReaderSelectionEndpoint,
    val startGlyph: ReaderSelectionGlyph = ReaderSelectionGlyph(range.startBlock, range.start),
    val endGlyph: ReaderSelectionGlyph = ReaderSelectionGlyph(range.endBlock, range.end - 1),
)

internal object ReaderSelectionAdapter {
    private const val ProvenanceTag = "reader-selection-provenance"
    private const val Separator = '|'

    fun annotate(
        text: AnnotatedString,
        blockIndex: Int,
        generation: String,
        sourceOffsets: List<Int?>,
    ): AnnotatedString {
        require(sourceOffsets.size == text.length)
        return AnnotatedString.Builder(text).apply {
            for (visualOffset in text.indices) {
                val sourceStart = sourceOffsets[visualOffset]
                val sourceEnd = sourceStart?.plus(1)
                addStringAnnotation(
                    tag = ProvenanceTag,
                    annotation = listOf(
                        generation,
                        blockIndex.toString(),
                        visualOffset.toString(),
                        sourceStart?.toString() ?: "x",
                        sourceEnd?.toString() ?: "x",
                    ).joinToString(Separator.toString()),
                    start = visualOffset,
                    end = visualOffset + 1,
                )
            }
        }.toAnnotatedString()
    }

    fun selection(
        selected: List<AnnotatedString>,
        all: List<AnnotatedString>,
        activeEndpoint: ReaderSelectionEndpoint = ReaderSelectionEndpoint.End,
    ): ReaderSelectionResult? {
        val document = all.filterNot(AnnotatedString::isEmpty).map(::documentBlock)
        if (document.isEmpty() || document.any { it == null }) return null
        val blocks = document.filterNotNull()
        if (blocks.map(DocumentBlock::index).distinct().size != blocks.size) return null
        val generation = blocks.first().generation
        if (blocks.any { it.generation != generation }) return null

        val positions = blocks.mapIndexed { position, block -> block.index to position }.toMap()
        val allowed = blocks.flatMap(DocumentBlock::characters).toHashSet()
        val fragments = selected.filterNot(AnnotatedString::isEmpty).map { text ->
            val characters = text.indices.map { offset ->
                val provenance = provenanceAt(text, offset) ?: return null
                DocumentCharacter(provenance, text[offset])
            }
            if (characters.isEmpty() || characters.any { it !in allowed }) return null
            val blockIndex = characters.first().provenance.blockIndex
            if (characters.any { it.provenance.blockIndex != blockIndex }) return null
            val visualOffsets = characters.map { it.provenance.visualOffset }
            if (visualOffsets.zipWithNext().any { (first, second) -> second != first + 1 }) return null
            SelectedFragment(blockIndex, characters)
        }
        if (fragments.isEmpty() || fragments.map(SelectedFragment::blockIndex).distinct().size != fragments.size) return null

        val orderedPositions = fragments.map { positions[it.blockIndex] ?: return null }
        if (orderedPositions.zipWithNext().any { (first, second) -> second != first + 1 }) return null
        val selectedPositions = orderedPositions
        if (selectedPositions.zipWithNext().any { (first, second) -> second != first + 1 }) return null

        val byBlock = fragments.associateBy(SelectedFragment::blockIndex)
        val firstBlock = blocks[selectedPositions.first()]
        val lastBlock = blocks[selectedPositions.last()]
        val first = byBlock.getValue(firstBlock.index)
        val last = byBlock.getValue(lastBlock.index)
        if (first !== last) {
            if (first.visualOffsets.last() != firstBlock.characters.lastIndex) return null
            if (last.visualOffsets.first() != 0) return null
            selectedPositions.drop(1).dropLast(1).forEach { position ->
                val block = blocks[position]
                val fragment = byBlock.getValue(block.index)
                if (fragment.visualOffsets != block.characters.indices.toList()) return null
            }
        }

        val start = first.characters.first().provenance.sourceStart ?: return null
        val end = last.characters.last().provenance.sourceEnd ?: return null
        if (firstBlock === lastBlock && end <= start) return null
        return ReaderSelectionResult(
            range = TextRange(firstBlock.index, start, lastBlock.index, end),
            activeEndpoint = activeEndpoint,
            startGlyph = ReaderSelectionGlyph(firstBlock.index, first.visualOffsets.first()),
            endGlyph = ReaderSelectionGlyph(lastBlock.index, last.visualOffsets.last()),
        )
    }

    fun sourceSelection(selection: ReaderSelectionResult, document: RenderedDocument): ReaderSourceSelection? {
        val raw = SelectionMapper.toRawRange(document, selection.range) ?: return null
        if (raw.endByte > document.sourceBytes.size) return null
        return ReaderSourceSelection(
            rawRange = raw,
            selectedText = document.sourceBytes.copyOfRange(raw.startByte, raw.endByte).decodeToString(),
        )
    }

    fun visibleEndpointBounds(
        selection: ReaderSelectionResult,
        layouts: Map<Int, ReaderTextLayout>,
        viewport: Rect,
    ): Rect? {
        val endpoints = preferredGlyphs(selection)
        return endpoints.firstNotNullOfOrNull { glyphEndpoint ->
            val entry = layouts[glyphEndpoint.blockIndex] ?: return@firstNotNullOfOrNull null
            if (
                !entry.coordinates.isAttached ||
                glyphEndpoint.visualOffset !in 0 until entry.layout.layoutInput.text.length
            ) {
                return@firstNotNullOfOrNull null
            }
            val glyph = entry.layout.getBoundingBox(glyphEndpoint.visualOffset)
            val inRoot = Rect(
                topLeft = entry.coordinates.localToRoot(glyph.topLeft),
                bottomRight = entry.coordinates.localToRoot(glyph.bottomRight),
            )
            inRoot.takeIf { it.overlaps(viewport) }
        }
    }

    fun resolveActiveEndpoint(
        pointerInRoot: androidx.compose.ui.geometry.Offset?,
        startBounds: Rect?,
        endBounds: Rect?,
        default: ReaderSelectionEndpoint = ReaderSelectionEndpoint.End,
    ): ReaderSelectionEndpoint {
        val pointer = pointerInRoot ?: return default
        if (startBounds == null) return if (endBounds == null) default else ReaderSelectionEndpoint.End
        if (endBounds == null) return ReaderSelectionEndpoint.Start
        val startDistance = (pointer - startBounds.center).getDistanceSquared()
        val endDistance = (pointer - endBounds.center).getDistanceSquared()
        return if (startDistance <= endDistance) ReaderSelectionEndpoint.Start else ReaderSelectionEndpoint.End
    }

    fun resolveActiveEndpoint(
        selection: ReaderSelectionResult,
        layouts: Map<Int, ReaderTextLayout>,
        pointerInRoot: androidx.compose.ui.geometry.Offset?,
    ): ReaderSelectionEndpoint = resolveActiveEndpoint(
        pointerInRoot = pointerInRoot,
        startBounds = glyphBounds(selection.startGlyph, layouts),
        endBounds = glyphBounds(selection.endGlyph, layouts),
        default = selection.activeEndpoint,
    )

    fun hitTestEndpoint(
        pointerInRoot: androidx.compose.ui.geometry.Offset,
        startBounds: Rect?,
        endBounds: Rect?,
        maxDistancePx: Float,
    ): ReaderSelectionEndpoint? {
        val candidates = listOfNotNull(
            startBounds?.let { ReaderSelectionEndpoint.Start to distanceSquared(pointerInRoot, it) },
            endBounds?.let { ReaderSelectionEndpoint.End to distanceSquared(pointerInRoot, it) },
        ).filter { (_, distance) -> distance <= maxDistancePx * maxDistancePx }
        return candidates.minByOrNull { (_, distance) -> distance }?.first
    }

    fun hitTestEndpoint(
        selection: ReaderSelectionResult,
        layouts: Map<Int, ReaderTextLayout>,
        pointerInRoot: androidx.compose.ui.geometry.Offset,
        maxDistancePx: Float,
    ): ReaderSelectionEndpoint? = hitTestEndpoint(
        pointerInRoot = pointerInRoot,
        startBounds = glyphBounds(selection.startGlyph, layouts),
        endBounds = glyphBounds(selection.endGlyph, layouts),
        maxDistancePx = maxDistancePx,
    )

    fun preferredGlyphs(selection: ReaderSelectionResult): List<ReaderSelectionGlyph> =
        when (selection.activeEndpoint) {
            ReaderSelectionEndpoint.Start -> listOf(selection.startGlyph, selection.endGlyph)
            ReaderSelectionEndpoint.End -> listOf(selection.endGlyph, selection.startGlyph)
        }

    private fun glyphBounds(
        glyphEndpoint: ReaderSelectionGlyph,
        layouts: Map<Int, ReaderTextLayout>,
    ): Rect? {
        val entry = layouts[glyphEndpoint.blockIndex] ?: return null
        if (!entry.coordinates.isAttached || glyphEndpoint.visualOffset !in 0 until entry.layout.layoutInput.text.length) {
            return null
        }
        val glyph = entry.layout.getBoundingBox(glyphEndpoint.visualOffset)
        return Rect(
            topLeft = entry.coordinates.localToRoot(glyph.topLeft),
            bottomRight = entry.coordinates.localToRoot(glyph.bottomRight),
        )
    }

    private fun distanceSquared(point: androidx.compose.ui.geometry.Offset, bounds: Rect): Float {
        val dx = when {
            point.x < bounds.left -> bounds.left - point.x
            point.x > bounds.right -> point.x - bounds.right
            else -> 0f
        }
        val dy = when {
            point.y < bounds.top -> bounds.top - point.y
            point.y > bounds.bottom -> point.y - bounds.bottom
            else -> 0f
        }
        return dx * dx + dy * dy
    }

    fun generation(
        document: ReaderDocument,
        selectionDocument: RenderedDocument?,
        reviewEnabled: Boolean,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(if (reviewEnabled) 1 else 0)
        selectionDocument?.sourceBytes?.let(digest::update)
        document.blocks.forEach { block ->
            digest.update(block.sourceIndex)
            digest.update(block.kind.name)
            digest.update(block.canonicalText)
            block.runs.forEach { run ->
                digest.update(run.text)
                digest.update(run.kind.name)
                digest.update(run.sourceDisplayStart ?: -1)
                run.sourceByteBoundaries.orEmpty().forEach { boundary -> digest.update(boundary) }
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun documentBlock(text: AnnotatedString): DocumentBlock? {
        if (text.isEmpty()) return null
        val characters = text.indices.map { offset ->
            DocumentCharacter(provenanceAt(text, offset) ?: return null, text[offset])
        }
        val first = characters.first().provenance
        if (characters.any {
                it.provenance.blockIndex != first.blockIndex ||
                    it.provenance.generation != first.generation
            } || characters.map { it.provenance.visualOffset } != text.indices.toList()
        ) {
            return null
        }
        return DocumentBlock(first.blockIndex, first.generation, characters)
    }

    private fun provenanceAt(text: AnnotatedString, offset: Int): Provenance? {
        val annotations = text.getStringAnnotations(ProvenanceTag, offset, offset + 1)
        if (annotations.size != 1) return null
        val parts = annotations.single().item.split(Separator)
        if (parts.size != 5) return null
        val generation = parts[0].takeIf(String::isNotEmpty) ?: return null
        val blockIndex = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val visualOffset = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val sourceStart = parts[3].takeUnless { it == "x" }?.toIntOrNull()
        val sourceEnd = parts[4].takeUnless { it == "x" }?.toIntOrNull()
        if ((sourceStart == null) != (sourceEnd == null)) return null
        if (sourceStart != null && (sourceStart < 0 || sourceEnd != sourceStart + 1)) return null
        return Provenance(generation, blockIndex, visualOffset, sourceStart, sourceEnd)
    }

    private fun MessageDigest.update(value: String) {
        val bytes = value.encodeToByteArray()
        update(bytes.size)
        update(bytes)
    }

    private fun MessageDigest.update(value: Int) {
        update(byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        ))
    }

    private data class Provenance(
        val generation: String,
        val blockIndex: Int,
        val visualOffset: Int,
        val sourceStart: Int?,
        val sourceEnd: Int?,
    )

    private data class DocumentCharacter(val provenance: Provenance, val character: Char)
    private data class DocumentBlock(
        val index: Int,
        val generation: String,
        val characters: List<DocumentCharacter>,
    )

    private data class SelectedFragment(
        val blockIndex: Int,
        val characters: List<DocumentCharacter>,
    ) {
        val visualOffsets: List<Int> = characters.map { it.provenance.visualOffset }
    }
}
