package net.inkyquill.pocketeditor.markdown

data class RawRange(val startByte: Int, val endByte: Int) {
    init {
        require(startByte >= 0 && endByte >= startByte)
    }

    fun intersects(other: RawRange): Boolean = startByte < other.endByte && other.startByte < endByte
}

data class TextRange(
    val startBlock: Int,
    val start: Int,
    val endBlock: Int,
    val end: Int,
) {
    constructor(block: Int, start: Int, end: Int) : this(block, start, block, end)

    fun normalized(): TextRange = when {
        startBlock < endBlock -> this
        startBlock > endBlock -> TextRange(endBlock, end, startBlock, start)
        start <= end -> this
        else -> TextRange(startBlock, end, endBlock, start)
    }
}

enum class BlockKind {
    HIDDEN_SOURCE,
    HEADING,
    PARAGRAPH,
    QUOTE,
    LIST_ITEM,
    CODE_BLOCK,
    TABLE_ROW,
    HTML_BLOCK,
    THEMATIC_BREAK,
}

enum class RenderKind {
    TEXT,
    EMPHASIS,
    STRONG,
    LINK,
    FOOTNOTE_REFERENCE,
    CODE,
    INERT_HTML,
}

data class RenderRun(
    val text: String,
    val start: Int,
    val end: Int,
    val kind: RenderKind,
    val rawRange: RawRange,
    val footnoteLabel: String? = null,
)

data class SyntaxSpan(
    val start: Int,
    val end: Int,
    val rawRange: RawRange,
)

data class RenderedBlock(
    val index: Int,
    val kind: BlockKind,
    val text: String,
    val rawRange: RawRange,
    val runs: List<RenderRun>,
    val hidden: Boolean = false,
    internal val byteBoundaries: IntArray = IntArray(0),
    internal val syntaxSpans: List<SyntaxSpan> = emptyList(),
    val headingLevel: Int? = null,
) {
    fun rawText(document: RenderedDocument): String =
        document.sourceBytes.copyOfRange(rawRange.startByte, rawRange.endByte).decodeToString()
}

data class RenderedDocument(
    val source: String,
    val sourceBytes: ByteArray,
    val blocks: List<RenderedBlock>,
    val footnotes: Map<String, String> = emptyMap(),
)
