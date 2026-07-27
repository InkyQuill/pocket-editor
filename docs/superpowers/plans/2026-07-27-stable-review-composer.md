# Stable Review Composer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Исправить issue #4 и #5: сделать контейнер review composer детерминированным для телефона/планшета и исключить потерю символов и скачки cursor при отстающем controller state.

**Architecture:** `ReaderScreen` выбирает контейнер только по физическому классу устройства и больше не хранит anchor редактора. `InlineAnnotationComposer` владеет saveable `TextFieldValue`, вычисляет validation/dirty от локального текста и передаёт в существующий controller только строки. Material 3 sheet и full-screen dialog применяют IME/safe insets на одном уровне; все неявные dismiss-события сходятся в единый локальный confirmation flow.

**Tech Stack:** Kotlin 2.3.10, Jetpack Compose BOM 2026.06.00, Material 3, Android SDK 36, JUnit 5 unit tests, AndroidX Compose UI instrumentation tests.

## Global Constraints

- Телефон определяется как `smallestScreenWidthDp < 600`, планшет — как `smallestScreenWidthDp >= 600`; текущая ширина окна не меняет физический класс.
- `SelectionFlyout`, `ReviewDraft`, `ReviewDraftStore`, база данных и FIFO-channel `EditorialReviewCallbacks` не меняются.
- На телефоне composer всегда `ModalBottomSheet`; на планшете всегда центрированный `Dialog`.
- Cursor selection и IME composition остаются только в локальном `TextFieldValue` и не сохраняются.
- Выбранный `SignalType` обновляется локально для мгновенного chip/dirty feedback, но сохраняется прежним controller callback.
- `ModalBottomSheet` сам обрабатывает safe/navigation insets; его content добавляет только IME padding.
- Full-screen tablet dialog применяет safe drawing и IME padding ровно один раз.
- Пустой новый draft закрывается неявным dismiss без подтверждения; dirty draft показывает подтверждение.
- Явная кнопка «Отмена» закрывает draft без дополнительного подтверждения.
- Новые runtime dependencies, миграция базы и обновление screenshot-эталонов не требуются.

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/net/inkyquill/pocketeditor/ui/review/InlineAnnotationComposer.kt` | Локальный `TextFieldValue`, identity, validation/dirty, form-factor containers, insets, focus и dismiss flow |
| `app/src/main/java/net/inkyquill/pocketeditor/ui/review/SignalComposer.kt` | Signal form, цитата выделения, controlled `TextFieldValue`, адаптивные actions |
| `app/src/main/java/net/inkyquill/pocketeditor/ui/review/EditComposer.kt` | Edit form, bounded «До», controlled `TextFieldValue`, локальная validation, адаптивные actions |
| `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt` | Выбор PhoneSheet/TabletModal и удаление anchor-размещения composer |
| `app/src/main/AndroidManifest.xml` | `adjustResize` compatibility для `MainActivity` |
| `app/src/main/res/values/strings.xml` | Copy подтверждения отмены dirty draft |
| `app/src/test/java/net/inkyquill/pocketeditor/ui/review/DraftTextFieldStateTest.kt` | Быстрые unit-регрессии cursor/composition и фильтрации persistence callback |
| `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt` | End-to-end Compose-регрессии ввода, контейнеров, insets, действий и dismiss |
| `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/AdaptiveReaderTest.kt` | Удаление тестов устаревшего `annotationPlacement`; сохранение flyout geometry tests |

---

### Task 1: Локальное состояние ввода и validation

**Files:**
- Create: `app/src/test/java/net/inkyquill/pocketeditor/ui/review/DraftTextFieldStateTest.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/InlineAnnotationComposer.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/SignalComposer.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/EditComposer.kt`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt`

**Interfaces:**
- Produces: `internal fun applyDraftTextFieldChange(current: TextFieldValue, next: TextFieldValue, onTextChanged: (String) -> Unit): TextFieldValue`
- Produces: параметры `value: TextFieldValue`, `onCommentChange: (TextFieldValue) -> Unit`, `stackedActions: Boolean` у `SignalComposer`
- Produces: параметры `value: TextFieldValue`, `onAfterChange: (TextFieldValue) -> Unit`, `stackedActions: Boolean` у `EditComposer`
- Preserves: `ReaderCallbacks.onDraftTextChanged: (String) -> Unit`

- [ ] **Step 1: Написать unit-регрессии полного `TextFieldValue`**

Создать `DraftTextFieldStateTest.kt`:

```kotlin
package net.inkyquill.pocketeditor.ui.review

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DraftTextFieldStateTest {
    @Test
    fun `selection and composition changes stay local without persistence writes`() {
        val current = TextFieldValue("quiet", selection = TextRange(5))
        val next = TextFieldValue(
            text = "quiet",
            selection = TextRange(1, 4),
            composition = TextRange(0, 5),
        )
        val writes = mutableListOf<String>()

        val result = applyDraftTextFieldChange(current, next, writes::add)

        assertEquals(next, result)
        assertEquals(TextRange(1, 4), result.selection)
        assertEquals(TextRange(0, 5), result.composition)
        assertEquals(emptyList<String>(), writes)
    }

    @Test
    fun `text changes publish the new string once and retain the ime state`() {
        val current = TextFieldValue("quiet", selection = TextRange(5))
        val next = TextFieldValue(
            text = "quiXet",
            selection = TextRange(4),
            composition = TextRange(0, 6),
        )
        val writes = mutableListOf<String>()

        val result = applyDraftTextFieldChange(current, next, writes::add)

        assertEquals(next, result)
        assertEquals(listOf("quiXet"), writes)
    }
}
```

