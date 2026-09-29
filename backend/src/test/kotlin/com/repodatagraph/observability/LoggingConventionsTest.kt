package com.repodatagraph.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every line the application logs goes through [LogEvents] (#44, FR4), so a caller cannot invent an
 * event, a field or a level that the registry does not declare. This reads the source rather than
 * trusting a convention nobody enforces.
 */
class LoggingConventionsTest {
    private val sources =
        File("src/main/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun relative(file: File) = file.invariantSeparatorsPath.substringAfter("src/main/kotlin/")

    @Test
    fun `only the event log creates a logger`() {
        val offenders =
            sources
                .filter { relative(it) !in LOGGER_ALLOWLIST }
                .filter { it.readText().contains("LoggerFactory.getLogger") }
                .map(::relative)

        assertThat(offenders).describedAs("files creating their own logger; emit through LogEvents instead").isEmpty()
    }

    @Test
    fun `no log message is written by hand outside the generated events`() {
        val handWritten = Regex("""\b(?:log|logger|LOG)\.(?:trace|debug|info|warn|error)\(""")
        val offenders =
            sources
                .filter { relative(it) != GENERATED }
                .filter { handWritten.containsMatchIn(it.readText()) }
                .map(::relative)

        assertThat(offenders).describedAs("files logging a message by hand; declare an event instead").isEmpty()
    }

    @Test
    fun `the scan is looking at the real source tree`() {
        assertThat(sources.map(::relative)).contains(GENERATED, "com/repodatagraph/observability/EventLog.kt")
    }

    private companion object {
        const val GENERATED = "com/repodatagraph/observability/LogEvents.kt"
        val LOGGER_ALLOWLIST = setOf("com/repodatagraph/observability/EventLog.kt")
    }
}
