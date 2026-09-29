import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File

/** Writes `LogEvents.kt` and `events.json` from the event registry (#44, FR3). */
abstract class LogEventCodegenTask : DefaultTask() {
    @get:InputFile
    abstract val registryFile: RegularFileProperty

    @get:OutputFile
    abstract val kotlinOutput: RegularFileProperty

    @get:OutputFile
    abstract val jsonOutput: RegularFileProperty

    @TaskAction
    fun generate() {
        val events = LogEventReader.read(registryFile.get().asFile)
        write(kotlinOutput.get().asFile, LogEventCodegen.kotlin(events))
        write(jsonOutput.get().asFile, LogEventCodegen.json(events))
        logger.lifecycle("Generated LogEvents for {} events", events.size)
    }

    /** LF regardless of platform, so a Windows checkout does not produce a spurious drift failure. */
    private fun write(
        target: File,
        content: String,
    ) {
        target.parentFile.mkdirs()
        target.writeText(content.replace("\r\n", "\n"))
    }
}

/**
 * Fails when `LogEvents.kt` or `events.json` no longer match the registry, so an event cannot be
 * declared without the function that emits it, or emitted by a function nobody declared.
 */
abstract class LogEventDriftCheckTask : DefaultTask() {
    @get:InputFile
    abstract val registryFile: RegularFileProperty

    // Inputs, not outputs: the check reads the committed files and compares (see OntologyDriftCheckTask).
    @get:InputFile
    abstract val kotlinOutput: RegularFileProperty

    @get:InputFile
    abstract val jsonOutput: RegularFileProperty

    @TaskAction
    fun check() {
        val events = LogEventReader.read(registryFile.get().asFile)
        val stale =
            listOf(
                kotlinOutput.get().asFile to LogEventCodegen.kotlin(events),
                jsonOutput.get().asFile to LogEventCodegen.json(events),
            ).filter { (file, expected) ->
                !file.exists() || file.readText().replace("\r\n", "\n") != expected
            }.map { (file, _) -> file.path }

        check(stale.isEmpty()) {
            "Log event outputs are stale: ${stale.joinToString()}. Run ./gradlew generateLogEvents and commit the result."
        }
    }
}
