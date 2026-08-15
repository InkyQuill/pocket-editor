package net.inkyquill.pocketeditor.sync

import android.content.Context
import android.content.SharedPreferences

interface RetryGenerationStore {
    fun current(bookId: String): Long
    fun isCurrent(bookId: String, generation: Long): Boolean = current(bookId) == generation
    fun advance(bookId: String): Long
    fun advanceForEnqueue(bookId: String): RetryGenerationAdvance
    fun restoreIfCurrent(bookId: String, generation: RetryGenerationAdvance): Boolean
    fun invalidateIfCurrent(bookId: String, generation: Long): Boolean
}

data class RetryGenerationAdvance(val previous: Long, val current: Long)

class InMemoryRetryGenerationStore : RetryGenerationStore {
    private val values = mutableMapOf<String, Long>()

    @Synchronized
    override fun current(bookId: String): Long = values[bookId] ?: 0L

    @Synchronized
    override fun advance(bookId: String): Long = next(current(bookId)).also { values[bookId] = it }

    @Synchronized
    override fun advanceForEnqueue(bookId: String): RetryGenerationAdvance {
        val previous = current(bookId)
        val current = next(previous)
        values[bookId] = current
        return RetryGenerationAdvance(previous, current)
    }

    @Synchronized
    override fun restoreIfCurrent(bookId: String, generation: RetryGenerationAdvance): Boolean {
        if (current(bookId) != generation.current) return false
        values[bookId] = generation.previous
        return true
    }

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

    override fun advanceForEnqueue(bookId: String): RetryGenerationAdvance = synchronized(lock) {
        val previous = preferences.getLong(key(bookId), 0L)
        val current = next(previous)
        persist(bookId, current)
        RetryGenerationAdvance(previous, current)
    }

    override fun restoreIfCurrent(bookId: String, generation: RetryGenerationAdvance): Boolean = synchronized(lock) {
        if (preferences.getLong(key(bookId), 0L) != generation.current) return@synchronized false
        persist(bookId, generation.previous)
        true
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
