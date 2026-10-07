// Exercise Galley Desk's production codecs against the directory fetched by Edda CLI.
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';
const [galleyRoot, bookRoot] = process.argv.slice(2);
const { decodeManifest, encodeManifest, decodeReview, encodeReview, classifyReview } = await import(
  pathToFileURL(resolve(galleyRoot, 'packages/pocket-format/src/index.ts')).href
);
const manifestPath = resolve(bookRoot, '.pocket-editor.json');
const manifest = decodeManifest(readFileSync(manifestPath, 'utf8'));
const chapter = manifest.chapters.find((value) => value.path === 'chapter-01.md');
const reviewPath = resolve(bookRoot, 'chapter-01.review.json');
const source = readFileSync(resolve(bookRoot, chapter.path));
const review = decodeReview(readFileSync(reviewPath, 'utf8'), chapter.id, chapter.path);
assert.equal(review.chapter_note, 'Offline Edda review');
assert.equal(review.edits.length, 2);
assert.equal(review.signals.length, 1);
// Both overlapping edits must survive review; no proposal is applied to Markdown.
classifyReview(source, review);
review.signals[0].comment = 'Desktop review via Galley';
writeFileSync(reviewPath, encodeReview(review));
writeFileSync(manifestPath, encodeManifest({ ...manifest, chapters: [...manifest.chapters].reverse() }));
assert.deepEqual(readFileSync(resolve(bookRoot, chapter.path)), source);
console.log('PASS: Galley Desk codecs read Pocket annotations and return comment/order edits');