- [ ] **Step 2: Запустить unit-тест и подтвердить красную фазу**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'net.inkyquill.pocketeditor.ui.review.DraftTextFieldStateTest'
```

Expected: `FAIL`; `applyDraftTextFieldChange` ещё не существует.

- [ ] **Step 3: Добавить instrumentation-регрессии delayed parent state**

В `ReviewInteractionTest.kt` импортировать:

```kotlin
import androidx.compose.ui.test.assertIsEnabled
import net.inkyquill.pocketeditor.ui.review.AnnotationComposerPlacement
import net.inkyquill.pocketeditor.ui.review.InlineAnnotationComposer
```

Добавить тесты:

```kotlin
@Test
fun reviewInputKeepsRapidCharactersAndCursorWhileParentStateLags() {
    val writes = mutableListOf<String>()
    val selection = ReviewSelection(0, 0, 5, RawRange(0, 5), "quiet")
    val draft = ReviewDraft.Signal(
        recordId = "signal-1",
        selection = selection,
        type = SignalType.NOTE,
        comment = "quiet",
        savedType = SignalType.NOTE,
        savedComment = "quiet",
    )
    compose.setContent {
        PocketEditorTheme(darkTheme = true) {
            InlineAnnotationComposer(
                session = ReviewDraftSession(draft),
                callbacks = ReaderCallbacks(onDraftTextChanged = writes::add),
                placement = AnnotationComposerPlacement.TabletModal,
            )
        }
    }

    val input = compose.onNodeWithTag("inline-annotation-input")
    input.performSemanticsAction(SemanticsActions.SetSelection) { it(3, 3, false) }
    compose.runOnIdle { assertEquals(emptyList<String>(), writes) }
    input.performTextInput("X")
    input.performTextInput("Y")

    input.assertTextContains("quiXYet")
    compose.runOnIdle {
        assertEquals(listOf("quiXet", "quiXYet"), writes)
    }
}

@Test
fun editValidationUsesLocalTextWhileParentStateLags() {
    val writes = mutableListOf<String>()
    val selection = ReviewSelection(0, 0, 5, RawRange(0, 5), "quiet")
    val draft = ReviewDraft.Edit(
        recordId = null,
        selection = selection,
        after = "quiet",
    )
    compose.setContent {
        PocketEditorTheme(darkTheme = true) {
            InlineAnnotationComposer(
                session = ReviewDraftSession(draft),
                callbacks = ReaderCallbacks(onDraftTextChanged = writes::add),
                placement = AnnotationComposerPlacement.TabletModal,
            )
        }
    }

    compose.onNodeWithTag("inline-annotation-input").performTextInput(" ending")

    compose.onNodeWithTag("save-draft").assertIsEnabled()
    compose.onNodeWithTag("inline-annotation-input").assertTextContains("quiet ending")
    compose.runOnIdle { assertEquals(listOf("quiet ending"), writes) }
}

@Test
fun savedSignalTypeUpdatesImmediatelyWhileParentStateLags() {
    val typeWrites = mutableListOf<SignalType>()
    val selection = ReviewSelection(0, 0, 5, RawRange(0, 5), "quiet")
    val draft = ReviewDraft.Signal(
        recordId = "signal-1",
        selection = selection,
        type = SignalType.NOTE,
        comment = "",
        savedType = SignalType.NOTE,
        savedComment = "",
    )
    compose.setContent {
        PocketEditorTheme(darkTheme = true) {
            InlineAnnotationComposer(
                session = ReviewDraftSession(draft),
                callbacks = ReaderCallbacks(onSignalTypeChanged = typeWrites::add),
                placement = AnnotationComposerPlacement.TabletModal,
            )
        }
    }

    compose.onNodeWithTag("signal-warning").performClick()

    compose.onNodeWithTag("signal-warning").assertIsSelected()
    compose.runOnIdle { assertEquals(listOf(SignalType.WARNING), typeWrites) }
}
```

- [ ] **Step 4: Запустить instrumentation-тесты и подтвердить красную фазу**

Run:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class='net.inkyquill.pocketeditor.ui.ReviewInteractionTest#reviewInputKeepsRapidCharactersAndCursorWhileParentStateLags,net.inkyquill.pocketeditor.ui.ReviewInteractionTest#editValidationUsesLocalTextWhileParentStateLags,net.inkyquill.pocketeditor.ui.ReviewInteractionTest#savedSignalTypeUpdatesImmediatelyWhileParentStateLags'
```

Expected: `FAIL`; строковый controlled field откатывается к `draft.comment`/`draft.after`, validation остаётся `Unchanged`, выбранный chip возвращается к старому типу.

- [ ] **Step 5: Реализовать reducer и локальное saveable state**

В `InlineAnnotationComposer.kt` добавить imports:

```kotlin
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
```

Добавить рядом с enum:

```kotlin
private data class DraftInputIdentity(
    val kind: String,
    val recordId: String?,
    val rawStartByte: Int,
    val rawEndByte: Int,
)

internal fun applyDraftTextFieldChange(
    current: TextFieldValue,
    next: TextFieldValue,
    onTextChanged: (String) -> Unit,
): TextFieldValue {
    if (next.text != current.text) onTextChanged(next.text)
    return next
}

private val ReviewDraft.inputIdentity: DraftInputIdentity
    get() = DraftInputIdentity(
        kind = when (this) {
            is ReviewDraft.Signal -> "signal"
            is ReviewDraft.Edit -> "edit"
        },
        recordId = recordId,
        rawStartByte = selection.rawRange.startByte,
        rawEndByte = selection.rawRange.endByte,
    )

private val ReviewDraft.inputText: String
    get() = when (this) {
        is ReviewDraft.Signal -> comment
        is ReviewDraft.Edit -> after
    }

private fun ReviewDraftSession.withInput(
    text: String,
    signalType: SignalType?,
): ReviewDraftSession = copy(
    draft = when (val value = requireNotNull(draft)) {
        is ReviewDraft.Signal -> value.copy(
            type = requireNotNull(signalType),
            comment = text,
        )
        is ReviewDraft.Edit -> value.copy(after = text)
    },
)
```

В начале `InlineAnnotationComposer`, сразу после `val draft`, создать единое состояние:

```kotlin
val identity = draft.inputIdentity
val focusRequester = remember(identity) { FocusRequester() }
var inputValue by rememberSaveable(
    identity,
    stateSaver = TextFieldValue.Saver,
) {
    mutableStateOf(
        TextFieldValue(
            text = draft.inputText,
            selection = TextRange(draft.inputText.length),
        ),
    )
}
var localSignalType by rememberSaveable(identity) {
    mutableStateOf((draft as? ReviewDraft.Signal)?.type)
}
val localSession = session.withInput(
    text = inputValue.text,
    signalType = localSignalType,
)
val localValidation = ReviewDraftStateMachine.validate(localSession)
val onInputChange: (TextFieldValue) -> Unit = { next ->
    inputValue = applyDraftTextFieldChange(
        current = inputValue,
        next = next,
        onTextChanged = callbacks.onDraftTextChanged,
    )
}
val onSignalTypeChange: (SignalType) -> Unit = { type ->
    localSignalType = type
    callbacks.onSignalTypeChanged(type)
}
LaunchedEffect(identity) { focusRequester.requestFocus() }
```

