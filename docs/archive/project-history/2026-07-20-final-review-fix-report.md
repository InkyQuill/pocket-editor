# Final review fix report

- Folder import retry retains the failed `FolderListing` and invokes
  `openFolder(selectedPath)`, so retry repeats validation and caching rather
  than only reopening the browser. Back remains the browser's normal route to
  the book list.
- Clean adjacent composers cancel on Back. Dirty drafts still consume Back and
  require explicit Save or Cancel.
- Composer fallback combines window policy with the device's
  `smallestScreenWidthDp`, so a physical tablet remains a tablet in a narrow
  split-screen window and receives `TabletModal` rather than a phone sheet.
- Tablet modal content is centered and constrained to 420 dp. The initial
  selection flyout uses a conservative width estimate until measurement
  arrives, avoiding an initial zero-width offscreen placement.

Verification:

- `./gradlew testDebugUnitTest --tests net.inkyquill.pocketeditor.ui.books.BookLibraryControllerTest`
- `ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.ui.ReviewInteractionTest`
