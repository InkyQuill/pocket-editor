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

The stable local keystore is `~/.keys/pocket-editor-release.jks`. The password
values may originate in the local, gitignored `.env`, but they must be copied
manually to the protected GitHub environment; do not commit them, print them,
or automate their upload from a shell command or repository script.

## Conventional Commits and Release Please

One-time prerequisite: in the repository, enable Settings → Actions → General → “Allow GitHub Actions to create and approve pull requests”. Release Please
uses the workflow `GITHUB_TOKEN`; without this repository setting it cannot
create or update the Release PR. Do not add a personal access token as a
workaround.

A GITHUB_TOKEN-created Release PR does not trigger pull_request workflows.
Review that PR manually before merging it. If branch protection requires a
pull-request-triggered check on every PR head, the repository needs an approved
policy exception or a separately reviewed event design; this workflow does not
weaken branch protection or introduce another secret. The merge still produces
a push to `main`, where verification and emulator jobs gate release creation.

`.github/workflows/android.yml` verifies pull-request titles against that
Conventional Commit rule. Supported types are `feat`, `fix`, `perf`, `refactor`,
`docs`, `test`, `build`, `ci`, `chore`, and `revert`; an optional scope and
breaking change marker (`!`) are allowed. For example: `feat(reader): remember
scroll position`.

On pull requests and pushes, the verify job runs `./gradlew test lint
assembleDebug assembleRelease`; this is an unsigned CI verification build. The
separate emulator job runs `connectedDebugAndroidTest`. On pushes to `main`,
after both jobs pass, `googleapis/release-please-action` `v4.4.1` uses the root
`simple` configuration, `.release-please-manifest.json`, `version.txt`, and
`CHANGELOG.md` to open or update the release PR. When that PR is merged and
Release Please creates a GitHub Release, the signed job checks out the exact
Release Please SHA/tag and attaches the signed assets. A manual workflow run is
verification-only and never publishes a release.

`version.txt` is the local default version name and must be a Semantic Version.
For a release build, CI injects the Release Please version as
`POCKET_EDITOR_VERSION_NAME` and the validated positive GitHub run number as
`POCKET_EDITOR_VERSION_CODE`. The latter is monotonic for Android upgrades;
local builds retain version code `1` unless explicitly overridden.

## Clean build and verification

For a local signed build, configure the signing inputs above and run this from
the repository root (unlike the unsigned CI verification build):

```bash
./gradlew clean test lint connectedDebugAndroidTest assembleRelease
SDK_ROOT=${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Android SDK location is required}}
mapfile -t APKSIGNERS < <(find "$SDK_ROOT/build-tools" -mindepth 2 -maxdepth 2 -type f -name apksigner -print | sort -V)
test "${#APKSIGNERS[@]}" -gt 0
APKSIGNER=${APKSIGNERS[-1]}
test -x "$APKSIGNER"
(
  cd app/build/outputs/apk/release
  "$APKSIGNER" verify --verbose --print-certs app-release.apk
  sha256sum app-release.apk > app-release.apk.sha256
  sha256sum --check app-release.apk.sha256
)
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
6. Complete the real-service steps in [the Yandex E2E runbook](yandex-e2e.md)
   before retaining the artifact.

Android normally rejects a lower `versionCode`. A safe rollback is a newly
built APK with a higher `versionCode`, the same application ID and signing key,
and previously reviewed source. Uninstall/reinstall deletes app-private cache,
database, drafts, and credentials and is not an in-place rollback. Remote
manifest/review sidecars remain the durable review source.

## CI release environment

Create a protected GitHub environment named `release`, then enter these exact
secret names manually in the GitHub UI:

| Secret | Source | Purpose |
| --- | --- | --- |
| `POCKET_EDITOR_RELEASE_KEYSTORE_BASE64` | Base64 encoding of `~/.keys/pocket-editor-release.jks` | CI-only decoded signing keystore |
| `POCKET_EDITOR_RELEASE_STORE_PASSWORD` | Local password manager or ignored `.env` | Keystore password |
| `POCKET_EDITOR_RELEASE_KEY_ALIAS` | Local password manager or ignored `.env` | Stable signing alias |
| `POCKET_EDITOR_RELEASE_KEY_PASSWORD` | Local password manager or ignored `.env` | Key password |
| `YANDEX_CLIENT_ID` | Local password manager or ignored `.env` | Android OAuth client ID |

The signed job uses the protected `release` environment. It checks out the
exact Release Please SHA/tag, validates the tag, SHA, Semantic Version, and
positive Android-safe version code, then injects
`POCKET_EDITOR_VERSION_NAME` and `POCKET_EDITOR_VERSION_CODE`. CI decodes the
keystore through standard input only under the runner temporary directory,
restricts it to mode `0600`, and removes it in an `always()` cleanup step.
Missing inputs deliberately fail the signing step; CI never falls back to debug
signing and never uploads an unsigned APK.

The release job runs `apksigner verify --verbose --print-certs`, creates and
checks `app-release.apk.sha256`, and uses `gh release upload ... --clobber` for
an idempotent GitHub Release upload. The two assets are attached only to the
exact Release Please tag: the signed APK and its SHA-256 checksum. Verification
requirements are maintained in [testing.md](../testing.md).

## Secret-safe troubleshooting

- Report only Gradle task names, exception categories, HTTP method/host,
  redacted endpoint category, status code, artifact filename/size, and digest.
- Never paste Gradle environments, request headers/bodies, tokens, queries,
  full Yandex paths, manuscript excerpts, keystore paths, or passwords.
- If signing fails, verify presence (not values) of the five environment
  variables, the release tag/SHA, and inspect the keystore interactively with
  `keytool`.
- If a release upload fails, confirm that Release Please reported a created
  release, the checked-out tag resolves to its reported SHA, and the GitHub Release
  exists. Re-running the same job safely replaces only the two named assets.
- If Gradle rejects a version override, use an unpadded positive integer no
  higher than Android's safe version-code range and a valid Semantic Version.
- If authentication fails, compare application ID and certificate fingerprint;
  do not capture OAuth redirects or SDK logs. Yandex SDK logging stays disabled.
- Delete failed artifacts from shared storage and rotate credentials if any
  secret may have appeared in logs.

## Retention record

For each retained build, record version code/name, UTC build date, Git commit,
signer SHA-256, APK SHA-256, E2E evidence date, and storage location in the
private release inventory. Do not add that inventory to this repository.
