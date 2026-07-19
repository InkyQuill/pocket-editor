package net.inkyquill.pocketeditor.sync

import android.content.Context
import android.content.SharedPreferences

interface RetryGenerationStore {
    fun current(bookId: String): Long
    fun isCurrent(bookId: String, generation: Long): Boolean = current(bookId) == generation
    fun advance(bookId: String): Long
    fun invalidateIfCurrent(bookId: String, generation: Long): Boolean
}

class InMemoryRetryGenerationStore : RetryGenerationStore {
    private val values = mutableMapOf<String, Long>()

    @Synchronized
    override fun current(bookId: String): Long = values[bookId] ?: 0L

    @Synchronized
    override fun advance(bookId: String): Long = next(current(bookId)).also { values[bookId] = it }

    @Synchronized
    override fun invalidateIfCurrent(bookId: String, generation: Long): Boolean {
        if (current(bookId) != generation) return false
        values[bookId] = next(generation)
        return true
    }
}

class SharedPreferencesRetryGenerationStore(
    private val preferences: SharedPreferences,
) : RetryGenerationStore {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    override fun current(bookId: String): Long = synchronized(lock) {
        preferences.getLong(key(bookId), 0L)
    }

    override fun advance(bookId: String): Long = synchronized(lock) {
        next(preferences.getLong(key(bookId), 0L)).also { persist(bookId, it) }
    }

    override fun invalidateIfCurrent(bookId: String, generation: Long): Boolean = synchronized(lock) {
        if (preferences.getLong(key(bookId), 0L) != generation) return@synchronized false
        persist(bookId, next(generation))
        true
    }

    private fun persist(bookId: String, generation: Long) {
        check(preferences.edit().putLong(key(bookId), generation).commit()) {
            "Retry generation could not be persisted"
        }
    }

    private fun key(bookId: String) = "generation_$bookId"

    private companion object {
        const val PREFERENCES_NAME = "sync_retry_generations"
        val lock = Any()
    }
}

private fun next(value: Long): Long = if (value == Long.MAX_VALUE) 1L else value + 1L
