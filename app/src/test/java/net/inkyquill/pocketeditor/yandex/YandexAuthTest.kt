package net.inkyquill.pocketeditor.yandex

import androidx.activity.ComponentActivity
import java.time.Instant
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class YandexAuthTest {
    @Test
    fun `sign in stores token privately and exposes only signed-in session`() = runBlocking {
        val vault = InMemoryTokenVault()
        val auth: YandexAuth = DefaultYandexAuth(vault) { _ ->
            LoginToken(SecretToken("oauth-value"), Instant.parse("2026-07-20T10:00:00Z"))
        }

        val signedIn = auth.signIn(mockk<ComponentActivity>())

        assertEquals(AuthSession.SignedIn(Instant.parse("2026-07-20T10:00:00Z")), signedIn)
        assertEquals(signedIn, auth.session.value)
        assertEquals("oauth-value", auth.accessToken().revealForAuthorization())
        assertEquals("<redacted>", auth.accessToken().toString())
    }

    @Test
    fun `sign out deletes credentials and accessToken becomes unauthorized`() = runBlocking {
        val vault = InMemoryTokenVault(LoginToken(SecretToken("oauth-value"), Instant.MAX))
        val auth: YandexAuth = DefaultYandexAuth(vault) { error("not used") }

        auth.signOut()

        assertEquals(AuthSession.SignedOut, auth.session.value)
        assertThrows(YandexDiskError.Unauthorized::class.java) {
            runBlocking { auth.accessToken() }
        }
        Unit
    }

    private class InMemoryTokenVault(initial: LoginToken? = null) : TokenVault {
        private var token = initial
        override fun read(): LoginToken? = token
        override fun write(token: LoginToken) { this.token = token }
        override fun clear() { token = null }
    }
}
