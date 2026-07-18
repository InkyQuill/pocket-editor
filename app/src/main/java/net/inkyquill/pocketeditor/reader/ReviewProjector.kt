package net.inkyquill.pocketeditor.reader

import net.inkyquill.pocketeditor.anchor.AnchorResolution
import net.inkyquill.pocketeditor.anchor.AnchorResolver
import net.inkyquill.pocketeditor.anchor.Resolved
import net.inkyquill.pocketeditor.markdown.BlockKind
import net.inkyquill.pocketeditor.markdown.MarkdownParser
import net.inkyquill.pocketeditor.markdown.RawRange
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
)

data class ReaderDocument(
    val blocks: List<ReaderBlock>,
    val unresolved: List<UnresolvedReview> = emptyList(),
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
                        runs = block.text.takeIf { it.isNotEmpty() }
                            ?.let { listOf(ReaderRun(it, ReaderRunKind.CANONICAL)) }
                            .orEmpty(),
                    )
                },
            )
        }

        val unresolved = mutableListOf<UnresolvedReview>()
        val signals = review.signals.mapNotNull { signal ->
            when (val resolution = AnchorResolver.resolve(rendered.sourceBytes, signal.anchor, signal.selectedText)) {
                is Resolved -> locate(rendered, resolution)?.let { location -> ActiveSignal(signal, resolution.asRawRange(), location) }
                    ?: run {
                        unresolved += UnresolvedReview(signal.id, ReviewRecordKind.SIGNAL, resolution)
                        null
                    }
                else -> {
                    unresolved += UnresolvedReview(signal.id, ReviewRecordKind.SIGNAL, resolution)
                    null
                }
            }
        }
        val edits = review.edits.mapNotNull { edit ->
            when (val resolution = AnchorResolver.resolve(rendered.sourceBytes, edit.anchor, edit.before)) {
                is Resolved -> locate(rendered, resolution)?.let { location -> ActiveEdit(edit, resolution.asRawRange(), location) }
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
                    .filter { it.signal.comment.isNotEmpty() }
                    .sortedWith(compareBy<ActiveSignal>({ it.rawRange.startByte }, { it.rawRange.endByte }, { it.signal.id }))
                    .map { ReaderComment(it.signal.id, it.signal.type, it.signal.comment, it.rawRange) },
            )
        }
        return ReaderDocument(blocks, unresolved)
    }

    private fun projectRuns(
        block: RenderedBlock,
        signals: List<ActiveSignal>,
        edits: List<ActiveEdit>,
    ): List<ReaderRun> {
        val result = mutableListOf<ReaderRun>()
        var cursor = 0
        for (edit in edits) {
            appendCanonical(result, block.text, cursor, edit.location.start, signals)
            val renderedBefore = block.text.substring(edit.location.start, edit.location.end)
            val renderedAfter = renderFragment(edit.edit.after)
            var beforeCursor = edit.location.start
            EditDiff.compute(renderedBefore, renderedAfter).forEach { diff ->
                when (diff.kind) {
                    DiffKind.UNCHANGED, DiffKind.DELETED -> {
                        val end = beforeCursor + diff.text.length
                        appendSourceBacked(
                            result,
                            block.text,
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
        appendCanonical(result, block.text, cursor, block.text.length, signals)
        return result
    }

    private fun renderFragment(raw: String): String = MarkdownParser.parse(raw).blocks
        .filterNot { it.hidden }
        .joinToString("\n\n") { it.text }

    private fun appendCanonical(
        output: MutableList<ReaderRun>,
        text: String,
        start: Int,
        end: Int,
        signals: List<ActiveSignal>,
    ) = appendSourceBacked(output, text, start, end, signals, ReaderRunKind.CANONICAL)

    private fun appendSourceBacked(
        output: MutableList<ReaderRun>,
        text: String,
        start: Int,
        end: Int,
        signals: List<ActiveSignal>,
        kind: ReaderRunKind,
    ) {
        if (start >= end) return
        val boundaries = buildSet {
            add(start)
            add(end)
            signals.forEach { signal ->
                if (signal.location.start in (start + 1) until end) add(signal.location.start)
                if (signal.location.end in (start + 1) until end) add(signal.location.end)
            }
        }.sorted()
        boundaries.zipWithNext().forEach { (pieceStart, pieceEnd) ->
            val active = signals.filter { it.location.start < pieceEnd && pieceStart < it.location.end }
            output.addMerged(
                ReaderRun(
                    text.substring(pieceStart, pieceEnd),
                    kind,
                    active.mapTo(linkedSetOf()) { it.signal.id },
                    active.mapTo(linkedSetOf()) { it.signal.type },
                ),
            )
        }
    }

    private fun MutableList<ReaderRun>.addMerged(run: ReaderRun) {
        if (run.text.isEmpty()) return
        val previous = lastOrNull()
        if (previous != null && previous.kind == run.kind && previous.signalIds == run.signalIds && previous.signalTypes == run.signalTypes) {
            this[lastIndex] = previous.copy(text = previous.text + run.text)
        } else {
            add(run)
        }
    }

    private fun locate(document: RenderedDocument, resolved: Resolved): LocalRange? {
        return document.blocks.asSequence()
            .filterNot { it.hidden }
            .mapNotNull { block ->
                val start = block.byteBoundaries.indexOfFirst { it == resolved.startByte }
                val end = block.byteBoundaries.indexOfLast { it == resolved.endByte }
                if (start >= 0 && end > start) LocalRange(block.index, start, end) else null
            }
            .singleOrNull()
    }

    private fun Resolved.asRawRange() = RawRange(startByte, endByte)

    private data class LocalRange(val blockIndex: Int, val start: Int, val end: Int)
    private data class ActiveSignal(val signal: Signal, val rawRange: RawRange, val location: LocalRange)
    private data class ActiveEdit(val edit: Edit, val rawRange: RawRange, val location: LocalRange)
}
