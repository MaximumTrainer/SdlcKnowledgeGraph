import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Generated files are committed so a reviewer sees the whole effect of an ontology change in the
 * diff. That is only true if it is impossible to commit the registry change without them, which is
 * this task's job: it regenerates in memory and fails when the committed file disagrees.
 */
class OntologyDriftCheckTaskTest {
    @TempDir
    lateinit var projectDir: File

    @Test
    fun `a registry change without regenerated output fails and names the stale files`() {
        val fixture = fixture()
        fixture.generate()

        fixture.ontologyDir.resolve("nodes.yaml").appendText(
            """
            |      - { name: slug, type: string, required: false }
            """.trimMargin() + "\n",
        )

        val failure = assertThrows<IllegalStateException> { fixture.driftCheck() }

        assertTrue(failure.message!!.contains("Ontology outputs are stale"), failure.message)
        assertTrue(failure.message!!.contains("ontology.ts"), failure.message)
        assertTrue(failure.message!!.contains("generateOntology"), failure.message)
    }

    @Test
    fun `freshly generated output passes`() {
        val fixture = fixture()
        fixture.generate()

        assertDoesNotThrow { fixture.driftCheck() }
    }

    @Test
    fun `a missing generated file is stale rather than ignored`() {
        val fixture = fixture()
        fixture.generate()
        fixture.typescript.delete()

        val failure = assertThrows<IllegalStateException> { fixture.driftCheck() }

        assertTrue(failure.message!!.contains("ontology.ts"), failure.message)
    }

    @Test
    fun `generated files are written with LF endings so a Windows checkout does not drift`() {
        val fixture = fixture()
        fixture.generate()

        assertEquals(0, fixture.typescript.readText().count { it == '\r' })
        assertEquals(0, fixture.graphql.readText().count { it == '\r' })
    }

    @Test
    fun `output committed with CRLF endings is still recognised as current`() {
        // Git may check the generated files out with CRLF. Comparing raw bytes would then fail the
        // build on every Windows machine for a difference no one introduced.
        val fixture = fixture()
        fixture.generate()
        fixture.typescript.writeText(fixture.typescript.readText().replace("\n", "\r\n"))

        assertDoesNotThrow { fixture.driftCheck() }
    }

    private fun fixture(): Fixture {
        val ontologyDir = projectDir.resolve("ontology/v1").apply { mkdirs() }
        ontologyDir.resolve("version.yaml").writeText("version: 1.0.0\n")
        ontologyDir.resolve("nodes.yaml").writeText(
            """
            |nodes:
            |  Repository:
            |    description: A git repository.
            |    identity: [host, org, name]
            |    properties:
            |      - { name: name, type: string, required: true }
            |      - { name: language, type: string, required: false }
            """.trimMargin() + "\n",
        )
        ontologyDir.resolve("edges.yaml").writeText(
            """
            |edges:
            |  BUILT_FROM:
            |    description: An artifact was built from a repository.
            |    from: [Artifact]
            |    to: [Repository]
            |    inverse: BUILDS
            |    properties: []
            """.trimMargin() + "\n",
        )
        return Fixture(projectDir, ontologyDir)
    }

    private class Fixture(
        projectDir: File,
        val ontologyDir: File,
    ) {
        val graphql: File = projectDir.resolve("out/schema.generated.graphqls")
        val typescript: File = projectDir.resolve("out/ontology.ts")
        val json: File = projectDir.resolve("out/ontology.json")

        private val project = ProjectBuilder.builder().withProjectDir(projectDir).build()

        fun generate() {
            val task = project.tasks.register("generateOntology", OntologyCodegenTask::class.java).get()
            task.ontologyDirectory.set(ontologyDir)
            task.graphqlOutput.set(graphql)
            task.typescriptOutput.set(typescript)
            task.jsonOutput.set(json)
            task.generate()
        }

        fun driftCheck() {
            val task = project.tasks.register("ontologyDriftCheck${counter++}", OntologyDriftCheckTask::class.java).get()
            task.ontologyDirectory.set(ontologyDir)
            task.graphqlOutput.set(graphql)
            task.typescriptOutput.set(typescript)
            task.jsonOutput.set(json)
            task.check()
        }

        private companion object {
            var counter = 0
        }
    }
}
