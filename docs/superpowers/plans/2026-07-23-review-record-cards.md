# Review Record Cards Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Заменить существующие карточки сигналов и правок на полноширинные карточки с цветным левым маркером, компактным текстом, переходом к anchor по тапу и меню редактирования/удаления по long-press.

**Architecture:** Визуальная и интерактивная логика остаётся в существующем `ReviewRecordCard` внутри `ReaderScreen.kt`. `ReaderScreen` поддерживает единственный активный `ReaderSearchTarget`: входной результат поиска и anchor карточки поступают в один и тот же путь `ReaderPane` → `targetBlockIndex` → `LazyListState.scrollToItem`, а режим layout определяет, закрывать ли панель после перехода.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Compose UI testing, JUnit 4, Android Gradle Plugin.

## Global Constraints

- Карточка занимает всю доступную ширину phone sheet, tablet portrait overlay и tablet landscape sidebar.
- Видимый тип записи обозначается только цветной полосой шириной `4.dp`; текстовая метка типа не добавляется.
- Исходный текст использует `bodySmall`, `onSurfaceVariant`, `maxLines = 2`, `TextOverflow.Ellipsis`.
- Основной текст использует `bodyMedium`, `maxLines = 4`, `TextOverflow.Ellipsis`; пустой комментарий сигнала не создаёт второй текстовый блок.
- Тип записи обязательно дублируется в click-semantics карточки.
- Обычный тап переиспользует `ReaderSearchTarget`; unresolved-запись без anchor сохраняет существующий отдельный reanchor-flow.
- После перехода phone sheet и tablet portrait overlay закрываются, tablet landscape sidebar остаётся открытым.
- Long-press открывает `DropdownMenu` с пунктами «Редактировать» и «Удалить» и соответствующими иконками.
- Удаление сохраняет существующий callback и undo-snackbar flow.
- Новые зависимости не добавляются.

---

## File Structure

- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt`
  - Хранит единый активный `ReaderSearchTarget`.
  - Преобразует `Anchor` карточки в цель перехода.
  - Рисует полноширинную карточку, цветной маркер и long-press menu.
- Modify: `app/src/main/res/values/strings.xml`
  - Добавляет русские подписи меню и semantics перехода/long-press.
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt`
  - Проверяет компоновку, усечение, semantics, callbacks меню и переход к anchor во всех трёх layout-режимах.

### Task 1: Полноширинная визуальная карточка

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt:720-861`
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt`

**Interfaces:**
- Consumes: `ReaderSignalItem`, `ReaderEditItem`, `LocalReviewColors`, `SignalType.labelResource`, `ReviewColors.signalColor`.
- Produces:
  - `ReviewRecordCard(recordId: String, sourceText: String, reviewText: String?, markerColor: Color, typeDescription: String, onNavigate: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit)`.
  - Stable test tags `review-record-card-<id>`, `review-record-marker-<id>`, `review-record-source-<id>`, `review-record-body-<id>`.

- [ ] **Step 1: Добавить failing instrumentation-тест компоновки и усечения**

Добавить импорты:

```kotlin
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import net.inkyquill.pocketeditor.reader.ReaderEditItem
import net.inkyquill.pocketeditor.reader.ReaderReviewItems
import net.inkyquill.pocketeditor.reader.ReaderSignalItem
```

Добавить тест в `ReviewInteractionTest`:

