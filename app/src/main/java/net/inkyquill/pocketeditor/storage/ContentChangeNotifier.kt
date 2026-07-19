package net.inkyquill.pocketeditor.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ContentKey(val bookId: String, val path: String)

class ContentChangeNotifier {
    private val mutableVersions = MutableStateFlow<Map<ContentKey, Long>>(emptyMap())
    val versions: StateFlow<Map<ContentKey, Long>> = mutableVersions.asStateFlow()

    fun changed(bookId: String, path: String) {
        val key = ContentKey(bookId, path)
        mutableVersions.update { current -> current + (key to (current[key] ?: 0L) + 1L) }
    }
}
