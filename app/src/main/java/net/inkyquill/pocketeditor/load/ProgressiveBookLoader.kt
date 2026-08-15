package net.inkyquill.pocketeditor.load

import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.book.ChapterEntry
import net.inkyquill.pocketeditor.book.ChapterTitleExtractor
import net.inkyquill.pocketeditor.database.BookDao
import net.inkyquill.pocketeditor.database.BookRootEntity
import net.inkyquill.pocketeditor.database.PendingPublicationEntity
import net.inkyquill.pocketeditor.database.ProgressiveLoadDao
import net.inkyquill.pocketeditor.database.ProgressiveLoadFileEntity
import net.inkyquill.pocketeditor.database.ProgressiveLoadRequestEntity
import net.inkyquill.pocketeditor.database.RemoteRevisionEntity
import net.inkyquill.pocketeditor.database.SyncDao
import net.inkyquill.pocketeditor.review.ReviewMutationCoordinator
import net.inkyquill.pocketeditor.search.SourceSearch
import net.inkyquill.pocketeditor.storage.AtomicBookStore
import net.inkyquill.pocketeditor.storage.BookPaths
import net.inkyquill.pocketeditor.storage.ContentChangeNotifier
import net.inkyquill.pocketeditor.storage.StrictUtf8
import net.inkyquill.pocketeditor.storage.sha256
import net.inkyquill.pocketeditor.ui.books.LibraryTransaction
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

    suspend fun rollback(bookId: String) = Unit
}

enum class CachePublicationCheckpoint {
    JOURNAL_STAGED,
    DURABLE_CACHE_COMMITTED,
    PATH_NOTIFIED,
    BOOK_NOTIFIED,
    ACKNOWLEDGED,
}

enum class DiscoveryCheckpoint { BEFORE_INSTALL, AFTER_INSTALL, BEFORE_DELETE }