Передавать `inputValue`, `onInputChange` и `localValidation` в формы. На этом шаге оставить `stackedActions = placement == AnnotationComposerPlacement.PhoneSheet`; изменение layout действий выполняется в Task 3.

В `SignalComposer.kt` и `EditComposer.kt` заменить строковые параметры:

```kotlin
import androidx.compose.ui.text.input.TextFieldValue

value: TextFieldValue,
onCommentChange: (TextFieldValue) -> Unit,
stackedActions: Boolean,
```

и:

```kotlin
import androidx.compose.ui.text.input.TextFieldValue

value: TextFieldValue,
onAfterChange: (TextFieldValue) -> Unit,
stackedActions: Boolean,
```

Оба `OutlinedTextField` получают `value = value`. В существующей `content` lambda
передавать локализованный Signal и callbacks:

```kotlin
is ReviewDraft.Signal -> SignalComposer(
    draft = requireNotNull(localSession.draft as? ReviewDraft.Signal),
    value = inputValue,
    onTypeChange = onSignalTypeChange,
    onCommentChange = onInputChange,
    onSave = callbacks.onSaveDraft,
    onCancel = callbacks.onCancelDraft,
    stackedActions = placement == AnnotationComposerPlacement.PhoneSheet,
    inputModifier = Modifier
        .focusRequester(focusRequester)
        .testTag("inline-annotation-input"),
)
is ReviewDraft.Edit -> EditComposer(
    draft = draft,
    value = inputValue,
    validation = localValidation,
    onAfterChange = onInputChange,
    onSave = callbacks.onSaveDraft,
    onCancel = callbacks.onCancelDraft,
    stackedActions = placement == AnnotationComposerPlacement.PhoneSheet,
    inputModifier = Modifier
        .focusRequester(focusRequester)
        .testTag("inline-annotation-input"),
)
```

Task 1 может оставить `stackedActions` без layout-ветвления; окончательный
phone/tablet action layout появляется в Task 3.

- [ ] **Step 6: Запустить red-green набор**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'net.inkyquill.pocketeditor.ui.review.DraftTextFieldStateTest'
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class='net.inkyquill.pocketeditor.ui.ReviewInteractionTest#reviewInputKeepsRapidCharactersAndCursorWhileParentStateLags,net.inkyquill.pocketeditor.ui.ReviewInteractionTest#editValidationUsesLocalTextWhileParentStateLags,net.inkyquill.pocketeditor.ui.ReviewInteractionTest#savedSignalTypeUpdatesImmediatelyWhileParentStateLags'
```

Expected: оба запуска `BUILD SUCCESSFUL`; selection-only не пишет в persistence callback, cursor-вставка даёт `quiXYet`, локальная Edit validation становится `Valid`, выбранный Signal chip не откатывается.

- [ ] **Step 7: Запустить все unit-тесты**

Run:

```bash
./gradlew :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, 0 failed tests.

- [ ] **Step 8: Commit**

```bash
git add \
  app/src/main/java/net/inkyquill/pocketeditor/ui/review/InlineAnnotationComposer.kt \
  app/src/main/java/net/inkyquill/pocketeditor/ui/review/SignalComposer.kt \
  app/src/main/java/net/inkyquill/pocketeditor/ui/review/EditComposer.kt \
  app/src/test/java/net/inkyquill/pocketeditor/ui/review/DraftTextFieldStateTest.kt \
  app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt
git commit -m "fix: keep review input state local"
```

---

### Task 2: Детерминированный container по физическому классу

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/InlineAnnotationComposer.kt`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/AdaptiveReaderTest.kt`

**Interfaces:**
- Produces: `enum class AnnotationComposerPlacement { PhoneSheet, TabletModal }`
- Preserves: `internal fun flyoutPlacementIsBelow(selection: Rect, viewport: Rect, flyoutHeightPx: Float, gapPx: Float, reservedAbovePx: Float): Boolean`
- Preserves: `internal fun anchoredHorizontalOffsetInRoot(anchor: Rect, viewport: Rect, contentWidthPx: Float, marginPx: Float = 0f): Int`
- Removes: `EphemeralDraftAnchor`, `annotationPlacement`, composer size/offset state и параметр `modifier` у `InlineAnnotationComposer`

- [ ] **Step 1: Переписать phone/tablet expectations до реализации**

В `ReviewInteractionTest.kt` заменить
`selectedTextComposerStaysInlineAndReviewOverviewHasNoActiveComposer` на
`phoneSelectionAlwaysUsesBottomSheetEvenWhenAnchorHasRoom`:

```kotlin
@Test
fun phoneSelectionAlwaysUsesBottomSheetEvenWhenAnchorHasRoom() {
    val reviewUi = mutableStateOf(ReviewUiState())
    val phone = Configuration(compose.activity.resources.configuration).apply {
        smallestScreenWidthDp = 360
    }
    compose.setContent {
        CompositionLocalProvider(LocalConfiguration provides phone) {
            PocketEditorTheme(darkTheme = true) {
                Box(Modifier.requiredSize(360.dp, 800.dp)) {
                    ReaderScreen(
                        sampleState(false).copy(reviewEnabled = true),
                        selectionCallbacks(reviewUi),
                        reviewUi.value,
                        windowSize = DpSize(360.dp, 800.dp),
                    )
                }
            }
        }
    }

    compose.onNodeWithTag("reader-text-0", useUnmergedTree = true)
        .performSemanticsAction(SemanticsActions.SetSelection) { it(0, 5, false) }
    compose.onNodeWithContentDescription("Добавить заметку").performClick()

    compose.onNodeWithTag("inline-annotation-phone-sheet").assertIsDisplayed()
    compose.onAllNodesWithTag("inline-annotation-modal").assertCountEquals(0)
}
```

Добавить tablet regression с просторным viewport:

