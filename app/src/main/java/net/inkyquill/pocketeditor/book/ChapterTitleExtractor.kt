package net.inkyquill.pocketeditor.book

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.commonmark.node.Code
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.Parser

data class ChapterMetadata(
    val title: String,
    val number: Int?,
)

object ChapterTitleExtractor {
    fun extract(path: String, bytes: ByteArray): ChapterMetadata {
        val lines = decodeUtf8(bytes).lineSequence().toList()
        var bodyStart = 0
        var number: Int? = null
        var title: String? = null
        if (lines.firstOrNull() == "---") {
            val end = lines.drop(1).indexOf("---").let { index -> if (index < 0) -1 else index + 1 }
            if (end > 0) {
                val values = lines.subList(1, end).mapNotNull { line ->
                    val separator = line.indexOf(':')
                    if (separator <= 0) null else line.substring(0, separator).trim() to
                        line.substring(separator + 1).trim().removeSurrounding("\"").removeSurrounding("'")
                }.toMap()
                number = values["number"]?.toIntOrNull()
                title = values["title"]?.takeIf(String::isNotBlank)
                bodyStart = end + 1
            }
        }
        val heading = firstLevelOneHeading(lines.drop(bodyStart).joinToString("\n"))
        return ChapterMetadata(title ?: heading ?: path.removeSuffix(".md"), number)
    }

    private fun decodeUtf8(bytes: ByteArray): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    private fun firstLevelOneHeading(source: String): String? {
        val headings = mutableListOf<Heading>()
        collectHeadings(markdownParser.parse(source), headings)
        return headings.firstOrNull { it.level == 1 }
            ?.plainText()
            ?.takeIf(String::isNotBlank)
    }

    private fun collectHeadings(node: Node, output: MutableList<Heading>) {
        var child = node.firstChild
        while (child != null) {
            if (child is Heading) output += child
            collectHeadings(child, output)
            child = child.next
        }
    }

    private fun Node.plainText(): String = buildString { appendPlainText(this@plainText) }.trim()

    private fun StringBuilder.appendPlainText(node: Node) {
        when (node) {
            is Text -> append(node.literal)
            is Code -> append(node.literal)
            is SoftLineBreak, is HardLineBreak -> append(' ')
        }
        var child = node.firstChild
        while (child != null) {
            appendPlainText(child)
            child = child.next
        }
    }

    private val markdownParser = Parser.builder().build()
}
