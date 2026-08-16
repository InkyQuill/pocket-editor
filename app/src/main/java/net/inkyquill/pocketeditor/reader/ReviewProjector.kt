package net.inkyquill.pocketeditor.reader

import net.inkyquill.pocketeditor.anchor.AnchorResolution
import net.inkyquill.pocketeditor.anchor.AnchorResolver
import net.inkyquill.pocketeditor.anchor.Resolved
import net.inkyquill.pocketeditor.markdown.BlockKind
import net.inkyquill.pocketeditor.markdown.MarkdownParser
import net.inkyquill.pocketeditor.markdown.RawRange
import net.inkyquill.pocketeditor.markdown.RenderKind
import net.inkyquill.pocketeditor.markdown.RenderedBlock
import net.inkyquill.pocketeditor.markdown.RenderedDocument
import net.inkyquill.pocketeditor.review.DiffKind
import net.inkyquill.pocketeditor.review.Edit
import net.inkyquill.pocketeditor.review.EditDiff
import net.inkyquill.pocketeditor.review.ReviewDocument
import net.inkyquill.pocketeditor.review.Signal
import net.inkyquill.pocketeditor.review.SignalType

enum class ReaderRunKind {
    CANONICAL,
    DELETED,
    ADDED,
}

data class ReaderRun(
    val text: String,
    val kind: ReaderRunKind,
    val signalIds: Set<String> = emptySet(),
    val signalTypes: Set<SignalType> = emptySet(),
    val sourceByteBoundaries: List<Int>? = null,
    val renderKind: RenderKind = RenderKind.TEXT,
    val footnoteLabel: String? = null,
    val sourceDisplayStart: Int? = null,
)

data class ReaderSourceSelection(
    val rawRange: RawRange,
    val selectedText: String,
    val spansMultipleBlocks: Boolean = false,
)

data class ReaderComment(
    val signalId: String,
    val type: SignalType,
    val text: String,
    val rawRange: RawRange,
)

enum class ReviewRecordKind {
    SIGNAL,
    EDIT,
}

data class UnresolvedReview(
    val recordId: String,
    val kind: ReviewRecordKind,
    val resolution: AnchorResolution,
)

