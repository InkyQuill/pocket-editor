package net.inkyquill.pocketeditor.markdown

object SelectionMapper {
    fun toRawRange(document: RenderedDocument, range: TextRange): RawRange? {
        if (range.startBlock != range.endBlock) return null
        val block = document.blocks.getOrNull(range.startBlock) ?: return null
        if (block.hidden || range.start < 0 || range.end > block.text.length || range.start >= range.end) return null

        if (block.syntaxSpans.any { span ->
                range.start < span.end && span.start < range.end &&
                    !(range.start <= span.start && range.end >= span.end)
            }
        ) {
            return null
        }

        block.syntaxSpans
            .filter { span -> range.start == span.start && range.end == span.end }
            .maxByOrNull { span -> span.rawRange.endByte - span.rawRange.startByte }
            ?.let { return it.rawRange }

        val startByte = block.byteBoundaries.getOrNull(range.start)?.takeIf { it >= 0 } ?: return null
        val endByte = block.byteBoundaries.getOrNull(range.end)?.takeIf { it >= 0 } ?: return null
        if (startByte >= endByte) return null
        return RawRange(startByte, endByte)
    }
}
