package net.inkyquill.pocketeditor.yandex

import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class YandexAuthTest {
    private val clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `sign in stores token privately and exposes only signed-in session`() = runBlocking {
        val vault = InMemoryTokenVault()
        val auth: YandexAuth = DefaultYandexAuth(vault, clock) { _ ->
            LoginToken(SecretToken("oauth-value"), Instant.parse("2031-01-01T00:00:00Z"))
        }

        val signedIn = auth.signIn(mockk<ComponentActivity>())

        assertEquals(AuthSession.SignedIn(Instant.parse("2031-01-01T00:00:00Z")), signedIn)
        assertEquals(signedIn, auth.session.value)
        assertEquals("oauth-value", auth.accessToken().revealForAuthorization())
        assertEquals("<redacted>", auth.accessToken().toString())
    }

    @Test
    fun `sign out deletes credentials and accessToken becomes unauthorized`() = runBlocking {
        val vault = InMemoryTokenVault(LoginToken(SecretToken("oauth-value"), Instant.MAX))
        val auth: YandexAuth = DefaultYandexAuth(vault, clock) { error("not used") }

        auth.signOut()

        assertEquals(AuthSession.SignedOut, auth.session.value)
        assertThrows(YandexDiskError.Unauthorized::class.java) {
            runBlocking { auth.accessToken() }
        }
        Unit
    }

    @Test
    fun `failed credential write leaves the session signed out`() {
        val vault = InMemoryTokenVault(writeSucceeds = false)
        val auth: YandexAuth = DefaultYandexAuth(vault, clock) {
            LoginToken(SecretToken("oauth-value"), Instant.parse("2031-01-01T00:00:00Z"))
        }

        assertThrows(CredentialPersistenceException::class.java) {
            runBlocking { auth.signIn(mockk<ComponentActivity>()) }
        }
        assertEquals(AuthSession.SignedOut, auth.session.value)
        assertEquals(null, vault.read())
    }

    @Test
    fun `failed credential deletion preserves the signed-in session`() {
        val token = LoginToken(SecretToken("oauth-value"), Instant.parse("2031-01-01T00:00:00Z"))
        val vault = InMemoryTokenVault(token, clearSucceeds = false)
        val auth: YandexAuth = DefaultYandexAuth(vault, clock) { error("not used") }

        assertThrows(CredentialPersistenceException::class.java) { runBlocking { auth.signOut() } }

        assertEquals(AuthSession.SignedIn(token.expiresAt), auth.session.value)
        assertEquals(token, vault.read())
    }

    @Test
    fun `SharedPreferences commit failure is returned by credential deletion`() {
        val preferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { preferences.edit() } returns editor
        every { editor.clear() } returns editor
        every { editor.commit() } returns false

        assertEquals(false, AndroidKeystoreTokenVault(preferences).clear())
    }

    private class InMemoryTokenVault(
        initial: LoginToken? = null,
        private val writeSucceeds: Boolean = true,
        private val clearSucceeds: Boolean = true,
    ) : TokenVault {
        private var token = initial
        override fun read(): LoginToken? = token
        override fun write(token: LoginToken): Boolean {
            if (writeSucceeds) this.token = token
            return writeSucceeds
        }
        override fun clear(): Boolean {
            if (clearSucceeds) token = null
            return clearSucceeds
        }
    }
}