data class ReaderBlock(
    val sourceIndex: Int,
    val kind: BlockKind,
    val canonicalText: String,
    val rawRange: RawRange,
    val runs: List<ReaderRun>,
    val comments: List<ReaderComment> = emptyList(),
    val protectedRawRanges: List<RawRange> = emptyList(),
    val headingLevel: Int? = null,
    val rawText: String = "",
) {
    fun displayRangeForRaw(target: RawRange): net.inkyquill.pocketeditor.markdown.TextRange? {
        var displayCursor = 0
        var displayStart: Int? = null
        var displayEnd: Int? = null
        for (run in runs) {
            val boundaries = run.sourceByteBoundaries
            if (boundaries != null) {
                boundaries.indexOf(target.startByte).takeIf { it >= 0 }?.let { displayStart = displayCursor + it }
                boundaries.indexOf(target.endByte).takeIf { it >= 0 }?.let { displayEnd = displayCursor + it }
            }
            displayCursor += run.text.length
        }
        val start = displayStart ?: return null
        val end = displayEnd ?: return null
        if (end <= start) return null
        return net.inkyquill.pocketeditor.markdown.TextRange(sourceIndex, start, end)
    }

    fun sourceSelection(displayStart: Int, displayEnd: Int): ReaderSourceSelection? {
        if (displayStart < 0 || displayEnd <= displayStart) return null
        var cursor = 0
        var rawStart: Int? = null
        var rawEnd: Int? = null
        var usedAtomicRunFallback = false
        val selected = StringBuilder()
        for (run in runs) {
            val runStart = cursor
            val runEnd = cursor + run.text.length
            cursor = runEnd
            val start = maxOf(displayStart, runStart)
            val end = minOf(displayEnd, runEnd)
            if (start >= end) continue
            val boundaries = run.sourceByteBoundaries ?: return null
            val localStart = start - runStart
            val localEnd = end - runStart
            var pieceStart = boundaries.getOrNull(localStart) ?: return null
            var pieceEnd = boundaries.getOrNull(localEnd) ?: return null
            if (pieceStart < 0 || pieceEnd < 0) {
                // Some run kinds (e.g. inline code) only track the raw position of their
                // outer edges, not each interior character. Any touch is widened to the
                // whole node below anyway, so fall back to the run's own full raw span
                // instead of failing outright.
                pieceStart = boundaries.firstOrNull { it >= 0 } ?: return null
                pieceEnd = boundaries.lastOrNull { it >= 0 } ?: return null
                usedAtomicRunFallback = true
            }
            if (pieceEnd < pieceStart) return null
            if (rawEnd != null && rawEnd != pieceStart) return null
            if (rawStart == null) rawStart = pieceStart
            rawEnd = pieceEnd
            selected.append(run.text, localStart, localEnd)
        }
        if (displayEnd > cursor) return null
        val mapped = RawRange(rawStart ?: return null, rawEnd ?: return null)
        val widened = widenPastProtectedRanges(mapped)
        if (!usedAtomicRunFallback && widened == mapped) {
            return ReaderSourceSelection(mapped, selected.toString(), spansMultipleBlocks = false)
        }

        // Widening pulled in raw bytes (Markdown syntax markers) that have no corresponding
        // run text, so the selected text must be re-sliced from the block's own raw source
        // rather than built from run.text - anchor resolution needs it to be byte-exact.
        val localStart = widened.startByte - rawRange.startByte
        val localEnd = widened.endByte - rawRange.startByte
        val rawBytes = rawText.encodeToByteArray()
        if (localStart < 0 || localEnd > rawBytes.size) return null
        return ReaderSourceSelection(
            widened,
            rawBytes.copyOfRange(localStart, localEnd).decodeToString(),
            spansMultipleBlocks = false,
        )
    }

    /**
     * A selection that only partially overlaps a Markdown syntax node (e.g. just "bold" inside
     * `**bold**`, or crossing into a link's brackets) can't attribute a signal or edit to just
     * part of that node, so it's widened to fully include every node it touches instead of being
     * rejected outright.
     */
    private fun widenPastProtectedRanges(start: RawRange): RawRange {
        var candidate = start
        var changed = true
        while (changed) {
            changed = false
            for (protected in protectedRawRanges) {
                val clipsWithoutCovering = candidate.intersects(protected) &&
                    !(candidate.startByte <= protected.startByte && candidate.endByte >= protected.endByte)
                if (clipsWithoutCovering) {
                    candidate = RawRange(
                        minOf(candidate.startByte, protected.startByte),
                        maxOf(candidate.endByte, protected.endByte),
                    )
                    changed = true
                }
            }
        }
        return candidate
    }
}

data class ReaderDocument(
    val blocks: List<ReaderBlock>,
    val unresolved: List<UnresolvedReview> = emptyList(),
    val footnotes: Map<String, String> = emptyMap(),
) {
    val reviewObjectCount: Int
        get() = blocks.sumOf { block ->
            block.comments.size + block.runs.count { it.kind != ReaderRunKind.CANONICAL || it.signalIds.isNotEmpty() }
        } + unresolved.size
}

