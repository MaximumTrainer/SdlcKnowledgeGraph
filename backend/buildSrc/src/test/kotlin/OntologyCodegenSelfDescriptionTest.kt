import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * How the registry's self-description reaches the generated files (#81): descriptions and
 * deprecations as GraphQL docstrings and `@deprecated`, as TypeScript JSDoc with `@example` and
 * `@deprecated`, enums as GraphQL enum types and TypeScript unions, and all of it in ontology.json.
 */
class OntologyCodegenSelfDescriptionTest {
    private val ontology =
        GenOntology(
            version = "1.3.0",
            nodeTypes =
                listOf(
                    GenNodeType(
                        name = "Deployment",
                        description = "One event of putting an artifact into an environment.",
                        identity = listOf("id"),
                        properties =
                            listOf(
                                GenProperty("id", "string", true, "Never emitted as a field", examples = listOf("d1")),
                                GenProperty(
                                    "status",
                                    "string",
                                    required = true,
                                    description = "Whether the deployment worked",
                                    enum = listOf("SUCCESS", "FAILED"),
                                    examples = listOf("SUCCESS"),
                                ),
                                GenProperty(
                                    "artifactType",
                                    "string",
                                    required = false,
                                    description = "What kind of artifact was deployed",
                                    enum = listOf("container-image", "jar"),
                                    examples = listOf("container-image"),
                                ),
                                GenProperty(
                                    "environmentId",
                                    "string",
                                    required = true,
                                    description = "The environment's id, from before the key",
                                    examples = listOf("Environment:production"),
                                    deprecated = GenDeprecation("1.3.0", "environmentKey"),
                                ),
                                GenProperty(
                                    "environmentKey",
                                    "string",
                                    required = false,
                                    description = "The key of the environment it targeted",
                                    format = null,
                                    examples = listOf("production"),
                                ),
                                GenProperty(
                                    "runUrl",
                                    "string",
                                    required = false,
                                    description = "Where the run can be read",
                                    format = "url",
                                    examples = listOf("https://github.com/acme/payments/actions/runs/1"),
                                ),
                                GenProperty(
                                    "resourceId",
                                    "string",
                                    required = false,
                                    description = "An identifier in the cloud",
                                    format = "arn",
                                    formatWhen = mapOf("provider" to "aws"),
                                    examples = listOf("arn:aws:s3:::logs"),
                                ),
                                GenProperty("tags", "string[]", false, "Labels the pipeline set", examples = listOf(listOf("blue", "green"))),
                                GenProperty("attempt", "int", false, "Which attempt this was", examples = listOf(2)),
                            ),
                        examples = listOf(mapOf("id" to "d1", "status" to "SUCCESS", "tags" to listOf("blue"))),
                        questions = listOf("What was deployed where?", "Which deployments failed?"),
                    ),
                ),
            edgeTypes =
                listOf(
                    GenEdgeType(
                        name = "OWNS_RESOURCE",
                        description = "A repository is responsible for a piece of infrastructure.",
                        from = listOf("Deployment"),
                        to = listOf("Deployment"),
                        inverse = "OWNED_BY_REPO",
                        properties =
                            listOf(
                                GenProperty(
                                    "rule",
                                    "string",
                                    false,
                                    "Which link rule proposed it",
                                    enum = listOf("tag", "iac"),
                                    examples = listOf("tag"),
                                ),
                            ),
                    ),
                ),
        )

    private val sdl = OntologyCodegen.graphqlSdl(ontology)
    private val typescript = OntologyCodegen.typescript(ontology)
    private val json = OntologyCodegen.json(ontology)

    private val deploymentNode = sdl.substringAfter("type DeploymentNode implements GraphNode {").substringBefore("\n}")
    private val deploymentInterface = typescript.substringAfter("export interface Deployment {").substringBefore("\n}")

    @Test
    fun `every SDL field carries its description as a docstring`() {
        assertTrue(deploymentNode.contains("  \"\"\"Where the run can be read\"\"\"\n  runUrl: String\n"), deploymentNode)
    }

    @Test
    fun `an SDL enum is generated for each enum property, and named in the field's docstring`() {
        assertTrue(sdl.contains("\"\"\"The values Deployment.status may take.\"\"\"\nenum DeploymentStatus {\n  SUCCESS\n  FAILED\n}"), sdl)
        assertTrue(
            deploymentNode.contains("  \"\"\"Whether the deployment worked. One of DeploymentStatus.\"\"\"\n  status: String!\n"),
            deploymentNode,
        )
    }

    @Test
    fun `an SDL enum value that is not a GraphQL name is spelt with underscores, and says how it is stored`() {
        assertTrue(
            sdl.contains("enum DeploymentArtifactType {\n  \"\"\"Stored as container-image.\"\"\"\n  container_image\n  jar\n}"),
            sdl,
        )
    }

