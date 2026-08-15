package net.inkyquill.pocketeditor.sync

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import net.inkyquill.pocketeditor.database.MergeBaseEntity
import net.inkyquill.pocketeditor.database.OutboxEntity
import net.inkyquill.pocketeditor.database.PendingDeletionEntity
import net.inkyquill.pocketeditor.database.PendingPublicationEntity
import net.inkyquill.pocketeditor.database.ProgressiveLoadFileEntity
import net.inkyquill.pocketeditor.database.RemoteRevisionEntity
import net.inkyquill.pocketeditor.database.SyncDao
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.book.ChapterEntry
import net.inkyquill.pocketeditor.book.ChapterTitleExtractor
import net.inkyquill.pocketeditor.merge.MergeResult
import net.inkyquill.pocketeditor.merge.RecordConflict
import net.inkyquill.pocketeditor.merge.RecordValue
import net.inkyquill.pocketeditor.merge.ReviewMerge
import net.inkyquill.pocketeditor.review.ReviewDocument
import net.inkyquill.pocketeditor.review.ReviewJson
import net.inkyquill.pocketeditor.review.ReviewMutationCoordinator
import net.inkyquill.pocketeditor.storage.BookStore
import net.inkyquill.pocketeditor.storage.SourceCache
import net.inkyquill.pocketeditor.storage.DirectorySyncStatus
import net.inkyquill.pocketeditor.storage.ContentChangeNotifier
import net.inkyquill.pocketeditor.storage.StrictUtf8
import net.inkyquill.pocketeditor.storage.BookPaths
import net.inkyquill.pocketeditor.yandex.RemoteFile
import net.inkyquill.pocketeditor.yandex.RemoteEntry
import net.inkyquill.pocketeditor.load.BACKGROUND_PRIORITY
import net.inkyquill.pocketeditor.load.ProgressiveLoadFileState
import net.inkyquill.pocketeditor.yandex.SyncLock
import net.inkyquill.pocketeditor.yandex.YandexDiskError
import net.inkyquill.pocketeditor.yandex.YandexDiskGateway

internal sealed interface SyncFailureClass {
    data object Retryable : SyncFailureClass
    data object SignIn : SyncFailureClass
    data object InvalidRemote : SyncFailureClass
    data object Conflict : SyncFailureClass
}

sealed interface SyncStatus {
    data object Saved : SyncStatus
    data object WaitingToSync : SyncStatus
    data object Syncing : SyncStatus
    data object SignInRequired : SyncStatus
    data class ActionRequired(val reason: String, val lock: SyncLock? = null) : SyncStatus
}

interface SyncMetadataStore : RemoteRevisionMetadata {
    override suspend fun outbox(bookId: String): List<OutboxEntity>
    suspend fun mergeBase(bookId: String, path: String): MergeBaseEntity?
    suspend fun recordRemote(value: RemoteRevisionEntity)
    suspend fun recordBase(value: MergeBaseEntity)
    suspend fun recordOutbox(value: OutboxEntity)
    suspend fun removeOutbox(bookId: String, path: String)
    suspend fun removeRemote(bookId: String, path: String)
    suspend fun removeBase(bookId: String, path: String)
    suspend fun stagePublication(bookId: String, path: String)
    suspend fun acceptRemoteDeletion(bookId: String, path: String)
    suspend fun acknowledgePublication(bookId: String, path: String)
}

data class IndexedChapter(val chapterId: String, val title: String, val bytes: ByteArray)

fun interface SourceIndexUpdater {
    suspend fun rebuildBook(bookId: String, chapters: List<IndexedChapter>)
}

fun interface ProgressiveSpineReconciler {
    suspend fun replace(bookId: String, rows: List<ProgressiveLoadFileEntity>)
}

class RoomSyncMetadataStore(private val dao: SyncDao) : SyncMetadataStore {
    override suspend fun outbox(bookId: String): List<OutboxEntity> =
        dao.getOutbox(bookId)

    override suspend fun confirmedRevisions(bookId: String): List<RemoteRevisionEntity> =
        dao.getRemoteRevisions(bookId)

    override suspend fun pendingPublicationPaths(bookId: String): List<String> =
        dao.getPendingPublicationPaths(bookId)

    override suspend fun mergeBase(bookId: String, path: String): MergeBaseEntity? = dao.getMergeBase(bookId, path)

    override suspend fun recordRemote(value: RemoteRevisionEntity) = dao.upsertRemoteRevision(value)

    override suspend fun recordBase(value: MergeBaseEntity) = dao.upsertMergeBase(value)

    override suspend fun recordOutbox(value: OutboxEntity) = dao.upsertOutbox(value)

    override suspend fun removeOutbox(bookId: String, path: String) = dao.deleteOutbox(bookId, path)

    override suspend fun removeRemote(bookId: String, path: String) = dao.deleteRemoteRevision(bookId, path)

    override suspend fun removeBase(bookId: String, path: String) = dao.deleteMergeBase(bookId, path)

    override suspend fun stagePublication(bookId: String, path: String) =
        dao.upsertPendingPublication(PendingPublicationEntity(bookId, path))

    override suspend fun acceptRemoteDeletion(bookId: String, path: String) =
        dao.acceptRemoteDeletion(bookId, path)

    override suspend fun acknowledgePublication(bookId: String, path: String) =
        dao.deletePendingPublication(bookId, path)
}

