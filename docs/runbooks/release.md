# Pocket Editor release runbook

Pocket Editor is a personal sideloaded application. A release is valid only
when it is minified, signed by the stable personal key, verified, checksummed,
and exercised by the Yandex E2E runbook. The repository and CI artifacts must
never contain a keystore, password, OAuth token, client secret, manuscript, or
private machine path.

## One-time signing setup

1. Create a long-lived RSA signing key in an encrypted personal secrets
   directory outside the repository. Do not use Android's debug keystore.

   ```bash
   keytool -genkeypair -v -keystore pocket-editor-release.jks \
     -alias pocket-editor -keyalg RSA -keysize 4096 -validity 10000 \
     -dname "CN=Pavel Obruchnikov, O=InkyQuill"
   ```

2. Back up the keystore and its passwords separately. Losing this identity
   prevents in-place upgrades; disclosing it allows unauthorized upgrades.
3. Obtain the SHA-256 signing-certificate fingerprint without copying the
   password into a command line:

   ```bash
   keytool -list -v -keystore pocket-editor-release.jks -alias pocket-editor
   ```

4. Register application ID `net.inkyquill.pocketeditor` and that exact SHA-256
   fingerprint in the Yandex Android OAuth application. Record the public
   client ID in the password manager. There is no client secret in the APK.

## Build inputs

Supply these values from a password manager or protected CI environment. Never
put them in `gradle.properties`, `local.properties`, shell history, or Git.

| Variable | Meaning |
| --- | --- |
| `POCKET_EDITOR_RELEASE_STORE_FILE` | Absolute path to the stable keystore |
| `POCKET_EDITOR_RELEASE_STORE_PASSWORD` | Keystore password |
| `POCKET_EDITOR_RELEASE_KEY_ALIAS` | Stable key alias |
| `POCKET_EDITOR_RELEASE_KEY_PASSWORD` | Key password |
| `YANDEX_CLIENT_ID` | Public Android OAuth client ID |

Gradle signs `release` only when all four signing values are non-empty. With
missing values, it produces `app-release-unsigned.apk`; that file is a local
configuration check, never a distributable release.

## Clean build and verification

From the repository root:

```bash
./gradlew clean test lint connectedDebugAndroidTest assembleRelease
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
sha256sum app/build/outputs/apk/release/app-release.apk > app/build/outputs/apk/release/app-release.apk.sha256
sha256sum --check app/build/outputs/apk/release/app-release.apk.sha256
```

The build must produce `app-release.apk`, not `app-release-unsigned.apk`.
Compare the reported signer SHA-256 with the registered Yandex fingerprint.
Keep the APK and adjacent checksum outside Git in access-controlled storage.

## Install, upgrade, and rollback

1. Retain the currently installed signed APK and its checksum.
2. Verify the new checksum and signature before connecting a device.
3. For a first install, run `adb install app-release.apk`.
4. For the mandatory preservation check, install over the existing app with
   `adb install -r app-release.apk`; do not clear data.
5. Launch offline first. Confirm the cached book, chapter, reading position,
   search, drafts, and review sidecars remain available. Then reconnect and
   confirm the Yandex session and a read-only refresh before permitting sync.
6. Complete every row in `yandex-e2e.md` before retaining the artifact.

Android normally rejects a lower `versionCode`. A safe rollback is a newly
built APK with a higher `versionCode`, the same application ID and signing key,
and previously reviewed source. Uninstall/reinstall deletes app-private cache,
database, drafts, and credentials and is not an in-place rollback. Remote
manifest/review sidecars remain the durable review source.

## CI release environment

The protected `release` GitHub environment requires the four signing secrets,
the OAuth client ID, and base64-encoded keystore secret named
`POCKET_EDITOR_RELEASE_KEYSTORE_BASE64`. A manual workflow run performs all
verification jobs before signing and uploads only the APK and checksum. Missing
secrets deliberately fail the validation step; CI never falls back to debug
signing.

## Secret-safe troubleshooting

- Report only Gradle task names, exception categories, HTTP method/host,
  redacted endpoint category, status code, artifact filename/size, and digest.
- Never paste Gradle environments, request headers/bodies, tokens, queries,
  full Yandex paths, manuscript excerpts, keystore paths, or passwords.
- If signing fails, verify presence (not values) of the five environment
  variables and inspect the keystore interactively with `keytool`.
- If authentication fails, compare application ID and certificate fingerprint;
  do not capture OAuth redirects or SDK logs. Yandex SDK logging stays disabled.
- Delete failed artifacts from shared storage and rotate credentials if any
  secret may have appeared in logs.

## Retention record

For each retained build, record version code/name, UTC build date, Git commit,
signer SHA-256, APK SHA-256, E2E evidence date, and storage location in the
private release inventory. Do not add that inventory to this repository.

## MVP acceptance trace

Automated evidence is necessary but does not replace a real-service or signed
upgrade row. The MVP remains release-blocked while either of those rows is not
PASS.

| Approved acceptance criterion | Evidence | Current status (2026-07-19) |
| --- | --- | --- |
| Authenticate, select multiple roots, stable TOCs | `YandexAuthTest`, `BookDiscoveryTest`, `BookFlowTest`; Yandex E2E 1–2 | Automated PASS; E2E BLOCKED |
| Every configured book works offline | `SyncEngineTest`, `BookFlowTest`, `SourceSearchRoomTest`; E2E 3–4 | Automated PASS; E2E NOT RUN |
| Clean/review reading, search, responsive layouts | `ReviewProjectorTest`, `ReviewInteractionTest`, `AdaptiveReaderTest`, `SearchNavigationTest` | PASS |
| Chapter note, four signals, comments, edits round-trip | `ReviewJsonTest`, `ReaderRepositoryTest`, `ReviewInteractionTest` | PASS |
| Drafts survive taps, Back, rotation, process death | `ReviewDraftStateMachineTest`, `ReviewDraftStoreTest`, `ReviewDraftRoomTest`, `ReviewInteractionTest`; E2E 5 | Automated PASS; E2E NOT RUN |
| External source/review changes reconcile safely | `ReviewMergeTest`, `SyncEngineTest`; E2E 6–9 | Automated PASS; E2E NOT RUN |
| Stale/ambiguous anchors are visible and never guessed | `AnchorTest`, `ReviewProjectorTest`, `SyncEngineTest`; E2E 9 | Automated PASS; E2E NOT RUN |
| Deleting Room loses no durable review | `PocketEditorDatabaseTest`, `PocketEditorMigrationTest`, `SyncSourceIndexRoomTest` | PASS |
| Only manifest/review plus transient lock are written | `AtomicBookStoreTest`, `YandexDiskGatewayTest`, `SyncEngineTest`; E2E 10 | Automated PASS; E2E NOT RUN |
| Automated suites and Yandex E2E pass | Full Gradle gate; Yandex E2E 1–11 | Automated PASS; E2E BLOCKED |
| Signed APK upgrades and authenticates | Release signature/checksum gate; Yandex E2E 11 | BLOCKED: stable signing/OAuth inputs and signed APK pair unavailable |
