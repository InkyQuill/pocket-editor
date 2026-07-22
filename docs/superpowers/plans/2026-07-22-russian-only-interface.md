# Russian-Only Interface Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Перевести весь пользовательский интерфейс Pocket Editor на русский язык, сохранив английское название продукта, и подтвердить отсутствие тестовых сбоев на Android-эмуляторе.

**Architecture:** Русский текст становится единственным набором Android string/plural resources и используется Compose-слоем через `stringResource`/`pluralStringResource`. Сериализуемые значения, содержимое книг и технические контракты не меняются; пользовательские сообщения platform-independent контроллеров переводятся без зависимости от Android. Instrumentation-тесты проверяют русский интерфейс и запускаются адресно на `emulator-5554`.

**Tech Stack:** Kotlin 2.3, Jetpack Compose Material 3, Android resources, JUnit 5, AndroidX Compose UI Test, Gradle 9.

## Global Constraints

- `Pocket Editor` всегда остаётся на английском.
- В пользовательском тексте используются «Яндекс Диск» и `Markdown`.
- Интерфейс русский при любой системной локали; отдельного английского resource set нет.
- Не меняются JSON-ключи, `@SerialName`, пути, имена файлов и содержимое книг.
- Все instrumentation-команды ограничиваются `ANDROID_SERIAL=emulator-5554`.
- Не изменять посторонние untracked-файлы `.agents/`, `.claude/` и документы от 2026-07-21.

---

### Task 1: Красная проверка и фундамент ресурсов

**Files:**
- Create: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/BooksScreen.kt`
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt`

**Interfaces:**
- Consumes: `BooksScreen(...)` и Compose semantics.
- Produces: `@string/app_name`, `@string/books_title` и resource-паттерн для следующих задач.

- [ ] **Step 1: Написать падающий тест русской книжной полки**

```kotlin
@Test
fun bookshelfUsesRussianInterfaceText() {
    compose.setContent {
        PocketEditorTheme(darkTheme = true) {
            BooksScreen(
                books = BOOKS, signedIn = true, signingIn = false, forgetBookId = null,
                onSignIn = {}, onAddBook = {}, onOpenBook = {}, onRequestForget = {},
                onConfirmForget = {}, onCancelForget = {}, onAppearance = {},
            )
        }
    }
    compose.onNodeWithText("Книги").assertIsDisplayed()
    compose.onAllNodesWithText("Books").assertCountEquals(0)
}
```

- [ ] **Step 2: Убедиться в корректном RED**

Run:

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.BookFlowTest#bookshelfUsesRussianInterfaceText
```

Expected: FAIL, потому что узла `Книги` ещё нет, а `Books` присутствует.

- [ ] **Step 3: Добавить единственный русский resource set**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Pocket Editor</string>
    <string name="books_title">Книги</string>
</resources>
```

В manifest использовать `android:label="@string/app_name"`; в `BooksScreen`
заменить `Text("Books")` на `Text(stringResource(R.string.books_title))`.

- [ ] **Step 4: Подтвердить GREEN и закоммитить**

Повторить Step 2. Expected: PASS. Затем:

```bash
git add app/src/main/res/values/strings.xml app/src/main/AndroidManifest.xml \
  app/src/main/java/net/inkyquill/pocketeditor/ui/books/BooksScreen.kt \
  app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt
git commit -m "feat: establish Russian interface resources"
```

