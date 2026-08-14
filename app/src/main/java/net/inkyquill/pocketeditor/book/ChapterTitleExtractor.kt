package net.inkyquill.pocketeditor.book

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

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
        val heading = lines.drop(bodyStart).firstNotNullOfOrNull { line ->
            H1.matchEntire(line.trim())?.groupValues?.get(1)?.trim()?.trimEnd('#')?.trim()?.takeIf(String::isNotBlank)
        }
        return ChapterMetadata(title ?: heading ?: path.removeSuffix(".md"), number)
    }

    private fun decodeUtf8(bytes: ByteArray): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    private val H1 = Regex("^#\\s+(.+)$")
}