object ReviewProjector {
    fun project(rendered: RenderedDocument, review: ReviewDocument?, reviewMode: Boolean): ReaderDocument {
        val visibleBlocks = rendered.blocks.filterNot { it.hidden }
        if (!reviewMode || review == null) {
            return ReaderDocument(
                visibleBlocks.map { block ->
                    ReaderBlock(
                        sourceIndex = block.index,
                        kind = block.kind,
                        canonicalText = block.text,
                        rawRange = block.rawRange,
                        runs = sourceRuns(block),
                        protectedRawRanges = block.syntaxSpans.map { it.rawRange },
                        headingLevel = block.headingLevel,
                        rawText = block.rawText(rendered),
                    )
                },
                footnotes = rendered.footnotes,
            )
        }

        val unresolved = mutableListOf<UnresolvedReview>()
        val signals = review.signals.flatMap { signal ->
            when (val resolution = AnchorResolver.resolve(rendered.sourceBytes, signal.anchor, signal.selectedText)) {
                is Resolved -> locateSlices(rendered, resolution.asRawRange()).let { locations ->
                    if (locations.isEmpty()) {
                        unresolved += UnresolvedReview(signal.id, ReviewRecordKind.SIGNAL, resolution)
                        emptyList()
                    } else {
                        locations.mapIndexed { index, location ->
                            ActiveSignal(signal, resolution.asRawRange(), location, attachComment = index == 0)
                        }
                    }
                }
                else -> {
                    unresolved += UnresolvedReview(signal.id, ReviewRecordKind.SIGNAL, resolution)
                    emptyList()
                }
            }
        }
        val edits = review.edits.mapNotNull { edit ->
            when (val resolution = AnchorResolver.resolve(rendered.sourceBytes, edit.anchor, edit.before)) {
                is Resolved -> locateSingleBlock(rendered, resolution.asRawRange())
                    ?.let { location -> ActiveEdit(edit, resolution.asRawRange(), location) }
                    ?: run {
                        unresolved += UnresolvedReview(edit.id, ReviewRecordKind.EDIT, resolution)
                        null
                    }
                else -> {
                    unresolved += UnresolvedReview(edit.id, ReviewRecordKind.EDIT, resolution)
                    null
                }
            }
        }

        val blocks = visibleBlocks.map { block ->
            val blockSignals = signals.filter { it.location.blockIndex == block.index }
            val blockEdits = edits.filter { it.location.blockIndex == block.index }.sortedBy { it.location.start }
            ReaderBlock(
                sourceIndex = block.index,
                kind = block.kind,
                canonicalText = block.text,
                rawRange = block.rawRange,
                runs = projectRuns(block, blockSignals, blockEdits),
                comments = blockSignals
                    .filter { it.attachComment && it.signal.comment.isNotEmpty() }
                    .sortedWith(compareBy<ActiveSignal>({ it.rawRange.startByte }, { it.rawRange.endByte }, { it.signal.id }))
                    .map { ReaderComment(it.signal.id, it.signal.type, it.signal.comment, it.rawRange) },
                protectedRawRanges = block.syntaxSpans.map { it.rawRange },
                headingLevel = block.headingLevel,
                rawText = block.rawText(rendered),
            )
        }
        return ReaderDocument(
            blocks = blocks,
            unresolved = unresolved,
            footnotes = rendered.footnotes,
        )
    }

    private fun projectRuns(
        block: RenderedBlock,
        signals: List<ActiveSignal>,
        edits: List<ActiveEdit>,
    ): List<ReaderRun> {
        val result = mutableListOf<ReaderRun>()
        var cursor = 0
        for (edit in edits) {
            appendCanonical(result, block, cursor, edit.location.start, signals)
            val renderedBefore = block.text.substring(edit.location.start, edit.location.end)
            val renderedAfter = renderFragment(edit.edit.after)
            var beforeCursor = edit.location.start
            EditDiff.compute(renderedBefore, renderedAfter).forEach { diff ->
                when (diff.kind) {
                    DiffKind.UNCHANGED, DiffKind.DELETED -> {
                        val end = beforeCursor + diff.text.length
                        appendSourceBacked(
                            result,
                            block,
                            beforeCursor,
                            end,
                            signals,
                            if (diff.kind == DiffKind.DELETED) ReaderRunKind.DELETED else ReaderRunKind.CANONICAL,
                        )
                        beforeCursor = end
                    }
                    DiffKind.ADDED -> result.addMerged(ReaderRun(diff.text, ReaderRunKind.ADDED))
                }
            }
            cursor = edit.location.end
        }
        appendCanonical(result, block, cursor, block.text.length, signals)
        return result
    }

    private fun renderFragment(raw: String): String = MarkdownParser.parse(raw).blocks
        .filterNot { it.hidden }
        .joinToString("\n\n") { it.text }

    private fun sourceRuns(block: RenderedBlock): List<ReaderRun> = buildList {
        appendSourceBacked(
            output = this,
            block = block,
            start = 0,
            end = block.text.length,
            signals = emptyList(),
            kind = ReaderRunKind.CANONICAL,
        )
    }

    private fun appendCanonical(
        output: MutableList<ReaderRun>,
        block: RenderedBlock,
        start: Int,
        end: Int,
        signals: List<ActiveSignal>,
    ) = appendSourceBacked(output, block, start, end, signals, ReaderRunKind.CANONICAL)

