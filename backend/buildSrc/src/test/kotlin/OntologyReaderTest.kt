import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The build-time reader is a second reading of the registry the application also reads at runtime.
 * Two readings of one file drift, so this pins the reader against the registry as it actually is:
 * the declared types, the declared version, and inverses on every edge.
 */
class OntologyReaderTest {
    private val ontology = OntologyReader.read(File("../src/main/resources/ontology/v1"))

    @Test
    fun `the registry declares the node types the lineage and meta work added`() {
        // Named rather than counted: a count failed every time a type was added, for the wrong reason.
        val declared = ontology.nodeTypes.map { it.name }
        listOf("Change", "PullRequest", "ExternalWorkItem", "Ontology", "SyncRun", "ConnectorState", "ServicePrincipal")
            .forEach { assertTrue(it in declared, "$it is missing from $declared") }
    }

    @Test
    fun `a property's self-description is read (#81)`() {
        val deployment = ontology.nodeTypes.first { it.name == "Deployment" }
        val status = deployment.properties.first { it.name == "status" }

        assertEquals(listOf("PENDING", "IN_PROGRESS", "SUCCESS", "FAILED", "ROLLED_BACK", "CANCELLED"), status.enum)
        assertTrue(status.examples.isNotEmpty(), "Deployment.status has no examples")
        assertEquals("1.3.0", deployment.properties.first { it.name == "environmentId" }.deprecated?.since)
        assertTrue(deployment.examples.isNotEmpty(), "Deployment has no example node")
        assertTrue(deployment.questions.isNotEmpty(), "Deployment names no questions")
    }

    @Test
    fun `a key the reader does not know fails reading rather than being ignored (#81)`(
        @org.junit.jupiter.api.io.TempDir dir: File,
    ) {
        File("../src/main/resources/ontology/v1").listFiles()!!.filter { it.extension == "yaml" }.forEach { it.copyTo(dir.resolve(it.name)) }
        dir.resolve("nodes.yaml").writeText(dir.resolve("nodes.yaml").readText().replace("    identity: [name]\n", "    identity: [name]\n    owner: platform\n"))

        val error = org.junit.jupiter.api.assertThrows<IllegalArgumentException> { OntologyReader.read(dir) }

        assertTrue(error.message!!.contains("owner"), error.message)
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
    fun `the impact and ownership flags are read, defaulting to none and forward (#21)`() {
        val edges = ontology.edgeTypes.associateBy { it.name }

        assertEquals(Triple("propagates", "inverse", "none"), edges.getValue("DEPENDS_ON").flags())
        assertEquals(Triple("propagates", "forward", "inherits"), edges.getValue("OWNS_RESOURCE").flags())
        assertEquals(Triple("none", "forward", "owner"), edges.getValue("OWNED_BY").flags())
        assertEquals(Triple("none", "forward", "none"), edges.getValue("RELATES_TO_CI").flags())
    }

    private fun GenEdgeType.flags() = Triple(impact, downstream, ownership)

    @Test
    fun `identity properties are read for the types the resolver derives keys from`() {
        val repository = ontology.nodeTypes.first { it.name == "Repository" }

        assertEquals(listOf("host", "org", "name"), repository.identity)
    }

    @Test
    fun `the registry declares the source systems a write may name, manual first (#117)`() {
        val names = ontology.sources.map { it.name }

        assertEquals("manual", names.first(), names.toString())
        listOf("manual", "github", "github-actions", "aws", "servicenow")
            .forEach { assertTrue(it in names, "$it is missing from $names") }
        ontology.sources.forEach { assertTrue(!it.description.isNullOrBlank(), "${it.name} has no description") }
    }
}
