package net.inkyquill.pocketeditor.ui.navigation

import net.inkyquill.pocketeditor.reader.ReaderSyncState

internal fun handleReaderSyncAction(
    state: ReaderSyncState?,
    remoteRoot: String?,
    onSignIn: (String) -> Unit,
    onSync: () -> Unit,
) {
    if (state == ReaderSyncState.SIGN_IN_REQUIRED) {
        remoteRoot?.takeIf(String::isNotBlank)?.let(onSignIn)
    } else {
        onSync()
    }
}
