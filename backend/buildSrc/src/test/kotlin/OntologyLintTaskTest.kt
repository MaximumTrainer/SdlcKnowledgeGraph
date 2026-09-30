import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * `./gradlew ontologyLint` against fixture registries (#81): the acceptance scenarios for the lint,
 * which runs before the application is built and so cannot be driven through its API.
 *
 * Each fixture under `ontology-lint/` is the `valid` one with one thing wrong.
 */
class OntologyLintTaskTest {
    @TempDir
    lateinit var projectDir: File

    @Test
    fun `a registry where every property is described with an example passes`() {
        assertDoesNotThrow { lint("valid") }
    }

    @Test
    fun `a missing description fails the build with a locatable message`() {
        val failure = assertThrows<GradleException> { lint("missing-description") }

        assertTrue(
            failure.message!!.contains("nodes.Repository.properties.topics: missing description [ONT001]"),
            failure.message,
        )
    }

    @Test
    fun `an example must validate against its property`() {
        val failure = assertThrows<GradleException> { lint("invalid-example") }

        val line = failure.message!!.lines().firstOrNull { "[ONT003]" in it }
        assertTrue(line != null && "nodes.Deployment.properties.status" in line, failure.message)
    }

    @Test
    fun `the output is grouped by the type it is about`() {
        val registry = copyOf("missing-description")
        registry.resolve("edges.yaml").writeText(
            registry.resolve("edges.yaml").readText().replace("        description: \"Which evidence established the ownership\"\n", ""),
        )

        val failure = assertThrows<GradleException> { lint(registry) }

        val lines = failure.message!!.lines()
        val repository = lines.indexOfFirst { it.trim() == "nodes.Repository" }
        val ownedBy = lines.indexOfFirst { it.trim() == "edges.OWNED_BY" }
        assertTrue(repository >= 0 && ownedBy > repository, failure.message)
        assertTrue(lines.any { it.contains("edges.OWNED_BY.properties.rule: missing description [ONT001]") }, failure.message)
    }

    @Test
    fun `a short description is an error unless warnings are allowed`() {
        val registry = copyOf("valid")
        registry.resolve("nodes.yaml").writeText(
            registry.resolve("nodes.yaml").readText().replace("\"The team's name, as the organisation spells it\"", "\"The name\""),
        )

        val failure = assertThrows<GradleException> { lint(registry) }
        assertTrue(failure.message!!.contains("nodes.Team.properties.name: description is under 20 characters [ONT009]"), failure.message)

        assertDoesNotThrow { lint(registry, warnOnly = true) }
    }

    @Test
    fun `an unknown key fails loading rather than being ignored`() {
        val registry = copyOf("valid")
        registry.resolve("nodes.yaml").writeText(
            registry.resolve("nodes.yaml").readText().replace("        format: email\n", "        format: email\n        sensitivity: pii\n"),
        )

        val failure = assertThrows<Exception> { lint(registry) }

        assertTrue(failure.message!!.contains("sensitivity"), failure.message)
    }

    private fun lint(
        fixture: String,
        warnOnly: Boolean = false,
    ) = lint(fixtureDir(fixture), warnOnly)

    private fun lint(
        directory: File,
        warnOnly: Boolean = false,
    ) {
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        val task =
            project.tasks.register("ontologyLint", OntologyLintTask::class.java) {
                ontologyDirectory.set(directory)
                this.warnOnly.set(warnOnly)
            }
        task.get().lint()
    }

    private fun copyOf(fixture: String): File {
        val target = projectDir.resolve("registry-$fixture").also { it.mkdirs() }
        fixtureDir(fixture).listFiles()!!.forEach { it.copyTo(target.resolve(it.name), overwrite = true) }
        return target
    }

    private fun fixtureDir(name: String): File = File(checkNotNull(javaClass.getResource("/ontology-lint/$name")) { "no fixture $name" }.toURI())
}
