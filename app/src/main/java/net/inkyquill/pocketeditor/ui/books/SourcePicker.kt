package net.inkyquill.pocketeditor.ui.books

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.inkyquill.pocketeditor.edda.*
import net.inkyquill.pocketeditor.source.sourceProviders

/** Credentials live in the keystore-backed account store; passwords are never saved in UI state. */
@Composable
fun SourcePicker(
    accounts: EddaAccounts,
    client: EddaClient,
    yandexSignedIn: Boolean,
    onYandexSignIn: () -> Unit,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
    yandexSigningIn: Boolean = false,
    yandexSignInError: String? = null,
) {
    var provider by remember { mutableStateOf<String?>(null) }
    var account by remember { mutableStateOf<EddaAccount?>(null) }
    var projects by remember { mutableStateOf<List<EddaProject>?>(null) }
    var addingAccount by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var knownAccounts by remember { mutableStateOf(accounts.list()) }
    var disconnected by remember { mutableStateOf(knownAccounts.filterNot { accounts.hasCredentials(it.key) }.map { it.key }.toSet()) }
    val scope = rememberCoroutineScope()
    fun loadProjects(selected: EddaAccount) {
        scope.launch {
            busy = true
            error = null
            try {
                val result = client.projects(selected.key)
                account = selected
                projects = result
                addingAccount = false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Не удалось открыть проекты. Проверьте соединение или войдите в аккаунт заново." }
            finally { busy = false }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(when { provider == null -> "Провайдер"; projects != null -> "Проект"; else -> "Аккаунт" }) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (provider == null) {
                    sourceProviders.filter { it.available }.forEach { item ->
                        OutlinedButton(onClick = { provider = item.id }, modifier = Modifier.fillMaxWidth()) { Text(item.title) }
                    }
                } else {
                    Text(sourceProviders.single { it.id == provider }.title, style = MaterialTheme.typography.labelLarge)
                    when {
                        provider == "disk" -> {
                            Text("Текущий аккаунт Яндекс Диска")
                            if (yandexSignedIn) Button(onClick = { onChoose("disk:/") }) { Text("Выбрать расположение") }
                            else Button(enabled = !yandexSigningIn, onClick = onYandexSignIn) { Text(if (yandexSigningIn) "Выполняется вход…" else "Войти через Яндекс") }
                            yandexSignInError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                        projects != null -> {
                            Text("${account?.email} · ${account?.server}", style = MaterialTheme.typography.bodySmall)
                            if (projects!!.isEmpty()) Text("В этом аккаунте пока нет файловых проектов.")
                            projects!!.forEach { project ->
                                OutlinedButton(onClick = { val selected = requireNotNull(account); accounts.rememberProject(selected.key, project); onChoose(EddaLocation(selected.key, project.id).root) }, modifier = Modifier.fillMaxWidth()) {
                                    Text(project.title)
                                }
                            }
                        }
                        addingAccount -> {
                            OutlinedTextField(server, { server = it }, label = { Text("Адрес сервера") }, placeholder = { Text("https://edda.example.org") }, singleLine = true, enabled = !busy)
                            OutlinedTextField(email, { email = it }, label = { Text("Электронная почта") }, singleLine = true, enabled = !busy)
                            OutlinedTextField(password, { password = it }, label = { Text("Пароль") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy)
                            Button(enabled = !busy && server.isNotBlank() && email.isNotBlank() && password.isNotEmpty(), onClick = {
                                scope.launch {
                                    busy = true
                                    error = null
                                    try {
                                        val selected = accounts.signIn(client, server, email, password)
                                        password = ""
                                        knownAccounts = accounts.list()
                                        disconnected = disconnected - selected.key
                                        val result = client.projects(selected.key)
                                        account = selected
                                        projects = result
                                        addingAccount = false
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { error = "Не удалось войти. Проверьте адрес сервера, почту, пароль и соединение." }
                                    finally { busy = false }
                                }
                            }) { Text("Войти") }
                        }
                        else -> {
                            knownAccounts.forEach { item ->
                                OutlinedButton(enabled = !busy && item.key !in disconnected, onClick = { loadProjects(item) }, modifier = Modifier.fillMaxWidth()) {
                                    Column { Text(item.email); Text(item.server, style = MaterialTheme.typography.bodySmall) }
                                }
                                Row {
                                    TextButton(enabled = !busy, onClick = { server = item.server; email = item.email; addingAccount = true }) { Text(if (item.key in disconnected) "Войти" else "Войти заново") }
                                    if (item.key !in disconnected) TextButton(enabled = !busy, onClick = {
                                        try { accounts.signOut(item.key); disconnected = disconnected + item.key }
                                        catch (_: Exception) { error = "Не удалось выйти из аккаунта." }
                                    }) { Text("Выйти") }
                                }
                            }
                            Button(enabled = !busy, onClick = { addingAccount = true }) { Text("Добавить аккаунт") }
                        }
                    }
                }
                if (busy) CircularProgressIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(enabled = !busy, onClick = {
                error = null
                when { projects != null -> { projects = null; account = null }; addingAccount -> { addingAccount = false; password = "" }; provider != null -> provider = null; else -> onDismiss() }
            }) { Text(if (provider == null) "Отмена" else "Назад") }
        },
    )
}