    @Test
    fun `a deprecated SDL field says since when and what replaces it`() {
        assertTrue(
            deploymentNode.contains("  environmentId: String! @deprecated(reason: \"since 1.3.0, replaced by environmentKey\")\n"),
            deploymentNode,
        )
    }

    @Test
    fun `a TypeScript union is generated for each enum property, and types the property`() {
        assertTrue(typescript.contains("export type DeploymentStatus = 'SUCCESS' | 'FAILED'\n"), typescript)
        assertTrue(typescript.contains("export type DeploymentArtifactType = 'container-image' | 'jar'\n"), typescript)
        assertTrue(deploymentInterface.contains("\n  status: DeploymentStatus\n"), deploymentInterface)
        assertTrue(deploymentInterface.contains("\n  artifactType?: DeploymentArtifactType\n"), deploymentInterface)
    }

    @Test
    fun `a union too long for one line is broken as Prettier would break it`() {
        val many = (1..12).map { "value_number_$it" }
        val wide =
            ontology.copy(
                nodeTypes =
                    listOf(
                        ontology.nodeTypes.single().copy(
                            properties = listOf(GenProperty("stage", "string", false, "A long list of values", enum = many, examples = listOf(many[0]))),
                        ),
                    ),
            )

        val union = OntologyCodegen.typescript(wide).substringAfter("export type DeploymentStage =").substringBefore("\n\n")

        assertEquals(many.joinToString(separator = "") { "\n  | '$it'" }, union)
    }

    @Test
    fun `a union that fits on the next line goes there, as Prettier puts it`() {
        val values = listOf("PENDING", "IN_PROGRESS", "SUCCESS", "FAILED", "ROLLED_BACK", "CANCELLED")
        val status = GenProperty("status", "string", true, "Whether the deployment worked", enum = values, examples = listOf("SUCCESS"))
        val wide = ontology.copy(nodeTypes = listOf(ontology.nodeTypes.single().copy(properties = listOf(status))))

        assertTrue(
            OntologyCodegen.typescript(wide).contains(
                "export type DeploymentStatus =\n  'PENDING' | 'IN_PROGRESS' | 'SUCCESS' | 'FAILED' | 'ROLLED_BACK' | 'CANCELLED'\n",
            ),
        )
    }

    @Test
    fun `every TypeScript property carries JSDoc with its description and first example`() {
        assertTrue(
            deploymentInterface.contains(
                "  /**\n   * Whether the deployment worked\n   * @example \"SUCCESS\"\n   */\n  status: DeploymentStatus\n",
            ),
            deploymentInterface,
        )
        assertTrue(deploymentInterface.contains("   * @example [\"blue\",\"green\"]\n   */\n  tags?: string[]\n"), deploymentInterface)
        assertTrue(deploymentInterface.contains("   * @example 2\n   */\n  attempt?: number"), deploymentInterface)
        assertFalse(deploymentInterface.contains("Never emitted as a field"), deploymentInterface)
    }

    @Test
    fun `a deprecated TypeScript property is tagged @deprecated, saying what replaces it`() {
        assertTrue(
            deploymentInterface.contains(
                "   * @example \"Environment:production\"\n   * @deprecated since 1.3.0, replaced by environmentKey\n   */\n  environmentId: string\n",
            ),
            deploymentInterface,
        )
    }

    @Test
    fun `the JSON snapshot carries examples, enums, formats and deprecations`() {
        assertTrue(json.contains(""""name": "status", "type": "string", "required": true, "description": "Whether the deployment worked", "enum": ["SUCCESS", "FAILED"], "examples": ["SUCCESS"] }"""), json)
        assertTrue(json.contains(""""format": "arn", "formatWhen": {"provider":"aws"}, "examples": ["arn:aws:s3:::logs"] }"""), json)
        assertTrue(json.contains(""""examples": [["blue","green"]] }"""), json)
        assertTrue(json.contains(""""examples": ["Environment:production"], "deprecated": { "since": "1.3.0", "replacedBy": "environmentKey" } }"""), json)
        assertTrue(json.contains(""""name": "rule", "type": "string", "required": false, "description": "Which link rule proposed it", "enum": ["tag", "iac"], "examples": ["tag"] }"""), json)
    }

    @Test
    fun `the JSON snapshot carries each type's questions and example nodes`() {
        assertTrue(json.contains(""""questions": ["What was deployed where?", "Which deployments failed?"],"""), json)
        assertTrue(json.contains(""""examples": [{"id":"d1","status":"SUCCESS","tags":["blue"]}]"""), json)
        assertTrue(json.contains("      \"questions\": [],\n      \"examples\": []\n"), json)
    }

    @Test
    fun `generating twice produces identical bytes`() {
        assertEquals(sdl, OntologyCodegen.graphqlSdl(ontology))
        assertEquals(typescript, OntologyCodegen.typescript(ontology))
        assertEquals(json, OntologyCodegen.json(ontology))
    }
}