```kotlin
@Test
fun reviewCardsUseFullWidthMutedSourceAndBoundedBodyWithoutInlineActions() {
    val longSource = List(12) { "исходный фрагмент $it" }.joinToString(" ")
    val longComment = List(24) { "полный комментарий $it" }.joinToString(" ")
    compose.setContent {
        PocketEditorTheme(darkTheme = false) {
            ReaderScreen(
                state = reviewCardState(longSource, longComment),
                callbacks = ReaderCallbacks(),
                windowSize = DpSize(360.dp, 800.dp),
            )
        }
    }

    compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()

    val density = compose.activity.resources.displayMetrics.density
    val panel = compose.onNodeWithTag("review-sheet").fetchSemanticsNode().boundsInRoot
    val card = compose.onNodeWithTag("review-record-card-signal-card").fetchSemanticsNode().boundsInRoot
    assertTrue(
        "card must fill the panel content width; card=$card panel=$panel",
        kotlin.math.abs(card.width - (panel.width - 40.dp.value * density)) <= 2f,
    )

    val marker = compose.onNodeWithTag("review-record-marker-signal-card").fetchSemanticsNode().boundsInRoot
    assertTrue(kotlin.math.abs(marker.width - 4.dp.value * density) <= 1f)

    val sourceLayout = compose.onNodeWithTag("review-record-source-signal-card").textLayout()
    assertEquals(2, sourceLayout.lineCount)
    assertTrue(sourceLayout.isLineEllipsized(1))

    val bodyLayout = compose.onNodeWithTag("review-record-body-signal-card").textLayout()
    assertEquals(4, bodyLayout.lineCount)
    assertTrue(bodyLayout.isLineEllipsized(3))
    val sourceBounds = compose.onNodeWithTag("review-record-source-signal-card")
        .fetchSemanticsNode().boundsInRoot
    val bodyBounds = compose.onNodeWithTag("review-record-body-signal-card")
        .fetchSemanticsNode().boundsInRoot
    assertTrue("source must be rendered above the review body", sourceBounds.bottom <= bodyBounds.top)
    assertTrue(
        "source typography must be smaller than the review body",
        sourceLayout.layoutInput.style.fontSize.value < bodyLayout.layoutInput.style.fontSize.value,
    )
    compose.onNodeWithContentDescription("Изменить сигнал signal-card").assertDoesNotExist()
    compose.onNodeWithContentDescription("Удалить сигнал signal-card").assertDoesNotExist()
}
```

Добавить fixture рядом с `sampleState`:

```kotlin
private fun reviewCardState(source: String, comment: String) = multiBlockState().copy(
    reviewItems = ReaderReviewItems(
        signals = listOf(
            ReaderSignalItem(
                id = "signal-card",
                type = SignalType.WARNING,
                selectedText = source,
                comment = comment,
                anchor = reviewAnchor(startByte = 8_000, endByte = 8_020),
            ),
        ),
        edits = listOf(
            ReaderEditItem(
                id = "edit-card",
                before = source,
                after = comment,
                anchor = reviewAnchor(startByte = 8_100, endByte = 8_120),
            ),
        ),
    ),
)

private fun reviewAnchor(startByte: Long, endByte: Long) = Anchor(
    sourceSha256 = "source",
    selectionSha256 = "selection",
    startByte = startByte,
    endByte = endByte,
    startLine = 1,
    endLine = 1,
    prefix = "",
    suffix = "",
)

private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
    var results: List<TextLayoutResult> = emptyList()
    performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
        val captured = mutableListOf<TextLayoutResult>()
        check(action(captured))
        results = captured
    }
    return results.single()
}
```

- [ ] **Step 2: Запустить тест и подтвердить RED**

