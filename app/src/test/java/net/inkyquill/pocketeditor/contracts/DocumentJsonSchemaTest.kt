package net.inkyquill.pocketeditor.contracts

import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import net.inkyquill.pocketeditor.book.BookManifest
import net.inkyquill.pocketeditor.review.ReviewJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DocumentJsonSchemaTest {
    @Test
    fun `v1 schemas validate the deterministic fixtures and canonical Kotlin output`() {
        val manifest = fixture("manifest-v1.json")
        val review = fixture("review-v1.json")

        assertValid(MANIFEST_SCHEMA, manifest)
        assertValid(MANIFEST_SCHEMA, BookManifest.encode(BookManifest.decode(manifest)))
        assertValid(REVIEW_SCHEMA, review)
        assertValid(REVIEW_SCHEMA, ReviewJson.encode(ReviewJson.decode(review, CHAPTER_ID, SOURCE_PATH)))
    }

    @Test
    fun `v1 schemas expose stable identifiers`() {
        assertEquals(MANIFEST_ID, schemaText(MANIFEST_SCHEMA).idValue())
        assertEquals(REVIEW_ID, schemaText(REVIEW_SCHEMA).idValue())
    }

    @Test
    fun `schemas reject unknown fields at root and nested object boundaries`() {
        val manifest = fixture("manifest-v1.json")
        val review = fixture("review-v1.json")

        assertInvalid(MANIFEST_SCHEMA, manifest.replace("\"title\": \"Алхимик\"", "\"title\": \"Алхимик\", \"unknown\": true"))
        assertInvalid(MANIFEST_SCHEMA, manifest.replace("\"title\": \"Вторая глава\"", "\"title\": \"Вторая глава\", \"unknown\": true"))
        assertInvalid(REVIEW_SCHEMA, review.replace("\"chapter_note\":", "\"unknown\": true, \"chapter_note\":"))
        assertInvalid(REVIEW_SCHEMA, review.replace("\"comment\":", "\"unknown\": true, \"comment\":"))
        assertInvalid(REVIEW_SCHEMA, review.replaceFirst("\"prefix\":", "\"unknown\": true, \"prefix\":"))
        assertInvalid(REVIEW_SCHEMA, review.replace("\"before\":", "\"unknown\": true, \"before\":"))
    }

    @Test
    fun `schemas reject unknown versions and signal union values`() {
        assertInvalid(MANIFEST_SCHEMA, fixture("manifest-v1.json").replace("\"schema_version\": 1", "\"schema_version\": 2"))
        assertInvalid(REVIEW_SCHEMA, fixture("review-v1.json").replace("\"schema_version\": 1", "\"schema_version\": 2"))
        assertInvalid(REVIEW_SCHEMA, fixture("review-v1.json").replace("\"type\": \"warning\"", "\"type\": \"unknown\""))
    }

    private fun assertValid(schemaName: String, document: String) {
        val errors = schema(schemaName).validate(document, InputFormat.JSON)
        assertTrue(errors.isEmpty()) { errors.joinToString("\n") }
    }

    private fun assertInvalid(schemaName: String, document: String) {
        val errors = schema(schemaName).validate(document, InputFormat.JSON)
        assertTrue(errors.isNotEmpty()) { "Expected $schemaName to reject the document" }
    }

    private fun schema(name: String): Schema = registry.getSchema(schemaText(name), InputFormat.JSON)

    private fun schemaText(name: String): String =
        requireNotNull(javaClass.getResource("/$name")) { "Missing schema resource: $name" }.readText()

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name")).readText()

    private fun String.idValue(): String =
        Regex("\"\\\$id\"\\s*:\\s*\"([^\"]+)\"").find(this)?.groupValues?.get(1)
            ?: error("Schema has no string \$id")

    private companion object {
        const val MANIFEST_SCHEMA = "manifest-v1.schema.json"
        const val REVIEW_SCHEMA = "review-v1.schema.json"
        const val MANIFEST_ID = "https://inkyquill.net/pocket-editor/schemas/manifest-v1.schema.json"
        const val REVIEW_ID = "https://inkyquill.net/pocket-editor/schemas/review-v1.schema.json"
        const val CHAPTER_ID = "0b4f1cad-c846-4551-a497-a745087f5de2"
        const val SOURCE_PATH = "chapter-01.md"

        val registry: SchemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
    }
}