```kotlin
@Test
fun physicalTabletAlwaysUsesModalEvenWhenAnchorHasRoom() {
    val reviewUi = mutableStateOf(ReviewUiState())
    val tablet = Configuration(compose.activity.resources.configuration).apply {
        smallestScreenWidthDp = 800
    }
    compose.setContent {
        CompositionLocalProvider(LocalConfiguration provides tablet) {
            PocketEditorTheme(darkTheme = true) {
                Box(Modifier.requiredSize(800.dp, 1_280.dp)) {
                    ReaderScreen(
                        sampleState(false).copy(reviewEnabled = true),
                        selectionCallbacks(reviewUi),
                        reviewUi.value,
                        windowSize = DpSize(800.dp, 1_280.dp),
                    )
                }
            }
        }
    }

    compose.onNodeWithTag("reader-text-0", useUnmergedTree = true)
        .performSemanticsAction(SemanticsActions.SetSelection) { it(0, 5, false) }
    compose.onNodeWithContentDescription("Добавить заметку").performClick()

    compose.onNodeWithTag("inline-annotation-modal").assertIsDisplayed()
    compose.onAllNodesWithTag("inline-annotation-phone-sheet").assertCountEquals(0)
}
```

Оставить `crampedPhoneSelectionUsesModalBottomSheetComposer` и
`physicalTabletInNarrowSplitScreenUsesCenteredCompactModal`: они покрывают тесные окна.

- [ ] **Step 2: Подтвердить красную фазу**

Run:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class='net.inkyquill.pocketeditor.ui.ReviewInteractionTest#phoneSelectionAlwaysUsesBottomSheetEvenWhenAnchorHasRoom,net.inkyquill.pocketeditor.ui.ReviewInteractionTest#physicalTabletAlwaysUsesModalEvenWhenAnchorHasRoom'
```

Expected: `FAIL`; текущий `annotationPlacement` выбирает `Below` или `Above`.

- [ ] **Step 3: Удалить устаревшие geometry tests composer**

В `ReviewInteractionTest.kt` удалить:

```text
landscapeContentsSidebarClampsRenderedSelectionFlyoutAndBelowComposerInRootSpace
landscapeContentsSidebarClampsRenderedAboveComposerInRootSpace
```

Сохранить отдельные тесты `SelectionFlyout`.

В `AdaptiveReaderTest.kt`:

- удалить import `annotationPlacement`;
- удалить `annotationPlacementReservesGapAndFlipsAboveBeforeDeviceFallback`;
- переименовать `centeredTabletBelowAndAboveComposersClampInReaderRootSpace` в
  `anchoredHorizontalOffsetClampsFlyoutInReaderRootSpace`;
- удалить из него четыре assertions `AnnotationComposerPlacement.Below/Above`;
- сохранить оба цикла `anchoredHorizontalOffsetInRoot`;
- не удалять imports/functions `flyoutPlacementIsBelow` и `anchoredHorizontalOffsetInRoot`.

- [ ] **Step 4: Упростить `ReaderScreen`**

В `ReaderScreen.kt`:

1. Удалить `EphemeralDraftAnchor`.
2. Удалить `tabletFallback`; передавать в `ReaderPane` именно `tabletDevice`.
3. Удалить `draftAnchor`, `composerHeightPx`, `composerWidthPx`,
   `composerEdgeMarginPx` и связанный `LaunchedEffect`.
4. В callbacks `SelectionFlyout` оставить только `callbacks.onSignalChosen(type)` и
   `callbacks.onEditChosen()`.
5. Заменить весь anchored composer block на:

```kotlin
val activeDraft = reviewDraftSession.draft
if (activeDraft != null) {
    InlineAnnotationComposer(
        session = reviewDraftSession,
        callbacks = callbacks,
        placement = if (tabletDevice) {
            AnnotationComposerPlacement.TabletModal
        } else {
            AnnotationComposerPlacement.PhoneSheet
        },
    )
}
```

6. Удалить `annotationPlacement`.
7. Оставить без изменения:

```kotlin
internal fun flyoutPlacementIsBelow(
    selection: Rect,
    viewport: Rect,
    flyoutHeightPx: Float,
    gapPx: Float,
    reservedAbovePx: Float,
): Boolean = when {
    viewport.bottom - selection.bottom >= flyoutHeightPx + gapPx -> true
    selection.top - viewport.top >= flyoutHeightPx + gapPx + reservedAbovePx -> false
    else -> true
}

internal fun anchoredHorizontalOffsetInRoot(
    anchor: Rect,
    viewport: Rect,
    contentWidthPx: Float,
    marginPx: Float = 0f,
): Int = viewport.left.toInt() +
    anchoredHorizontalOffset(anchor, viewport, contentWidthPx, marginPx)
```

Удалить ставшие неиспользуемыми imports `ReviewDraft`, `ReviewSelection`,
`onSizeChanged` и `Class`.

- [ ] **Step 5: Упростить API `InlineAnnotationComposer`**

В `InlineAnnotationComposer.kt`:

```kotlin
enum class AnnotationComposerPlacement { PhoneSheet, TabletModal }
```

Удалить параметр:

```kotlin
modifier: Modifier = Modifier,
```

и ветки `Below`/`Above`. `when` должен быть exhaustive только для
`PhoneSheet`/`TabletModal`.

- [ ] **Step 6: Запустить целевые и compile-regression тесты**

Run:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class='net.inkyquill.pocketeditor.ui.ReviewInteractionTest#phoneSelectionAlwaysUsesBottomSheetEvenWhenAnchorHasRoom,net.inkyquill.pocketeditor.ui.ReviewInteractionTest#physicalTabletAlwaysUsesModalEvenWhenAnchorHasRoom,net.inkyquill.pocketeditor.ui.ReviewInteractionTest#crampedPhoneSelectionUsesModalBottomSheetComposer,net.inkyquill.pocketeditor.ui.ReviewInteractionTest#physicalTabletInNarrowSplitScreenUsesCenteredCompactModal,net.inkyquill.pocketeditor.ui.AdaptiveReaderTest'
```

Expected: `BUILD SUCCESSFUL`; новые просторные и существующие тесные сценарии выбирают один container для каждого физического класса, `AdaptiveReaderTest` компилируется без `annotationPlacement`.

- [ ] **Step 7: Commit**

```bash
git add \
  app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt \
  app/src/main/java/net/inkyquill/pocketeditor/ui/review/InlineAnnotationComposer.kt \
  app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt \
  app/src/androidTest/java/net/inkyquill/pocketeditor/ui/AdaptiveReaderTest.kt
git commit -m "fix: choose review composer by device class"
```

---

### Task 3: Контекст выделения и Material 3 actions

