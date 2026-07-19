# Pocket Editor JSON Schemas

These versioned [JSON Schema draft 2020-12](https://json-schema.org/draft/2020-12)
documents are the machine-readable interchange contracts for files stored beside
a book on Yandex Disk:

- [`manifest-v1.schema.json`](manifest-v1.schema.json) validates
  `.pocket-editor.json`.
- [`review-v1.schema.json`](review-v1.schema.json) validates
  `<chapter>.review.json`.

Each published schema has a stable, absolute `$id`. A `v1` schema is immutable;
an incompatible document change requires a new schema file and `$id`. Objects
are closed with `additionalProperties: false`, and `schema_version` is fixed to
the matching integer so agents cannot silently write a newer shape as v1.

The schemas express all portable structural constraints implemented by the
Kotlin serializers. Kotlin validation remains authoritative for relational
rules JSON Schema cannot express directly, including unique chapter IDs and
paths, chapter/ignored-path disjointness, anchor range ordering, distinct record
IDs across arrays, differing edit text, and non-overlapping edit ranges.

The deterministic fixtures and their Kotlin canonical re-encodings are validated
against these exact files by `DocumentJsonSchemaTest`:

```sh
./gradlew testDebugUnitTest --tests '*DocumentJsonSchemaTest'
```

See the [approved design specification](../docs/superpowers/specs/2026-07-18-pocket-editor-design.md#durable-file-layout)
for the complete durable-file semantics.
