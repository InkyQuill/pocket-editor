package net.inkyquill.pocketeditor.ui.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.inkyquill.pocketeditor.load.ProgressiveLoadErrorCategory
import net.inkyquill.pocketeditor.load.ProgressiveLoadPhase
import net.inkyquill.pocketeditor.load.ProgressiveLoadSnapshot

fun ProgressiveLoadSnapshot.primaryText(nowMillis: Long): String = when {
    phase == ProgressiveLoadPhase.COMPLETE -> "Книга доступна без сети"
    lastErrorCategory == ProgressiveLoadErrorCategory.UNAUTHORIZED -> "Нужно войти в Яндекс Диск"
    lastErrorCategory == ProgressiveLoadErrorCategory.INVALID_REMOTE -> "Требуется действие"
    lastErrorCategory == ProgressiveLoadErrorCategory.OFFLINE -> "Нет сети · продолжим автоматически"
    lastErrorCategory == ProgressiveLoadErrorCategory.RATE_LIMITED && retryAt != null ->
        "Лимит Яндекс Диска · повтор через ${((retryAt - nowMillis).coerceAtLeast(0) + 999) / 1000} с"
    activePath != null -> "Загружаем $activePath"
    completedFiles > 0 -> "Загружено $completedFiles из $totalFiles"
    else -> "Готовим книгу…"
}

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
    val progressDescription = "Загружено ${snapshot.completedFiles} из ${snapshot.totalFiles}"
    Card(modifier) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(snapshot.primaryText(nowMillis), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(
                progress = {
                    if (snapshot.totalFiles <= 0) 0f
                    else snapshot.completedFiles.toFloat() / snapshot.totalFiles.toFloat()
                },
                modifier = Modifier.fillMaxWidth().semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = progressDescription
                },
            )
            if (incomplete) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (snapshot.phase == ProgressiveLoadPhase.INITIAL || snapshot.phase == ProgressiveLoadPhase.BACKGROUND) {
                        TextButton(onClick = onPause) { Text("Приостановить") }
                    }
                    if (snapshot.phase == ProgressiveLoadPhase.PAUSED ||
                        snapshot.phase == ProgressiveLoadPhase.CANCELLED ||
                        snapshot.phase == ProgressiveLoadPhase.ACTION_REQUIRED
                    ) {
                        TextButton(onClick = onContinue) { Text("Продолжить") }
                    }
                    if (!snapshot.cancelled) {
                        TextButton(onClick = onCancel) { Text("Отменить") }
                    }
                    if (snapshot.lastErrorCategory == ProgressiveLoadErrorCategory.UNAUTHORIZED) {
                        TextButton(onClick = onSignIn) { Text("Войти") }
                    }
                }
            }
        }
    }
}
