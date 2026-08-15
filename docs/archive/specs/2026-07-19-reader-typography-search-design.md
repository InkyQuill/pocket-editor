# Reader Typography and Search Highlight Design

**Date:** 2026-07-19  
**Status:** Approved

## Purpose

Correct the typography observed during real-device E2E without expanding Pocket
Editor into a configurable ebook engine. The change must make Russian prose
comfortable to read, restore hierarchy to application chrome, preserve Markdown
semantics, and make search matches visible immediately.

## Fonts and packaging

- Bundle Literata Regular, Italic, SemiBold, and Bold for rendered book content.
- Bundle the Manrope weights used by application chrome.
- Include both SIL OFL notices in the application distribution.
- Do not use downloadable fonts or depend on device font availability.
- Verify the embedded font family and style metadata in tests or build checks;
  resource filenames alone are not evidence of the bundled typeface.

Literata was chosen after a like-for-like Russian Cyrillic specimen against Lora
and Vollkorn in light and dark themes. Literata was judged softer and easier to
read in both continuous prose and the application-scale screen comparison.

## Typography ownership

Manrope owns all application chrome: top bars, status text, buttons, fields,
Contents, Review, Books, Appearance, and search controls. Literata owns rendered
Markdown prose and prose excerpts in search results.

The in-app decrease/reset/increase control changes only Literata reader styles.
Android accessibility font scaling continues to affect both families and must
remain usable at supported accessibility scales.

## Reader scale

| Markdown | Weight | Size / line height | Top / bottom spacing |
| --- | --- | --- | --- |
| H1 | SemiBold | `28/35sp` | `24/10dp` |
| H2 | SemiBold | `23/30sp` | `22/8dp` |
| H3 | SemiBold | `19/26sp` | `18/6dp` |
| H4 | SemiBold | `17/24sp` | `16/4dp` |
| H5 | Bold | `16/23sp` | `14/4dp` |
| H6 | Bold | `14/21sp` | `12/4dp` |
| Paragraph, list, blockquote | Regular | `16/25sp` | `0/12dp` |

Emphasis uses Italic and strong uses Bold while inheriting the surrounding size
and line height. Heading letter case is never transformed because displayed text
must remain faithful to source and its anchor mapping.

## Prose Markdown rendering

The Markdown domain model preserves H1-H6 instead of using one generic heading
kind. Paragraphs, emphasis, strong, blockquotes, ordered and unordered lists,
links, and thematic breaks receive distinct presentation while retaining exact
raw-source ranges.

- Blockquotes: quiet surface, left marker, readable inset, no forced italic.
- Lists: hanging indentation with aligned continuation lines.
- Links: semantic color plus underline; color is not the only affordance.
- Thematic breaks: subdued divider with vertical breathing room.
- Tables and fenced code blocks: unchanged and outside this work.

Review selections, anchors, edits, signals, and search navigation continue to
address raw UTF-8 byte ranges. Typography must not change those mappings.

## Chrome hierarchy

The reader top bar uses Manrope `18sp` SemiBold for chapter identity. Sync state
and actionable sync errors use `13sp` Regular with semantic color. Controls keep
their existing accessible touch targets. Long error text may wrap or truncate
without making the status visually equal to the chapter name.

## Search results

Each result shows a Manrope SemiBold chapter title and a Literata `14/21sp`
excerpt. Every occurrence corresponding to the result uses bold weight plus an
accessible background highlight in both themes. Selecting the result navigates
to its exact raw range and temporarily highlights the same range in the reader.

Highlight colors are theme tokens and must remain distinguishable from the four
review-signal colors. Accessibility semantics identify the matched text without
reading decorative state aloud.

## Verification

- Inspect embedded font metadata for Literata and Manrope in the built APK.
- Render Cyrillic Regular, Italic, SemiBold, and Bold without fallback glyphs.
- Test H1-H6 parsing and raw-range preservation.
- Test each prose block and inline style in light and dark themes.
- Test that the reader size setting changes prose but not top-bar or panel text.
- Test Android accessibility scales independently of the reader setting.
- Test match highlighting in excerpts and after navigation, including multiple
  occurrences and Unicode/Cyrillic queries.
- Capture phone, portrait-tablet, and landscape-tablet screenshots.

## Non-goals

- User-selectable font families.
- Margin, justification, paragraph-spacing, or line-height controls.
- Tables or fenced-code presentation work.
- Search across notes, signals, or edits.