interface PendingDeletionStore {
    suspend fun put(value: PendingDeletionEntity)
    suspend fun get(tokenId: String): PendingDeletionEntity?
    suspend fun pendingForBook(bookId: String): List<PendingDeletionEntity>
    suspend fun remove(tokenId: String): Boolean
    suspend fun complete(tokenId: String, outbox: OutboxEntity?): Boolean
}

class RoomPendingDeletionStore(private val dao: SyncDao) : PendingDeletionStore {
    override suspend fun put(value: PendingDeletionEntity) = dao.upsertPendingDeletion(value)
    override suspend fun get(tokenId: String): PendingDeletionEntity? = dao.getPendingDeletion(tokenId)
    override suspend fun pendingForBook(bookId: String): List<PendingDeletionEntity> = dao.pendingDeletions(bookId)
    override suspend fun remove(tokenId: String): Boolean = dao.deletePendingDeletion(tokenId) == 1
    override suspend fun complete(tokenId: String, outbox: OutboxEntity?): Boolean =
        dao.completePendingDeletion(tokenId, outbox) == 1
}

class SyncEngine internal constructor(
    private val gateway: YandexDiskGateway,
    private val bookStore: BookStore,
    private val sourceCache: SourceCache,
    private val metadata: SyncMetadataStore,
    private val baseStore: SyncBaseStore,
    private val conflicts: ConflictRepository,
    private val reviewMutations: ReviewMutationCoordinator,
    private val pendingDeletions: PendingDeletionStore,
    private val contentChanges: ContentChangeNotifier,
    private val holderId: String,
    private val lockFactory: () -> SyncLock,
    private val sourceIndexUpdater: SourceIndexUpdater = SourceIndexUpdater { _, _ -> },
    private val eligibility: SyncEligibility = SyncEligibility { true },
    private val progressiveSpine: ProgressiveSpineReconciler = ProgressiveSpineReconciler { _, _ -> },
) {
    private val statuses = MutableStateFlow<Map<String, SyncStatus>>(emptyMap())

    fun status(bookId: String): Flow<SyncStatus> = statuses
        .map { it[bookId] ?: SyncStatus.Saved }
        .distinctUntilChanged()

    suspend fun breakObservedLock(bookId: String, remoteRootPath: String, observedLock: SyncLock): SyncStatus {
        val current = statuses.value[bookId] as? SyncStatus.ActionRequired
            ?: throw IllegalStateException("Активная блокировка для снятия не найдена")
        require(current.lock == observedLock) { "Наблюдаемая блокировка изменилась. Обновите данные перед её снятием" }
        return syncBook(bookId, remoteRootPath, observedLock)
    }

    suspend fun resolveReviewConflict(
        bookId: String,
        path: String,
        expectedConflictIdentity: String,
        choices: Map<String, ConflictChoice>,
    ) = reviewMutations.withReview(bookId, path) {
        val conflict = conflicts.conflict(bookId, path) as? SyncConflict.Review
            ?: throw IllegalArgumentException("Review conflict was not found")
        check(conflict.identity == expectedConflictIdentity) { "Review conflict was replaced" }
        resolveReviewConflictLocked(bookId, path, conflict, choices)
    }

    private suspend fun resolveReviewConflictLocked(
        bookId: String,
        path: String,
        conflict: SyncConflict.Review,
        choices: Map<String, ConflictChoice>,
    ) {
        val resolved = conflicts.previewReviewResolution(bookId, conflict, choices)
        if (conflict.remoteDeleted) {
            when (choices.getValue(REMOTE_REVIEW_DELETION_RECORD_ID)) {
                ConflictChoice.KEEP_MINE -> {
                    val local = writeReview(bookId, path, resolved)
                    metadata.recordOutbox(
                        OutboxEntity(
                            bookId,
                            path,
                            local.sha256,
                            null,
                            net.inkyquill.pocketeditor.database.OutboxState.PENDING,
                        ),
                    )
                }
                ConflictChoice.KEEP_YANDEX -> {
                    requireDeletionDurable(bookStore.deleteReview(bookId, path), "Review cache")
                    metadata.removeOutbox(bookId, path)
                }
            }
            requireDeletionDurable(baseStore.delete(bookId, path), "Review sync base")
            metadata.removeBase(bookId, path)
            metadata.removeRemote(bookId, path)
            check(conflicts.removeIfCurrent(bookId, conflict)) { "Review conflict was replaced during resolution" }
            contentChanges.changed(bookId, path)
            contentChanges.bookChanged(bookId)
            refreshStatusAfterConflictResolution(bookId)
            return
        }
        require(conflict.remoteRevision.isNotBlank() && conflict.remoteBytes.isNotEmpty()) {
            "Review conflict has no confirmed remote base"
        }
        val remoteBase = writeDurableBase(bookId, path, conflict.remoteBytes, conflict.remoteRevision)
        metadata.recordBase(MergeBaseEntity(bookId, path, remoteBase.sha256, conflict.remoteRevision))
        metadata.recordRemote(RemoteRevisionEntity(bookId, path, conflict.remoteRevision, remoteBase.sha256))
        val local = writeReview(bookId, path, resolved)
        if (local.sha256 == remoteBase.sha256) {
            metadata.removeOutbox(bookId, path)
        } else {
            metadata.recordOutbox(
                OutboxEntity(
                    bookId,
                    path,
                    local.sha256,
                    remoteBase.sha256,
                    net.inkyquill.pocketeditor.database.OutboxState.PENDING,
                ),
            )
        }
        check(conflicts.removeIfCurrent(bookId, conflict)) { "Review conflict was replaced during resolution" }
        contentChanges.changed(bookId, path)
        contentChanges.bookChanged(bookId)
        refreshStatusAfterConflictResolution(bookId)
    }

    suspend fun resolveManifestConflict(
        bookId: String,
        expectedConflictIdentity: String,
        choice: ConflictChoice,
    ) = reviewMutations.withReview(bookId, MANIFEST_PATH) {
        val conflict = conflicts.conflict(bookId, MANIFEST_PATH) as? SyncConflict.Manifest
            ?: throw IllegalArgumentException("Manifest conflict was not found")
        check(conflict.identity == expectedConflictIdentity) { "Manifest conflict was replaced" }
        resolveManifestConflictLocked(bookId, conflict, choice)
    }

    private suspend fun resolveManifestConflictLocked(
        bookId: String,
        conflict: SyncConflict.Manifest,
        choice: ConflictChoice,
    ) {
        require(conflict.remoteRevision.isNotBlank() && conflict.remoteBytes.isNotEmpty()) {
            "Manifest conflict has no confirmed remote base"
        }
        val resolved = conflicts.previewManifestResolution(bookId, conflict, choice)
        val remoteBase = writeDurableBase(bookId, MANIFEST_PATH, conflict.remoteBytes, conflict.remoteRevision)
        metadata.recordBase(MergeBaseEntity(bookId, MANIFEST_PATH, remoteBase.sha256, conflict.remoteRevision))
        metadata.recordRemote(RemoteRevisionEntity(bookId, MANIFEST_PATH, conflict.remoteRevision, remoteBase.sha256))
        val local = writeManifest(bookId, resolved)
        rebuildSourceIndex(bookId, resolved)
        if (choice == ConflictChoice.KEEP_YANDEX || local.sha256 == remoteBase.sha256) {
            metadata.removeOutbox(bookId, MANIFEST_PATH)
        } else {
            metadata.recordOutbox(
                OutboxEntity(
                    bookId,
                    MANIFEST_PATH,
                    local.sha256,
                    remoteBase.sha256,
                    net.inkyquill.pocketeditor.database.OutboxState.PENDING,
                ),
            )
        }
        check(conflicts.removeIfCurrent(bookId, conflict)) { "Manifest conflict was replaced during resolution" }
        contentChanges.changed(bookId, MANIFEST_PATH)
        contentChanges.bookChanged(bookId)
        refreshStatusAfterConflictResolution(bookId)
    }

    suspend fun syncBook(
        bookId: String,
        remoteRootPath: String,
        breakObservedLock: SyncLock? = null,
    ): SyncStatus {
        if (!eligibility.allowsSync(bookId)) {
            return SyncStatus.Saved.also { setStatus(bookId, it) }
        }
        setStatus(bookId, SyncStatus.Syncing)
        var ownedLock: SyncLock? = null
        var result: SyncStatus? = null
        var primaryFailure: Throwable? = null
        val publication = SyncPublication()
        try {
            metadata.pendingPublicationPaths(bookId).forEach(publication::recover)
            if (breakObservedLock != null) {
                gateway.breakObservedLock(remoteRootPath, breakObservedLock)
            }
            val requestedLock = lockFactory().also { lock ->
                require(lock.holderId == holderId) { "Lock factory must use this device holder ID" }
            }
            ownedLock = try {
                gateway.tryAcquireLock(remoteRootPath, requestedLock)
            } catch (error: YandexDiskError.NotFound) {
                throw YandexDiskError.InvalidRemote("Configured remote root is missing", error)
            }
            result = reviewMutations.withBookShared(bookId) {
                synchronizeUnderLock(bookId, remoteRootPath, ownedLock, publication, this)
            }
        } catch (cancelled: CancellationException) {
            primaryFailure = cancelled
            throw cancelled
        } catch (error: Exception) {
            result = syncFailureClass(bookId, error).status()
        } finally {
            ownedLock?.let { lock ->
                val releaseFailure = runCatching {
                    withContext(NonCancellable) { gateway.releaseOwnedLock(remoteRootPath, lock) }
                }.exceptionOrNull()
                if (releaseFailure != null && primaryFailure != null) {
                    var cancellation: Throwable? = primaryFailure
                    while (cancellation != null) {
                        cancellation.addSuppressed(releaseFailure)
                        cancellation = cancellation.cause
                    }
                } else if (releaseFailure != null) {
                    result = result.afterReleaseFailure(releaseFailure)
                }
            }
            val publicationFailure = runCatching {
                withContext(NonCancellable) { publication.publish(bookId, contentChanges, metadata) }
            }.exceptionOrNull()
            if (publicationFailure != null && primaryFailure != null) {
                var cancellation: Throwable? = primaryFailure
                while (cancellation != null) {
                    cancellation.addSuppressed(publicationFailure)
                    cancellation = cancellation.cause
                }
            } else if (publicationFailure != null) {
                result = result.afterPublicationFailure(publicationFailure)
            }
        }
        return requireNotNull(result).also {
            setStatus(bookId, it)
        }
    }

    private fun SyncStatus?.afterReleaseFailure(error: Throwable): SyncStatus = when (error.syncFailureClass()) {
        SyncFailureClass.Retryable -> when (this) {
            is SyncStatus.ActionRequired,
            SyncStatus.SignInRequired,
            -> this
            else -> SyncStatus.WaitingToSync
        }
        SyncFailureClass.SignIn -> SyncStatus.SignInRequired
        SyncFailureClass.InvalidRemote -> this as? SyncStatus.ActionRequired
            ?: SyncFailureClass.InvalidRemote.status()
        SyncFailureClass.Conflict -> this as? SyncStatus.ActionRequired
            ?: SyncFailureClass.Conflict.status()
    }

    private fun SyncStatus?.afterPublicationFailure(error: Throwable): SyncStatus =
        if (error.syncFailureClass() == SyncFailureClass.Retryable) {
            SyncStatus.WaitingToSync
        } else {
            afterReleaseFailure(error)
        }

    private suspend fun syncFailureClass(bookId: String, error: Throwable): SyncFailureClass {
        val errorClass = error.syncFailureClass()
        return if (errorClass == SyncFailureClass.Retryable && conflicts.conflicts(bookId).first().isNotEmpty()) {
            SyncFailureClass.Conflict
        } else {
            errorClass
        }
    }

    private fun Throwable.syncFailureClass(): SyncFailureClass = when (this) {
        is YandexDiskError.Offline,
        is YandexDiskError.CandidateCleanupUnconfirmed,
        is YandexDiskError.LockHeld,
        is YandexDiskError.LockLost,
        is YandexDiskError.RateLimited,
        is YandexDiskError.ServerFailure,
        is YandexDiskError.UploadIncomplete,
        -> SyncFailureClass.Retryable
        is YandexDiskError.Unauthorized -> SyncFailureClass.SignIn
        is YandexDiskError.InvalidRemote,
        is IllegalArgumentException,
        -> SyncFailureClass.InvalidRemote
        else -> SyncFailureClass.Retryable
    }

    private fun SyncFailureClass.status(): SyncStatus = when (this) {
        SyncFailureClass.Retryable -> SyncStatus.WaitingToSync
        SyncFailureClass.SignIn -> SyncStatus.SignInRequired
        SyncFailureClass.InvalidRemote -> SyncStatus.ActionRequired("Удалённое состояние книги некорректно")
        SyncFailureClass.Conflict -> SyncStatus.ActionRequired("Разрешите конфликты синхронизации")
    }

    private suspend fun synchronizeUnderLock(
        bookId: String,
        rootPath: String,
        lock: SyncLock,
        publication: SyncPublication,
        mutations: ReviewMutationCoordinator.BookMutationScope,
    ): SyncStatus {
        val entries = try {
            gateway.listFolder(rootPath)
        } catch (error: YandexDiskError.NotFound) {
            throw YandexDiskError.InvalidRemote("Configured remote root is missing", error)
        }
            .filter { it.type == "file" }
            .associateBy { it.name }
        val pending = metadata.outbox(bookId).associateBy(OutboxEntity::path)
        val confirmed = metadata.confirmedRevisions(bookId).associateBy(RemoteRevisionEntity::path)
        val deferredReviewPaths = pendingDeletions.pendingForBook(bookId).mapTo(mutableSetOf()) { it.reviewPath }

        val localManifest = bookStore.readManifest(bookId)
        val manifestEntry = entries[MANIFEST_PATH]
        val manifestOutbox = pending[MANIFEST_PATH]
        val shouldDownloadManifest = manifestEntry != null && (
            manifestOutbox != null || confirmed[MANIFEST_PATH]?.remoteRevision != manifestEntry.revision
        )
        // Download and validate changed metadata before replacing any last-valid cache file.
        val remoteManifestFile = manifestEntry?.takeIf { shouldDownloadManifest }?.let { gateway.download(it.path) }
        val remoteManifest = remoteManifestFile?.let { BookManifest.decode(StrictUtf8.decode(it.bytes, "Remote manifest")) }
        require((remoteManifest ?: localManifest).bookId == bookId) {
            "Remote manifest book_id does not match the registered book"
        }
        if (manifestEntry == null) {
            when {
                manifestOutbox == null -> throw YandexDiskError.InvalidRemote("Remote manifest is missing")
                manifestOutbox.baseSha256 != null -> {
                    conflicts.replace(
                        bookId,
                        SyncConflict.MissingBase(
                            MANIFEST_PATH,
                            "Remote manifest was deleted while a based local mutation was pending",
                        ),
                    )
                    return SyncStatus.ActionRequired("Манифест удалён на Яндекс Диске при неотправленном локальном изменении")
                }
            }
        }

        val sourcePaths = (localManifest.chapters + remoteManifest?.chapters.orEmpty())
            .map { it.path }
            .distinct()
        val remoteManifestPaths = when {
            remoteManifest != null -> remoteManifest.chapters.mapTo(mutableSetOf()) { it.path }
            manifestEntry != null -> localManifest.chapters.mapTo(mutableSetOf()) { it.path }
            else -> mutableSetOf()
        }
        val introducedSourcePaths = remoteManifest?.chapters.orEmpty()
            .mapTo(mutableSetOf()) { it.path }
            .apply { removeAll(localManifest.chapters.map(ChapterEntry::path).toSet()) }
        remoteManifest?.chapters.orEmpty().forEach { chapter ->
            require(entries[chapter.path] != null) {
                "Remote manifest references a missing source: ${chapter.path}"
            }
        }
        val localManifestSha = sha256(BookManifest.encode(localManifest).encodeToByteArray())
        val remoteReplacementIsPossible = manifestOutbox == null ||
            manifestOutbox.localSha256 == localManifestSha && manifestOutbox.localSha256 == manifestOutbox.baseSha256
        if (
            remoteManifestFile != null && remoteManifest != null && remoteReplacementIsPossible &&
            pendingReviewWouldBeOrphaned(localManifest, remoteManifest, pending.values)
        ) {
            conflicts.replace(
                bookId,
                SyncConflict.Manifest(
                    MANIFEST_PATH,
                    localManifest,
                    remoteManifest,
                    remoteManifestFile.bytes,
                    remoteManifestFile.revision,
                    allowedChoices = setOf(ConflictChoice.KEEP_MINE),
                ),
            )
            return SyncStatus.ActionRequired("Удалённый манифест исключает неотправленную локальную рецензию")
        }
        val stagedSources = sourcePaths.mapNotNull { path ->
            val entry = entries[path]
            require(entry != null || path !in remoteManifestPaths) {
                "Remote manifest references a missing source: $path"
            }
            entry?.takeIf {
                path in introducedSourcePaths || confirmed[path]?.remoteRevision != entry.revision
            }?.let { path to gateway.download(it.path).also { file -> validateSource(file.bytes) } }
        }
        stagedSources.forEach { (path, file) ->
            sourceCache.replaceDownloadedSource(bookId, path, file.bytes)
            publication.stage(bookId, path, metadata)
            metadata.recordRemote(RemoteRevisionEntity(bookId, path, file.revision, sha256(file.bytes)))
        }
        val remoteSpine = remoteManifest?.let { manifest ->
            buildProgressiveSpine(bookId, manifest, entries)
        }

        var blocked = false
        if (remoteManifestFile != null && remoteManifest != null) {
            blocked = processManifest(
                bookId,
                remoteManifestFile,
                remoteManifest,
                localManifest,
                pending[MANIFEST_PATH],
                rootPath,
                lock,
                publication,
                mutations,
                remoteSpine,
            ) || blocked
        } else if (pending[MANIFEST_PATH] != null) {
            uploadNewManifest(bookId, localManifest, pending.getValue(MANIFEST_PATH), rootPath, lock)
        }

        val activeManifest = bookStore.readManifest(bookId)
        rebuildSourceIndex(bookId, activeManifest)
        // A manifest conflict freezes sidecar projection: remote identities may no longer match the local cache.
        if (blocked) {
            publication.commit()
            return SyncStatus.ActionRequired("Разрешите конфликты синхронизации")
        }
        val identityChangedReviewPaths = remoteManifest?.chapters.orEmpty().mapNotNullTo(mutableSetOf()) { chapter ->
            chapter.path.takeIf { path -> localManifest.chapters.singleOrNull { it.path == path }?.id != chapter.id }
                ?.plus(REVIEW_SUFFIX)
        }
        val remoteReviews = buildMap<String, Pair<RemoteFile, ReviewDocument>> {
            activeManifest.chapters.forEach { chapter ->
                val reviewPath = chapter.path + REVIEW_SUFFIX
                if (reviewPath in deferredReviewPaths) return@forEach
                entries[reviewPath]?.takeIf { entry ->
                    pending[reviewPath] != null ||
                        reviewPath in identityChangedReviewPaths ||
                        confirmed[reviewPath]?.remoteRevision != entry.revision
                }?.let { entry ->
                    val file = gateway.download(entry.path)
                    put(
                        reviewPath,
                        file to ReviewJson.decode(StrictUtf8.decode(file.bytes, "Remote review $reviewPath"), chapter.id, chapter.path),
                    )
                }
            }
        }
        activeManifest.chapters.forEach { chapter ->
            val path = chapter.path + REVIEW_SUFFIX
            if (path in deferredReviewPaths) return@forEach
            val remote = remoteReviews[path]
            val remoteEntry = entries[path]
            when {
                remote != null -> {
                    blocked = processReview(
                        bookId,
                        path,
                        remote.first,
                        remote.second,
                        rootPath,
                        lock,
                        publication,
                        mutations,
                    ) || blocked
                }
                remoteEntry != null -> Unit
                else -> when (
                    processMissingRemoteReview(
                        bookId,
                        path,
                        confirmedBeforeSync = path in confirmed,
                        publication,
                        mutations,
                    )
                ) {
                    MissingRemoteReviewProcess.Upload -> uploadNewReview(bookId, path, rootPath, lock, mutations)
                    MissingRemoteReviewProcess.Blocked -> blocked = true
                    MissingRemoteReviewProcess.Unchanged -> Unit
                }
            }
        }
        val currentlyDeferredReviewPaths = pendingDeletions.pendingForBook(bookId).mapTo(mutableSetOf()) { it.reviewPath }
        val remaining = metadata.outbox(bookId).filterNot { it.path in currentlyDeferredReviewPaths }
        return when {
            blocked -> SyncStatus.ActionRequired("Разрешите конфликты синхронизации")
            remaining.isNotEmpty() -> SyncStatus.WaitingToSync
            else -> SyncStatus.Saved
        }.also { publication.commit() }
    }

    private suspend fun rebuildSourceIndex(bookId: String, manifest: BookManifest) {
        sourceIndexUpdater.rebuildBook(
            bookId,
            manifest.chapters.map { chapter ->
                bookStore.readSource(bookId, chapter.path).let { source ->
                    IndexedChapter(chapter.id, ChapterTitleExtractor.extract(chapter.path, source).title, source)
                }
            },
        )
    }

    private suspend fun buildProgressiveSpine(
        bookId: String,
        manifest: BookManifest,
        entries: Map<String, RemoteEntry>,
    ): List<ProgressiveLoadFileEntity> = manifest.chapters.mapIndexed { index, chapter ->
        val entry = requireNotNull(entries[chapter.path])
        val bytes = bookStore.readSource(bookId, chapter.path)
        ProgressiveLoadFileEntity(
            bookId = bookId,
            path = chapter.path,
            chapterId = chapter.id,
            spineIndex = index,
            expectedRevision = entry.revision,
            expectedSize = entry.size ?: bytes.size.toLong(),
            sha256 = sha256(bytes),
            state = ProgressiveLoadFileState.CACHED,
            priority = BACKGROUND_PRIORITY,
            remoteName = entry.name,
        )
    }

    private fun pendingReviewWouldBeOrphaned(
        local: BookManifest,
        remote: BookManifest,
        pending: Collection<OutboxEntity>,
    ): Boolean {
        val localByPath = local.chapters.associateBy(ChapterEntry::path)
        val remoteByPath = remote.chapters.associateBy(ChapterEntry::path)
        return pending.asSequence()
            .map(OutboxEntity::path)
            .filter { it.endsWith(REVIEW_SUFFIX) }
            .map { it.removeSuffix(REVIEW_SUFFIX) }
            .any { sourcePath ->
                val localChapter = localByPath[sourcePath]
                localChapter == null || remoteByPath[sourcePath]?.id != localChapter.id
            }
    }

    private suspend fun processManifest(
        bookId: String,
        remoteFile: RemoteFile,
        remote: BookManifest,
        local: BookManifest,
        outbox: OutboxEntity?,
        rootPath: String,
        lock: SyncLock,
        publication: SyncPublication,
        mutations: ReviewMutationCoordinator.BookMutationScope,
        remoteSpine: List<ProgressiveLoadFileEntity>?,
    ): Boolean = mutations.withReview(MANIFEST_PATH) {
        processManifestLocked(bookId, remoteFile, remote, local, outbox, rootPath, lock, publication, remoteSpine)
    }

    private suspend fun processManifestLocked(
        bookId: String,
        remoteFile: RemoteFile,
        remote: BookManifest,
        local: BookManifest,
        outbox: OutboxEntity?,
        rootPath: String,
        lock: SyncLock,
        publication: SyncPublication,
        remoteSpine: List<ProgressiveLoadFileEntity>?,
    ): Boolean {
        if (outbox == null) {
            bookStore.replaceDownloadedManifest(bookId, remoteFile.bytes)
            progressiveSpine.replace(bookId, requireNotNull(remoteSpine))
            publication.stage(bookId, MANIFEST_PATH, metadata)
            confirmRemote(bookId, MANIFEST_PATH, remoteFile.bytes, remoteFile.revision)
            conflicts.remove(bookId, MANIFEST_PATH)
            return false
        }
        val localBytes = BookManifest.encode(local).encodeToByteArray()
        if (sha256(localBytes) != outbox.localSha256) return missingBase(bookId, MANIFEST_PATH, "Local manifest changed outside outbox")
        val base = trustedBase(bookId, MANIFEST_PATH, outbox) ?: return missingBase(
            bookId,
            MANIFEST_PATH,
            "Exact manifest merge base is unavailable",
        )
        val remoteHash = sha256(remoteFile.bytes)
        return when {
            remoteHash == base.sha256 -> {
                uploadConfirmed(bookId, MANIFEST_PATH, localBytes, rootPath, lock)
                false
            }
            outbox.localSha256 == base.sha256 -> {
                bookStore.replaceDownloadedManifest(bookId, remoteFile.bytes)
                progressiveSpine.replace(bookId, requireNotNull(remoteSpine))
                publication.stage(bookId, MANIFEST_PATH, metadata)
                confirmRemote(bookId, MANIFEST_PATH, remoteFile.bytes, remoteFile.revision)
                metadata.removeOutbox(bookId, MANIFEST_PATH)
                false
            }
            else -> {
                conflicts.replace(
                    bookId,
                    SyncConflict.Manifest(
                        MANIFEST_PATH,
                        local,
                        remote,
                        remoteFile.bytes,
                        remoteFile.revision,
                    ),
                )
                true
            }
        }
    }

    private suspend fun processReview(
        bookId: String,
        path: String,
        remoteFile: RemoteFile,
        remote: ReviewDocument,
        rootPath: String,
        lock: SyncLock,
        publication: SyncPublication,
        mutations: ReviewMutationCoordinator.BookMutationScope,
    ): Boolean {
        val result = mutations.withReview(path) {
            if (isReviewDeferred(bookId, path)) return@withReview ReviewProcess.Deferred
            val outbox = metadata.outbox(bookId).singleOrNull { it.path == path }
            if (outbox == null) {
                writeReview(bookId, path, remote, publication)
                confirmRemote(bookId, path, remoteFile.bytes, remoteFile.revision)
                conflicts.remove(bookId, path)
                return@withReview ReviewProcess.Done
            }
            val local = bookStore.readReview(bookId, path)
                ?: return@withReview ReviewProcess.Blocked(
                    missingBase(bookId, path, "Pending review is missing from the local cache"),
                )
            val localBytes = ReviewJson.encode(local).encodeToByteArray()
            if (sha256(localBytes) != outbox.localSha256) {
                return@withReview ReviewProcess.Blocked(missingBase(bookId, path, "Local review changed outside outbox"))
            }
            val base = trustedBase(bookId, path, outbox)
                ?: return@withReview ReviewProcess.Blocked(
                    missingBase(bookId, path, "Exact review merge base is unavailable"),
                )
            val remoteHash = sha256(remoteFile.bytes)
            if (remoteHash == base.sha256) {
                return@withReview ReviewProcess.Upload(localBytes)
            }
            if (outbox.localSha256 == base.sha256) {
                writeReview(bookId, path, remote, publication)
                confirmRemote(bookId, path, remoteFile.bytes, remoteFile.revision)
                metadata.removeOutbox(bookId, path)
                return@withReview ReviewProcess.Done
            }
            val baseDocument = ReviewJson.decode(StrictUtf8.decode(base.bytes, "Review sync base"), remote.chapterId, remote.sourcePath)
            when (val merge = ReviewMerge.merge(baseDocument, local, remote)) {
                is MergeResult.Conflicted -> {
                    conflicts.replace(
                        bookId,
                        SyncConflict.Review(path, merge.partial, merge.conflicts, remoteFile.bytes, remoteFile.revision),
                    )
                    ReviewProcess.Blocked(true)
                }
                is MergeResult.Merged -> {
                    val revision = writeReview(bookId, path, merge.document, publication)
                    metadata.recordOutbox(outbox.copy(localSha256 = revision.sha256, baseSha256 = base.sha256))
                    ReviewProcess.Upload(ReviewJson.encode(merge.document).encodeToByteArray())
                }
            }
        }
        return when (result) {
            ReviewProcess.Done -> false
            ReviewProcess.Deferred -> false
            is ReviewProcess.Blocked -> result.value
            is ReviewProcess.Upload -> {
                uploadReviewConfirmed(bookId, path, result.bytes, rootPath, lock, mutations)
                false
            }
        }
    }

    private suspend fun processMissingRemoteReview(
        bookId: String,
        path: String,
        confirmedBeforeSync: Boolean,
        publication: SyncPublication,
        mutations: ReviewMutationCoordinator.BookMutationScope,
    ): MissingRemoteReviewProcess = mutations.withReview(path) {
        if (isReviewDeferred(bookId, path)) return@withReview MissingRemoteReviewProcess.Unchanged
        val outbox = metadata.outbox(bookId).singleOrNull { it.path == path }
        if (outbox == null) {
            if (!confirmedBeforeSync) return@withReview MissingRemoteReviewProcess.Unchanged
            requireDeletionDurable(bookStore.deleteReview(bookId, path), "Review cache")
            requireDeletionDurable(baseStore.delete(bookId, path), "Review sync base")
            metadata.acceptRemoteDeletion(bookId, path)
            conflicts.remove(bookId, path)
            publication.stagePersisted(path)
            return@withReview MissingRemoteReviewProcess.Unchanged
        }
        val baseSha = outbox.baseSha256 ?: return@withReview MissingRemoteReviewProcess.Upload
        val local = requireNotNull(bookStore.readReview(bookId, path)) { "Pending review is missing from the local cache" }
        val localBytes = ReviewJson.encode(local).encodeToByteArray()
        require(sha256(localBytes) == outbox.localSha256) { "Local review changed outside outbox" }
        val base = baseStore.read(bookId, path)
        val metadataBase = metadata.mergeBase(bookId, path)
        if (base == null || base.sha256 != baseSha || metadataBase?.sha256 != baseSha) {
            conflicts.replace(bookId, SyncConflict.MissingBase(path, "Exact review merge base is unavailable"))
        } else {
            conflicts.replace(
                bookId,
                SyncConflict.Review(
                    path = path,
                    partial = local,
                    records = listOf(
                        RecordConflict(
                            REMOTE_REVIEW_DELETION_RECORD_ID,
                            base = null,
                            local = RecordValue.ChapterNoteValue("Локальная рецензия изменена"),
                            remote = null,
                        ),
                    ),
                    remoteDeleted = true,
                ),
            )
        }
        MissingRemoteReviewProcess.Blocked
    }

    private suspend fun uploadNewManifest(
        bookId: String,
        local: BookManifest,
        outbox: OutboxEntity,
        rootPath: String,
        lock: SyncLock,
    ) {
        val bytes = BookManifest.encode(local).encodeToByteArray()
        require(sha256(bytes) == outbox.localSha256) { "Local manifest changed outside outbox" }
        uploadConfirmed(bookId, MANIFEST_PATH, bytes, rootPath, lock)
    }

    private suspend fun uploadNewReview(
        bookId: String,
        path: String,
        rootPath: String,
        lock: SyncLock,
        mutations: ReviewMutationCoordinator.BookMutationScope,
    ) {
        val bytes = mutations.withReview(path) {
            if (isReviewDeferred(bookId, path)) return@withReview null
            val outbox = metadata.outbox(bookId).singleOrNull { it.path == path }
                ?: return@withReview null
            val local = requireNotNull(bookStore.readReview(bookId, path)) { "Pending review is missing from the local cache" }
            ReviewJson.encode(local).encodeToByteArray().also {
                require(sha256(it) == outbox.localSha256) { "Local review changed outside outbox" }
            }
        } ?: return
        uploadReviewConfirmed(bookId, path, bytes, rootPath, lock, mutations)
    }

    private suspend fun uploadReviewConfirmed(
        bookId: String,
        path: String,
        bytes: ByteArray,
        rootPath: String,
        lock: SyncLock,
        mutations: ReviewMutationCoordinator.BookMutationScope,
    ) {
        val prepared = mutations.withReview(path) {
            if (isReviewDeferred(bookId, path)) return@withReview null
            val outbox = metadata.outbox(bookId).singleOrNull { it.path == path }
                ?: return@withReview null
            val snapshot = bytes.copyOf()
            val snapshotHash = sha256(snapshot)
            if (outbox.localSha256 != snapshotHash) return@withReview null
            PreparedReviewUpload(snapshot, snapshotHash, outbox)
        } ?: return
        val revision = gateway.uploadGuarded(rootPath, path, prepared.bytes, lock)
        mutations.withReview(path) {
            confirmRemote(bookId, path, prepared.bytes, revision)
            val current = metadata.outbox(bookId).singleOrNull { it.path == path }
            if (current?.localSha256 == prepared.outbox.localSha256) {
                metadata.removeOutbox(bookId, path)
            } else if (current != null) {
                metadata.recordOutbox(
                    current.copy(
                        baseSha256 = prepared.sha256,
                        state = net.inkyquill.pocketeditor.database.OutboxState.PENDING,
                    ),
                )
            }
            conflicts.remove(bookId, path)
        }
    }

    private suspend fun isReviewDeferred(bookId: String, path: String): Boolean =
        pendingDeletions.pendingForBook(bookId).any { it.reviewPath == path }

    private fun validateSource(bytes: ByteArray) {
        runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
        }.getOrElse { throw IllegalArgumentException("Canonical source must be valid UTF-8", it) }
    }

    private suspend fun uploadConfirmed(
        bookId: String,
        path: String,
        bytes: ByteArray,
        rootPath: String,
        lock: SyncLock,
    ) {
        val revision = gateway.uploadGuarded(rootPath, path, bytes, lock)
        confirmRemote(bookId, path, bytes, revision)
        metadata.removeOutbox(bookId, path)
        conflicts.remove(bookId, path)
    }

    private sealed interface ReviewProcess {
        data object Done : ReviewProcess
        data object Deferred : ReviewProcess
        data class Blocked(val value: Boolean) : ReviewProcess
        data class Upload(val bytes: ByteArray) : ReviewProcess
    }

    private data class PreparedReviewUpload(
        val bytes: ByteArray,
        val sha256: String,
        val outbox: OutboxEntity,
    )

    private suspend fun confirmRemote(bookId: String, path: String, bytes: ByteArray, revision: String) {
        val base = writeDurableBase(bookId, path, bytes, revision)
        metadata.recordBase(MergeBaseEntity(bookId, path, base.sha256, revision))
        metadata.recordRemote(RemoteRevisionEntity(bookId, path, revision, base.sha256))
    }

    private fun writeDurableBase(bookId: String, path: String, bytes: ByteArray, revision: String): SyncBase {
        val base = baseStore.write(bookId, path, bytes, revision)
        require(base.directorySyncStatus == DirectorySyncStatus.SYNCED) {
            "Sync base directory durability is unsupported; remote confirmation remains pending"
        }
        return base
    }

    private fun requireDeletionDurable(status: DirectorySyncStatus, label: String) {
        require(status == DirectorySyncStatus.SYNCED) {
            "$label directory durability is unsupported; deletion confirmation remains pending"
        }
    }

    private suspend fun trustedBase(bookId: String, path: String, outbox: OutboxEntity): SyncBase? {
        val metadataBase = metadata.mergeBase(bookId, path) ?: return null
        val base = baseStore.read(bookId, path) ?: return null
        return base.takeIf {
            outbox.baseSha256 == it.sha256 &&
                metadataBase.sha256 == it.sha256 &&
                metadataBase.remoteRevision == it.remoteRevision
        }
    }

    private fun missingBase(bookId: String, path: String, detail: String): Boolean {
        conflicts.replace(bookId, SyncConflict.MissingBase(path, detail))
        return true
    }

    private fun setStatus(bookId: String, status: SyncStatus) {
        statuses.update { current -> current + (bookId to status) }
    }

    private suspend fun writeManifest(bookId: String, value: BookManifest) =
        bookStore.writeManifest(bookId, value)

    private suspend fun writeReview(
        bookId: String,
        path: String,
        value: ReviewDocument,
        publication: SyncPublication? = null,
    ) = bookStore.writeReview(bookId, path, value).also {
        publication?.stage(bookId, path, metadata)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private suspend fun refreshStatusAfterConflictResolution(bookId: String) {
        val status = when {
            metadata.pendingPublicationPaths(bookId).isNotEmpty() -> SyncStatus.WaitingToSync
            conflicts.conflicts(bookId).first().isNotEmpty() -> SyncStatus.ActionRequired("Разрешите конфликты синхронизации")
            metadata.outbox(bookId).isNotEmpty() -> SyncStatus.WaitingToSync
            else -> SyncStatus.Saved
        }
        setStatus(bookId, status)
    }

    private companion object {
        const val MANIFEST_PATH = ".pocket-editor.json"
        const val REVIEW_SUFFIX = ".review.json"
    }

    private class SyncPublication {
        private val staged = linkedSetOf<String>()
        private val committed = linkedSetOf<String>()

        suspend fun stage(bookId: String, path: String, metadata: SyncMetadataStore) {
            metadata.stagePublication(bookId, path)
            staged += path
        }

        fun stagePersisted(path: String) {
            staged += path
        }

        fun recover(path: String) {
            committed += path
        }

        fun commit() {
            committed += staged
            staged.clear()
        }

        suspend fun publish(bookId: String, notifier: ContentChangeNotifier, metadata: SyncMetadataStore) {
            if (committed.isEmpty()) return
            notifier.changed(bookId, committed)
            notifier.bookChanged(bookId)
            committed.forEach { path -> metadata.acknowledgePublication(bookId, path) }
        }
    }

    private enum class MissingRemoteReviewProcess { Unchanged, Upload, Blocked }
}
