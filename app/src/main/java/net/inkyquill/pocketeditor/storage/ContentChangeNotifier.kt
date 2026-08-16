package net.inkyquill.pocketeditor.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ContentKey(val bookId: String, val path: String)

class ContentChangeNotifier {
    private val mutableVersions = MutableStateFlow<Map<ContentKey, Long>>(emptyMap())
    val versions: StateFlow<Map<ContentKey, Long>> = mutableVersions.asStateFlow()
    private val mutableBookVersions = MutableStateFlow<Map<String, Long>>(emptyMap())
    val bookVersions: StateFlow<Map<String, Long>> = mutableBookVersions.asStateFlow()

    fun changed(bookId: String, path: String) {
        changed(bookId, setOf(path))
    }

    fun changed(bookId: String, paths: Set<String>) {
        if (paths.isEmpty()) return
        mutableVersions.update { current ->
            current + paths.associate { path ->
                val key = ContentKey(bookId, path)
                key to (current[key] ?: 0L) + 1L
            }
        }
    }

    fun bookChanged(bookId: String) {
        mutableBookVersions.update { current -> current + (bookId to (current[bookId] ?: 0L) + 1L) }
    }
}