**Files:**
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/SignalComposer.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/EditComposer.kt`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt`

**Interfaces:**
- Consumes: `stackedActions` из Task 1
- Produces: test tags `signal-selection-quote`, `signal-selection-marker`
- Preserves: `save-draft`, `cancel-draft`, `inline-annotation-input`

- [ ] **Step 1: Написать layout-регрессии**

Добавить в `ReviewInteractionTest.kt`:

```kotlin
@Test
fun detachedSignalComposerQuotesTheSelectedText() {
    val selection = ReviewSelection(0, 0, 38, RawRange(0, 38), "Keep the quiet pressure through the end.")
    val draft = ReviewDraft.Signal(null, selection, SignalType.WARNING, "")
    compose.setContent {
        PocketEditorTheme(darkTheme = true) {
            InlineAnnotationComposer(
                session = ReviewDraftSession(draft),
                callbacks = ReaderCallbacks(),
                placement = AnnotationComposerPlacement.TabletModal,
            )
        }
    }

    compose.onNodeWithTag("signal-selection-quote")
        .assertTextContains("Keep the quiet pressure through the end.")
    compose.onNodeWithTag("signal-selection-marker").assertIsDisplayed()
}

@Test
fun phoneComposerStacksAFullWidthSaveAboveCancel() {
    val selection = ReviewSelection(0, 0, 5, RawRange(0, 5), "quiet")
    val draft = ReviewDraft.Signal(null, selection, SignalType.NOTE, "")
    compose.setContent {
        PocketEditorTheme(darkTheme = true) {
            InlineAnnotationComposer(
                session = ReviewDraftSession(draft),
                callbacks = ReaderCallbacks(),
                placement = AnnotationComposerPlacement.PhoneSheet,
            )
        }
    }

    val form = compose.onNodeWithTag("signal-composer").fetchSemanticsNode().boundsInRoot
    val save = compose.onNodeWithTag("save-draft").fetchSemanticsNode().boundsInRoot
    val cancel = compose.onNodeWithTag("cancel-draft").fetchSemanticsNode().boundsInRoot
    compose.runOnIdle {
        assertTrue(save.width >= form.width - 32f * compose.density.density - 2f)
        assertTrue(save.bottom <= cancel.top)
    }
}

@Test
fun tabletComposerKeepsCancelLeftOfSave() {
    val selection = ReviewSelection(0, 0, 5, RawRange(0, 5), "quiet")
    val draft = ReviewDraft.Edit(null, selection, "changed")
    compose.setContent {
        PocketEditorTheme(darkTheme = true) {
            InlineAnnotationComposer(
                session = ReviewDraftSession(draft),
                callbacks = ReaderCallbacks(),
                placement = AnnotationComposerPlacement.TabletModal,
            )
        }
    }

    val save = compose.onNodeWithTag("save-draft").fetchSemanticsNode().boundsInRoot
    val cancel = compose.onNodeWithTag("cancel-draft").fetchSemanticsNode().boundsInRoot
    compose.runOnIdle { assertTrue(cancel.right <= save.left) }
}
```

- [ ] **Step 2: Подтвердить красную фазу**

Run:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class='net.inkyquill.pocketeditor.ui.ReviewInteractionTest#detachedSignalComposerQuotesTheSelectedText,net.inkyquill.pocketeditor.ui.ReviewInteractionTest#phoneComposerStacksAFullWidthSaveAboveCancel,net.inkyquill.pocketeditor.ui.ReviewInteractionTest#tabletComposerKeepsCancelLeftOfSave'
```

Expected: `FAIL`; quote tags отсутствуют, phone actions ещё Row, текущий порядок — Save перед Cancel.

- [ ] **Step 3: Добавить quote в `SignalComposer`**

Добавить imports:

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
```

Сразу после заголовка формы добавить:

```kotlin
val signalColor = LocalReviewColors.current.signalColor(draft.type)
Row(
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    modifier = Modifier
        .fillMaxWidth()
        .height(IntrinsicSize.Min),
) {
    Box(
        Modifier
            .fillMaxHeight()
            .width(2.dp)
            .background(signalColor)
            .testTag("signal-selection-marker"),
    )
    Text(
        text = draft.selection.selectedText,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag("signal-selection-quote"),
    )
}
```

Внутри `FlowRow` сохранить локальный `signalColor` для каждого chip под именем
`typeColor`, чтобы quote использовал цвет выбранного `draft.type`.

- [ ] **Step 4: Ограничить блок «До»**

В `EditComposer.kt` добавить `TextOverflow` и заменить исходный Text:

```kotlin
Text(
    text = draft.selection.selectedText,
    style = MaterialTheme.typography.bodyMedium,
    maxLines = 3,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier.testTag("edit-before"),
)
```

- [ ] **Step 5: Реализовать phone/tablet actions в обеих формах**

В `SignalComposer.kt` добавить общий package-internal компонент:

```kotlin
@Composable
internal fun ComposerActions(
    stacked: Boolean,
    saveEnabled: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(
                onClick = onSave,
                enabled = saveEnabled,
                modifier = Modifier.fillMaxWidth().testTag("save-draft"),
            ) {
                Text(stringResource(R.string.save))
            }
            TextButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().testTag("cancel-draft"),
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("cancel-draft")) {
                Text(stringResource(R.string.cancel))
            }
            Button(
                onClick = onSave,
                enabled = saveEnabled,
                modifier = Modifier.testTag("save-draft"),
            ) {
                Text(stringResource(R.string.save))
            }
        }
    }
}
```

В `SignalComposer` заменить текущий Row:

```kotlin
ComposerActions(
    stacked = stackedActions,
    saveEnabled = true,
    onSave = onSave,
    onCancel = onCancel,
)
```

В `EditComposer` заменить текущий Row:

```kotlin
ComposerActions(
    stacked = stackedActions,
    saveEnabled = validation == DraftValidation.Valid,
    onSave = onSave,
    onCancel = onCancel,
)
```

Добавить в `SignalComposer.kt` нужный import:

```kotlin
import androidx.compose.ui.Alignment
```

- [ ] **Step 6: Запустить layout-регрессии**