### Task 2: Книжная полка, вход и импорт

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/BooksScreen.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/FolderBrowserScreen.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/ImportConfirmationScreen.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/BookLibraryController.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/books/RoomYandexBookLibraryData.kt`
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt`
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowScreenshotTest.kt`

**Interfaces:**
- Consumes: ресурсы Task 1 и пользовательские названия книг/глав.
- Produces: русский flow входа, полки, выбора папки, импорта и восстановления.

- [ ] **Step 1: Перевести UI expectations и получить RED**

Использовать ожидания `Не удалось войти: OAuth unavailable`, `Повторить вход`,
`Выйти из Яндекс Диска`, `Выберите папку с книгой`, `Проверьте книгу`.
Содержимое фикстур `Alchemy of Rain`, названия глав и OAuth payload не переводить.
Запустить `BookFlowTest`; Expected: FAIL на первых русских ожиданиях.

- [ ] **Step 2: Перенести Compose-текст в ресурсы**

Добавить ресурсы заголовков, действий, ошибок, загрузки, подтверждений и semantics.
Динамический текст оформить format strings:

```xml
<string name="sign_in_error">Не удалось войти: %1$s</string>
<string name="forget_book_title">Забыть книгу «%1$s»?</string>
<string name="chapter_title_label">Название главы %1$d</string>
<string name="include_chapter">Добавить главу «%1$s»</string>
```

Строку для semantics вычислять до блока: `val description = stringResource(...)`,
затем `Modifier.semantics { contentDescription = description }`.

- [ ] **Step 3: Перевести сообщения контроллеров, доходящие до UI**

Перевести validation/fallback strings: отсутствие Markdown-глав, пустое название,
невыбранные главы, недоступный manifest/cache и `Something went wrong`.
Технические ключи, journal entries, protocol values и JSON не менять.

- [ ] **Step 4: Проверить и закоммитить книжный flow**

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.BookFlowTest
git add app/src/main/res/values/strings.xml app/src/main/java/net/inkyquill/pocketeditor/ui/books \
  app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt \
  app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowScreenshotTest.kt
git commit -m "feat: translate book library and import flows"
```

Expected: все тесты класса PASS, включая два layout-теста из CI-лога.

### Task 3: Оглавление, обновления, поиск и настройки

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/contents/ContentsPanel.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/contents/DiscoveryPanel.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/search/SearchScreen.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/settings/AppearanceScreen.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/navigation/PocketEditorRoot.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderViewModel.kt`
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/BookFlowTest.kt`

**Interfaces:**
- Consumes: выбранные пользователем названия и результаты поиска.
- Produces: русский текст вторичных экранов без перевода подстановок.

- [ ] **Step 1: Добавить ожидания и получить RED**

Проверять `Оглавление`, `Главы`, `Обновления книги`, `Искать в этой книге`,
`Оформление`, `Размер текста`, `Открываем библиотеку`. Sample sentence заменить
русским предложением с сохранением проверки изменения font size.

- [ ] **Step 2: Реализовать ресурсы и plurals**

```xml
<plurals name="book_updates_count">
    <item quantity="one">Проверить %d обновление книги</item>
    <item quantity="few">Проверить %d обновления книги</item>
    <item quantity="many">Проверить %d обновлений книги</item>
    <item quantity="other">Проверить %d обновления книги</item>
</plurals>
<string name="search_match_description">Совпадение: %1$s</string>
<string name="appearance_scale">%1$d%% · системный размер шрифта Android тоже учитывается</string>
```

- [ ] **Step 3: Проверить и закоммитить**

Запустить адресные methods `BookFlowTest` для contents/discovery/search/appearance;
Expected: PASS. Затем закоммитить перечисленные production/test files сообщением
`feat: translate contents search and appearance`.

### Task 4: Читалка и adaptive-панели

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/AdaptiveReaderScaffold.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderDocument.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/reader/ReaderScreen.kt`
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/AdaptiveReaderTest.kt`
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReaderScreenshotTest.kt`

**Interfaces:**
- Consumes: `ReaderState`, `ReaderSyncState`, canonical book text и review overlay.
- Produces: русский chrome/semantics; canonical prose остаётся неизменной.

- [ ] **Step 1: Перевести expectations и получить RED**

Перевести интерфейсные проверки: `Открыть оглавление`,
`Режим рецензирования выключен`, `Сохранено`, `Главы`,
`Полный редакторский слой`. Строки книги `The City of Brass`, `Base text`,
`review overlay` и chapter-note fixtures оставить как данные.

- [ ] **Step 2: Реализовать reader resources и mappings**

Добавить format strings для удалённого/добавленного текста, search result,
edit/delete, re-anchor и sync states. Review count оформить `plurals`. Не строить
русские подписи через `enum.name.lowercase()`; добавить явный UI mapping для
`NOTE`, `CHANGE_REQUIRED`, `WARNING`, `REVIEW` и edit record.

- [ ] **Step 3: Проверить три CI transition failures**

Адресно запустить:

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.AdaptiveReaderTest
```

Expected: 32/32 PASS, включая три переданных transition tests. При повторе
сначала сохранить XML/stacktrace и состояние layout mode; production-код менять
только после доказанной первопричины. Baseline на `380040b`: 0 failures.

- [ ] **Step 4: Закоммитить читалку**

Закоммитить ресурсы, три reader files и два test files сообщением
`feat: translate reader and adaptive controls`.

