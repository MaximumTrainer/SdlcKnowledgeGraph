import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The build-time reader is a second reading of the registry the application also reads at runtime.
 * Two readings of one file drift, so this pins the reader against the registry as it actually is:
 * the eleven declared types, the declared version, and inverses on every edge.
 */
class OntologyReaderTest {
    private val ontology = OntologyReader.read(File("../src/main/resources/ontology/v1"))

    @Test
    fun `the registry declares eleven node types`() {
        assertEquals(11, ontology.nodeTypes.size, ontology.nodeTypes.joinToString { it.name })
    }

    @Test
    fun `the nine core types of the minimum viable graph are present`() {
        val declared = ontology.nodeTypes.map { it.name }.toSet()
        listOf(
            "Repository",
            "Team",
            "Service",
            "Pipeline",
            "Artifact",
            "Deployment",
            "Environment",
            "CloudResource",
            "ConfigurationItem",
        ).forEach { assertTrue(it in declared, "$it is missing from $declared") }
    }

    @Test
    fun `the version is read from version yaml rather than hard-coded`() {
        val declared =
            File("../src/main/resources/ontology/v1/version.yaml")
                .readLines()
                .first { it.startsWith("version:") }
                .substringAfter("version:")
                .trim()

        assertEquals(declared, ontology.version)
    }

    @Test
    fun `every edge type declares an inverse so the graph can be traversed backwards`() {
        ontology.edgeTypes.forEach { edge ->
            assertTrue(edge.inverse.isNotBlank(), "${edge.name} declares no inverse")
            assertTrue(edge.from.isNotEmpty(), "${edge.name} declares no from types")
            assertTrue(edge.to.isNotEmpty(), "${edge.name} declares no to types")
        }
    }

    @Test
    fun `identity properties are read for the types the resolver derives keys from`() {
        val repository = ontology.nodeTypes.first { it.name == "Repository" }

        assertEquals(listOf("host", "org", "name"), repository.identity)
    }
}