Run:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class='net.inkyquill.pocketeditor.ui.ReviewInteractionTest#reviewCardsUseFullWidthMutedSourceAndBoundedBodyWithoutInlineActions'
```

Expected: `FAIL`; отсутствует тег `review-record-card-signal-card`, а старая карточка содержит inline-кнопки.

- [ ] **Step 3: Реализовать минимальную визуальную карточку**

Добавить импорты в `ReaderScreen.kt`:

```kotlin
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import net.inkyquill.pocketeditor.ui.review.signalColor
import net.inkyquill.pocketeditor.ui.theme.LocalReviewColors
```

Обновить отображение записей в `ReviewShell`:

```kotlin
val reviewColors = LocalReviewColors.current
state.reviewItems?.signals?.forEach { signal ->
    ReviewRecordCard(
        recordId = signal.id,
        sourceText = signal.selectedText,
        reviewText = signal.comment.takeIf(String::isNotBlank),
        markerColor = reviewColors.signalColor(signal.type),
        typeDescription = stringResource(R.string.signal_description, stringResource(signal.type.labelResource)),
        onNavigate = {},
        onEdit = { callbacks.onEditSignal(signal) },
        onDelete = { callbacks.onDeleteSignal(signal.id) },
    )
}
state.reviewItems?.edits?.forEach { edit ->
    ReviewRecordCard(
        recordId = edit.id,
        sourceText = edit.before,
        reviewText = edit.after,
        markerColor = reviewColors.changeNeeded,
        typeDescription = stringResource(R.string.edit),
        onNavigate = {},
        onEdit = { callbacks.onEditEdit(edit) },
        onDelete = { callbacks.onDeleteEdit(edit.id) },
    )
}
```

Заменить старый `ReviewRecordCard`:

```kotlin
@Composable
private fun ReviewRecordCard(
    recordId: String,
    sourceText: String,
    reviewText: String?,
    markerColor: Color,
    typeDescription: String,
    onNavigate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("review-record-card-$recordId")
            .semantics { contentDescription = typeDescription },
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(4.dp)
                    .background(markerColor)
                    .testTag("review-record-marker-$recordId"),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f).padding(12.dp),
            ) {
                Text(
                    text = sourceText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("review-record-source-$recordId"),
                )
                reviewText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("review-record-body-$recordId"),
                    )
                }
            }
        }
    }
}
```

На этом шаге `onNavigate`, `onEdit` и `onDelete` намеренно ещё не подключены: Task 2 начинает с RED-теста взаимодействий и добавляет их одним изменением.

- [ ] **Step 4: Запустить тест и подтвердить GREEN**

Run:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class='net.inkyquill.pocketeditor.ui.ReviewInteractionTest#reviewCardsUseFullWidthMutedSourceAndBoundedBodyWithoutInlineActions'
```

Expected: `BUILD SUCCESSFUL`, тест `PASS`.

- [ ] **Step 5: Закоммитить визуальную карточку**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt \
  app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt
