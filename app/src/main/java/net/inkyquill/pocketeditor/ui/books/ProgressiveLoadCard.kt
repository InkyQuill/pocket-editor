package net.inkyquill.pocketeditor.ui.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import net.inkyquill.pocketeditor.load.ProgressiveLoadErrorCategory
import net.inkyquill.pocketeditor.load.ProgressiveLoadPhase
import net.inkyquill.pocketeditor.load.ProgressiveLoadSnapshot
import kotlinx.coroutines.delay

const val COMPLETION_CARD_MILLIS = 5_000L

fun selectVisibleLoad(
    loads: List<ProgressiveLoadSnapshot>,
    selectedBookId: String?,
    recentRoots: List<String>,
): ProgressiveLoadSnapshot? {
    val rootRanks = recentRoots.withIndex().associate { it.value.canonicalLoadRoot() to it.index }
    val ordering = compareBy<ProgressiveLoadSnapshot>(
        { if (it.bookId == selectedBookId) 1 else 0 },
        { rootRanks[it.remoteRootPath.canonicalLoadRoot()] ?: -1 },
        ProgressiveLoadSnapshot::generation,
        ProgressiveLoadSnapshot::remoteRootPath,
    )
    return loads.filter { it.phase != ProgressiveLoadPhase.COMPLETE }.maxWithOrNull(ordering)
        ?: loads.filter { it.phase == ProgressiveLoadPhase.COMPLETE }.maxWithOrNull(ordering)
}

fun ProgressiveLoadSnapshot.shouldResumeOnReconnect(): Boolean =
    !paused && !cancelled &&
        phase in setOf(ProgressiveLoadPhase.PREPARING, ProgressiveLoadPhase.INITIAL, ProgressiveLoadPhase.BACKGROUND) &&
        lastErrorCategory in setOf(
            ProgressiveLoadErrorCategory.OFFLINE,
            ProgressiveLoadErrorCategory.TIMEOUT,
            ProgressiveLoadErrorCategory.RATE_LIMITED,
            ProgressiveLoadErrorCategory.SERVER,
            ProgressiveLoadErrorCategory.TEMPORARY_AVAILABILITY,
        )

private fun String.canonicalLoadRoot(): String = trim().let { value ->
    if (value.endsWith('/') && value.length > "disk:/".length) value.dropLast(1) else value
}

@Composable
fun ProgressiveLoadHost(
    snapshot: ProgressiveLoadSnapshot?,
    nowMillis: Long,
    onPause: () -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    completionDisplayMillis: Long = COMPLETION_CARD_MILLIS,
    content: @Composable () -> Unit,
) {
    var hiddenCompletionId by remember { mutableStateOf<String?>(null) }
    var tickingNow by remember(snapshot?.bookId, snapshot?.retryAt) { mutableLongStateOf(nowMillis) }
    LaunchedEffect(snapshot?.bookId, snapshot?.retryAt, snapshot?.lastErrorCategory) {
        tickingNow = nowMillis
        while (snapshot?.retryAt != null && tickingNow < snapshot.retryAt) {
            delay(1_000L)
            tickingNow = System.currentTimeMillis()
        }
    }
    LaunchedEffect(snapshot?.bookId, snapshot?.phase) {
        if (snapshot?.phase == ProgressiveLoadPhase.COMPLETE) {
            delay(completionDisplayMillis)
            hiddenCompletionId = snapshot.bookId
        } else if (snapshot != null) {
            hiddenCompletionId = null
        }
    }
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) { content() }
        if (snapshot != null && !(snapshot.phase == ProgressiveLoadPhase.COMPLETE && hiddenCompletionId == snapshot.bookId)) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(12.dp),
            ) {
                if (snapshot.phase == ProgressiveLoadPhase.COMPLETE) {
                    Snackbar(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .widthIn(max = 520.dp)
                            .testTag("progressive-load-card")
                            .semantics {
                                liveRegion = LiveRegionMode.Polite
                                stateDescription = snapshot.primaryText(tickingNow)
                            },
                    ) { Text(snapshot.primaryText(tickingNow)) }
                } else {
                    ProgressiveLoadCard(
                        snapshot, tickingNow, onPause, onContinue, onCancel, onSignIn,
                        Modifier.align(Alignment.Center).fillMaxWidth().widthIn(max = 520.dp),
                    )
                }
            }
        }
    }
}

