# Разработка Pocket Editor

## Требования

Используйте JDK 17 и Gradle wrapper. Android-конфигурация: `compileSdk 37`, `targetSdk 36`, `minSdk 26`. Локальная версия — `0.1.0` из `version.txt`.

## Структура проекта

- `app/src/main` — код Android-приложения и ресурсы.
- `app/src/test` — JVM-тесты.
- `app/src/androidTest` — инструментальные тесты.
- `app/schemas` — экспортируемые Room-схемы.
- `schemas` — публичные JSON-схемы документов.
- `.github/workflows` — проверка, релиз и политика CI.

## Локальная конфигурация

`local.properties` указывает путь к Android SDK. Файл `.env` игнорируется Git и может содержать публичный мобильный `YANDEX_CLIENT_ID`. Для release-сборки также допустим Gradle property или переменная окружения `YANDEX_CLIENT_ID`.

Подписывающие секреты и keystore остаются вне Git. Не помещайте в документацию значения из `.env` или `~/.keys`, не печатайте их в shell history и не добавляйте в fixtures.

## Сборка и быстрые проверки

```bash
./gradlew test
./gradlew lint
./gradlew assembleDebug
./gradlew compileDebugAndroidTestKotlin
./gradlew connectedDebugAndroidTest
./gradlew assembleRelease
```

`test` запускает JVM-проверки; `lint` проверяет статические Android-правила; `assembleDebug` создаёт debug APK; `compileDebugAndroidTestKotlin` компилирует инструментальные тесты; `connectedDebugAndroidTest` выполняет их на подключённом устройстве/AVD; `assembleRelease` собирает минимизированный release APK и требует настроенный `YANDEX_CLIENT_ID`.

## Room и схемы данных

При изменении базы экспортируйте Room-схему в `app/schemas` и добавьте миграционные тесты. Не меняйте существующие схемы задним числом: новая версия и миграция должны проверяться на реалистичных старых данных.

## Правила изменений

Канонический Markdown не изменяется приложением. Не добавляйте в репозиторий частные книги, токены, полные пути или данные реальных пользователей. Для write-тестов используйте одноразовые удалённые fixtures; `aria` доступна только для чтения. Изменения хранилища и синхронизации требуют unit- и connected-проверок, а публичные форматы — схем и совместимости.

## Conventional Commits и pull request

Pull request в `main` должен иметь заголовок Conventional Commits и непустое описание. Допустимые типы и release-flow описаны в [runbook релиза](runbooks/release.md); перед отправкой выполните релевантный локальный набор.

## Известные ограничения

Есть оставшийся backlog по сокращению Android lint-предупреждений. В точечных
изменениях устраняйте относящиеся к ним предупреждения, не смешивая эту работу с
обновлениями зависимостей. Не фиксируйте численный счётчик: отчёт генерируется и
может устареть.

## Связанные документы

Смотрите [проверку](testing.md), [архитектуру](architecture.md), [руководство пользователя](user-guide.md), [схемы](../schemas/README.md) и [runbook Yandex E2E](runbooks/yandex-e2e.md).