Run:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class='net.inkyquill.pocketeditor.ui.ReviewInteractionTest#detachedSignalComposerQuotesTheSelectedText,net.inkyquill.pocketeditor.ui.ReviewInteractionTest#phoneComposerStacksAFullWidthSaveAboveCancel,net.inkyquill.pocketeditor.ui.ReviewInteractionTest#tabletComposerKeepsCancelLeftOfSave,net.inkyquill.pocketeditor.ui.ReviewInteractionTest#signalComposerKeepsSixteenDpPaddingAroundItsContentOnEveryEdge'
```

Expected: `BUILD SUCCESSFUL`; quote присутствует, phone Save full-width, tablet actions следуют M3 order, существующий 16.dp padding сохранён.

- [ ] **Step 7: Commit**

```bash
git add \
  app/src/main/java/net/inkyquill/pocketeditor/ui/review/SignalComposer.kt \
  app/src/main/java/net/inkyquill/pocketeditor/ui/review/EditComposer.kt \
  app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt
git commit -m "feat: keep selection context in review composer"
```

---

### Task 4: IME-aware containers и единый dismiss flow

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/InlineAnnotationComposer.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/SignalComposer.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/EditComposer.kt`
- Test: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt`

**Interfaces:**
- Produces: локальный `requestDismiss: () -> Unit`
- Produces: strings `discard_review_changes_title`, `discard_review_changes`, `continue_review_editing`
- Removes: общий `ReaderScreen` `BackHandler` для active draft
- Preserves: `ReaderCallbacks.onCancelDraft`

- [ ] **Step 1: Переписать dismiss-тесты под утверждённую семантику**

Заменить `dirtyEditSurvivesBackAndOutsideDismissUntilExplicitCancel` тестом:

```kotlin
@Test
fun dirtyEditOffersDiscardConfirmationForBackAndOutsideDismiss() {
    val reviewUi = mutableStateOf(ReviewUiState())
    var cancels = 0
    val selection = ReviewSelection(0, 0, 9, RawRange(0, 9), "Canonical")
    val draft = ReviewDraft.Edit(
        recordId = "edit-1",
        selection = selection,
        after = "Replacement",
        savedAfter = "Canonical",
    )
    compose.setContent {
        PocketEditorTheme(darkTheme = true) {
            ReaderScreen(
                sampleState(true),
                ReaderCallbacks(onCancelDraft = { cancels++; reviewUi.value = ReviewUiState() }),
                reviewUi.value,
                windowSize = DpSize(800.dp, 1_280.dp),
            )
        }
    }
    compose.runOnIdle {
        reviewUi.value = ReviewUiState(draftSession = ReviewDraftSession(draft))
    }

    compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithText("Отменить изменения?").assertIsDisplayed()
    compose.onNodeWithText("Продолжить редактирование").performClick()
    compose.onNodeWithTag("inline-annotation-input").assertTextContains("Replacement")
    compose.onNodeWithTag("inline-annotation-input").assertIsFocused()

    compose.onNodeWithTag("inline-annotation-modal")
        .performTouchInput { click(Offset(1f, 1f)) }
    compose.onNodeWithText("Отменить изменения?").assertIsDisplayed()
    compose.onNodeWithText("Отменить изменения").performClick()

    compose.runOnIdle { assertEquals(1, cancels) }
}
```

Заменить `backCancelsACleanAdjacentComposerInsteadOfFinishingTheActivity` на:

```kotlin
@Test
fun emptyNewSignalDismissesWithoutConfirmation() {
    val reviewUi = mutableStateOf(ReviewUiState())
    var cancels = 0
    val selection = ReviewSelection(0, 0, 5, RawRange(0, 5), "quiet")
    val draft = ReviewDraft.Signal(null, selection, SignalType.NOTE, "")
    compose.setContent {
        PocketEditorTheme(darkTheme = true) {
            InlineAnnotationComposer(
                session = ReviewDraftSession(draft),
                callbacks = ReaderCallbacks(onCancelDraft = { cancels++ }),
                placement = AnnotationComposerPlacement.TabletModal,
            )
        }
    }

    compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    compose.runOnIdle { assertEquals(1, cancels) }
    compose.onAllNodesWithText("Отменить изменения?").assertCountEquals(0)
}
```

Добавить regression локального SignalType в dismiss flow:

```kotlin
@Test
fun savedSignalTypeChangeProtectsDismissBeforeParentStateCatchesUp() {
    val selection = ReviewSelection(0, 0, 5, RawRange(0, 5), "quiet")
    val draft = ReviewDraft.Signal(
        recordId = "signal-1",
        selection = selection,
        type = SignalType.NOTE,
        comment = "",
        savedType = SignalType.NOTE,
        savedComment = "",
    )
    compose.setContent {
        PocketEditorTheme(darkTheme = true) {
            InlineAnnotationComposer(
                session = ReviewDraftSession(draft),
                callbacks = ReaderCallbacks(),
                placement = AnnotationComposerPlacement.TabletModal,
            )
        }
    }

    compose.onNodeWithTag("signal-warning").performClick()
    compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    compose.onNodeWithText("Отменить изменения?").assertIsDisplayed()
}
```

Добавить regression повторного открытия:

```kotlin
@Test
fun reopeningTheSameSelectionStartsFromTheNewDraftValue() {
    val draft = mutableStateOf<ReviewDraft?>(
        ReviewDraft.Signal(
            null,
            ReviewSelection(0, 0, 5, RawRange(0, 5), "quiet"),
            SignalType.NOTE,
            "",
        ),
    )
    compose.setContent {
        PocketEditorTheme(darkTheme = true) {
            draft.value?.let {
                InlineAnnotationComposer(
                    session = ReviewDraftSession(it),
                    callbacks = ReaderCallbacks(onCancelDraft = { draft.value = null }),
                    placement = AnnotationComposerPlacement.TabletModal,
                )
            }
        }
    }

    compose.onNodeWithTag("inline-annotation-input").performTextInput("old")
    compose.onNodeWithTag("cancel-draft").performClick()
    compose.runOnIdle {
        draft.value = ReviewDraft.Signal(
            null,
            ReviewSelection(0, 0, 5, RawRange(0, 5), "quiet"),
            SignalType.NOTE,
            "",
        )
    }

    compose.onNodeWithTag("inline-annotation-input").assertTextEquals("")
    compose.onAllNodesWithText("old").assertCountEquals(0)
}
```

Добавить import:

```kotlin
import androidx.compose.ui.test.assertTextEquals
```

- [ ] **Step 2: Добавить tablet IME geometry regression**

В `ReviewInteractionTest.kt` импортировать:

```kotlin
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.core.view.WindowInsetsCompat
```

Добавить тест:

```kotlin
@Test
fun tabletModalCentersInTheVisibleAreaAboveIme() {
    val selection = ReviewSelection(0, 0, 5, RawRange(0, 5), "quiet")
    val draft = ReviewDraft.Signal(null, selection, SignalType.NOTE, "")
    compose.setContent {
        PocketEditorTheme(darkTheme = true) {
            InlineAnnotationComposer(
                session = ReviewDraftSession(draft),
                callbacks = ReaderCallbacks(),
                placement = AnnotationComposerPlacement.TabletModal,
            )
        }
    }
    compose.onNodeWithTag("inline-annotation-input").performClick()
    compose.runOnUiThread {
        val view = compose.activity.window.decorView
        val imm = compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view.findFocus(), InputMethodManager.SHOW_IMPLICIT)
    }
    compose.waitUntil(5_000) {
        val view = compose.activity.window.decorView
        WindowInsetsCompat.toWindowInsetsCompat(view.rootWindowInsets, view)
            .isVisible(WindowInsetsCompat.Type.ime())
    }

    val root = compose.onNodeWithTag("inline-annotation-modal").fetchSemanticsNode().boundsInRoot
    val card = compose.onNodeWithTag("inline-annotation-composer").fetchSemanticsNode().boundsInRoot
    compose.runOnIdle {
        assertTrue(card.bottom <= root.bottom + 1f)
        assertTrue(kotlin.math.abs(card.center.y - root.center.y) <= 2f)
    }
}
```

Если emulator настроен с physical keyboard, перед запуском отключить его один раз:

```bash
adb shell settings put secure show_ime_with_hard_keyboard 1
```

- [ ] **Step 3: Подтвердить красную фазу**

Run:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class='net.inkyquill.pocketeditor.ui.ReviewInteractionTest#dirtyEditOffersDiscardConfirmationForBackAndOutsideDismiss,net.inkyquill.pocketeditor.ui.ReviewInteractionTest#emptyNewSignalDismissesWithoutConfirmation,net.inkyquill.pocketeditor.ui.ReviewInteractionTest#savedSignalTypeChangeProtectsDismissBeforeParentStateCatchesUp,net.inkyquill.pocketeditor.ui.ReviewInteractionTest#reopeningTheSameSelectionStartsFromTheNewDraftValue,net.inkyquill.pocketeditor.ui.ReviewInteractionTest#tabletModalCentersInTheVisibleAreaAboveIme'
```

