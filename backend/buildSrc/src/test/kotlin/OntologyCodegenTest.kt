import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The generator is the only thing standing between one ontology declaration and five hand-written
 * restatements of it. These tests pin the two things a consumer depends on: that a registry type
 * maps to the shape the consumer expects, and that generating twice produces the same bytes, which
 * is what lets the drift check treat any difference as an error rather than noise.
 */
class OntologyCodegenTest {
    private val ontology =
        GenOntology(
            version = "1.2.3",
            nodeTypes =
                listOf(
                    GenNodeType(
                        name = "Repository",
                        description = "A git repository.",
                        identity = listOf("host", "org", "name"),
                        properties =
                            listOf(
                                GenProperty("name", "string", required = true, description = "Repository name"),
                                GenProperty("language", "string", required = false, description = null),
                                GenProperty("topics", "string[]", required = true, description = null),
                                GenProperty("stars", "int", required = false, description = null),
                                GenProperty("archived", "boolean", required = true, description = null),
                                GenProperty("id", "string", required = false, description = "Never emitted"),
                            ),
                    ),
                ),
            edgeTypes =
                listOf(
                    GenEdgeType(
                        name = "BUILT_FROM",
                        description = "An artifact was built from a repository.",
                        from = listOf("Artifact"),
                        to = listOf("Repository"),
                        inverse = "BUILDS",
                        properties = listOf(GenProperty("commitSha", "string", required = true, description = null)),
                    ),
                ),
        )

    private val sdl = OntologyCodegen.graphqlSdl(ontology)
    private val typescript = OntologyCodegen.typescript(ontology)
    private val json = OntologyCodegen.json(ontology)

    @Test
    fun `generated SDL marks every file as generated`() {
        assertTrue(sdl.startsWith("# GENERATED FROM ontology/v1 - DO NOT EDIT"))
    }

    @Test
    fun `every node type implements the GraphNode interface`() {
        assertTrue(sdl.contains("interface GraphNode {"))
        assertTrue(sdl.contains("type RepositoryNode implements GraphNode {"))
        assertTrue(sdl.contains("  id: ID!"))
        assertTrue(sdl.contains("  key: String!"))
        assertTrue(sdl.contains("  provenance: Provenance!"))
    }

    @Test
    fun `SDL property types follow the registry type mapping`() {
        assertTrue(sdl.contains("  name: String!"), sdl)
        assertTrue(sdl.contains("  language: String\n"), sdl)
        assertTrue(sdl.contains("  topics: [String!]!"), sdl)
        assertTrue(sdl.contains("  stars: Int\n"), sdl)
        assertTrue(sdl.contains("  archived: Boolean!"), sdl)
    }

    @Test
    fun `edge types become an enum and a typed Edge`() {
        assertTrue(sdl.contains("enum EdgeType {"))
        assertTrue(sdl.contains("  BUILT_FROM"))
        assertTrue(sdl.contains("  inverse: String!"))
    }

    @Test
    fun `a registry property named id does not collide with the structural one`() {
        // Every generated type already carries `id`. Emitting the registry's own `id` property as
        // well produces a type that will not compile, in either language.
        val repositoryType = sdl.substringAfter("type RepositoryNode").substringBefore("\n}")
        assertEquals(1, repositoryType.lines().count { it.trimEnd() == "  id: ID!" }, repositoryType)

        val repositoryInterface = typescript.substringAfter("export interface Repository {").substringBefore("\n}")
        assertEquals(1, repositoryInterface.lines().count { it.trimEnd() == "  id: string" }, repositoryInterface)
    }

    @Test
    fun `generated TypeScript marks optional properties and maps types`() {
        assertTrue(typescript.startsWith("// GENERATED FROM ontology/v1 - DO NOT EDIT"))
        assertTrue(typescript.contains("export interface Repository {"))
        assertTrue(typescript.contains("  name: string\n"), typescript)
        assertTrue(typescript.contains("  language?: string"), typescript)
        assertTrue(typescript.contains("  topics: string[]"), typescript)
        assertTrue(typescript.contains("  stars?: number"), typescript)
        assertTrue(typescript.contains("  archived: boolean"), typescript)
    }

    @Test
    fun `generated TypeScript publishes the registry as values as well as types`() {
        assertTrue(typescript.contains("export const ONTOLOGY_VERSION = '1.2.3'"))
        assertTrue(typescript.contains("export type NodeType =\n  | 'Repository'"))
        assertTrue(typescript.contains("export const NODE_TYPES: readonly NodeType[] = [\n  'Repository'\n]"))
        assertTrue(typescript.contains("export const EDGE_TYPES: readonly EdgeTypeName[] = [\n  'BUILT_FROM'\n]"))
    }

    @Test
    fun `the JSON snapshot carries the fields the ontology endpoint returns`() {
        assertTrue(json.contains(""""version": "1.2.3","""))
        assertTrue(json.contains(""""identity": ["host", "org", "name"],"""), json)
        assertTrue(json.contains(""""name": "topics", "type": "string[]", "required": true"""), json)
        assertTrue(json.contains(""""inverse": "BUILDS","""), json)
        assertTrue(json.contains(""""description": null"""), json)
    }

    @Test
    fun `the JSON snapshot leaves the structural id in place for the endpoint to match`() {
        // Unlike the SDL and TypeScript views, the snapshot is a copy of what the registry declares,
        // because it is compared against the ontology endpoint's own serialisation.
        assertTrue(json.contains(""""name": "id","""), json)
    }

    @Test
    fun `generating twice produces identical bytes`() {
        assertEquals(sdl, OntologyCodegen.graphqlSdl(ontology))
        assertEquals(typescript, OntologyCodegen.typescript(ontology))
        assertEquals(json, OntologyCodegen.json(ontology))
    }

    @Test
    fun `generated files carry no timestamp that would make them differ between runs`() {
        val year = Regex("""20\d\d-\d\d-\d\d""")
        assertFalse(year.containsMatchIn(sdl))
        assertFalse(year.containsMatchIn(typescript))
    }
}