git commit -m "feat: redesign review record cards"
```

### Task 2: Long-press menu и единый переход к anchor

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt:158-243,720-861`
- Modify: `app/src/main/res/values/strings.xml:177-197`
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt`

**Interfaces:**
- Consumes: `ReviewRecordCard` и test tags из Task 1; входной `ReaderScreen.searchTarget`.
- Produces:
  - `activeSearchTarget: ReaderSearchTarget?`, общий для внешнего поиска и карточек.
  - `Anchor.toReaderSearchTarget(): ReaderSearchTarget?`.
  - `ReviewShell(..., onNavigateToReview: (ReaderSearchTarget) -> Unit)`.
  - Long-click menu, вызывающее существующие `onEditSignal`, `onEditEdit`, `onDeleteSignal`, `onDeleteEdit`.

- [ ] **Step 1: Добавить failing-тест меню и callbacks**

Добавить импорты:

```kotlin
import androidx.compose.ui.test.longClick
```

Добавить тест:

```kotlin
@Test
fun reviewCardLongPressOffersEditAndDeleteWithoutTriggeringNavigation() {
    var editedSignal: ReaderSignalItem? = null
    var deletes = 0
    compose.setContent {
        PocketEditorTheme(darkTheme = false) {
            ReaderScreen(
                state = reviewCardState("Привязанный текст", "Комментарий"),
                callbacks = ReaderCallbacks(
                    onEditSignal = { editedSignal = it },
                    onDeleteSignal = { deletes++ },
                ),
                windowSize = DpSize(360.dp, 800.dp),
            )
        }
    }
    compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()

    val card = compose.onNodeWithTag("review-record-card-signal-card")
    card.performTouchInput { longClick() }
    compose.onNodeWithText("Редактировать").assertIsDisplayed().performClick()
    compose.runOnIdle {
        assertEquals("Комментарий", editedSignal?.comment)
        assertEquals(0, deletes)
    }

    card.performTouchInput { longClick() }
    compose.onNodeWithText("Удалить").assertIsDisplayed().performClick()
    compose.runOnIdle {
        assertEquals("Комментарий", editedSignal?.comment)
        assertEquals(1, deletes)
    }
}
```

- [ ] **Step 2: Добавить failing-тест перехода и semantics**

```kotlin
@Test
fun reviewCardTapUsesReaderSearchTargetAndClosesOnlyOverlayPanels() {
    val cases = listOf(
        DpSize(360.dp, 800.dp) to "review-sheet",
        DpSize(800.dp, 1_280.dp) to "review-overlay",
        DpSize(1_280.dp, 800.dp) to "review-sidebar",
    )

    cases.forEach { (size, panelTag) ->
        compose.setContent {
            PocketEditorTheme(darkTheme = false) {
                ReaderScreen(
                    state = reviewCardState("Привязанный текст", "Комментарий"),
                    callbacks = ReaderCallbacks(),
                    windowSize = size,
                )
            }
        }
        if (size.width < 1_000.dp) {
            compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()
        }

        compose.onNodeWithTag("review-record-card-signal-card")
            .assertHasClickAction()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("Сигнал: Предупреждение"),
                ),
            )
            .performClick()

        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("reader-block-80", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        if (panelTag == "review-sidebar") {
            compose.onNodeWithTag(panelTag).assertIsDisplayed()
        } else {
            compose.onAllNodesWithTag(panelTag).assertCountEquals(0)
        }
    }
}
```

- [ ] **Step 3: Добавить failing-тест на no-op тапа без anchor**

Спека требует: «если anchor отсутствует, тап ничего не меняет; перепривязка выполняется существующим отдельным действием для unresolved-записи». `ReaderSignalItem.anchor` и `ReaderEditItem.anchor` нулабельны в проде (unresolved-записи), поэтому этот путь нужно покрыть отдельно от happy-path перехода.

Добавить fixture рядом с `reviewCardState`:

```kotlin
private fun reviewCardStateWithoutAnchor(source: String, comment: String) = multiBlockState().copy(
    reviewItems = ReaderReviewItems(
        signals = listOf(
            ReaderSignalItem(
                id = "signal-card",
                type = SignalType.WARNING,
                selectedText = source,
                comment = comment,
                anchor = null,
            ),
        ),
        edits = emptyList(),
    ),
)
```

Добавить тест:

```kotlin
@Test
fun reviewCardTapWithoutAnchorIsANoOpAndKeepsThePanelOpen() {
    compose.setContent {
        PocketEditorTheme(darkTheme = false) {
            ReaderScreen(
                state = reviewCardStateWithoutAnchor("Привязанный текст", "Комментарий"),
                callbacks = ReaderCallbacks(),
                windowSize = DpSize(360.dp, 800.dp),
            )
        }
    }
    compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()

    compose.onNodeWithTag("review-record-card-signal-card").performClick()

    compose.onNodeWithTag("review-sheet").assertIsDisplayed()
    compose.onAllNodesWithTag("reader-block-80", useUnmergedTree = true).assertCountEquals(0)
}
```

- [ ] **Step 4: Запустить все три теста и подтвердить RED**

Run:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class='net.inkyquill.pocketeditor.ui.ReviewInteractionTest#reviewCardLongPressOffersEditAndDeleteWithoutTriggeringNavigation,net.inkyquill.pocketeditor.ui.ReviewInteractionTest#reviewCardTapUsesReaderSearchTargetAndClosesOnlyOverlayPanels,net.inkyquill.pocketeditor.ui.ReviewInteractionTest#reviewCardTapWithoutAnchorIsANoOpAndKeepsThePanelOpen'
```

Expected: `FAIL`; у карточки ещё нет combined click/long-click, меню и перехода.

- [ ] **Step 5: Добавить русские ресурсы**

Добавить в `strings.xml`:

```xml
<string name="edit_review_record">Редактировать</string>
<string name="open_review_record">Перейти к месту рецензии</string>
<string name="review_record_actions">Открыть действия с рецензией</string>
```