    private fun appendSourceBacked(
        output: MutableList<ReaderRun>,
        block: RenderedBlock,
        start: Int,
        end: Int,
        signals: List<ActiveSignal>,
        kind: ReaderRunKind,
    ) {
        if (start >= end) return
        val boundaries = buildSet {
            add(start)
            add(end)
            block.runs.forEach { run ->
                if (run.start in (start + 1) until end) add(run.start)
                if (run.end in (start + 1) until end) add(run.end)
            }
            signals.forEach { signal ->
                if (signal.location.start in (start + 1) until end) add(signal.location.start)
                if (signal.location.end in (start + 1) until end) add(signal.location.end)
            }
        }.sorted()
        boundaries.zipWithNext().forEach { (pieceStart, pieceEnd) ->
            val active = signals.filter { it.location.start < pieceEnd && pieceStart < it.location.end }
            val sourceRun = block.runs
                .firstOrNull { run -> run.start <= pieceStart && pieceEnd <= run.end }
            val renderKind = sourceRun?.kind ?: RenderKind.TEXT
            output.addMerged(
                ReaderRun(
                    block.text.substring(pieceStart, pieceEnd),
                    kind,
                    active.mapTo(linkedSetOf()) { it.signal.id },
                    active.mapTo(linkedSetOf()) { it.signal.type },
                    block.byteBoundaries.slice(pieceStart..pieceEnd),
                    renderKind,
                    sourceRun?.footnoteLabel,
                    pieceStart,
                ),
            )
        }
    }

    private fun MutableList<ReaderRun>.addMerged(run: ReaderRun) {
        if (run.text.isEmpty()) return
        val previous = lastOrNull()
        if (
            previous != null &&
            previous.kind == run.kind &&
            previous.renderKind == run.renderKind &&
            previous.footnoteLabel == run.footnoteLabel &&
            previous.signalIds == run.signalIds &&
            previous.signalTypes == run.signalTypes &&
            provenanceCanMerge(previous, run)
        ) {
            this[lastIndex] = previous.copy(
                text = previous.text + run.text,
                sourceByteBoundaries = previous.sourceByteBoundaries?.plus(run.sourceByteBoundaries.orEmpty().drop(1)),
            )
        } else {
            add(run)
        }
    }

    private fun provenanceCanMerge(previous: ReaderRun, next: ReaderRun): Boolean = when {
        previous.sourceByteBoundaries == null && next.sourceByteBoundaries == null -> true
        previous.sourceByteBoundaries == null || next.sourceByteBoundaries == null -> false
        else -> previous.sourceByteBoundaries.last() == next.sourceByteBoundaries.first()
    }

    internal fun locateSlices(document: RenderedDocument, rawRange: RawRange): List<LocalRange> {
        if (document.blocks.any { it.hidden && it.rawRange.intersects(rawRange) }) return emptyList()
        return document.blocks.mapNotNull { block ->
            if (block.hidden || !block.rawRange.intersects(rawRange)) return@mapNotNull null
            val intersectionStart = maxOf(block.rawRange.startByte, rawRange.startByte)
            val intersectionEnd = minOf(block.rawRange.endByte, rawRange.endByte)
            val start = if (rawRange.startByte >= block.rawRange.startByte) {
                block.byteBoundaries.indexOfFirst { it == intersectionStart }
            } else {
                block.byteBoundaries.indexOfFirst { it >= intersectionStart }
            }
            val end = if (rawRange.endByte <= block.rawRange.endByte) {
                block.byteBoundaries.indexOfLast { it == intersectionEnd }
            } else {
                block.byteBoundaries.indexOfLast { it in 0..intersectionEnd }
            }
            if (start >= 0 && end > start) LocalRange(block.index, start, end) else null
        }
    }

    private fun locateSingleBlock(document: RenderedDocument, rawRange: RawRange): LocalRange? =
        document.blocks.asSequence()
            .filterNot { it.hidden }
            .mapNotNull { block ->
                val start = block.byteBoundaries.indexOfFirst { it == rawRange.startByte }
                val end = block.byteBoundaries.indexOfLast { it == rawRange.endByte }
                if (start >= 0 && end > start) LocalRange(block.index, start, end) else null
            }
            .singleOrNull()

    private fun Resolved.asRawRange() = RawRange(startByte, endByte)

    internal data class LocalRange(val blockIndex: Int, val start: Int, val end: Int)
    private data class ActiveSignal(
        val signal: Signal,
        val rawRange: RawRange,
        val location: LocalRange,
        val attachComment: Boolean,
    )
    private data class ActiveEdit(val edit: Edit, val rawRange: RawRange, val location: LocalRange)
}
