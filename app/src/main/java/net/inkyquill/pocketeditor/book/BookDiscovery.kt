package net.inkyquill.pocketeditor.book

import java.security.MessageDigest

data class ChapterProposal(
    val path: String,
    val suggestedTitle: String,
    val suggestedOrder: Int,
)

data class DiscoveryFile(
    val path: String,
    val bytes: ByteArray,
    val sha256: String? = null,
)

data class MissingChapter(
    val chapter: ChapterEntry,
    val sameHashRenamePath: String?,
)

data class DiscoveryResult(
    val proposals: List<ChapterProposal>,
    val missing: List<MissingChapter>,
)

class BookDiscovery {
    fun propose(
        files: List<DiscoveryFile>,
        manifest: BookManifest? = null,
        cachedSourceHashes: Map<String, String> = emptyMap(),
    ): DiscoveryResult {
        val ordinaryMarkdown = files
            .filter { file ->
                file.path.endsWith(".md") &&
                    '/' !in file.path &&
                    '\\' !in file.path &&
                    !file.path.startsWith('.')
            }
        val knownPaths = manifest?.let { value ->
            value.chapters.map(ChapterEntry::path).toSet() + value.ignoredFiles
        }.orEmpty()
        val ordered = ordinaryMarkdown
            .map { file -> file to ChapterTitleExtractor.extract(file.path, file.bytes) }
            .sortedWith { left, right ->
                val leftNumber = left.second.number
                val rightNumber = right.second.number
                when {
                    leftNumber != null && rightNumber == null -> -1
                    leftNumber == null && rightNumber != null -> 1
                    leftNumber != null && rightNumber != null && leftNumber != rightNumber ->
                        leftNumber.compareTo(rightNumber)
                    else -> naturalCompare(left.first.path, right.first.path)
                }
            }
        val proposals = ordered
            .filterNot { (file, _) -> file.path in knownPaths }
            .mapIndexed { index, (file, metadata) ->
                ChapterProposal(file.path, metadata.title, index)
            }
        val available = ordinaryMarkdown.associateBy(DiscoveryFile::path)
        val unlisted = ordinaryMarkdown.filterNot { file -> file.path in knownPaths }
        val missing = manifest?.chapters.orEmpty()
            .filterNot { chapter -> chapter.path in available }
            .map { chapter ->
                val expectedHash = cachedSourceHashes[chapter.path]
                val matches = if (expectedHash == null) emptyList() else {
                    unlisted.filter { file -> (file.sha256 ?: sha256(file.bytes)) == expectedHash }
                }
                MissingChapter(chapter, matches.singleOrNull()?.path)
            }
        return DiscoveryResult(proposals, missing)
    }

    fun add(
        manifest: BookManifest,
        proposal: ChapterProposal,
        chapterId: String,
        order: Int,
    ): BookManifest {
        require(order in 0..manifest.chapters.size)
        require(manifest.chapters.none { it.path == proposal.path })
        require(proposal.path !in manifest.ignoredFiles)
        val chapters = manifest.chapters.toMutableList().apply {
            add(order, ChapterEntry(chapterId, proposal.path))
        }
        return manifest.copy(chapters = chapters).validated()
    }

    fun ignore(manifest: BookManifest, path: String): BookManifest {
        require(manifest.chapters.none { it.path == path })
        return manifest.copy(ignoredFiles = (manifest.ignoredFiles + path).distinct()).validated()
    }

    fun replace(manifest: BookManifest, chapterId: String, newPath: String): BookManifest {
        val old = requireNotNull(manifest.chapters.singleOrNull { it.id == chapterId }) {
            "Unknown chapter id: $chapterId"
        }
        require(manifest.chapters.none { it.id != chapterId && it.path == newPath })
        return manifest.copy(
            chapters = manifest.chapters.map { chapter ->
                if (chapter.id == chapterId) chapter.copy(path = newPath) else chapter
            },
            ignoredFiles = (manifest.ignoredFiles - newPath + old.path).distinct(),
        ).validated()
    }

    fun locate(manifest: BookManifest, chapterId: String, path: String): BookManifest {
        require(manifest.chapters.none { it.path == path })
        require(path !in manifest.ignoredFiles)
        return manifest.copy(
            chapters = manifest.chapters.map { chapter ->
                if (chapter.id == chapterId) chapter.copy(path = path) else chapter
            }.also { chapters -> require(chapters.any { it.id == chapterId }) },
        ).validated()
    }

    fun remove(manifest: BookManifest, chapterId: String): BookManifest = manifest.copy(
        chapters = manifest.chapters.filterNot { it.id == chapterId }
            .also { chapters -> require(chapters.size < manifest.chapters.size) },
    ).validated()

    private fun BookManifest.validated(): BookManifest = also { BookManifest.encode(it) }

    private fun naturalCompare(left: String, right: String): Int {
        val leftParts = NATURAL_PART.findAll(left).map { it.value }.toList()
        val rightParts = NATURAL_PART.findAll(right).map { it.value }.toList()
        for (index in 0 until minOf(leftParts.size, rightParts.size)) {
            val a = leftParts[index]
            val b = rightParts[index]
            val comparison = if (a.first().isDigit() && b.first().isDigit()) {
                a.trimStart('0').ifEmpty { "0" }.compareTo(b.trimStart('0').ifEmpty { "0" })
                    .takeIf { a.trimStart('0').length == b.trimStart('0').length }
                    ?: a.trimStart('0').length.compareTo(b.trimStart('0').length)
            } else {
                a.compareTo(b, ignoreCase = true)
            }
            if (comparison != 0) return comparison
        }
        return leftParts.size.compareTo(rightParts.size).takeIf { it != 0 } ?: left.compareTo(right)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        val NATURAL_PART = Regex("\\d+|\\D+")
    }
}
