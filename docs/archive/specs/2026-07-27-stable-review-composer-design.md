# Stable Review Composer Design

## Цель

Исправить [issue #4](https://github.com/InkyQuill/pocket-editor/issues/4) и
[issue #5](https://github.com/InkyQuill/pocket-editor/issues/5):

- редактор заметки или замены на телефоне всегда открывается как bottom sheet;
- на физическом планшете редактор всегда открывается как центрированное modal-окно;
- modal центрируется в части экрана, остающейся видимой над экранной клавиатурой;
- быстрый ввод, IME composition и перемещение курсора не теряют и не переставляют символы.

## Подтверждённые причины

### Нестабильный контейнер

`ReaderScreen.kt` сохраняет bounds выделения в `draftAnchor` и передаёт их в
`annotationPlacement()`. Функция выбирает `Below` или `Above`, когда рядом с
выделением хватает места, и только в тесном viewport возвращает `PhoneSheet` или
`TabletModal`. Поэтому один и тот же телефон получает разные контейнеры в
зависимости от позиции выделения и измеренной высоты редактора.

### Нестабильный ввод

`SignalComposer` и `EditComposer` передают в `OutlinedTextField` строку из
`ReviewDraft`. Каждое `onValueChange` проходит через неограниченный FIFO-channel,
`EditorialReviewController.mutationMutex` и `ReviewDraftStore.save()`. Только
после persistence контроллер публикует новое значение в `StateFlow`.

Из-за задержки поле повторно получает устаревший текст, а строковый overload
`OutlinedTextField` не предоставляет приложению контроль над cursor selection и
IME composition. Это создаёт окно, в котором IME уже приняла символ, а Compose
ещё отображает предыдущую строку.

### Отстающее состояние за пределами текста

Та же задержка влияет не только на поле. `EditComposer.validation` считается из
`ReviewDraftStateMachine.validate(session)`, то есть из `draft.after`, а
`blocksDismissal` — из `ReviewDraftSession.isDirty`. Пока controller догоняет
ввод, кнопка «Сохранить» может кратковременно отключаться с сообщением
`Unchanged`, а защита от закрытия — отставать от реального содержимого поля.
Поэтому локальным должен стать не только текст, но и производные от него
валидация и dirty-состояние.

## Область изменений

Изменения ограничиваются редактором активного review draft и его тестами.

Не меняются:

- привязанный к выделению `SelectionFlyout`;
- `ReviewDraft`, `ReviewDraftStore` и формат базы данных;
- последовательная очередь `EditorialReviewCallbacks`;
- правила сохранения, отмены, восстановления и retry;
- review overview, карточки рецензий и переход к их anchor.

## Адаптивное поведение

Физический класс устройства остаётся совместимым с текущим приложением:

- `LocalConfiguration.current.smallestScreenWidthDp < 600` — телефон;
- `LocalConfiguration.current.smallestScreenWidthDp >= 600` — планшет.

Это правило намеренно не зависит от текущей ширины окна. Физический планшет в
узком split-screen продолжает использовать tablet modal.

После выбора действия в `SelectionFlyout`:

- телефон всегда создаёт `AnnotationComposerPlacement.PhoneSheet`;
- планшет всегда создаёт `AnnotationComposerPlacement.TabletModal`.

Позиция выделения, доступное место сверху или снизу, размер редактора и
ориентация окна не участвуют в выборе контейнера.

Из `AnnotationComposerPlacement` удаляются `Below` и `Above`. Вместе с ними
удаляются `annotationPlacement()`, `EphemeralDraftAnchor`, `draftAnchor`,
измерения высоты редактора (`composerHeightPx`, `composerWidthPx`,
`composerEdgeMarginPx`) и offset-логика, применявшаяся только к привязанному
редактору. `anchoredHorizontalOffsetInRoot()`, `flyoutPlacementIsBelow()` и
измерения flyout сохраняются: их продолжает использовать `SelectionFlyout`.
Семантические данные выделения остаются в `ReviewDraft.selection`.

Параметр `modifier` у `InlineAnnotationComposer` после удаления anchored-веток
не имеет ни одного места применения и удаляется вместе с ними, чтобы не
оставлять молча игнорируемый API.

### Компенсация потерянного контекста

Anchored-редактор был единственной визуальной связью между формой и выделенным
фрагментом. Bottom sheet и особенно tablet modal эту связь разрывают — на
планшете карточка ещё и перекрывает сам фрагмент. Чтобы не потерять контекст:

- `SignalComposer` показывает выделенный фрагмент над полем комментария:
  `draft.selection.selectedText`, `bodySmall`, `onSurfaceVariant`,
  `maxLines = 2`, `TextOverflow.Ellipsis`, с вертикальной линейкой 2.dp цветом
  `LocalReviewColors.current.signalColor(draft.type)` слева;
- `EditComposer` ограничивает блок «До» тем же `maxLines = 3` с ellipsis. Сейчас
  он не ограничен, и длинное выделение с открытой IME выталкивает поле ввода за
  пределы видимой области.

## Контейнеры и IME

Insets применяются только на уровне container root — ровно один раз, чтобы не
создать двойной IME padding. Используются стандартные модификаторы
(`Modifier.imePadding()`, `Modifier.windowInsetsPadding(WindowInsets.safeDrawing)`);
Layout-level `WindowInsetsRulers` не требуется, поскольку задача сводится к
отступу и центрированию, а не к позиционированию относительно кадров анимации
IME.

### Телефон

`ModalBottomSheet` остаётся Material 3 bottom sheet с тегом
`inline-annotation-phone-sheet`. Изменения:

- `sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)`.
  Без этого на высоком экране лист открывается наполовину и конкурирует с
  клавиатурой за высоту;
- контент рисуется без вложенного `Surface`. Сейчас внутри листа лежит
  `Surface(surfaceContainerHigh, tonalElevation 8, shadowElevation 8,
  shapes.large)` — карточка внутри карточки с двойной подложкой и двойным
  скруглением. В sheet контент плоский, фон даёт сам `ModalBottomSheet`;
- контейнер контента заполняет ширину и получает `imePadding()`, допускает
  вертикальную прокрутку при недостаточной высоте и сохраняет доступность input
  и кнопок над клавиатурой. Navigation/safe-area insets второй раз не
  добавляются: их уже обрабатывает сам Material 3 `ModalBottomSheet`.

Тег `inline-annotation-composer` переезжает на контейнер контента, поэтому
существующие проверки видимости и bounds продолжают работать.

### Планшет

`Dialog` остаётся с `usePlatformDefaultWidth = false` и получает полноразмерный
прозрачный root. Поскольку это full-screen dialog в edge-to-edge приложении,
`DialogProperties.decorFitsSystemWindows` устанавливается в `false` — иначе окно
диалога не получит IME insets.

Root:

- заполняет окно;
- ограничивается safe drawing area и `imePadding()`;
- выравнивает карточку по центру полученной видимой области;
- не использует anchor выделения;
- сохраняет максимальную ширину карточки `420.dp`;
- допускает вертикальную прокрутку содержимого при недостаточной высоте.

Таким образом, при открытии клавиатуры центр пересчитывается относительно
области над IME, а не полного физического экрана.

**Dismiss по касанию вне карточки.** Full-screen root означает, что «снаружи»
окна диалога не остаётся области, и системный outside-touch больше не вызывает
`onDismissRequest` — остался бы только Back. Поэтому прозрачный root получает
собственный pointer-обработчик касания без click semantics, вызывающий тот же
путь dismiss, а карточка перехватывает касания, чтобы клик по ней не закрывал
редактор. `Modifier.clickable` здесь не используется: даже без явной semantics
role он добавил бы доступное действие для всего full-screen root.

Карточка приводится к токенам M3 dialog: `MaterialTheme.shapes.extraLarge`,
`surfaceContainerHigh`, `tonalElevation = 6.dp`, внутренний padding `24.dp`,
внешний отступ root — `24.dp`. `shapes.large` остаётся у `SelectionFlyout` и
содержимого bottom sheet.

### Манифест

`android:windowSoftInputMode="adjustResize"` добавляется в `MainActivity` для
единообразия и совместимости со старыми устройствами, но не является механизмом
исправления: `MainActivity` вызывает `enableEdgeToEdge()`, то есть
`decorFitsSystemWindows = false`, и для targetSdk 30+ окно не ресайзится —
поведение полностью определяется insets, применяемыми в container root.

## Состояние текстового поля

`InlineAnnotationComposer` владеет одним локальным `TextFieldValue` для активного
draft. Оно включает текст, cursor selection и IME composition.

Для `ReviewDraft.Signal` рядом хранится локальный `SignalType`. Нажатие chip
синхронно обновляет выбранный тип и только затем отправляет существующий
`onSignalTypeChanged` в controller. Это закрывает ту же гонку для
effective dirty-state: немедленный dismiss после смены типа сохранённого Signal
не может увидеть старое значение и закрыть форму без подтверждения.

Идентичность редакторской сессии состоит из:

- вида draft (`Signal` или `Edit`);
- `recordId`;
- `selection.rawRange.startByte`;
- `selection.rawRange.endByte`.

При появлении новой идентичности локальное значение инициализируется из
`draft.comment` или `draft.after`, а cursor устанавливается в конец текста.
Состояние сохраняется через `rememberSaveable(identity, stateSaver = TextFieldValue.Saver)`.

Та же identity — единственный ключ `LaunchedEffect` для `focusRequester`, чтобы
инициализация значения и запрос фокуса не могли разъехаться (сейчас ключи
`draft.recordId, draft::class` не совпадают с составом identity).

Отмена и повторное открытие редактора на том же диапазоне не считаются одной
сессией: `InlineAnnotationComposer` покидает композицию при `draft == null`, и
локальное значение создаётся заново. Это фиксируется тестом.

Обычное асинхронное обновление того же draft не переинициализирует локальное
значение. Это предотвращает откат к старой строке, пока controller/persistence
догоняют UI. Авторитетная смена записи или диапазона создаёт новую идентичность
и новое начальное значение.

На каждое событие поля:

1. Полный новый `TextFieldValue` синхронно записывается в локальное Compose state.
2. Если `text` не изменился, persistence callback не вызывается.
3. Если `text` изменился, строка передаётся существующему
   `ReaderCallbacks.onDraftTextChanged`.
4. Контроллер сохраняет строку прежним последовательным путём.

`SignalComposer` и `EditComposer` принимают `TextFieldValue` и
`(TextFieldValue) -> Unit`. Cursor и composition не добавляются в `ReviewDraft`
и не сохраняются в базе.

`SignalComposer` получает копию Signal с локальными `type` и `comment`, поэтому
chip и производные цвета обновляются без ожидания parent state. Изменение типа
по-прежнему передаётся через `ReaderCallbacks.onSignalTypeChanged`.

### Валидация Edit

`EditComposer` получает готовый `DraftValidation`, вычисленный от локального
текста, а не от `draft.after`:

- `Unchanged`, если локальный текст равен `draft.selection.selectedText`;
- `Overlapping`, если `draft.rawRange` пересекается с `occupiedEditRanges`
  (правило и данные не меняются);
- иначе `Valid`.

`ReviewDraftStateMachine.validate` остаётся источником правила; в
`InlineAnnotationComposer` оно применяется к сессии с подставленным локальным
текстом. Иначе кнопка «Сохранить» кратковременно отключается прямо во время
ввода — тот же класс дефекта, что issue #5, только в кнопке.

## Dismiss и действия

Пока controller state догоняет локальное поле, защита от случайного закрытия не
может полагаться только на `ReviewDraftSession.blocksDismissal`.

Эффективное dirty-состояние вычисляется из локального текста:

- новый `Signal` dirty, если локальный текст не пуст (сам факт `savedType == null`
  dirty-состояния не создаёт);
- сохранённый `Signal` dirty, если изменён type или локальный текст не равен
  `savedComment`;
- новый `Edit` dirty, если локальный текст не равен исходному
  `selection.selectedText`, которым поле предзаполнено;
- сохранённый `Edit` dirty, если локальный текст не равен `savedAfter`.

Это отличается от текущего `ReviewDraftSession.isDirty`, где любой несохранённый
draft считается грязным. Из-за этого пустая, только что открытая форма молча
игнорирует Back и касание вне карточки, и пользователю остаётся только искать
кнопку «Отмена». Пустой новый draft — clean и закрывается жестом.
`ReviewDraftSession.isDirty` при этом не меняется: он остаётся правилом
контроллера, а UI использует своё эффективное значение.

Для грязного draft Back и касание вне карточки не игнорируются молча, а
открывают подтверждение «Отменить изменения?» с действиями «Отменить
изменения» (вызывает `onCancelDraft`) и «Продолжить редактирование». Молчаливое
игнорирование жеста — тот же раздражитель, что issue #4: пользователь не
получает обратной связи.

Sheet dismiss, tablet root gesture и Back сходятся в один локальный
`requestDismiss` внутри `InlineAnnotationComposer`. Существующий общий
`BackHandler` в `ReaderScreen`, который проверяет отстающий
`reviewUiState.draftSession.blocksDismissal`, удаляется: иначе он обходит
effective dirty-state и новый confirmation flow.

Явная кнопка «Отмена» всегда вызывает существующий `onCancelDraft` без
подтверждения. Нажатие «Сохранить» отправляется в тот же FIFO-channel после уже
отправленных text events, поэтому контроллер сохраняет последнее введённое
значение.

При persistence-ошибке локальный текст остаётся видимым. Существующие
`ReviewUiError` и retry не меняются.

### Порядок действий

Ряд кнопок приводится к M3: подтверждающее действие справа,
`Arrangement.End`, «Отмена» слева от «Сохранить». В phone sheet «Сохранить»
занимает всю ширину, «Отмена» — текстовая кнопка под ней: на телефоне это и
попадание крупнее, и привычная для sheet иерархия. Test tags `save-draft` и
`cancel-draft` сохраняются.

## Доступность

- Автоматический focus на input сохраняется. В phone sheet запрос фокуса
  выполняется после того, как лист достиг видимого состояния, чтобы клавиатура
  не открывалась поверх ещё анимирующегося листа.
- Test tags `inline-annotation-composer`, `inline-annotation-input`,
  `inline-annotation-phone-sheet` и `inline-annotation-modal` сохраняются.
- Поле и кнопки доступны при увеличенном font scale и открытой IME.
- Вертикальная прокрутка применяется к содержимому карточки, а не к full-screen
  dialog root.
- Прозрачная область dismiss не объявляется кнопкой для TalkBack; закрытие через
  screen reader выполняется Back или кнопкой «Отмена».

## Изменяемые файлы

- `app/src/main/AndroidManifest.xml` — `adjustResize` для `MainActivity`.
- `app/src/main/res/values/strings.xml` — заголовок и действия подтверждения
  отмены dirty draft.
- `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt` —
  детерминированный выбор PhoneSheet/TabletModal, удаление `EphemeralDraftAnchor`,
  `draftAnchor`, `annotationPlacement()`, измерений редактора и общего
  `BackHandler` активного draft.
- `app/src/main/java/net/inkyquill/pocketeditor/ui/review/InlineAnnotationComposer.kt`
  — локальный `TextFieldValue`, effective dirty-state, подтверждение отмены,
  IME-aware контейнеры, dismiss-область tablet modal и прокручиваемое содержимое.
- `app/src/main/java/net/inkyquill/pocketeditor/ui/review/SignalComposer.kt` —
  controlled input через `TextFieldValue`, цитата выделения, порядок кнопок.
- `app/src/main/java/net/inkyquill/pocketeditor/ui/review/EditComposer.kt` —
  controlled input через `TextFieldValue`, валидация от локального текста,
  ограниченный блок «До», порядок кнопок.
- `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt`
  — регрессии контейнера, IME, cursor и delayed parent state; удаление
  anchored-тестов `landscapeContentsSidebarClampsRenderedSelectionFlyoutAndBelowComposerInRootSpace`
  и `landscapeContentsSidebarClampsRenderedAboveComposerInRootSpace`.
- `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/AdaptiveReaderTest.kt`
  — удаление `annotationPlacementReservesGapAndFlipsAboveBeforeDeviceFallback` и
  части `centeredTabletBelowAndAboveComposersClampInReaderRootSpace`, относящейся
  к `annotationPlacement`; проверки `anchoredHorizontalOffsetInRoot` и
  `flyoutPlacementIsBelow` сохраняются, поскольку flyout не меняется. Без этого
  androidTest не компилируется.
- Screenshot-тесты не затрагиваются: `ReviewScreenshotTest` снимает review-панель
  и не содержит сцен composer. Если сцена composer будет добавлена позже, эталон
  создаётся отдельным шагом и предъявляется пользователю.

Новый persistence-слой, миграция базы или новая runtime dependency не нужны.

## Стратегия тестирования

### Контейнер

- Телефон с выделением сверху, посередине и снизу всегда показывает
  `inline-annotation-phone-sheet`.
- Телефон не создаёт tablet modal.
- Физический планшет в portrait, landscape и узком split-screen всегда
  показывает `inline-annotation-modal`.
- Планшет не создаёт phone sheet.
- `SelectionFlyout` остаётся рядом с выделением до выбора действия.

### Insets и размеры

- Без IME tablet-карточка центрирована по обеим осям доступного dialog root.
- После focus и открытия IME карточка центрирована в области над клавиатурой и
  не пересекает её.
- На малой высоте содержимое прокручивается, input и обе кнопки достижимы.
- Phone sheet также оставляет focused input и действия над IME.
- Длинный выделенный фрагмент не выталкивает input за пределы видимой области
  ни в sheet, ни в modal.

### Ввод

- При parent state, который намеренно не отражает изменения немедленно, серия
  быстрых вводов сохраняет все символы.
- После установки cursor в середину текста следующий символ появляется в
  выбранной позиции.
- Cursor selection не отскакивает после parent recomposition.
- Изменение selection без изменения строки не вызывает
  `onDraftTextChanged`.
- IME composition хранится в локальном `TextFieldValue`.
- Тип сохранённого Signal визуально переключается сразу при отстающем parent
  state и немедленно участвует в effective dirty-state.
- При отстающем parent state кнопка «Сохранить» в `EditComposer` остаётся
  активной сразу после изменения текста и не мигает `Unchanged`.

### Жизненный цикл

- Dirty draft не закрывается по Back и касанию вне карточки, а показывает
  подтверждение — даже до обновления controller state.
- Подтверждение «Отменить изменения» закрывает draft; «Продолжить
  редактирование» сохраняет текст и фокус.
- Пустой новый draft закрывается по Back и касанию вне карточки без
  подтверждения.
- Касание вне карточки в tablet modal действительно доходит до dismiss-пути
  (регрессия на full-screen dialog root).
- Явная «Отмена» закрывает draft.
- «Сохранить» получает последний текст.
- Восстановленный draft открывается с сохранённым текстом.
- Отмена и повторное открытие редактора на том же диапазоне дают пустое
  начальное значение, а не текст предыдущей сессии.
- Изменение размеров окна и поворот не теряют локальное значение.

## Критерии приёмки

- Issue #4 не воспроизводится ни для одной позиции выделения на телефоне или
  планшете.
- Issue #5 не воспроизводится при быстром вводе, перемещении cursor и IME
  composition.
- Modal планшета остаётся центрированным в видимой области над клавиатурой и
  закрывается касанием вне карточки.
- Существующие unit-тесты проходят.
- Обновлённые instrumentation-тесты проходят на phone и tablet emulator.
- Screenshot diffs, если они появились, представлены пользователю до обновления
  эталонов.
