import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** An event declared without regenerating fails the build and names the stale file (#44, FR3). */
class LogEventDriftCheckTaskTest {
    @TempDir
    lateinit var projectDir: File

    @Test
    fun `an event added without regeneration fails and names LogEvents kt`() {
        val fixture = Fixture(projectDir)
        fixture.generate()
        fixture.registry.appendText("  - { name: edge.created, level: INFO, message: edge created, description: d, fields: [] }\n")

        val failure = assertThrows<IllegalStateException> { fixture.driftCheck() }

        assertTrue(failure.message!!.contains("Log event outputs are stale"), failure.message)
        assertTrue(failure.message!!.contains("LogEvents.kt"), failure.message)
        assertTrue(failure.message!!.contains("generateLogEvents"), failure.message)
    }

    @Test
    fun `freshly generated output passes, whatever the line endings`() {
        val fixture = Fixture(projectDir)
        fixture.generate()
        fixture.kotlin.writeText(fixture.kotlin.readText().replace("\n", "\r\n"))

        assertDoesNotThrow { fixture.driftCheck() }
    }

    private class Fixture(
        projectDir: File,
    ) {
        val registry: File =
            projectDir.resolve("events.yaml").apply {
                writeText("events:\n  - { name: node.created, level: INFO, message: node created, description: d, fields: [] }\n")
            }
        val kotlin: File = projectDir.resolve("out/LogEvents.kt")
        val json: File = projectDir.resolve("out/events.json")
        private val project = ProjectBuilder.builder().withProjectDir(projectDir).build()

        fun generate() =
            project.tasks.register("generateLogEvents", LogEventCodegenTask::class.java).get().run {
                registryFile.set(registry)
                kotlinOutput.set(kotlin)
                jsonOutput.set(json)
                generate()
            }

        fun driftCheck() =
            project.tasks.register("logEventDriftCheck${System.nanoTime()}", LogEventDriftCheckTask::class.java).get().run {
                registryFile.set(registry)
                kotlinOutput.set(kotlin)
                jsonOutput.set(json)
                check()
            }
    }
}
