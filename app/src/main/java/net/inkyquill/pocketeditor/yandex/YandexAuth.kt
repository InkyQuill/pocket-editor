package net.inkyquill.pocketeditor.yandex

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.activity.ComponentActivity
import com.yandex.authsdk.YandexAuthLoginOptions
import com.yandex.authsdk.YandexAuthOptions
import com.yandex.authsdk.YandexAuthResult
import com.yandex.authsdk.YandexAuthSdk
import java.security.KeyStore
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface AuthSession {
    data object SignedOut : AuthSession
    data class SignedIn(val expiresAt: Instant) : AuthSession
}

class SecretToken(private val value: String) {
    init {
        require(value.isNotBlank())
    }

    internal fun revealForAuthorization(): String = value

    override fun toString(): String = "<redacted>"
}

data class LoginToken(val secret: SecretToken, val expiresAt: Instant)

interface TokenVault {
    fun read(): LoginToken?
    fun write(token: LoginToken): Boolean
    fun clear(): Boolean
}

interface YandexAuth {
    val session: StateFlow<AuthSession>
    suspend fun signIn(activity: ComponentActivity): AuthSession.SignedIn
    suspend fun signOut()
    suspend fun accessToken(): SecretToken
}

class DefaultYandexAuth(
    private val vault: TokenVault,
    private val clock: Clock = Clock.systemUTC(),
    private val login: suspend (ComponentActivity) -> LoginToken,
) : YandexAuth {
    private val current = MutableStateFlow<AuthSession>(vault.read().toSession(clock.instant()))
    override val session: StateFlow<AuthSession> = current.asStateFlow()

    override suspend fun signIn(activity: ComponentActivity): AuthSession.SignedIn {
        val token = login(activity)
        if (!vault.write(token)) throw CredentialPersistenceException("Could not store Yandex credentials")
        return AuthSession.SignedIn(token.expiresAt).also { current.value = it }
    }

    override suspend fun signOut() {
        if (!vault.clear()) throw CredentialPersistenceException("Could not delete Yandex credentials")
        current.value = AuthSession.SignedOut
    }

    override suspend fun accessToken(): SecretToken {
        val token = vault.read()
        if (token == null || !token.expiresAt.isAfter(clock.instant())) {
            if (token != null && !vault.clear()) {
                throw CredentialPersistenceException("Could not delete expired Yandex credentials")
            }
            current.value = AuthSession.SignedOut
            throw YandexDiskError.Unauthorized()
        }
        return token.secret
    }

    companion object {
        fun create(context: Context): DefaultYandexAuth {
            val sdk = YandexAuthSdk.create(YandexAuthOptions(context, loggingEnabled = false))
            return DefaultYandexAuth(AndroidKeystoreTokenVault(context)) { activity -> sdk.login(activity) }
        }
    }
}

private fun LoginToken?.toSession(now: Instant): AuthSession =
    if (this != null && expiresAt.isAfter(now)) AuthSession.SignedIn(expiresAt) else AuthSession.SignedOut

private suspend fun YandexAuthSdk.login(activity: ComponentActivity): LoginToken =
    suspendCancellableCoroutine { continuation ->
        val key = "yandex-auth-${UUID.randomUUID()}"
        lateinit var launcher: androidx.activity.result.ActivityResultLauncher<YandexAuthLoginOptions>
        launcher = activity.activityResultRegistry.register(key, contract) { result ->
            launcher.unregister()
            when (result) {
                is YandexAuthResult.Success -> continuation.resume(
                    LoginToken(
                        secret = SecretToken(result.token.value),
                        expiresAt = Instant.now().plusSeconds(result.token.expiresIn),
                    ),
                )
                is YandexAuthResult.Failure -> continuation.resumeWithException(result.exception)
                YandexAuthResult.Cancelled -> continuation.cancel(AuthCancelledException())
            }
        }
        continuation.invokeOnCancellation { launcher.unregister() }
        launcher.launch(YandexAuthLoginOptions())
    }

class AuthCancelledException : Exception("Yandex sign-in was cancelled")
class CredentialPersistenceException(message: String) : IllegalStateException(message)

@SuppressLint("ApplySharedPref", "UseKtx")
class AndroidKeystoreTokenVault internal constructor(
    private val preferences: SharedPreferences,
) : TokenVault {
    constructor(context: Context) : this(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE))

    override fun read(): LoginToken? {
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = preferences.getString(KEY_IV, null) ?: return null
        val expiresAt = preferences.getLong(KEY_EXPIRES_AT, Long.MIN_VALUE)
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, Base64.getDecoder().decode(iv)))
            val plaintext = cipher.doFinal(Base64.getDecoder().decode(ciphertext)).toString(Charsets.UTF_8)
            LoginToken(SecretToken(plaintext), Instant.ofEpochSecond(expiresAt))
        }.getOrElse {
            clear()
            null
        }
    }

    override fun write(token: LoginToken): Boolean {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(token.secret.revealForAuthorization().toByteArray())
        return preferences.edit()
            .putString(KEY_CIPHERTEXT, Base64.getEncoder().encodeToString(ciphertext))
            .putString(KEY_IV, Base64.getEncoder().encodeToString(cipher.iv))
            .putLong(KEY_EXPIRES_AT, token.expiresAt.epochSecond)
            .commit()
    }

    override fun clear(): Boolean = preferences.edit().clear().commit()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES = "yandex_auth_private"
        const val KEY_CIPHERTEXT = "ciphertext"
        const val KEY_IV = "iv"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_ALIAS = "pocket_editor_yandex_oauth"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
    }
}
