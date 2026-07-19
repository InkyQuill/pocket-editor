package net.inkyquill.pocketeditor.sync

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RetryGenerationStoreTest {
    @Test
    fun `shared preferences generation survives store recreation`() {
        val values = mutableMapOf<String, Long>()
        val pending = mutableMapOf<String, Long>()
        val preferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { preferences.getLong(any(), any()) } answers {
            values[firstArg()] ?: secondArg()
        }
        every { preferences.edit() } returns editor
        every { editor.putLong(any(), any()) } answers {
            pending[firstArg()] = secondArg()
            editor
        }
        every { editor.commit() } answers {
            values.putAll(pending)
            pending.clear()
            true
        }
        val first = SharedPreferencesRetryGenerationStore(preferences)
        val generation = first.advance(BOOK_ID)

        val recreated = SharedPreferencesRetryGenerationStore(preferences)

        assertTrue(recreated.isCurrent(BOOK_ID, generation))
        assertTrue(recreated.invalidateIfCurrent(BOOK_ID, generation))
        assertFalse(first.isCurrent(BOOK_ID, generation))
    }

    private companion object {
        val BOOK_ID = UUID.randomUUID().toString()
    }
}
