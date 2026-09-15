package net.inkyquill.pocketeditor.contracts

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import net.inkyquill.pocketeditor.anchor.AnchorFactory
import net.inkyquill.pocketeditor.book.BookDiscovery
import net.inkyquill.pocketeditor.book.ChapterProposal
import net.inkyquill.pocketeditor.book.DiscoveryFile
import net.inkyquill.pocketeditor.review.Anchor
import net.inkyquill.pocketeditor.review.ReviewJson
import net.inkyquill.pocketeditor.review.classifyReview
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * File-based Kotlin half of the Galley Desk cross-language contract check (desktop exchange).
 *
 * Without the [ENV_DIR] environment variable the test executes the bundled [FIXTURE] resource.
 * With the variable set, `input.json` from that directory is the mandatory input and, only after
 * every case has been computed, `output.json` is written next to it for the future TypeScript
 * consumer (Galley Desk task C2). Review data always flows through the real [ReviewJson] codec
 * and [classifyReview]; anchors are always produced by [AnchorFactory].
 */
class DesktopExchangeTest {
    @Test
    fun `bundled fixture round-trips reviews and regenerates anchors`() {
        val input = javaClass.getResource(FIXTURE)!!.readText()
        val root = Json.parseToJsonElement(input).jsonObject
        assertTrue(root.getValue("cases").jsonArray.isNotEmpty())

        val output = computeOutput(root)
        assertEquals(output, computeOutput(root), "output.json content must be deterministic")

        val cases = output.getValue("cases").jsonArray.map(JsonElement::jsonObject)
        assertEquals(EXPECTED_CASES.keys.toList(), cases.map { it.string("name") })
        EXPECTED_CASES.forEach { (name, expected) ->
            val case = cases.single { it.string("name") == name }
            assertEquals(expected.activeEditIds, case.strings("activeEditIds"), name)
            assertEquals(expected.conflictingEditIds, case.strings("conflictingEditIds"), name)
            assertEquals(expected.unavailableIds, case.strings("unavailableIds"), name)
        }

        // Overlapping stored ranges survive the real codec unchanged.
        val overlapReview = cases.single { it.string("name") == "overlapping-edits" }
            .getValue("review").jsonObject
        assertEquals(
            listOf(0L to 3L, 2L to 5L),
            overlapReview.getValue("edits").jsonArray.map { edit ->
                val anchor = edit.jsonObject.getValue("anchor").jsonObject
                anchor.long("start_byte") to anchor.long("end_byte")
            },
        )

        assertFixtureAnchorsAreRegenerable(root)
        assertAnchorCases(root, output)
        assertDiscoveryCases(output)
    }

    @Test
    fun `galley exchange env dir drives input and output files`() {
        val exchangeDir = System.getenv(ENV_DIR)?.takeUnless(String::isBlank)
        if (exchangeDir == null) {
            // Missing env runs the bundled fixtures instead of skipping the contract check.
            assertTrue(computeOutput(bundledInput()).getValue("cases").jsonArray.isNotEmpty())
            return
        }
        val input = File(exchangeDir, "input.json")
        val output = computeOutput(Json.parseToJsonElement(input.readText()).jsonObject)
        val outputFile = File(exchangeDir, "output.json")
        // Written only after every case above has been computed successfully.
        outputFile.writeText(prettyJson.encodeToString(JsonElement.serializer(), output) + "\n")
        assertEquals(output, Json.parseToJsonElement(outputFile.readText()).jsonObject)
    }

    private fun assertFixtureAnchorsAreRegenerable(root: JsonObject) {
        root.getValue("cases").jsonArray.map(JsonElement::jsonObject).forEach { case ->
            val source = case.string("source").encodeToByteArray()
            val review = case.getValue("review").jsonObject
            review.readAnchors().forEach { (recordId, anchor) ->
                if (anchor.describesRevisionOf(source)) {
                    val regenerated = AnchorFactory.create(
                        source,
                        anchor.startByte.toInt(),
                        anchor.endByte.toInt(),
                    )
                    assertEquals(regenerated, anchor, "case ${case.string("name")}, record $recordId")
                }
            }
        }
    }

