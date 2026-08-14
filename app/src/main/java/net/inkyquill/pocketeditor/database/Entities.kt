package net.inkyquill.pocketeditor.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "book_roots")
data class BookRootEntity(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "remote_root_path") val remoteRootPath: String?,
    @ColumnInfo(name = "local_directory") val localDirectory: String,
    @ColumnInfo(name = "registered_at") val registeredAt: Long,
)

@Entity(tableName = "remote_revisions", primaryKeys = ["book_id", "path"])
data class RemoteRevisionEntity(
    @ColumnInfo(name = "book_id") val bookId: String,
    val path: String,
    @ColumnInfo(name = "remote_revision") val remoteRevision: String,
    val sha256: String?,
)

@Entity(tableName = "pending_publications", primaryKeys = ["book_id", "path"])
data class PendingPublicationEntity(
    @ColumnInfo(name = "book_id") val bookId: String,
    val path: String,
)

@Entity(tableName = "merge_bases", primaryKeys = ["book_id", "path"])
data class MergeBaseEntity(
    @ColumnInfo(name = "book_id") val bookId: String,
    val path: String,
    val sha256: String,
    @ColumnInfo(name = "remote_revision") val remoteRevision: String?,
)

enum class OutboxState {
    PENDING,
    UPLOADING,
    RETRY,
    NEEDS_REMOTE_COMPARE,
}

@Entity(tableName = "outbox", primaryKeys = ["book_id", "path"])
data class OutboxEntity(
    @ColumnInfo(name = "book_id") val bookId: String,
    val path: String,
    @ColumnInfo(name = "local_sha256") val localSha256: String,
    @ColumnInfo(name = "base_sha256") val baseSha256: String?,
    val state: OutboxState,
    val attempts: Int = 0,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long? = null,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
) {
    val isUploadable: Boolean
        get() = state == OutboxState.PENDING || state == OutboxState.RETRY
}

@Entity(tableName = "pending_deletions")
data class PendingDeletionEntity(
    @PrimaryKey @ColumnInfo(name = "token_id") val tokenId: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "chapter_id") val chapterId: String,
    @ColumnInfo(name = "review_path") val reviewPath: String,
    @ColumnInfo(name = "record_id") val recordId: String,
    @ColumnInfo(name = "record_type") val recordType: String,
    @ColumnInfo(name = "record_payload") val recordPayload: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "reading_positions")
data class ReadingPositionEntity(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "chapter_id") val chapterId: String,
    @ColumnInfo(name = "block_index") val blockIndex: Int,
    @ColumnInfo(name = "byte_offset") val byteOffset: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "drafts", primaryKeys = ["book_id", "chapter_id", "draft_type", "record_key"])
data class DraftEntity(
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "chapter_id") val chapterId: String,
    @ColumnInfo(name = "draft_type") val draftType: String,
    @ColumnInfo(name = "record_id") val recordId: String?,
    val text: String,
    @ColumnInfo(name = "selection_start") val selectionStart: Int,
    @ColumnInfo(name = "selection_end") val selectionEnd: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "record_key") val recordKey: String = recordId.orEmpty(),
)

@Entity(
    tableName = "import_drafts",
    indices = [Index(value = ["remote_root_path"], unique = true)],
)
data class ImportDraftEntity(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "remote_root_path") val remoteRootPath: String,
    @ColumnInfo(name = "local_directory") val localDirectory: String,
    @ColumnInfo(name = "document_json") val documentJson: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
