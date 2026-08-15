# Pocket Editor handoff

Updated: 2026-07-20

## Continue from here

Clone `https://github.com/InkyQuill/pocket-editor.git`, then check out
`feat/pocket-editor-mvp`. Remote `main` and that feature branch point to the
same complete MVP commit at this handoff, so a normal clone is also usable
immediately.

The approved product specification is in
`docs/superpowers/specs/2026-07-18-pocket-editor-design.md`. The current polish
specification and plan are:

- `docs/superpowers/specs/2026-07-19-reader-typography-search-design.md`
- `docs/superpowers/plans/2026-07-19-reader-typography-search.md`

Do not edit canonical Markdown. Pocket Editor writes only its manifest,
`*.review.json` sidecars, and the transient cooperative lock.

## Current implementation

- Direct Yandex Disk OAuth and folder selection work in a signed release APK.
- Books contain Markdown chapter files directly in the selected folder; no
  `_index.md` or project-specific structure is required.
- Canonical chapters are cached for offline reading and remain read-only.
- Chapter notes, anchored passage signals, non-overlapping edits, local drafts,
  merge/conflict handling, and sync locking are implemented.
- Review off renders clean canonical text. Review on shows the complete
  editorial overlay.
- Portrait uses modal Contents and Review panels; the wide layout uses
  collapsible sidebars.
- Literata (book prose only) and Manrope (application chrome) are bundled with
  their OFL licenses. Markdown prose typography covers headings H1-H6,
  paragraphs, emphasis, strong text, links, block quotes, lists, and thematic
  breaks. Tables and fenced code are deliberately out of MVP scope.
- Offline source search highlights the exact query substring in each excerpt
  and highlights/positions the destination in the reader.
- The Android action bar is disabled; app-owned chrome is used throughout.

## Verification completed

The following passed on 2026-07-20 from the repository root:

```bash
./gradlew lintDebug testDebugUnitTest assembleDebug
ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest
./gradlew assembleRelease
```

Instrumentation result: 101 executions, 0 failures; four screenshot tests were
skipped by their intentional capture flag. The minified release APK was also
checked to contain all six bundled font files and both OFL license assets.

The installed release certificate SHA-256 was verified as:

```text
7C:BA:A7:A0:DF:1B:29:85:69:4D:BF:98:61:5A:C8:76:E1:5B:9F:A0:37:8D:EB:D6:66:22:EF:81:58:48:09:A6
```

Signing alias: `pocket-editor`.

## Real Yandex E2E state

The signed release APK was installed on `emulator-5554`. Authentication,
folder import, two-chapter cache, and initial sync passed against the disposable
Yandex folder `PocketEditor-E2E-2026-07-19`.

Canonical fixture hashes before testing:

```text
a2666354aeafdbd2582ee134332fe784a0cbff5d4e2abef188712629b4e8c5c2  01-arrival.md
1cea330b9447847e2ea13f5c3e80192033fcfdac651d4b94de9364e8853f9d6c  02-return.md
```

With airplane mode enabled and Wi-Fi disabled, the following passed:

- cached chapter reading and status `Waiting to sync`;
- offline search for `quiet`, including exact excerpt highlighting and reader
  destination highlighting;
- chapter note `Offline chapter note draft` persisted after force-stop;
- a blue passage note `Offline line note draft` persisted after force-stop;
- signal color was changed Warning -> Note before saving;
- tapping outside the active comment field did not dismiss the composer;
- Review off showed clean canonical text; Review on restored the saved note,
  blue highlight, and inline comment block.

The emulator-only state is not transferable to another computer. Continue the
runbook from `docs/runbooks/yandex-e2e.md`; repeat the fixture import on a work
device, then finish these gates:

1. Exercise all four signal colors, a signal without a comment, a
   non-overlapping edit, and an unsaved draft restored after process death.
2. Reconnect, run Sync now, and verify the remote review sidecar contains the
   saved chapter and passage notes while both canonical Markdown hashes remain
   unchanged.
3. Exercise external review/source changes, merge conflicts, stale and
   ambiguous anchors, and the two-client lock race.
4. Audit that canonical Markdown upload count is zero and writes are limited
   to manifest/review/lock files.
5. Perform the signed in-place upgrade test on the Samsung device with
   `adb install -r`.

## Local setup on the work computer

Use JDK 17 and an Android SDK compatible with the checked-in Gradle project.
Create `local.properties` locally if Android Studio does not create it.

Supply signing and OAuth values through the environment only:

```bash
export POCKET_EDITOR_RELEASE_STORE_FILE=/absolute/path/to/pocket-editor-release.jks
export POCKET_EDITOR_RELEASE_STORE_PASSWORD='...'
export POCKET_EDITOR_RELEASE_KEY_ALIAS=pocket-editor
export POCKET_EDITOR_RELEASE_KEY_PASSWORD='...'
export YANDEX_CLIENT_ID='...'
```

Do not commit `.env`, `local.properties`, a JKS/keystore, passwords, tokens, or
generated APKs. The ignore rules already exclude the expected local files.
There is no Yandex client secret in the Android APK.

For a fresh validation run:

```bash
./gradlew lintDebug testDebugUnitTest assembleDebug
./gradlew connectedDebugAndroidTest
./gradlew assembleRelease
```

Then follow `docs/runbooks/release.md` for signature verification, checksum,
installation, and the E2E acceptance gate.