Expected: `FAIL`; confirmation copy отсутствует, новый draft считается dirty по controller state, full-screen/IME geometry ещё не реализована.

- [ ] **Step 4: Добавить manifest compatibility и copy**

В `AndroidManifest.xml`:

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:windowSoftInputMode="adjustResize">
```

В `strings.xml`:

```xml
<string name="discard_review_changes_title">Отменить изменения?</string>
<string name="discard_review_changes">Отменить изменения</string>
<string name="continue_review_editing">Продолжить редактирование</string>
```

- [ ] **Step 5: Реализовать effective dirty и confirmation**

В `InlineAnnotationComposer.kt` добавить:

```kotlin
private fun ReviewDraft.isDirtyWithInput(text: String): Boolean = when (this) {
    is ReviewDraft.Signal -> if (recordId == null) {
        text.isNotEmpty()
    } else {
        savedType == null || type != savedType || text != savedComment
    }
    is ReviewDraft.Edit -> if (recordId == null) {
        text != selection.selectedText
    } else {
        savedAfter == null || text != savedAfter
    }
}
```

В composable:

```kotlin
var confirmDiscard by rememberSaveable(identity) { mutableStateOf(false) }
val isDirty = requireNotNull(localSession.draft).isDirtyWithInput(inputValue.text)
val requestDismiss = {
    if (isDirty) confirmDiscard = true else callbacks.onCancelDraft()
}
BackHandler(enabled = true, onBack = requestDismiss)
```

После container `when` добавить:

```kotlin
if (confirmDiscard) {
    AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.discard_review_changes_title)) },
        confirmButton = {
            TextButton(
                onClick = {
                    confirmDiscard = false
                    callbacks.onCancelDraft()
                },
            ) {
                Text(stringResource(R.string.discard_review_changes))
            }
        },
        dismissButton = {
            TextButton(onClick = { confirmDiscard = false }) {
                Text(stringResource(R.string.continue_review_editing))
            }
        },
    )
}
```

В `ReaderScreen.kt` удалить общий блок:

```kotlin
BackHandler(reviewUiState.draftSession.draft != null) {
    if (!reviewUiState.draftSession.blocksDismissal) callbacks.onCancelDraft()
}
```

- [ ] **Step 6: Реализовать IME-aware sheet**

Создать:

```kotlin
val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
val scrollState = rememberScrollState()
```

Добавить в `SignalComposer` и `EditComposer` параметр:

```kotlin
contentPadding: Dp = 16.dp,
```

и заменить фиксированный `.padding(16.dp)` на `.padding(contentPadding)`.

Заменить старую `content` lambda на форму, которой container передаёт собственный
внутренний padding:

```kotlin
val form: @Composable (stackedActions: Boolean, contentPadding: Dp) -> Unit =
    { stackedActions, contentPadding ->
        when (draft) {
            is ReviewDraft.Signal -> SignalComposer(
                draft = requireNotNull(localSession.draft as? ReviewDraft.Signal),
                value = inputValue,
                onTypeChange = onSignalTypeChange,
                onCommentChange = onInputChange,
                onSave = callbacks.onSaveDraft,
                onCancel = callbacks.onCancelDraft,
                stackedActions = stackedActions,
                contentPadding = contentPadding,
                inputModifier = Modifier
                    .focusRequester(focusRequester)
                    .testTag("inline-annotation-input"),
            )
            is ReviewDraft.Edit -> EditComposer(
                draft = draft,
                value = inputValue,
                validation = localValidation,
                onAfterChange = onInputChange,
                onSave = callbacks.onSaveDraft,
                onCancel = callbacks.onCancelDraft,
                stackedActions = stackedActions,
                contentPadding = contentPadding,
                inputModifier = Modifier
                    .focusRequester(focusRequester)
                    .testTag("inline-annotation-input"),
            )
        }
    }
