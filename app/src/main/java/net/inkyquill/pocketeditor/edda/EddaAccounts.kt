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
    fun credentials(key: String): EddaCredentials {
        val account = list().singleOrNull { it.key == key } ?: throw YandexDiskError.Unauthorized()
        val token = vault(key).read() ?: throw YandexDiskError.Unauthorized()
        return EddaCredentials(account.server, token.secret.revealForAuthorization())
    }
    suspend fun signIn(client: EddaClient, server: String, email: String, password: String): EddaAccount = withContext(Dispatchers.IO) {
        val normalized = EddaClient.normalizeServer(server)
        val login = client.login(normalized, email.trim(), password)
        val key = "$normalized\n${login.author.id}".encodeToByteArray().sha256()
        val account = EddaAccount(key, normalized, login.author.id, login.author.email)
        check(vault(key).write(LoginToken(SecretToken(login.token), Instant.MAX))) { "Не удалось сохранить вход" }
        synchronized(this@EddaAccounts) {
            check(preferences.edit().putString("accounts", Json.encodeToString(list().filterNot { it.key == key } + account)).commit())
        }
        account
    }
    fun rememberProject(account: String, project: EddaProject) {
        check(preferences.edit().putString("project:$account:${project.id}", project.title).commit())
    }
    fun describe(root: String): String {
        if (root.isBlank()) return "Локальная копия"
        if (!root.startsWith("edda://")) return "Яндекс Диск / " + root.removePrefix("disk:")
        val location = EddaLocation.parse(root)
        val account = list().singleOrNull { it.key == location.account }
        val project = preferences.getString("project:${location.account}:${location.project}", location.project)
        return "Open Edda / ${account?.email ?: "Аккаунт недоступен"} (${account?.server ?: ""}) / $project / ${location.path.ifBlank { "/" }}"
    }
    fun signOut(key: String) {
        // Keep source identity and offline books so a later sign-in resumes the same bindings.
        check(vault(key).clear()) { "Не удалось удалить вход" }
    }
}