- [ ] **Step 6: Подключить общий activeSearchTarget и закрытие overlay**

Добавить import:

```kotlin
import net.inkyquill.pocketeditor.review.Anchor
```

В `ReaderScreen`, рядом с состоянием панелей, создать одно состояние цели и синхронизировать его с публичным параметром:

```kotlin
var activeSearchTarget by remember(state.bookId, state.chapterId) {
    mutableStateOf(searchTarget)
}
LaunchedEffect(searchTarget) {
    activeSearchTarget = searchTarget
}
```

Передать callback в `ReviewShell`:

```kotlin
review = { closeLabel, onClose ->
    ReviewShell(
        state = state,
        reviewUiState = reviewUiState,
        closeLabel = closeLabel,
        onClose = onClose,
        callbacks = callbacks,
        onNavigateToReview = { target ->
            activeSearchTarget = target
            if (policy.mode != ReaderLayoutMode.TABLET_LANDSCAPE) {
                reviewExpanded = false
            }
        },
    )
}
```

Передать в существующий `ReaderPane` общий target:

```kotlin
searchTarget = activeSearchTarget,
```

Расширить `ReviewShell`:

```kotlin
private fun ReviewShell(
    state: ReaderState,
    reviewUiState: ReviewUiState,
    closeLabel: String,
    onClose: () -> Unit,
    callbacks: ReaderCallbacks,
    onNavigateToReview: (ReaderSearchTarget) -> Unit,
)
```

Добавить безопасное преобразование anchor:

```kotlin
private fun Anchor.toReaderSearchTarget(): ReaderSearchTarget? {
    if (startByte !in 0..Int.MAX_VALUE.toLong()) return null
    if (endByte !in startByte..Int.MAX_VALUE.toLong()) return null
    return ReaderSearchTarget(startByte.toInt(), endByte.toInt())
}
```

В обоих вызовах `ReviewRecordCard` заменить `onNavigate = {}`:

```kotlin
onNavigate = {
    signal.anchor?.toReaderSearchTarget()?.let(onNavigateToReview)
},
```

и:

```kotlin
onNavigate = {
    edit.anchor?.toReaderSearchTarget()?.let(onNavigateToReview)
},
```

- [ ] **Step 7: Реализовать combined click и DropdownMenu**

Добавить импорты:

```kotlin
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
```

Обернуть `Surface` в `Box`, добавить локальное состояние menu и modifier карточки:

```kotlin
var menuExpanded by remember { mutableStateOf(false) }
Box(Modifier.fillMaxWidth()) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("review-record-card-$recordId")
            .semantics { contentDescription = typeDescription }
            .combinedClickable(
                onClickLabel = stringResource(R.string.open_review_record),
                onClick = onNavigate,
                onLongClickLabel = stringResource(R.string.review_record_actions),
                onLongClick = { menuExpanded = true },
            ),
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(4.dp)
                    .background(markerColor)
                    .testTag("review-record-marker-$recordId"),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f).padding(12.dp),
            ) {
                Text(
                    text = sourceText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("review-record-source-$recordId"),
                )
                reviewText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("review-record-body-$recordId"),
                    )
                }
            }
        }
    }
    DropdownMenu(
        expanded = menuExpanded,
        onDismissRequest = { menuExpanded = false },
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.edit_review_record)) },
            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
            onClick = {
                menuExpanded = false
                onEdit()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.delete)) },
            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
            onClick = {
                menuExpanded = false
                onDelete()
            },
        )
    }
}
```

- [ ] **Step 8: Запустить тесты взаимодействий и подтвердить GREEN**

Run:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class='net.inkyquill.pocketeditor.ui.ReviewInteractionTest#reviewCardLongPressOffersEditAndDeleteWithoutTriggeringNavigation,net.inkyquill.pocketeditor.ui.ReviewInteractionTest#reviewCardTapUsesReaderSearchTargetAndClosesOnlyOverlayPanels,net.inkyquill.pocketeditor.ui.ReviewInteractionTest#reviewCardTapWithoutAnchorIsANoOpAndKeepsThePanelOpen'
```

Expected: `BUILD SUCCESSFUL`, все три теста `PASS`.

- [ ] **Step 9: Закоммитить взаимодействия**

```bash
git add app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt \
  app/src/main/res/values/strings.xml \
  app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt
git commit -m "feat: add review card navigation and actions menu"
```

### Task 3: Адаптивная регрессия и полная валидация

**Files:**
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt`

**Interfaces:**
- Consumes: production UI и fixtures из Tasks 1–2.
- Produces: единый adaptive regression-test для phone, tablet portrait и tablet landscape.

- [ ] **Step 1: Добавить адаптивный тест ширины и пустого комментария**

```kotlin
@Test
fun reviewCardsFillEveryPanelModeAndOmitBlankSignalBody() {
    val sizes = listOf(
        DpSize(360.dp, 800.dp) to "review-sheet",
        DpSize(800.dp, 1_280.dp) to "review-overlay",
        DpSize(1_280.dp, 800.dp) to "review-sidebar",
    )

    sizes.forEach { (size, panelTag) ->
        compose.setContent {
            PocketEditorTheme(darkTheme = true) {
                ReaderScreen(
                    state = reviewCardState("Короткий исходный текст", ""),
                    callbacks = ReaderCallbacks(),
                    windowSize = size,
                )
            }
        }
        if (size.width < 1_000.dp) {
            compose.onNodeWithContentDescription("Открыть панель рецензии").performClick()
        }

        val density = compose.activity.resources.displayMetrics.density
        val panel = compose.onNodeWithTag(panelTag).fetchSemanticsNode().boundsInRoot
        val signalCard = compose.onNodeWithTag("review-record-card-signal-card")
            .fetchSemanticsNode().boundsInRoot
        val editCard = compose.onNodeWithTag("review-record-card-edit-card")
            .fetchSemanticsNode().boundsInRoot
        val expectedWidth = panel.width - 40.dp.value * density

        assertTrue(kotlin.math.abs(signalCard.width - expectedWidth) <= 2f)
        assertTrue(kotlin.math.abs(editCard.width - expectedWidth) <= 2f)
        compose.onNodeWithTag("review-record-body-signal-card").assertDoesNotExist()
        compose.onNodeWithTag("review-record-body-edit-card").assertIsDisplayed()
        compose.onNodeWithTag("review-record-card-edit-card")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("Правка"),
                ),
            )
    }
}
```

- [ ] **Step 2: Запустить новый adaptive-тест**

Run:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class='net.inkyquill.pocketeditor.ui.ReviewInteractionTest#reviewCardsFillEveryPanelModeAndOmitBlankSignalBody'
```

Expected: `BUILD SUCCESSFUL`, тест `PASS`.

- [ ] **Step 3: Запустить весь ReviewInteractionTest**

Run:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class='net.inkyquill.pocketeditor.ui.ReviewInteractionTest'
```

Expected: `BUILD SUCCESSFUL`, все тесты класса `PASS`, без новых skipped-тестов.

- [ ] **Step 4: Запустить JVM-тесты и lint**

Run:

```bash
./gradlew testDebugUnitTest lintDebug
```

Expected: `BUILD SUCCESSFUL`; zero test failures и zero lint errors.

- [ ] **Step 5: Запустить полный instrumentation-набор**

Run:

```bash
./gradlew connectedDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`; zero failures. Разрешены только существующие fixture/screenshot skips.

- [ ] **Step 6: Проверить diff и отсутствие случайных файлов**

Run:

```bash
git diff --check
git status --short
```

Expected: `git diff --check` не выводит ошибок; в изменениях текущей задачи только три заявленных файла, пользовательские untracked `.agents/`, `.claude/`, `icon.png` и документы от 2026-07-21 не индексируются.

- [ ] **Step 7: Закоммитить adaptive regression-тест**

```bash
git add app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt
git commit -m "test: cover adaptive review cards"
```
