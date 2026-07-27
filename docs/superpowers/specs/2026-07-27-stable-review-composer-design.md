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
удаляются `annotationPlacement()`, `draftAnchor`, измерения размера редактора и
offset-логика, применявшаяся только к привязанному редактору. Bounds выделения
по-прежнему используются `SelectionFlyout`, а семантические данные выделения
остаются в `ReviewDraft.selection`.

## Контейнеры и IME

### Телефон

`ModalBottomSheet` остаётся Material 3 bottom sheet с тегом
`inline-annotation-phone-sheet`. Его content-контейнер:

- заполняет доступную ширину;
- учитывает `WindowInsetsRulers.Ime` ровно один раз;
- допускает вертикальную прокрутку при недостаточной высоте;
- сохраняет доступность input и кнопок над клавиатурой.

### Планшет

`Dialog` остаётся с `usePlatformDefaultWidth = false` и получает полноразмерный
прозрачный root. Поскольку это full-screen dialog в edge-to-edge приложении,
`DialogProperties.decorFitsSystemWindows` устанавливается в `false`.

Root:

- заполняет окно;
- ограничивается safe drawing area и `WindowInsetsRulers.Ime`;
- выравнивает карточку по центру полученной видимой области;
- не использует anchor выделения;
- сохраняет максимальную ширину карточки `420.dp`;
- допускает вертикальную прокрутку содержимого при недостаточной высоте.

Таким образом, при открытии клавиатуры центр пересчитывается относительно
области над IME, а не полного физического экрана.

В `MainActivity` через manifest устанавливается
`android:windowSoftInputMode="adjustResize"`. Insets применяются только на
уровне container root, чтобы не создать двойной IME padding.

## Состояние текстового поля

`InlineAnnotationComposer` владеет одним локальным `TextFieldValue` для активного
draft. Оно включает текст, cursor selection и IME composition.

Идентичность редакторской сессии состоит из:

- вида draft (`Signal` или `Edit`);
- `recordId`;
- `selection.rawRange.startByte`;
- `selection.rawRange.endByte`.

При появлении новой идентичности локальное значение инициализируется из
`draft.comment` или `draft.after`, а cursor устанавливается в конец текста.
Состояние сохраняется через `rememberSaveable` и `TextFieldValue.Saver`.

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

## Dismiss и действия

Пока controller state догоняет локальное поле, защита от случайного закрытия не
может полагаться только на `ReviewDraftSession.blocksDismissal`.

Эффективное dirty-состояние вычисляется из локального текста:

- новый `Signal` остаётся dirty, поскольку `savedType == null`;
- сохранённый `Signal` dirty, если изменён type или локальный текст не равен
  `savedComment`;
- новый `Edit` остаётся dirty, поскольку `savedAfter == null`;
- сохранённый `Edit` dirty, если локальный текст не равен `savedAfter`.

Back и dismiss через scrim разрешены только для clean draft. Явная кнопка
«Отмена» всегда вызывает существующий `onCancelDraft`. Нажатие «Сохранить»
отправляется в тот же FIFO-channel после уже отправленных text events, поэтому
контроллер сохраняет последнее введённое значение.

При persistence-ошибке локальный текст остаётся видимым. Существующие
`ReviewUiError` и retry не меняются.

## Доступность

- Автоматический focus на input сохраняется.
- Test tags `inline-annotation-composer`, `inline-annotation-input`,
  `inline-annotation-phone-sheet` и `inline-annotation-modal` сохраняются.
- Поле и кнопки доступны при увеличенном font scale и открытой IME.
- Вертикальная прокрутка применяется к содержимому карточки, а не к full-screen
  dialog root.

## Изменяемые файлы

- `app/src/main/AndroidManifest.xml` — `adjustResize` для `MainActivity`.
- `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt` —
  детерминированный выбор PhoneSheet/TabletModal и удаление anchor-размещения
  редактора.
- `app/src/main/java/net/inkyquill/pocketeditor/ui/review/InlineAnnotationComposer.kt`
  — локальный `TextFieldValue`, effective dirty-state, IME-aware контейнеры и
  прокручиваемое содержимое.
- `app/src/main/java/net/inkyquill/pocketeditor/ui/review/SignalComposer.kt` —
  controlled input через `TextFieldValue`.
- `app/src/main/java/net/inkyquill/pocketeditor/ui/review/EditComposer.kt` —
  controlled input через `TextFieldValue`.
- `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt`
  — регрессии контейнера, IME, cursor и delayed parent state.
- Существующие screenshot-сцены review composer запускаются без автоматического
  обновления эталонов; появившиеся diffs сначала предъявляются пользователю.

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

### Ввод

- При parent state, который намеренно не отражает изменения немедленно, серия
  быстрых вводов сохраняет все символы.
- После установки cursor в середину текста следующий символ появляется в
  выбранной позиции.
- Cursor selection не отскакивает после parent recomposition.
- Изменение selection без изменения строки не вызывает
  `onDraftTextChanged`.
- IME composition хранится в локальном `TextFieldValue`.

### Жизненный цикл

- Dirty draft блокирует Back и scrim dismiss даже до обновления controller
  state.
- Явная «Отмена» закрывает draft.
- «Сохранить» получает последний текст.
- Восстановленный draft открывается с сохранённым текстом.
- Изменение размеров окна и поворот не теряют локальное значение.

## Критерии приёмки

- Issue #4 не воспроизводится ни для одной позиции выделения на телефоне или
  планшете.
- Issue #5 не воспроизводится при быстром вводе, перемещении cursor и IME
  composition.
- Modal планшета остаётся центрированным в видимой области над клавиатурой.
- Существующие unit-тесты проходят.
- Обновлённые instrumentation-тесты проходят на phone и tablet emulator.
- Screenshot diffs, если они появились, представлены пользователю до обновления
  эталонов.
