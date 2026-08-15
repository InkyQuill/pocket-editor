package net.inkyquill.pocketeditor.load

import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.book.ChapterEntry
import net.inkyquill.pocketeditor.database.ProgressiveLoadDao
import net.inkyquill.pocketeditor.database.ProgressiveLoadFileEntity
import net.inkyquill.pocketeditor.storage.BookPaths
import net.inkyquill.pocketeditor.storage.StrictUtf8
import net.inkyquill.pocketeditor.yandex.RemoteEntry
import net.inkyquill.pocketeditor.yandex.RemoteFile
import net.inkyquill.pocketeditor.yandex.YandexDiskError
import net.inkyquill.pocketeditor.yandex.YandexDiskGateway

data class ProgressiveBookSeed(
    val manifest: BookManifest,
    val remoteRootPath: String,
    val files: List<ProgressiveLoadFileEntity>,
    val rawBinder: Boolean,
    val remoteManifest: RemoteFile?,
)

fun interface ProgressiveSeedInstaller {
    suspend fun install(
        seed: ProgressiveBookSeed,
        cachedSources: Map<String, ByteArray>,
    ): ProgressiveLoadSnapshot
}

class ProgressiveBookLoader private constructor(
    private val gateway: YandexDiskGateway,
    private val loads: ProgressiveLoadDao,
    private val installer: ProgressiveSeedInstaller,
    private val bookIdFactory: () -> String,
    private val chapterIdFactory: () -> String,
) {
    private val starts = Mutex()
    private val installedByRoot = mutableMapOf<String, ProgressiveLoadSnapshot>()

    suspend fun start(remoteRootPath: String): ProgressiveLoadSnapshot = starts.withLock {
        val root = normalizeRoot(remoteRootPath)
        installedByRoot[root]?.let { return@withLock it }
        loads.getJobByRemoteRoot(root)?.let { job ->
            return@withLock requireNotNull(loads.snapshot(job.bookId)).also { installedByRoot[root] = it }
        }
        val entries = gateway.listFolder(root)
        val seed = try {
            buildSeed(root, entries)
        } catch (failure: YandexDiskError) {
            throw failure
        } catch (failure: Exception) {
            throw YandexDiskError.InvalidRemote("Invalid Yandex book structure", failure)
        }
        installer.install(seed, emptyMap()).also { installedByRoot[root] = it }
    }

    private suspend fun buildSeed(root: String, entries: List<RemoteEntry>): ProgressiveBookSeed {
        val files = entries.filter { it.type == "file" }
        val manifestEntry = files.singleOrNull { it.name == BookPaths.MANIFEST_NAME }
        return if (manifestEntry != null) buildManifestSeed(root, files, manifestEntry) else buildRawSeed(root, files)
    }

    private fun buildRawSeed(root: String, entries: List<RemoteEntry>): ProgressiveBookSeed {
        val normalized = entries.asSequence()
            .filter { it.name.isOrdinaryMarkdown() }
            .map { it to normalizedRelativePath(it.name) }
            .toList()
        require(normalized.isNotEmpty()) { "Book folder has no ordinary Markdown files" }
        require(normalized.map { it.second }.distinct().size == normalized.size) {
            "Markdown paths collide after Unicode normalization"
        }
        val ordered = normalized.sortedWith(compareBy({ it.second.lowercase(Locale.ROOT) }, { it.second }))
        val bookId = bookIdFactory()
        val chapters = ordered.map { (_, path) -> ChapterEntry(chapterIdFactory(), path) }
        val manifest = BookManifest(
            schemaVersion = BookManifest.SCHEMA_VERSION,
            bookId = bookId,
            title = root.substringAfterLast('/').ifBlank { "Book" },
            chapters = chapters,
        )
        BookManifest.decode(BookManifest.encode(manifest))
        return ProgressiveBookSeed(
            manifest,
            root,
            ordered.mapIndexed { index, (entry, path) ->
                ProgressiveLoadFileEntity(
                    bookId, path, chapters[index].id, index, entry.revision, entry.size,
                    null, ProgressiveLoadFileState.PENDING, initialPriority(index),
                )
            },
            rawBinder = true,
            remoteManifest = null,
        )
    }

    private suspend fun buildManifestSeed(
        root: String,
        entries: List<RemoteEntry>,
        manifestEntry: RemoteEntry,
    ): ProgressiveBookSeed {
        val remoteManifest = gateway.download(manifestEntry.path)
        val manifest = BookManifest.decode(StrictUtf8.decode(remoteManifest.bytes, "Book manifest"))
        require(manifest.chapters.isNotEmpty()) { "Book manifest has no chapters" }
        val entriesByPath = entries.associateBy { normalizedRelativePath(it.name) }
        val rows = manifest.chapters.mapIndexed { index, chapter ->
            require(chapter.path.isOrdinaryMarkdown()) { "Tracked source is not an ordinary Markdown file: ${chapter.path}" }
            val path = normalizedRelativePath(chapter.path)
            val entry = requireNotNull(entriesByPath[path]) { "Tracked source is missing: $path" }
            ProgressiveLoadFileEntity(
                manifest.bookId, path, chapter.id, index, entry.revision, entry.size,
                null, ProgressiveLoadFileState.PENDING, initialPriority(index),
            )
        }
        return ProgressiveBookSeed(manifest, root, rows, rawBinder = false, remoteManifest = remoteManifest)
    }

    companion object {
        internal fun builderOnly(
            gateway: YandexDiskGateway,
            loads: ProgressiveLoadDao,
            installer: ProgressiveSeedInstaller,
            bookIdFactory: () -> String = { UUID.randomUUID().toString() },
            chapterIdFactory: () -> String = { UUID.randomUUID().toString() },
        ) = ProgressiveBookLoader(gateway, loads, installer, bookIdFactory, chapterIdFactory)
    }
}

private fun normalizeRoot(value: String): String {
    val normalized = value.trim()
    require(normalized.startsWith("disk:/")) { "Remote root must be an absolute Yandex Disk path" }
    return if (normalized == "disk:/") normalized else normalized.trimEnd('/')
}

private fun normalizedRelativePath(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFC).also { normalized ->
        require(normalized.isNotEmpty() && '/' !in normalized && '\\' !in normalized)
    }

private fun String.isOrdinaryMarkdown(): Boolean =
    endsWith(".md", ignoreCase = false) && !startsWith('.') && '/' !in this && '\\' !in this
