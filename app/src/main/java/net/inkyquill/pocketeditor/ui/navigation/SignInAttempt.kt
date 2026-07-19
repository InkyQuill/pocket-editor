package net.inkyquill.pocketeditor.ui.navigation

import kotlinx.coroutines.CancellationException

data class SignInUiState(val loading: Boolean = false, val error: String? = null)

internal suspend fun performSignIn(
    onState: (SignInUiState) -> Unit,
    action: suspend () -> Unit,
) {
    onState(SignInUiState(loading = true))
    try {
        action()
        onState(SignInUiState())
    } catch (cancelled: CancellationException) {
        onState(SignInUiState())
        throw cancelled
    } catch (failure: Throwable) {
        onState(SignInUiState(error = failure.message ?: "Sign in failed"))
    }
}
