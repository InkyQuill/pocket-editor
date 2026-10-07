# Pocket Editor logo

Approved mark: a manuscript in a pocket shaped like an open book.

- `pocket-editor-logo.svg`: two-color master, five vector contours, transparent background.
- `pocket-editor-logo-mono.svg`: the same mark with `currentColor` fill.
- `vectorize-source.svg`: original Vectorize tracing, retained for provenance.
- `android-icon-preview.svg` / `.png`: square, circular, and example themed presentations.

Palette: ink `#113356`, terracotta `#C16046`, Android background `#F7F3EC`.

Android resources are generated from the color master with
`python scripts/generate-launcher-icons.py` (requires Inkscape CLI; no GUI).
The manifest already references `ic_launcher` and `ic_launcher_round`.
Both names have adaptive resources from API 26, an explicit monochrome layer
used on API 33+, and PNG fallbacks at five densities. Android chooses the mask and themed
colors; the dark themed preview is illustrative.

The 108 dp layers use a centered logo approximately 52 × 59 dp inside the safe
region. See [Android adaptive icon guidance](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).
