# Pocket Editor

Pocket Editor — local-first Android-читалка и редакторский слой для Markdown-книг на Яндекс Диске. Приложение открывает локально закэшированную книгу, а канонические исходники Markdown остаются в удалённой папке неизменными.

## English overview

Pocket Editor is a local-first Android reader and editorial overlay for Markdown books stored on Yandex Disk. It caches books for offline reading while keeping canonical Markdown read-only. The app writes only its manifest, review sidecars, and a transient cooperative lock.

## Возможности

- После выбора обычной папки Markdown порядок глав определяется детерминированно: по нормализованному пути. Позже его можно изменить только отдельным действием «Содержание».
- Первые три главы доступны для чтения, пока остальные последовательно загружаются в фоне; компактная карточка показывает прогресс.
- Есть содержание, поиск по исходному тексту, настройка оформления и режимы чистого чтения и рецензирования.
- Рецензирование хранит сигналы, комментарии, правки и заметки отдельно от исходного текста.

## Приватность и данные

Книга и рабочие данные хранятся локально в приватном хранилище приложения. Канонический Markdown никогда не меняется: приложение записывает только манифест, sidecar-файлы рецензий и временную кооперативную блокировку. Закэшированные книги читаются без сети; «Удалить с устройства» удаляет только локальную копию и не затрагивает данные на Яндекс Диске. У приложения нет собственной серверной части, аналитики или телеметрии.

## Требования

Минимальная версия — Android 8.0 (API 26). Установка выполняется вручную из GitHub Releases: приложение распространяется только sideload-способом и не публикуется в Google Play.

## Установка

Скачайте APK и соседний SHA-256 checksum из GitHub Releases, проверьте контрольную сумму и установите APK. Для обновления поверх существующей установки используйте APK, подписанный тем же ключом; подробности — в [runbook релиза](docs/runbooks/release.md).

## Первый запуск

1. Войдите через Яндекс.
2. Выберите папку с Markdown-книгой.
3. Откройте первые закэшированные главы.
4. Оставьте приложение завершать фоновую последовательную загрузку.

## Разработка и проверка

Компактный локальный gate:

```bash
./gradlew test lint assembleDebug compileDebugAndroidTestKotlin
```

Полная настройка и команды описаны в [разработке](docs/development.md) и [проверке](docs/testing.md).

## Релизы и CI

Заголовки pull request используют Conventional Commits. Release Please создаёт release PR и теги; после выпуска CI прикладывает подписанный APK и его SHA-256 checksum. Полный процесс — в [runbook релиза](docs/runbooks/release.md).

## Документация

- [Руководство пользователя](docs/user-guide.md)
- [Архитектура](docs/architecture.md)
- [Разработка](docs/development.md)
- [Проверка](docs/testing.md)
- [ADR: local-first overlay reader](docs/adr/0001-local-first-overlay-reader.md)
- [Runbook релиза](docs/runbooks/release.md)
- [Yandex Disk E2E](docs/runbooks/yandex-e2e.md)
- [Схемы документов](schemas/README.md)

## Лицензия

Проект распространяется по [лицензии MIT](LICENSE), © 2026 Pavel Obruchnikov.