fun ProgressiveLoadSnapshot.primaryText(nowMillis: Long): String = when {
    phase == ProgressiveLoadPhase.COMPLETE -> "Книга доступна без сети"
    lastErrorCategory == ProgressiveLoadErrorCategory.UNAUTHORIZED -> "Нужно войти в Яндекс Диск"
    lastErrorCategory == ProgressiveLoadErrorCategory.INVALID_REMOTE -> "Требуется действие"
    lastErrorCategory == ProgressiveLoadErrorCategory.OFFLINE -> "Нет сети · продолжим автоматически"
    lastErrorCategory == ProgressiveLoadErrorCategory.RATE_LIMITED && retryAt != null ->
        "Лимит Яндекс Диска · повтор через ${((retryAt - nowMillis).coerceAtLeast(0) + 999) / 1000} с"
    lastErrorCategory == ProgressiveLoadErrorCategory.TIMEOUT -> retryingText("Яндекс Диск не ответил", nowMillis)
    lastErrorCategory == ProgressiveLoadErrorCategory.SERVER -> retryingText("Яндекс Диск временно недоступен", nowMillis)
    lastErrorCategory == ProgressiveLoadErrorCategory.TEMPORARY_AVAILABILITY ->
        retryingText("Файл временно недоступен", nowMillis)
    activePath != null -> "Загружаем $activePath"
    completedFiles > 0 -> "Загружено $completedFiles из $totalFiles"
    else -> "Готовим книгу…"
}

private fun ProgressiveLoadSnapshot.retryingText(prefix: String, nowMillis: Long): String = retryAt?.let {
    "$prefix · повтор через ${((it - nowMillis).coerceAtLeast(0) + 999) / 1000} с"
} ?: "$prefix · повторим автоматически"

@Composable
fun ProgressiveLoadCard(
    snapshot: ProgressiveLoadSnapshot,
    nowMillis: Long,
    onPause: () -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val incomplete = snapshot.phase != ProgressiveLoadPhase.COMPLETE
    val status = snapshot.primaryText(nowMillis)
    val progressDescription = if (snapshot.totalFiles <= 0) status
        else "Загружено ${snapshot.completedFiles} из ${snapshot.totalFiles}"
    Card(
        modifier.testTag("progressive-load-card").semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = progressDescription
            stateDescription = status
        },
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(status, style = MaterialTheme.typography.bodyMedium)
            if (snapshot.totalFiles <= 0) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { snapshot.completedFiles.toFloat() / snapshot.totalFiles.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (incomplete) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    if (snapshot.phase == ProgressiveLoadPhase.PREPARING ||
                        snapshot.phase == ProgressiveLoadPhase.INITIAL ||
                        snapshot.phase == ProgressiveLoadPhase.BACKGROUND
                    ) {
                        TextButton(onClick = onPause) { Text("Приостановить") }
                    }
                    if (snapshot.lastErrorCategory != ProgressiveLoadErrorCategory.UNAUTHORIZED &&
                        (snapshot.phase == ProgressiveLoadPhase.PAUSED ||
                        snapshot.phase == ProgressiveLoadPhase.CANCELLED ||
                        snapshot.phase == ProgressiveLoadPhase.ACTION_REQUIRED)
                    ) {
                        TextButton(onClick = onContinue) { Text("Продолжить") }
                    }
                    if (snapshot.lastErrorCategory == ProgressiveLoadErrorCategory.UNAUTHORIZED) {
                        TextButton(onClick = onSignIn) { Text("Войти") }
                    }
                    if (!snapshot.cancelled) {
                        TextButton(onClick = onCancel) { Text("Отменить") }
                    }
                }
            }
        }
    }
}