```

Для `PhoneSheet`:

```kotlin
ModalBottomSheet(
    onDismissRequest = requestDismiss,
    sheetState = sheetState,
    modifier = Modifier.testTag("inline-annotation-phone-sheet"),
) {
    Box(
        Modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(scrollState)
            .testTag("inline-annotation-composer"),
    ) {
        form(stackedActions = true, contentPadding = 16.dp)
    }
}
```

Не добавлять `navigationBarsPadding`: safe/navigation insets уже обрабатывает
`ModalBottomSheet`.

Заменить немедленный `LaunchedEffect(identity)` из Task 1 на ожидание expanded
sheet:

```kotlin
LaunchedEffect(identity, placement) {
    if (placement == AnnotationComposerPlacement.PhoneSheet) {
        snapshotFlow { sheetState.currentValue }
            .first { it == SheetValue.Expanded }
    }
    focusRequester.requestFocus()
}
```

- [ ] **Step 7: Реализовать full-screen tablet root**

Для `TabletModal` использовать:

```kotlin
Dialog(
    onDismissRequest = requestDismiss,
    properties = DialogProperties(
        usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false,
    ),
) {
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(24.dp)
            .testTag("inline-annotation-modal"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(isDirty) {
                    detectTapGestures { requestDismiss() }
                },
        )
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .verticalScroll(scrollState)
                .pointerInput(Unit) {
                    detectTapGestures { }
                }
                .testTag("inline-annotation-composer"),
        ) {
            form(stackedActions = false, contentPadding = 24.dp)
        }
    }
}
```

`form` — локальная composable lambda, которая выбирает `SignalComposer` или
`EditComposer` и передаёт `inputValue`, `onInputChange`, `localValidation`,
callbacks и `stackedActions`. В PhoneSheet не оборачивать её во второй `Surface`.

- [ ] **Step 8: Запустить dismiss/IME red-green набор**

Run:

```bash
adb shell settings put secure show_ime_with_hard_keyboard 1
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class='net.inkyquill.pocketeditor.ui.ReviewInteractionTest#dirtyEditOffersDiscardConfirmationForBackAndOutsideDismiss,net.inkyquill.pocketeditor.ui.ReviewInteractionTest#emptyNewSignalDismissesWithoutConfirmation,net.inkyquill.pocketeditor.ui.ReviewInteractionTest#savedSignalTypeChangeProtectsDismissBeforeParentStateCatchesUp,net.inkyquill.pocketeditor.ui.ReviewInteractionTest#reopeningTheSameSelectionStartsFromTheNewDraftValue,net.inkyquill.pocketeditor.ui.ReviewInteractionTest#tabletModalCentersInTheVisibleAreaAboveIme,net.inkyquill.pocketeditor.ui.ReviewInteractionTest#dirtyComposerSurvivesAdaptiveRotation'
```

Expected: `BUILD SUCCESSFUL`; dirty dismiss подтверждается, clean dismiss закрывает, повторная сессия чистая, card центрирована в IME-visible root.

- [ ] **Step 9: Запустить полные затронутые test suites**

Run:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class='net.inkyquill.pocketeditor.ui.ReviewInteractionTest,net.inkyquill.pocketeditor.ui.AdaptiveReaderTest'
./gradlew :app:lintDebug
```

Expected: все три команды `BUILD SUCCESSFUL`, 0 failed tests, 0 lint errors.

- [ ] **Step 10: Проверить diff и отсутствие мёртвого anchor-кода**

Run:

```bash
git diff --check
rg -n 'EphemeralDraftAnchor|draftAnchor|annotationPlacement|AnnotationComposerPlacement\\.(Below|Above)' \
  app/src/main app/src/test app/src/androidTest
```

Expected: `git diff --check` без вывода; `rg` не находит совпадений. Отдельно:

```bash
rg -n 'flyoutPlacementIsBelow|anchoredHorizontalOffsetInRoot' \
  app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt \
  app/src/androidTest/java/net/inkyquill/pocketeditor/ui/AdaptiveReaderTest.kt
```

Expected: обе функции и их flyout tests остаются.

- [ ] **Step 11: Commit**

```bash
git add \
  app/src/main/AndroidManifest.xml \
  app/src/main/res/values/strings.xml \
  app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt \
  app/src/main/java/net/inkyquill/pocketeditor/ui/review/InlineAnnotationComposer.kt \
  app/src/main/java/net/inkyquill/pocketeditor/ui/review/SignalComposer.kt \
  app/src/main/java/net/inkyquill/pocketeditor/ui/review/EditComposer.kt \
  app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt
git commit -m "fix: center and protect review composer"
```

---

## Final Verification

- [ ] **Step 1: Проверить рабочее дерево**

Run:

```bash
git status --short --branch
git log --oneline -5
```

Expected: четыре implementation-коммита после docs-коммитов; из исходных
untracked-файлов могут оставаться только `.agents/`, `.claude/`,
`docs/superpowers/plans/2026-07-21-ux-bugfixes-and-polish.md`,
`docs/superpowers/specs/2026-07-21-ux-bugfixes-and-polish-design.md` и `icon.png`.

- [ ] **Step 2: Выполнить финальный полный gate**

Run:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
adb shell settings put secure show_ime_with_hard_keyboard 1
./gradlew :app:connectedDebugAndroidTest
```

Expected: unit tests, lint и полный instrumentation suite завершаются
`BUILD SUCCESSFUL`, 0 failures.

- [ ] **Step 3: Ручная проверка на реальной клавиатуре**

На phone emulator или устройстве:

1. Выделить текст сверху, посередине и снизу главы.
2. Открыть signal и edit composer; каждый раз должен появляться bottom sheet.
3. Быстро набрать `абвгд`, поставить cursor между `в` и `г`, ввести `X`.
4. Убедиться, что поле показывает `абвXгд`, cursor остаётся после `X`.

На tablet emulator или устройстве:

1. Повторить открытие composer в portrait, landscape и узком split-screen.
2. Убедиться, что всегда появляется modal, центрированный над IME.
3. Нажать Back в пустом draft — composer закрывается.
4. Изменить текст и нажать Back — появляется «Отменить изменения?».
5. Выбрать «Продолжить редактирование» — текст и focus сохраняются.

Expected: issue #4 и #5 не воспроизводятся; container не зависит от anchor,
символы и cursor стабильны.
