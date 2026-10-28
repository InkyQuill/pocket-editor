package net.inkyquill.pocketeditor.edda

import android.content.Context
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.inkyquill.pocketeditor.storage.sha256
import net.inkyquill.pocketeditor.yandex.AndroidKeystoreTokenVault
import net.inkyquill.pocketeditor.yandex.LoginToken
import net.inkyquill.pocketeditor.yandex.SecretToken
import net.inkyquill.pocketeditor.yandex.YandexDiskError

@Serializable
data class EddaAccount(val key: String, val server: String, val authorId: String, val email: String)

class EddaAccounts(private val context: Context) {
    private val preferences = context.getSharedPreferences("edda_accounts", Context.MODE_PRIVATE)
    fun list(): List<EddaAccount> = Json.decodeFromString(preferences.getString("accounts", "[]") ?: "[]")
    private fun vault(key: String) = AndroidKeystoreTokenVault(context.getSharedPreferences("edda_token_$key", Context.MODE_PRIVATE))
    fun hasCredentials(key: String): Boolean = vault(key).read() != null
    @Synchronized
    fun credentials(key: String): EddaCredentials {
        val account = list().singleOrNull { it.key == key } ?: throw YandexDiskError.Unauthorized()
        val token = vault(key).read() ?: throw YandexDiskError.Unauthorized()
        val stored = token.secret.revealForAuthorization()
        // Old installations stored only the access token; retain it until sign-in is required.
        val session = if (stored.startsWith("{")) Json.decodeFromString<EddaLogin>(stored) else null
        return EddaCredentials(account.server, session?.token ?: stored, session?.refreshToken, account.authorId)
    }
    suspend fun signIn(client: EddaClient, server: String, email: String, password: String, expectedAccountKey: String? = null): EddaAccount = withContext(Dispatchers.IO) {
        val normalized = EddaClient.normalizeServer(server)
        val login = client.login(normalized, email.trim(), password)
        val key = "$normalized\n${login.author.id}".encodeToByteArray().sha256()
        require(expectedAccountKey == null || key == expectedAccountKey) { "Войдите в исходный аккаунт книги" }
        val account = EddaAccount(key, normalized, login.author.id, login.author.email)
        synchronized(this@EddaAccounts) {
            saveSession(key, login)
            check(preferences.edit().putString("accounts", Json.encodeToString(list().filterNot { it.key == key } + account)).commit())
        }
        account
    }
    private fun saveSession(key: String, login: EddaLogin) {
        require(login.token.isNotBlank())
        // One encrypted record atomically persists both tokens, including rotated refresh tokens.
        check(vault(key).write(LoginToken(SecretToken(Json.encodeToString(login)), Instant.MAX))) { "Не удалось сохранить вход" }
    }
    @Synchronized
    internal fun saveRefreshedSession(key: String, previousToken: String, login: EddaLogin) {
        val current = credentials(key)
        // Do not resurrect an account signed out or replaced while refresh was in flight.
        if (current.token != previousToken || current.authorId != login.author.id) throw YandexDiskError.Unauthorized()
        saveSession(key, login)
    }
    fun rememberProject(account: String, project: EddaProject) {
        synchronized(this) {
            check(preferences.edit().putString("project:$account:${project.id}", project.title).commit())
        }
    }
    fun describe(root: String): String {
        if (root.isBlank()) return "Локальная копия"
        if (!root.startsWith("edda://")) return "Яндекс Диск / " + root.removePrefix("disk:")
        val location = EddaLocation.parse(root)
        val account = list().singleOrNull { it.key == location.account }
        val project = preferences.getString("project:${location.account}:${location.project}", location.project)
        return "Open Edda / ${account?.email ?: "Аккаунт недоступен"} (${account?.server ?: ""}) / $project / ${location.path.ifBlank { "/" }}"
    }
    @Synchronized
    fun signOut(key: String) {
        // Keep source identity and offline books so a later sign-in resumes the same bindings.
        check(vault(key).clear()) { "Не удалось удалить вход" }
    }
}