class ProgressiveBookLoader private constructor(
    private val gateway: YandexDiskGateway,
    private val loads: ProgressiveLoadDao,
    private val installer: ProgressiveSeedInstaller,
    private val bookIdFactory: () -> String,
    private val chapterIdFactory: () -> String,
    private val requests: DiscoveryRequestStore,
    private val runner: RunnerDependencies?,
) : ProgressiveLoadRunner {
    private val starts = Mutex()
    private val installedByRoot = ConcurrentHashMap<String, ProgressiveLoadSnapshot>()

    suspend fun request(remoteRootPath: String): ProgressiveLoadSnapshot = starts.withLock {
        val root = normalizeRoot(remoteRootPath)
        loads.getJobByRemoteRoot(root)?.let { existing ->
            if (existing.paused || existing.cancelled || existing.phase == ProgressiveLoadPhase.ACTION_REQUIRED) {
                requireNotNull(runner) { "Runner dependencies are not configured" }.scheduler.continueLoad(existing.bookId)
            }
            return@withLock requireNotNull(loads.snapshot(existing.bookId))
        }
        runner?.books?.getRoots()?.firstOrNull { it.remoteRootPath?.let(::normalizeRoot) == root }?.let { registered ->
            return@withLock adoptRegisteredRoot(registered, runner)
        }
        val dependencies = requireNotNull(runner) { "Runner dependencies are not configured" }
        requests.get(root)?.let { existing ->
            runCatching { ensureDiscoveryScheduled(existing, dependencies) }
            return@withLock requireNotNull(requests.get(root)).toSnapshot()
        }
        val request = ProgressiveLoadRequestEntity(
                remoteRootPath = root,
                requestId = "discovery:${UUID.randomUUID()}",
                generation = 0,
                phase = ProgressiveLoadPhase.PREPARING,
                retryAttempt = 0,
                retryAt = null,
                lastErrorCategory = null,
                paused = false,
                cancelled = false,
                updatedAt = System.currentTimeMillis(),
        )
        requests.insertIfAbsent(request)
        val durable = requireNotNull(requests.get(root))
        runCatching { ensureDiscoveryScheduled(durable, dependencies) }
        requireNotNull(requests.get(root)).toSnapshot()
    }

    suspend fun reconcileDiscoveryRequests() {
        val dependencies = requireNotNull(runner) { "Runner dependencies are not configured" }
        requests.getAll()
            .filter { !it.paused && !it.cancelled && it.phase != ProgressiveLoadPhase.ACTION_REQUIRED }
            .forEach { runCatching { ensureDiscoveryScheduled(it, dependencies) } }
    }

    private suspend fun ensureDiscoveryScheduled(
        request: ProgressiveLoadRequestEntity,
        dependencies: RunnerDependencies,
    ) {
        if (request.paused || request.cancelled || request.phase == ProgressiveLoadPhase.ACTION_REQUIRED) {
            dependencies.scheduler.continueLoad(request.requestId)
        } else {
            dependencies.scheduler.enqueueCurrent(request.requestId, request.generation, java.time.Duration.ZERO)
        }
    }

    suspend fun start(remoteRootPath: String): ProgressiveLoadSnapshot = starts.withLock {
        val root = normalizeRoot(remoteRootPath)
        installedByRoot[root]?.let { cached ->
            return@withLock (loads.snapshot(cached.bookId) ?: cached).also { installedByRoot[root] = it }
        }
        loads.getJobByRemoteRoot(root)?.let { job ->
            return@withLock requireNotNull(loads.snapshot(job.bookId)).also { installedByRoot[root] = it }
        }
        runner?.books?.getRoots()?.firstOrNull { it.remoteRootPath?.let(::normalizeRoot) == root }?.let { registered ->
            return@withLock adoptRegisteredRoot(registered, runner).also { installedByRoot[root] = it }
        }
        val entries = gateway.listFolder(root)
        val seed = try {
            buildSeed(root, entries)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: YandexDiskError) {
            throw failure
        } catch (failure: Exception) {
            throw YandexDiskError.InvalidRemote("Invalid Yandex book structure", failure)
        }
        installer.install(seed, emptyMap()).also { snapshot ->
            installedByRoot[root] = snapshot
            if (snapshot.phase != ProgressiveLoadPhase.COMPLETE) runner?.scheduler?.start(snapshot.bookId)
        }
    }

    override suspend fun runOne(bookId: String, generation: Long): ProgressiveLoadRunResult {
        val dependencies = requireNotNull(runner) { "Runner dependencies are not configured" }
        requests.getByRequestId(bookId)?.let { return discover(it.remoteRootPath, bookId, generation, dependencies) }
        val currentJob = loads.getJob(bookId)
        if (currentJob?.generation != generation) return ProgressiveLoadRunResult.Stale
        replayPendingPublications(bookId, dependencies)
        reconcileCachedRows(bookId, dependencies)
        val claimed = loads.claimNext(bookId, generation) ?: return when {
            loads.getJob(bookId)?.generation != generation -> ProgressiveLoadRunResult.Stale
            loads.getFiles(bookId).all { it.state == ProgressiveLoadFileState.CACHED } -> ProgressiveLoadRunResult.Complete
            else -> ProgressiveLoadRunResult.ActionRequired
        }
        return try {
            val job = requireNotNull(loads.getJob(bookId))
            val remote = gateway.download(childPath(job.remoteRootPath, claimed.remoteName))
            if (loads.getJob(bookId)?.generation != generation) {
                loads.restorePending(bookId, claimed.path, generation, null, 0, null)
                return ProgressiveLoadRunResult.Stale
            }
            if (remote.revision != claimed.expectedRevision) {
                throw TemporaryAvailabilityException("Remote revision changed before cache publication")
            }
            StrictUtf8.decode(remote.bytes, "Chapter ${claimed.path}")
            val title = ChapterTitleExtractor.extract(claimed.path, remote.bytes).title
            var durableCommit = false
            dependencies.reviewMutations.withBookShared(bookId) {
                dependencies.transaction.run {
                    if (loads.ownsClaim(bookId, claimed.path, generation)) {
                        dependencies.sync.upsertPendingPublication(PendingPublicationEntity(bookId, claimed.path))
                    }
                }
                if (!loads.ownsClaim(bookId, claimed.path, generation)) return@withBookShared
                dependencies.publicationCheckpoint(CachePublicationCheckpoint.JOURNAL_STAGED)
                val revision = dependencies.store.replaceDownloadedSource(bookId, claimed.path, remote.bytes)
                dependencies.transaction.run {
                    if (loads.ownsClaim(bookId, claimed.path, generation)) {
                        dependencies.search.replaceChapter(bookId, claimed.chapterId, title, remote.bytes)
                        dependencies.sync.upsertRemoteRevision(
                            RemoteRevisionEntity(bookId, claimed.path, remote.revision, revision.sha256),
                        )
                        loads.markCached(bookId, claimed.path, generation, revision.sha256)
                        durableCommit = true
                    } else {
                        dependencies.sync.deletePendingPublication(bookId, claimed.path)
                    }
                }
                if (durableCommit) dependencies.publicationCheckpoint(CachePublicationCheckpoint.DURABLE_CACHE_COMMITTED)
            }
            if (!durableCommit) return ProgressiveLoadRunResult.Stale
            dependencies.contentChanges.changed(bookId, claimed.path)
            dependencies.publicationCheckpoint(CachePublicationCheckpoint.PATH_NOTIFIED)
            dependencies.contentChanges.bookChanged(bookId)
            dependencies.publicationCheckpoint(CachePublicationCheckpoint.BOOK_NOTIFIED)
            dependencies.transaction.run { dependencies.sync.deletePendingPublication(bookId, claimed.path) }
            dependencies.publicationCheckpoint(CachePublicationCheckpoint.ACKNOWLEDGED)
            ProgressiveLoadRunResult.FileCached
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                loads.restorePending(bookId, claimed.path, generation, null, retryAttempt = 0, retryAt = null)
            }
            throw cancelled
        } catch (failure: Throwable) {
            classifyFailure(bookId, claimed, generation, failure, dependencies)
        }
    }

    private suspend fun discover(
        remoteRootPath: String,
        requestId: String,
        generation: Long,
        dependencies: RunnerDependencies,
    ): ProgressiveLoadRunResult {
        val request = requests.get(remoteRootPath) ?: return ProgressiveLoadRunResult.Stale
        if (request.requestId != requestId) return ProgressiveLoadRunResult.Stale
        if (request.generation != generation) return ProgressiveLoadRunResult.Stale
        return try {
            dependencies.books?.getRoots()
                ?.firstOrNull { it.remoteRootPath?.let(::normalizeRoot) == request.remoteRootPath }
                ?.let { registered ->
                    if (!discoveryIsCurrent(request)) return ProgressiveLoadRunResult.Stale
                    val installed = loads.snapshot(registered.bookId) ?: adoptRegisteredRoot(registered, dependencies)
                    return completeDiscovery(request, installed, ownedByRequest = false, dependencies)
                }
            val entries = gateway.listFolder(request.remoteRootPath)
            if (!discoveryIsCurrent(request)) return ProgressiveLoadRunResult.Stale
            val seed = try {
                buildSeed(request.remoteRootPath, entries)
            } catch (failure: YandexDiskError) {
                throw failure
            } catch (failure: Exception) {
                throw YandexDiskError.InvalidRemote("Invalid Yandex book structure", failure)
            }
            if (!discoveryIsCurrent(request)) return ProgressiveLoadRunResult.Stale
            dependencies.discoveryCheckpoint(DiscoveryCheckpoint.BEFORE_INSTALL)
            if (!discoveryIsCurrent(request)) return ProgressiveLoadRunResult.Stale
            val installed = installer.install(seed, emptyMap())
            dependencies.discoveryCheckpoint(DiscoveryCheckpoint.AFTER_INSTALL)
            completeDiscovery(request, installed, ownedByRequest = true, dependencies)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val latest = requests.get(request.remoteRootPath) ?: return ProgressiveLoadRunResult.Stale
            if (latest.requestId != requestId || latest.generation != generation || latest.paused || latest.cancelled) {
                return ProgressiveLoadRunResult.Stale
            }
            val attempt = latest.retryAttempt + 1
            when (val disposition = dependencies.retryPolicy.classify(failure, attempt)) {
                is LoadFailureDisposition.Retry -> {
                    val changed = requests.compareAndSet(
                        latest.copy(
                            phase = ProgressiveLoadPhase.PREPARING,
                            retryAttempt = attempt,
                            retryAt = disposition.retryAt.toEpochMilli(),
                            lastErrorCategory = disposition.category,
                            updatedAt = System.currentTimeMillis(),
                        ),
                        generation,
                    )
                    if (changed) ProgressiveLoadRunResult.Retry(disposition.retryAt) else ProgressiveLoadRunResult.Stale
                }
                LoadFailureDisposition.SignInRequired -> {
                    val changed = requests.compareAndSet(
                        latest.copy(
                            phase = ProgressiveLoadPhase.PAUSED,
                            retryAttempt = attempt,
                            retryAt = null,
                            paused = true,
                            lastErrorCategory = ProgressiveLoadErrorCategory.UNAUTHORIZED,
                            updatedAt = System.currentTimeMillis(),
                        ),
                        generation,
                    )
                    if (changed) ProgressiveLoadRunResult.SignInRequired else ProgressiveLoadRunResult.Stale
                }
                is LoadFailureDisposition.ActionRequired -> {
                    val changed = requests.compareAndSet(
                        latest.copy(
                            phase = ProgressiveLoadPhase.ACTION_REQUIRED,
                            retryAttempt = attempt,
                            retryAt = null,
                            lastErrorCategory = disposition.category,
                            updatedAt = System.currentTimeMillis(),
                        ),
                        generation,
                    )
                    if (changed) ProgressiveLoadRunResult.ActionRequired else ProgressiveLoadRunResult.Stale
                }
            }
        }
    }

    private suspend fun discoveryIsCurrent(expected: ProgressiveLoadRequestEntity): Boolean {
        val current = requests.get(expected.remoteRootPath) ?: return false
        return current.requestId == expected.requestId && current.generation == expected.generation &&
            !current.paused && !current.cancelled && current.phase != ProgressiveLoadPhase.ACTION_REQUIRED
    }

    private suspend fun completeDiscovery(
        request: ProgressiveLoadRequestEntity,
        installed: ProgressiveLoadSnapshot,
        ownedByRequest: Boolean,
        dependencies: RunnerDependencies,
    ): ProgressiveLoadRunResult {
        if (!discoveryIsCurrent(request)) {
            if (ownedByRequest) installer.rollback(installed.bookId)
            return ProgressiveLoadRunResult.Stale
        }
        val scheduledByRequest = ownedByRequest && installed.phase != ProgressiveLoadPhase.COMPLETE
        if (scheduledByRequest) {
            try {
                dependencies.scheduler.start(installed.bookId)
            } catch (failure: Throwable) {
                if (ownedByRequest) installer.rollback(installed.bookId)
                throw failure
            }
        }
        dependencies.discoveryCheckpoint(DiscoveryCheckpoint.BEFORE_DELETE)
        if (!requests.deleteIfGeneration(request.remoteRootPath, request.requestId, request.generation)) {
            if (scheduledByRequest) dependencies.scheduler.cancel(installed.bookId)
            if (ownedByRequest) installer.rollback(installed.bookId)
            return ProgressiveLoadRunResult.Stale
        }
        installedByRoot[request.remoteRootPath] = installed
        return ProgressiveLoadRunResult.Complete
    }

    suspend fun migrateLegacyDrafts() {
        val dependencies = requireNotNull(runner) { "Runner dependencies are not configured" }
        val adapter = dependencies.legacyAdapter ?: return
        adapter.seeds().forEach { legacy ->
            if (loads.getJob(legacy.manifest.bookId) != null) return@forEach
            installer.install(
                ProgressiveBookSeed(
                    legacy.manifest,
                    legacy.remoteRootPath,
                    legacy.files,
                    rawBinder = true,
                    remoteManifest = null,
                ),
                legacy.cachedSources,
            )
            adapter.discard(legacy.manifest.bookId)
            if (!legacy.readyWithoutNetwork) dependencies.scheduler.start(legacy.manifest.bookId)
        }
    }

    private suspend fun adoptRegisteredRoot(
        root: BookRootEntity,
        dependencies: RunnerDependencies,
    ): ProgressiveLoadSnapshot {
        loads.snapshot(root.bookId)?.let { return it }
        val manifest = dependencies.store.readManifest(root.bookId)
        val revisions = dependencies.sync.getRemoteRevisions(root.bookId).associateBy { it.path }
        val rows = manifest.chapters.mapIndexed { index, chapter ->
            val bytes = runCatching { dependencies.store.readSource(root.bookId, chapter.path) }.getOrNull()
            bytes?.let { StrictUtf8.decode(it, "Chapter ${chapter.path}") }
            ProgressiveLoadFileEntity(
                root.bookId,
                chapter.path,
                chapter.id,
                index,
                revisions[chapter.path]?.remoteRevision.orEmpty(),
                bytes?.size?.toLong(),
                bytes?.sha256(),
                if (bytes == null) ProgressiveLoadFileState.ACTION_REQUIRED else ProgressiveLoadFileState.CACHED,
                initialPriority(index),
                remoteName = chapter.path,
            )
        }
        dependencies.transaction.run {
            loads.insertJob(
                net.inkyquill.pocketeditor.database.ProgressiveLoadJobEntity(
                    root.bookId,
                    requireNotNull(root.remoteRootPath),
                    if (rows.all { it.state == ProgressiveLoadFileState.CACHED }) {
                        ProgressiveLoadPhase.COMPLETE
                    } else {
                        ProgressiveLoadPhase.ACTION_REQUIRED
                    },
                    rows.size,
                    rows.count { it.state == ProgressiveLoadFileState.CACHED },
                    null,
                    0,
                    null,
                    0,
                    paused = false,
                    cancelled = false,
                    lastErrorCategory = if (rows.any { it.state == ProgressiveLoadFileState.ACTION_REQUIRED }) {
                        ProgressiveLoadErrorCategory.INVALID_REMOTE
                    } else {
                        null
                    },
                ),
            )
            loads.insertFiles(rows)
        }
        return requireNotNull(loads.snapshot(root.bookId))
    }

    private suspend fun replayPendingPublications(bookId: String, dependencies: RunnerDependencies) {
        dependencies.sync.getPendingPublicationPaths(bookId).forEach { path ->
            dependencies.contentChanges.changed(bookId, path)
            dependencies.contentChanges.bookChanged(bookId)
            dependencies.transaction.run { dependencies.sync.deletePendingPublication(bookId, path) }
        }
    }

    private suspend fun reconcileCachedRows(bookId: String, dependencies: RunnerDependencies) {
        val revisions = dependencies.sync.getRemoteRevisions(bookId).associateBy { it.path }
        loads.getFiles(bookId).filter { it.state == ProgressiveLoadFileState.CACHED }.forEach { file ->
            val matches = runCatching {
                val bytes = dependencies.store.readSource(bookId, file.path)
                StrictUtf8.decode(bytes, "Chapter ${file.path}")
                val revision = revisions[file.path]
                file.sha256 != null && bytes.sha256() == file.sha256 &&
                    revision?.sha256 == file.sha256 && revision.remoteRevision == file.expectedRevision
            }.getOrDefault(false)
            if (!matches) {
                dependencies.transaction.run {
                    dependencies.search.removeChapter(bookId, file.chapterId)
                    dependencies.sync.deleteRemoteRevision(bookId, file.path)
                    loads.resetCachedMismatch(bookId, file.path)
                }
            }
        }
    }

    private suspend fun classifyFailure(
        bookId: String,
        claimed: ProgressiveLoadFileEntity,
        generation: Long,
        failure: Throwable,
        dependencies: RunnerDependencies,
    ): ProgressiveLoadRunResult = try {
        val effectiveFailure = if (failure is YandexDiskError.NotFound) {
            val job = loads.getJob(bookId)
            val listing = try {
                if (job == null) emptyList() else gateway.listFolder(job.remoteRootPath)
            } catch (confirmationFailure: Throwable) {
                if (confirmationFailure is CancellationException) throw confirmationFailure
                return classifyFailure(
                    bookId,
                    claimed,
                    generation,
                    if (confirmationFailure is YandexDiskError.NotFound) {
                        TemporaryAvailabilityException("Missing-file confirmation was unavailable")
                    } else {
                        confirmationFailure
                    },
                    dependencies,
                )
            }
            val present = listing.filter { it.type == "file" }
                .mapNotNull { runCatching { normalizedRelativePath(it.name) }.getOrNull() }
                .any { it == normalizedRelativePath(claimed.remoteName) }
            if (present) {
                TemporaryAvailabilityException("Listed file was temporarily unavailable")
            } else {
                withContext(NonCancellable) {
                    loads.markActionRequired(
                        bookId,
                        claimed.path,
                        generation,
                        ProgressiveLoadErrorCategory.INVALID_REMOTE,
                    )
                }
                return ProgressiveLoadRunResult.ActionRequired
            }
        } else {
            failure
        }
        val previousAttempt = loads.getJob(bookId)?.retryAttempt ?: 0
        val attempt = if (previousAttempt == Int.MAX_VALUE) Int.MAX_VALUE else previousAttempt + 1
        return when (val disposition = dependencies.retryPolicy.classify(effectiveFailure, attempt)) {
            is LoadFailureDisposition.Retry -> {
                withContext(NonCancellable) {
                    loads.restorePending(
                        bookId, claimed.path, generation, disposition.category,
                        attempt, disposition.retryAt.toEpochMilli(),
                    )
                }
                ProgressiveLoadRunResult.Retry(disposition.retryAt)
            }
            LoadFailureDisposition.SignInRequired -> {
                withContext(NonCancellable) { loads.pauseUnauthorized(bookId, claimed.path, generation) }
                ProgressiveLoadRunResult.SignInRequired
            }
            is LoadFailureDisposition.ActionRequired -> {
                withContext(NonCancellable) {
                    loads.markActionRequired(bookId, claimed.path, generation, disposition.category)
                }
                ProgressiveLoadRunResult.ActionRequired
            }
        }
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) {
            loads.restorePending(bookId, claimed.path, generation, null, retryAttempt = 0, retryAt = null)
        }
        throw cancelled
    }

    private suspend fun buildSeed(root: String, entries: List<RemoteEntry>): ProgressiveBookSeed {
        val files = entries.filter { it.type == "file" }
        val manifestEntries = files.filter { it.name == BookPaths.MANIFEST_NAME }
        require(manifestEntries.size <= 1) { "Book folder contains duplicate manifests" }
        val manifestEntry = manifestEntries.singleOrNull()
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
                    null, ProgressiveLoadFileState.PENDING, initialPriority(index), remoteName = entry.name,
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
        val normalizedEntries = entries.map { it to normalizedRelativePath(it.name) }
        require(normalizedEntries.map { it.second }.distinct().size == normalizedEntries.size) {
            "Remote paths collide after Unicode normalization"
        }
        val entriesByPath = normalizedEntries.associate { (entry, path) -> path to entry }
        val rows = manifest.chapters.mapIndexed { index, chapter ->
            require(chapter.path.isOrdinaryMarkdown()) { "Tracked source is not an ordinary Markdown file: ${chapter.path}" }
            val normalizedPath = normalizedRelativePath(chapter.path)
            val entry = requireNotNull(entriesByPath[normalizedPath]) { "Tracked source is missing: ${chapter.path}" }
            ProgressiveLoadFileEntity(
                manifest.bookId, chapter.path, chapter.id, index, entry.revision, entry.size,
                null, ProgressiveLoadFileState.PENDING, initialPriority(index), remoteName = entry.name,
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
        ) = ProgressiveBookLoader(
            gateway, loads, installer, bookIdFactory, chapterIdFactory, InMemoryDiscoveryRequestStore(), null,
        )

        fun create(
            gateway: YandexDiskGateway,
            loads: ProgressiveLoadDao,
            installer: ProgressiveSeedInstaller,
            store: AtomicBookStore,
            sync: SyncDao,
            search: SourceSearch,
            reviewMutations: ReviewMutationCoordinator,
            contentChanges: ContentChangeNotifier,
            transaction: LibraryTransaction,
            scheduler: ProgressiveLoadScheduler,
            retryPolicy: ProgressiveLoadRetryPolicy,
            legacyAdapter: LegacyImportDraftAdapter? = null,
            books: BookDao? = null,
            requests: DiscoveryRequestStore = InMemoryDiscoveryRequestStore(),
            publicationCheckpoint: (CachePublicationCheckpoint) -> Unit = {},
            discoveryCheckpoint: suspend (DiscoveryCheckpoint) -> Unit = {},
            bookIdFactory: () -> String = { UUID.randomUUID().toString() },
            chapterIdFactory: () -> String = { UUID.randomUUID().toString() },
        ) = ProgressiveBookLoader(
            gateway,
            loads,
            installer,
            bookIdFactory,
            chapterIdFactory,
            requests,
            RunnerDependencies(
                store, sync, search, reviewMutations, contentChanges, transaction, scheduler,
                retryPolicy, legacyAdapter, books, publicationCheckpoint,
                discoveryCheckpoint,
            ),
        )
    }
}

private data class RunnerDependencies(
    val store: AtomicBookStore,
    val sync: SyncDao,
    val search: SourceSearch,
    val reviewMutations: ReviewMutationCoordinator,
    val contentChanges: ContentChangeNotifier,
    val transaction: LibraryTransaction,
    val scheduler: ProgressiveLoadScheduler,
    val retryPolicy: ProgressiveLoadRetryPolicy,
    val legacyAdapter: LegacyImportDraftAdapter?,
    val books: BookDao?,
    val publicationCheckpoint: (CachePublicationCheckpoint) -> Unit,
    val discoveryCheckpoint: suspend (DiscoveryCheckpoint) -> Unit,
)

private fun childPath(root: String, name: String) = "${root.trimEnd('/')}/$name"

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