    private fun assertAnchorCases(root: JsonObject, output: JsonObject) {
        val inputs = root.getValue("anchorCases").jsonArray.map(JsonElement::jsonObject)
        val outputs = output.getValue("anchors").jsonArray.map(JsonElement::jsonObject)
        assertEquals(inputs.map { it.string("name") }, outputs.map { it.string("name") })
        inputs.zip(outputs).forEach { (input, entry) ->
            val source = input.string("source")
            val from = input.int("from")
            val to = input.int("to")
            assertEquals(source, entry.string("source"), input.string("name"))
            assertEquals(from, entry.int("from"), input.string("name"))
            assertEquals(to, entry.int("to"), input.string("name"))
            assertEquals(
                AnchorFactory.create(source.encodeToByteArray(), from, to),
                anchorJson.decodeFromString(Anchor.serializer(), entry.getValue("anchor").jsonObject.toString()),
                input.string("name"),
            )
        }
    }

    private fun assertDiscoveryCases(output: JsonObject) {
        val discovery = output.getValue("discovery").jsonArray.map(JsonElement::jsonObject)
        assertEquals(
            EXPECTED_DISCOVERY,
            discovery.associate { it.string("name") to it.strings("paths") },
        )
    }

    private fun bundledInput(): JsonObject = Json.parseToJsonElement(
        requireNotNull(javaClass.getResource(FIXTURE)) { "Missing bundled fixture: $FIXTURE" }.readText(),
    ).jsonObject

    private fun computeOutput(root: JsonObject): JsonObject {
        val cases = root.getValue("cases").jsonArray.map { element -> exchangeCase(element.jsonObject) }
        return buildJsonObject {
            put("cases", JsonArray(cases))
            root["anchorCases"]?.let { section ->
                put("anchors", JsonArray(section.jsonArray.map { anchorCaseOutput(it.jsonObject) }))
            }
            root["discoveryCases"]?.let { section ->
                put("discovery", JsonArray(section.jsonArray.map { discoveryCaseOutput(it.jsonObject) }))
            }
        }
    }

    private fun exchangeCase(case: JsonObject): JsonObject {
        val name = case.string("name")
        val source = case.string("source")
        val reviewObject = case.getValue("review").jsonObject
        val document = ReviewJson.decode(
            reviewObject.toString(),
            reviewObject.string("chapter_id"),
            reviewObject.string("source_path"),
        )
        val canonical = ReviewJson.encode(document)
        // Canonical form must be a codec fixed point even for non-canonical external input.
        check(canonical == ReviewJson.encode(ReviewJson.decode(canonical, document.chapterId, document.sourcePath))) {
            "Review codec canonicalization is not stable for case $name"
        }
        assertInputFieldsPreserved(reviewObject, Json.parseToJsonElement(canonical).jsonObject, name)
        val state = classifyReview(source.encodeToByteArray(), document)
        return buildJsonObject {
            put("name", name)
            put("review", Json.parseToJsonElement(canonical))
            put("activeEditIds", idList(state.activeEdits.keys.sorted()))
            put("conflictingEditIds", idList(state.conflictingEdits.sorted()))
            put("unavailableIds", idList(state.unavailable.keys.sorted()))
        }
    }

    private fun anchorCaseOutput(case: JsonObject): JsonObject {
        val source = case.string("source")
        val from = case.int("from")
        val to = case.int("to")
        val anchor = AnchorFactory.create(source.encodeToByteArray(), from, to)
        return buildJsonObject {
            put("name", case.string("name"))
            put("source", source)
            put("from", JsonPrimitive(from))
            put("to", JsonPrimitive(to))
            put("anchor", Json.parseToJsonElement(anchorJson.encodeToString(Anchor.serializer(), anchor)))
        }
    }

    private fun discoveryCaseOutput(case: JsonObject): JsonObject {
        val files = case.getValue("files").jsonArray.map { file ->
            val value = file.jsonObject
            DiscoveryFile(path = value.string("path"), bytes = value.string("text").encodeToByteArray())
        }
        val paths = BookDiscovery().propose(files).proposals.map(ChapterProposal::path)
        return buildJsonObject {
            put("name", case.string("name"))
            put("paths", JsonArray(paths.map(::JsonPrimitive)))
        }
    }

