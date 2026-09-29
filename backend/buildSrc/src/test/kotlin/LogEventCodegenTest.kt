import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The event registry (#44, FR2, FR3) rendered into the only way the application may log: one Kotlin
 * function per event taking exactly its declared fields, and the JSON the website renders.
 */
class LogEventCodegenTest {
    @TempDir
    lateinit var dir: File

    private val registry =
        """
        |events:
        |  - name: node.created
        |    level: INFO
        |    message: node created
        |    description: A node was written through the API.
        |    fields:
        |      - { name: type, type: string }
        |      - { name: properties, type: "string[]" }
        |  - name: graph.store.failed
        |    level: ERROR
        |    message: graph store failed
        |    description: A read or write against Neo4j threw.
        |    throwable: true
        |    fields:
        |      - { name: operation, type: string }
        |  - name: ingest.unauthorized
        |    level: WARN
        |    message: ingest refused without a valid token
        |    description: Someone posted to an ingest endpoint without the token.
        |    security: true
        |    fields:
        |      - { name: endpoint, type: string }
        |      - { name: attempts, type: int }
        |      - { name: created, type: boolean }
        """.trimMargin() + "\n"

    private fun read(text: String = registry) = LogEventReader.read(dir.resolve("events.yaml").apply { writeText(text) })

    @Test
    fun `renders one function per event, taking exactly its declared fields`() {
        val kotlin = LogEventCodegen.kotlin(read())

        assertTrue(kotlin.contains("fun nodeCreated(\n        type: String,\n        properties: List<String>,\n    )"), kotlin)
        assertTrue(kotlin.contains("EventLog.emit(\"node.created\", Level.INFO, \"node created\","), kotlin)
        assertTrue(kotlin.contains("fun ingestUnauthorized(\n        endpoint: String,\n        attempts: Int,\n        created: Boolean,\n    )"), kotlin)
        assertTrue(kotlin.contains("security = true"), kotlin)
    }

    @Test
    fun `an event that records a failure takes the throwable as well`() {
        val kotlin = LogEventCodegen.kotlin(read())

        assertTrue(kotlin.contains("fun graphStoreFailed(\n        operation: String,\n        cause: Throwable,\n    )"), kotlin)
        assertTrue(kotlin.contains("cause = cause"), kotlin)
    }

    @Test
    fun `marks the file as generated, and generates the same bytes twice`() {
        val kotlin = LogEventCodegen.kotlin(read())

        assertTrue(kotlin.startsWith("// GENERATED FROM backend/src/main/resources/observability/events.yaml - DO NOT EDIT."), kotlin)
        assertEquals(kotlin, LogEventCodegen.kotlin(read()))
        assertEquals(LogEventCodegen.json(read()), LogEventCodegen.json(read()))
    }

    @Test
    fun `renders the registry as JSON for the website`() {
        val json = LogEventCodegen.json(read())

        assertTrue(json.contains("\"name\" : \"ingest.unauthorized\""), json)
        assertTrue(json.contains("\"security\" : true"), json)
    }

    @Test
    fun `refuses a registry that would generate something ambiguous`() {
        val problems =
            listOf(
                "events:\n  - { name: Node_Created, level: INFO, message: m, description: d, fields: [] }\n" to "name",
                "events:\n  - { name: a.b, level: LOUD, message: m, description: d, fields: [] }\n" to "level",
                "events:\n  - { name: a.b, level: INFO, message: m, description: d, fields: [{ name: x, type: float }] }\n" to "type",
                "events:\n  - { name: a.b, level: INFO, message: m, description: d, fields: [] }\n" +
                    "  - { name: a.b, level: INFO, message: m, description: d, fields: [] }\n" to "twice",
                "events:\n  - { name: a.b, level: INFO, message: 'has {} placeholder', description: d, fields: [] }\n" to "placeholder",
            )
        problems.forEach { (text, expected) ->
            val failure = assertThrows<IllegalStateException>(expected) { read(text) }
            assertTrue(failure.message!!.contains(expected), "${failure.message} should mention $expected")
        }
    }
}
