import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature

/**
 * Renders the event registry into `LogEvents.kt`, the only way the application may log, and into
 * `events.json` for the website (#44, FR3).
 */
object LogEventCodegen {
    const val SOURCE = "backend/src/main/resources/observability/events.yaml"

    fun kotlin(events: List<GenLogEvent>): String =
        buildString {
            appendLine("// GENERATED FROM $SOURCE - DO NOT EDIT. Run ./gradlew generateLogEvents.")
            appendLine("package com.repodatagraph.observability")
            appendLine()
            appendLine("import org.slf4j.event.Level")
            appendLine()
            appendLine("/**")
            appendLine(" * Every event the application may log, one function per event declared in events.yaml.")
            appendLine(" *")
            appendLine(" * Each takes exactly the fields its declaration names, so a caller cannot invent a field or a level,")
            appendLine(" * and every value passes through [SensitiveFieldMasker] on its way out (docs/OBSERVABILITY.md).")
            appendLine(" */")
            appendLine("object LogEvents {")
            events.forEachIndexed { index, event ->
                if (index > 0) appendLine()
                appendFunction(event)
            }
            appendLine("}")
        }

    private fun StringBuilder.appendFunction(event: GenLogEvent) {
        appendLine("    /** ${event.description} */")
        val parameters = event.fields.map { "${it.name}: ${kotlinType(it.type)}" } + listOfNotNull("cause: Throwable".takeIf { event.throwable })
        if (parameters.isEmpty()) {
            appendLine("    fun ${functionName(event.name)}() =")
        } else {
            appendLine("    fun ${functionName(event.name)}(")
            parameters.forEach { appendLine("        $it,") }
            appendLine("    ) =")
        }
        val fields = event.fields.joinToString(", ") { "\"${it.name}\" to ${it.name}" }
        val extras =
            listOfNotNull(
                "security = true".takeIf { event.security },
                "cause = cause".takeIf { event.throwable },
            )
        val arguments = listOf("mapOf($fields)") + extras
        appendLine("        EventLog.emit(\"${event.name}\", Level.${event.level}, ${kotlinString(event.message)}, ${arguments.joinToString(", ")})")
    }

    fun json(events: List<GenLogEvent>): String =
        ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .writeValueAsString(
                mapOf(
                    "source" to SOURCE,
                    "events" to
                        events.map { event ->
                            linkedMapOf(
                                "name" to event.name,
                                "level" to event.level,
                                "message" to event.message,
                                "description" to event.description,
                                "security" to event.security,
                                "throwable" to event.throwable,
                                "fields" to event.fields.map { linkedMapOf("name" to it.name, "type" to it.type) },
                            )
                        },
                ),
            ).replace("\r\n", "\n") + "\n"

    /** `node.identity.collision` is `nodeIdentityCollision`. */
    private fun functionName(name: String): String =
        name.split('.').mapIndexed { index, part -> if (index == 0) part else part.replaceFirstChar(Char::uppercase) }.joinToString("")

    private fun kotlinType(type: String): String =
        when (type) {
            "int" -> "Int"
            "boolean" -> "Boolean"
            "instant" -> "java.time.Instant"
            "string[]" -> "List<String>"
            else -> "String"
        }

    private fun kotlinString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
}
