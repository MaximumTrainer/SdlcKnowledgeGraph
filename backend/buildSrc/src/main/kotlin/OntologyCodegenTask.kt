import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File

/** Writes the generated GraphQL, TypeScript and JSON views of the ontology. */
abstract class OntologyCodegenTask : DefaultTask() {
    @get:InputDirectory
    abstract val ontologyDirectory: DirectoryProperty

    @get:OutputFile
    abstract val graphqlOutput: RegularFileProperty

    @get:OutputFile
    abstract val typescriptOutput: RegularFileProperty

    @get:OutputFile
    abstract val jsonOutput: RegularFileProperty

    @TaskAction
    fun generate() {
        val ontology = OntologyReader.read(ontologyDirectory.get().asFile)

        write(graphqlOutput.get().asFile, OntologyCodegen.graphqlSdl(ontology))
        write(typescriptOutput.get().asFile, OntologyCodegen.typescript(ontology))
        write(jsonOutput.get().asFile, OntologyCodegen.json(ontology))

        logger.lifecycle(
            "Generated ontology views for {} node types and {} edge types",
            ontology.nodeTypes.size,
            ontology.edgeTypes.size,
        )
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
 * Fails when the committed generated files no longer match the registry.
 *
 * Generated files are committed so reviewers see the effect of an ontology change in the diff. That
 * only works if it is impossible to commit a registry change without the regenerated output, which
 * is what this check enforces in CI and on push.
 */
abstract class OntologyDriftCheckTask : DefaultTask() {
    @get:InputDirectory
    abstract val ontologyDirectory: DirectoryProperty

    // These are inputs, not outputs: the check reads the committed files and compares. Declaring
    // them as outputs made Gradle think this task produced the resources that processResources
    // consumes, which is an ordering problem it rightly refuses to guess at.
    @get:InputFile
    abstract val graphqlOutput: RegularFileProperty

    @get:InputFile
    abstract val typescriptOutput: RegularFileProperty

    @get:InputFile
    abstract val jsonOutput: RegularFileProperty

    @TaskAction
    fun check() {
        val ontology = OntologyReader.read(ontologyDirectory.get().asFile)

        val stale =
            listOf(
                graphqlOutput.get().asFile to OntologyCodegen.graphqlSdl(ontology),
                typescriptOutput.get().asFile to OntologyCodegen.typescript(ontology),
                jsonOutput.get().asFile to OntologyCodegen.json(ontology),
            ).filter { (file, expected) ->
                !file.exists() || file.readText().replace("\r\n", "\n") != expected.replace("\r\n", "\n")
            }.map { (file, _) -> file.path }

        check(stale.isEmpty()) {
            "Ontology outputs are stale: ${stale.joinToString()}. Run ./gradlew generateOntology and commit the result."
        }
    }
}
