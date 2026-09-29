import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import java.io.File

/** One declared field of an event. `type` uses the ontology's wire names. */
data class GenEventField(
    val name: String,
    val type: String,
)

/**
 * One event the application may log (#44, FR2).
 *
 * @param throwable whether the event records a failure, and so takes the exception that caused it
 * @param security whether the event matters to whoever watches for abuse, so it can be routed apart
 */
data class GenLogEvent(
    val name: String,
    val level: String,
    val message: String,
    val description: String,
    val fields: List<GenEventField>,
    val throwable: Boolean = false,
    val security: Boolean = false,
)

/**
 * Reads `observability/events.yaml` and refuses anything that would generate something ambiguous: a
 * name that is not dot-separated lower case, a level SLF4J does not have, a field type the ontology
 * does not have, a name declared twice, or a message with a `{}` placeholder, which would invite
 * formatting a value into the one string that is not masked.
 */
object LogEventReader {
    private val NAME = Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+$")
    private val FIELD = Regex("^[a-z][A-Za-z0-9]*$")
    private val LEVELS = setOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR")
    private val TYPES = setOf("string", "int", "boolean", "instant", "string[]")

    fun read(file: File): List<GenLogEvent> {
        val root = YAMLMapper().readTree(file)
        val events = root.path("events").map(::event)
        val problems = events.flatMap(::problemsOf) + duplicates(events)
        check(problems.isEmpty()) { "invalid log event registry ${file.name}:\n  - " + problems.joinToString("\n  - ") }
        return events
    }

    private fun event(node: JsonNode) =
        GenLogEvent(
            name = node.path("name").asText(),
            level = node.path("level").asText(),
            message = node.path("message").asText(),
            description = node.path("description").asText(),
            fields = node.path("fields").map { GenEventField(it.path("name").asText(), it.path("type").asText()) },
            throwable = node.path("throwable").asBoolean(false),
            security = node.path("security").asBoolean(false),
        )

    private fun problemsOf(event: GenLogEvent): List<String> =
        listOfNotNull(
            "${event.name}: the name must be dot-separated lower case, such as node.created".takeUnless { NAME.matches(event.name) },
            "${event.name}: level ${event.level} is not one of ${LEVELS.joinToString()}".takeUnless { event.level in LEVELS },
            "${event.name}: the message must not be blank".takeIf { event.message.isBlank() },
            "${event.name}: the message has a {} placeholder; declare a field instead".takeIf { "{}" in event.message },
            "${event.name}: the description must not be blank".takeIf { event.description.isBlank() },
        ) +
            event.fields.flatMap { field ->
                listOfNotNull(
                    "${event.name}.${field.name}: a field name must be camelCase".takeUnless { FIELD.matches(field.name) },
                    "${event.name}.${field.name}: type ${field.type} is not one of ${TYPES.joinToString()}".takeUnless { field.type in TYPES },
                    "${event.name}.${field.name}: event and cause are reserved".takeIf { field.name in RESERVED },
                )
            }

    private fun duplicates(events: List<GenLogEvent>): List<String> =
        events
            .groupingBy { it.name }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .map { "$it is declared twice" }

    private val RESERVED = setOf("event", "cause")
}
