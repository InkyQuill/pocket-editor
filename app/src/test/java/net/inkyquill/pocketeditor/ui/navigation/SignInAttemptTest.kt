package net.inkyquill.pocketeditor.ui.navigation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SignInAttemptTest {
    @Test
    fun `ordinary sign in failure clears loading and exposes retryable error`() = runBlocking {
        val states = mutableListOf<SignInUiState>()

        performSignIn(states::add) { error("OAuth unavailable") }

        assertEquals(SignInUiState(loading = false, error = "OAuth unavailable"), states.last())
    }

    @Test
    fun `sign in cancellation clears loading and propagates`() = runBlocking {
        val states = mutableListOf<SignInUiState>()

        assertThrows(CancellationException::class.java) {
            runBlocking { performSignIn(states::add) { throw CancellationException("closed") } }
        }

        assertFalse(states.last().loading)
        assertEquals(null, states.last().error)
    }
}
