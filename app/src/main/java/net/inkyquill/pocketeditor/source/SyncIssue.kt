package net.inkyquill.pocketeditor.source

/** Provider-independent reason requiring user action; display copy is separate. */
enum class SyncIssue { INVALID_REMOTE, CONFLICT, DUPLICATE_REVIEW, MISSING_MANIFEST, ORPHANED_REVIEW, UNKNOWN }