    /**
     * Codec-independent check: every field present in the input review (chapter_note, comments,
     * after, anchors, ...) must reach the canonical output with an equal value; records are
     * matched by id, so encode-side reordering does not matter. Catches fields silently dropped
     * by decode, which the encode fixed-point check alone cannot see.
     */
    private fun assertInputFieldsPreserved(input: JsonObject, canonical: JsonObject, name: String) {
        input.forEach { (key, value) ->
            // Record arrays are canonicalized to id order by encode; they are compared
            // per record below, so positional equality here would reject legal input order.
            if (key == "edits" || key == "signals") return@forEach
            assertEquals(value, canonical[key], "$name: review field $key")
        }
        listOf("edits", "signals").forEach { section ->
            val inputRecords = input[section]?.jsonArray?.map(JsonElement::jsonObject).orEmpty()
            val canonicalRecords = canonical[section]?.jsonArray?.map(JsonElement::jsonObject).orEmpty()
            assertEquals(inputRecords.size, canonicalRecords.size, "$name: $section count")
            val byId = canonicalRecords.associateBy { it.string("id") }
            inputRecords.forEach { record ->
                val id = record.string("id")
                val saved = requireNotNull(byId[id]) { "$name: $section record $id missing from canonical output" }
                record.forEach { (key, value) ->
                    assertEquals(value, saved[key], "$name: $section/$id field $key")
                }
            }
        }
    }

    private fun JsonObject.readAnchors(): List<Pair<String, Anchor>> =
        (getValue("edits").jsonArray + getValue("signals").jsonArray).map { record ->
            val value = record.jsonObject
            value.string("id") to
                anchorJson.decodeFromString(Anchor.serializer(), value.getValue("anchor").jsonObject.toString())
        }

    /** True when the anchor claims to describe exactly this source revision, so [AnchorFactory] must reproduce it. */
    private fun Anchor.describesRevisionOf(source: ByteArray): Boolean {
        if (startByte < 0 || endByte <= startByte || endByte > source.size) return false
        val start = startByte.toInt()
        val end = endByte.toInt()
        return source.sha256Hex() == sourceSha256 && source.copyOfRange(start, end).sha256Hex() == selectionSha256
    }

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }

    private fun idList(ids: List<String>): JsonArray = JsonArray(ids.map(::JsonPrimitive))

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

    private fun JsonObject.strings(key: String): List<String> =
        getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private data class CaseExpectation(
        val activeEditIds: List<String>,
        val conflictingEditIds: List<String>,
        val unavailableIds: List<String>,
    )

    private companion object {
        const val ENV_DIR = "GALLEY_EXCHANGE_DIR"
        const val FIXTURE = "/fixtures/desktop-exchange.json"
        const val EDIT_1 = "11111111-1111-4111-8111-111111111111"
        const val EDIT_2 = "22222222-2222-4222-8222-222222222222"
        const val EDIT_3 = "33333333-3333-4333-8333-333333333333"
        const val EDIT_4 = "44444444-4444-4444-8444-444444444444"
        const val SIGNAL_1 = "55555555-5555-4555-8555-555555555555"

        val EXPECTED_CASES = linkedMapOf(
            "overlapping-edits" to CaseExpectation(
                activeEditIds = emptyList(),
                conflictingEditIds = listOf(EDIT_1, EDIT_2),
                unavailableIds = emptyList(),
            ),
            "lf-crlf-line-breaks" to CaseExpectation(
                activeEditIds = listOf(EDIT_1, EDIT_2),
                conflictingEditIds = emptyList(),
                unavailableIds = emptyList(),
            ),
            "emoji-source" to CaseExpectation(
                activeEditIds = listOf(EDIT_1),
                conflictingEditIds = emptyList(),
                unavailableIds = emptyList(),
            ),
            "cyrillic-source" to CaseExpectation(
                activeEditIds = listOf(EDIT_1),
                conflictingEditIds = emptyList(),
                unavailableIds = emptyList(),
            ),
            "repeated-occurrences" to CaseExpectation(
                activeEditIds = listOf(EDIT_1),
                conflictingEditIds = emptyList(),
                unavailableIds = listOf(SIGNAL_1),
            ),
            "stale-selection-hash" to CaseExpectation(
                activeEditIds = emptyList(),
                conflictingEditIds = emptyList(),
                unavailableIds = listOf(EDIT_1),
            ),
            "empty-source" to CaseExpectation(
                activeEditIds = emptyList(),
                conflictingEditIds = emptyList(),
                unavailableIds = listOf(EDIT_1),
            ),
            "mixed-availability" to CaseExpectation(
                activeEditIds = listOf(EDIT_3),
                conflictingEditIds = listOf(EDIT_1, EDIT_2),
                unavailableIds = listOf(EDIT_4),
            ),
        )
        val EXPECTED_DISCOVERY = mapOf(
            "mixed-files" to listOf("10-last.md", "01-first.md", "02-second.md"),
        )

        val prettyJson = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
        val anchorJson = Json
    }
}