### Task 5: Редакторские инструменты и конфликты

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/ChapterNote.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/ConflictCardMapper.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/ConflictResolver.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/EditComposer.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/EditorialReviewController.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/InlineAnnotationComposer.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/ReviewDraft.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/ReviewDraftStore.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/SelectionFlyout.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/SignalComposer.kt`
- Modify: `app/src/main/java/net/inkyquill/pocketeditor/ui/review/SignalSemantics.kt`
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewInteractionTest.kt`
- Modify: `app/src/androidTest/java/net/inkyquill/pocketeditor/ui/ReviewScreenshotTest.kt`
- Modify: `app/src/test/java/net/inkyquill/pocketeditor/ui/review/ConflictCardMapperTest.kt`

**Interfaces:**
- Consumes: stable `SignalType`, `ConflictChoice` и review JSON.
- Produces: русские подписи без изменения сериализации.

- [ ] **Step 1: Перевести expectations и получить RED**

Проверять `Добавить заметку`, `Комментарий к сигналу, необязательно`, `Сохранить`,
`Заметка к главе`, `Оставить мою версию`, `Взять версию с Яндекс Диска`.
Draft fixtures `Restored draft` и `Original comment` не переводить.

- [ ] **Step 2: Реализовать resource mapping**

Compose-компоненты получают label/help из ресурсов. `SignalType` и
`ConflictChoice` не меняются. UI previews `Deleted`, `Empty chapter note`,
`Deleted text`, validation и discarded-draft error переводятся на границе UI;
draft JSON остаётся byte-compatible.

- [ ] **Step 3: Проверить и закоммитить**

Запустить весь `ReviewInteractionTest`; Expected: 27/27 PASS, включая два
layout-теста из CI. Затем запустить `ConflictCardMapperTest` и закоммитить
перечисленные files сообщением `feat: translate editorial review tools`.

### Task 6: Аудит полноты и все известные регрессии

**Files:**
- Modify if audit requires: `app/src/main/java/net/inkyquill/pocketeditor/ui/**/*.kt`
- Modify if audit requires: related files under `app/src/androidTest` and `app/src/test`

**Interfaces:**
- Consumes: Tasks 1–5.
- Produces: отсутствие случайно оставшегося английского UI.

- [ ] **Step 1: Выполнить статический аудит литералов**

```bash
rg -n 'Text\(\s*"|contentDescription\s*=\s*"|paneTitle\s*=\s*"|label\s*=\s*"' \
  app/src/main/java/net/inkyquill/pocketeditor/ui --glob '*.kt'
```

Каждый результат классифицировать как test tag/technical key, символ `+`/`−`,
пользовательские данные или пропуск. Каждый пропуск перенести в resources.

- [ ] **Step 2: Проверить lint и JVM tests**

Run: `./gradlew lintDebug testDebugUnitTest`.
Expected: BUILD SUCCESSFUL без `HardcodedText` и resource errors.

- [ ] **Step 3: Повторить три UI test classes**

На `emulator-5554` полностью запустить `AdaptiveReaderTest`, `BookFlowTest` и
`ReviewInteractionTest`. Expected: 0 failures. Переданный CI-лог отражает более
раннее состояние; текущий `origin/main` содержит regression fixes, а baseline
до русификации дал 137 tests, 0 failures, 5 skipped. Цель — доказать, что
русификация не вернула семь симптомов.

- [ ] **Step 4: Закоммитить только найденные пропуски**

Если audit потребовал изменений: `git diff --cached --check`, затем commit
`test: complete Russian interface coverage`. Без изменений коммит пропустить.

### Task 7: Финальная проверка

**Files:**
- Verify only: repository and generated reports.

**Interfaces:**
- Consumes: готовую реализацию.
- Produces: свежие доказательства сборки и тестов.

- [ ] **Step 1: Полная JVM, lint и debug-сборка**

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Полная instrumentation-проверка**

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
```

Expected: 137 tests, 0 failures; существующие screenshot/minified fixture skips допустимы.

- [ ] **Step 3: Проверить diff и рабочее дерево**

```bash
git diff --check
git status --short
git log --oneline -8
```

Посторонние untracked-файлы должны остаться нетронутыми.

- [ ] **Step 4: Передать результат**

Сообщить переведённые области, точные результаты unit/lint/build и instrumentation,
а также отдельно подтвердить проверку семи CI-симптомов на текущей ревизии.
