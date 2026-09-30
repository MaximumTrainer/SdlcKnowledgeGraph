import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option

/**
 * `./gradlew ontologyLint` (#81): fails the build when the registry does not describe itself well
 * enough for a machine reader, printing every finding with its registry path and rule code.
 *
 * `--warn-only` turns ONT009, the one rule about taste, into a warning. `--report-data` also runs the
 * enum conformance report against the graph named by NEO4J_URI, which is wired in build.gradle.kts
 * because it needs the application's test classpath; here it is only accepted as an option.
 */
abstract class OntologyLintTask : DefaultTask() {
    @get:InputDirectory
    abstract val ontologyDirectory: DirectoryProperty

    @get:Input
    @get:Option(option = "warn-only", description = "Report ONT009 as a warning rather than failing on it")
    abstract val warnOnly: Property<Boolean>

    @get:Input
    @get:Option(option = "report-data", description = "Also list stored values outside their enum (needs NEO4J_URI)")
    abstract val reportData: Property<Boolean>

    init {
        warnOnly.convention(false)
        reportData.convention(false)
        group = "verification"
        description = "Checks that every ontology type and property describes itself (#81)."
    }

    @TaskAction
    fun lint() {
        val ontology = OntologyReader.read(ontologyDirectory.get().asFile)
        val findings = OntologyLint.lint(ontology)
        val report = OntologyLint.report(findings, warnOnly.get())

        if (OntologyLint.errors(findings, warnOnly.get()).isNotEmpty()) throw GradleException(report)
        if (findings.isNotEmpty()) logger.warn(report)
        logger.lifecycle(
            "Ontology lint passed for {} node types and {} edge types",
            ontology.nodeTypes.size,
            ontology.edgeTypes.size,
        )
    }
}
