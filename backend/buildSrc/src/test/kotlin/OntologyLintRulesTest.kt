import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * One test per lint rule (#81). Each starts from a registry that passes every rule and breaks exactly
 * one thing, so a finding is attributable to the rule under test and nothing else.
 */
class OntologyLintRulesTest {
    @Test
    fun `a complete registry has nothing to report`() {
        assertEquals(emptyList<String>(), lines(valid()))
    }

    @Test
    fun `ONT001 a property without a description`() {
        val ontology = valid().withProperty("Repository", "topics") { it.copy(description = null) }

        assertEquals(listOf("nodes.Repository.properties.topics: missing description [ONT001]"), lines(ontology))
    }

    @Test
    fun `ONT001 applies to an edge's properties too`() {
        val ontology = valid().copy(edgeTypes = valid().edgeTypes.map { edge -> edge.copy(properties = edge.properties.map { it.copy(description = null) }) })

        assertEquals(listOf("edges.OWNED_BY.properties.rule: missing description [ONT001]"), lines(ontology))
    }

    @Test
    fun `ONT002 a property without an example`() {
        val ontology = valid().withProperty("Team", "email") { it.copy(examples = emptyList()) }

        assertEquals(listOf("nodes.Team.properties.email: missing examples [ONT002]"), lines(ontology))
    }

    @Test
    fun `ONT003 an example outside the enum`() {
        val ontology = valid().withProperty("Repository", "visibility") { it.copy(examples = listOf("done")) }

        assertEquals(
            listOf("nodes.Repository.properties.visibility: example \"done\" is not one of public, private, internal [ONT003]"),
            lines(ontology),
        )
    }

    @Test
    fun `ONT003 an example of the wrong type`() {
        val ontology = valid().withProperty("Repository", "topics") { it.copy(examples = listOf("billing")) }

        assertEquals(listOf("nodes.Repository.properties.topics: example \"billing\" is not of type string[] [ONT003]"), lines(ontology))
    }

    @Test
    fun `ONT003 an example of the wrong format`() {
        val ontology = valid().withProperty("Team", "email") { it.copy(examples = listOf("platform")) }

        assertEquals(listOf("nodes.Team.properties.email: example \"platform\" is not of format email [ONT003]"), lines(ontology))
    }

    @Test
    fun `ONT003 an example node that the API would refuse`() {
        val ontology =
            valid().withType("Repository") {
                it.copy(examples = listOf(mapOf("url" to "https://github.com/acme/payments", "colour" to "blue")))
            }

        assertEquals(
            listOf(
                "nodes.Repository.examples[0]: example node lacks required property name [ONT003]",
                "nodes.Repository.examples[0]: example node sets colour, which the type does not declare [ONT003]",
            ),
            lines(ontology),
        )
    }

    @Test
    fun `ONT004 a node type without an example node`() {
        val ontology = valid().withType("Team") { it.copy(examples = emptyList()) }

        assertEquals(listOf("nodes.Team: no example node [ONT004]"), lines(ontology))
    }

    @Test
    fun `ONT005 a core node type without questions`() {
        val ontology = valid().withType("Repository") { it.copy(questions = emptyList()) }

        assertEquals(listOf("nodes.Repository: a core type names no questions it helps answer [ONT005]"), lines(ontology))
    }

    @Test
    fun `ONT005 does not ask questions of a type outside the core`() {
        val library =
            GenNodeType(
                name = "Library",
                description = "A third-party package a repository depends on.",
                identity = listOf("name"),
                properties = listOf(prop("name", required = true, description = "The package name as the manifest spells it", example = "left-pad")),
                examples = listOf(mapOf("name" to "left-pad")),
            )

        assertEquals(emptyList<String>(), lines(valid().copy(nodeTypes = valid().nodeTypes + library)))
    }

    @Test
    fun `ONT006 a duplicated enum value`() {
        val ontology =
            valid().withProperty("Repository", "visibility") { it.copy(enum = listOf("public", "private", "public")) }

        assertEquals(listOf("nodes.Repository.properties.visibility: enum value 'public' appears more than once [ONT006]"), lines(ontology))
    }

    @Test
    fun `ONT006 an enum that mixes naming conventions`() {
        val ontology =
            valid().withProperty("Repository", "visibility") {
                it.copy(enum = listOf("public_repo", "private-repo"), examples = listOf("public_repo"))
            }

        assertEquals(
            listOf("nodes.Repository.properties.visibility: enum mixes naming conventions: public_repo, private-repo [ONT006]"),
            lines(ontology),
        )
    }

    @Test
    fun `ONT006 an enum value in no convention at all`() {
        val ontology =
            valid().withProperty("Repository", "visibility") { it.copy(enum = listOf("Public", "private"), examples = listOf("private")) }

        assertEquals(
            listOf(
                "nodes.Repository.properties.visibility: enum value 'Public' is not snake_case, kebab-case or UPPER_SNAKE_CASE [ONT006]",
            ),
            lines(ontology),
        )
    }

    @Test
    fun `ONT006 accepts one convention per enum, whichever it is`() {
        listOf(listOf("in_progress", "done"), listOf("container-image", "jar"), listOf("IN_PROGRESS", "SUCCESS")).forEach { values ->
            val ontology = valid().withProperty("Repository", "visibility") { it.copy(enum = values, examples = listOf(values.first())) }
            assertEquals(emptyList<String>(), lines(ontology), values.toString())
        }
    }

    @Test
    fun `ONT007 a format the registry does not know`() {
        val ontology = valid().withProperty("Team", "email") { it.copy(format = "mailbox") }

        assertEquals(listOf("nodes.Team.properties.email: unknown format 'mailbox' [ONT007]"), lines(ontology))
    }

    @Test
    fun `ONT007 a conditional format on a property the type does not declare`() {
        val ontology = valid().withProperty("Repository", "url") { it.copy(formatWhen = mapOf("host" to "github.com")) }

        assertEquals(
            listOf("nodes.Repository.properties.url: formatWhen names 'host', which the type does not declare [ONT007]"),
            lines(ontology),
        )
    }

    @Test
    fun `ONT008 a deprecation replaced by nothing that exists`() {
        val ontology =
            valid().withProperty("Repository", "serviceId") { it.copy(deprecated = GenDeprecation("1.3.0", "SERVED_BY")) }

        assertEquals(
            listOf("nodes.Repository.properties.serviceId: deprecated in favour of 'SERVED_BY', which is neither a property of Repository nor an edge type [ONT008]"),
            lines(ontology),
        )
    }

    @Test
    fun `ONT008 accepts a property of the same type as the replacement`() {
        val ontology = valid().withProperty("Repository", "serviceId") { it.copy(deprecated = GenDeprecation("1.3.0", "name")) }

        assertEquals(emptyList<String>(), lines(ontology))
    }

    @Test
    fun `ONT009 a description too short to say anything`() {
        val ontology = valid().withProperty("Team", "name") { it.copy(description = "The name") }

        assertEquals(listOf("nodes.Team.properties.name: description is under 20 characters [ONT009]"), lines(ontology))
    }

    @Test
    fun `ONT009 a description that only repeats the name`() {
        val ontology = valid().withProperty("Repository", "defaultBranch") { it.copy(description = "defaultBranch") }

        assertTrue(lines(ontology).contains("nodes.Repository.properties.defaultBranch: description only repeats the property name [ONT009]"))
    }

    @Test
    fun `ONT009 is a warning when warnings are allowed, and every other rule stays an error`() {
        val findings = OntologyLint.lint(valid().withProperty("Team", "name") { it.copy(description = "The name") })

        assertEquals(listOf("ONT009"), findings.map { it.code })
        assertTrue(OntologyLint.errors(findings, warnOnly = true).isEmpty())
        assertEquals(1, OntologyLint.errors(findings, warnOnly = false).size)
    }

    @Test
    fun `ONT010 an edge type without a description`() {
        val ontology = valid().copy(edgeTypes = valid().edgeTypes.map { if (it.name == "OWNED_BY") it.copy(description = null) else it })

        assertEquals(listOf("edges.OWNED_BY: missing description [ONT010]"), lines(ontology))
    }

    @Test
    fun `ONT010 an edge type connecting a type that is not declared`() {
        val ontology = valid().copy(edgeTypes = valid().edgeTypes.map { if (it.name == "OWNED_BY") it.copy(to = listOf("Team", "Squad")) else it })

        assertEquals(listOf("edges.OWNED_BY: to names undeclared node type 'Squad' [ONT010]"), lines(ontology))
    }

    @Test
    fun `ONT011 a deprecated identity property`() {
        val ontology = valid().withProperty("Team", "name") { it.copy(deprecated = GenDeprecation("1.3.0", "email")) }

        assertEquals(listOf("nodes.Team.properties.name: identity property is deprecated [ONT011]"), lines(ontology))
    }

    @Test
    fun `ONT012 an environment alias that names two environments`() {
        val ontology =
            valid().copy(
                environments =
                    listOf(
                        GenEnvironment("production", "The environment customers use", listOf("prod", "live")),
                        GenEnvironment("demo", "The environment prospects are shown", listOf("live")),
                    ),
            )

        assertEquals(listOf("environments.demo: alias 'live' already names production [ONT012]"), lines(ontology))
    }

    @Test
    fun `ONT012 an environment alias that is another environment's name, or a name without a description`() {
        val ontology =
            valid().copy(
                environments =
                    listOf(
                        GenEnvironment("production", "The environment customers use", listOf("staging")),
                        GenEnvironment("staging", null, emptyList()),
                    ),
            )

        assertEquals(
            listOf(
                "environments.production: alias 'staging' is the name of another environment [ONT012]",
                "environments.staging: missing description [ONT012]",
            ),
            lines(ontology),
        )
    }

    @Test
    fun `ONT013 a merge scope naming a property the type does not declare`() {
        val ontology = valid().withType("Team") { it.copy(mergeScope = listOf("name", "colour")) }

        assertEquals(listOf("nodes.Team: mergeScope names 'colour', which the type does not declare [ONT013]"), lines(ontology))
    }

    @Test
    fun `the report groups findings under the type they are about, errors counted`() {
        val ontology =
            valid()
                .withProperty("Repository", "topics") { it.copy(description = null) }
                .withType("Team") { it.copy(examples = emptyList()) }

        val report = OntologyLint.report(OntologyLint.lint(ontology), warnOnly = false)

        assertEquals(
            """
            |Ontology lint: 2 errors, 0 warnings
            |nodes.Repository
            |  nodes.Repository.properties.topics: missing description [ONT001]
            |nodes.Team
            |  nodes.Team: no example node [ONT004]
            """.trimMargin(),
            report,
        )
    }

    private fun lines(ontology: GenOntology): List<String> = OntologyLint.lint(ontology).map { it.line }

    private fun GenOntology.withType(
        name: String,
        change: (GenNodeType) -> GenNodeType,
    ) = copy(nodeTypes = nodeTypes.map { if (it.name == name) change(it) else it })

    private fun GenOntology.withProperty(
        type: String,
        property: String,
        change: (GenProperty) -> GenProperty,
    ) = withType(type) { nodeType -> nodeType.copy(properties = nodeType.properties.map { if (it.name == property) change(it) else it }) }

    private fun valid() =
        GenOntology(
            version = "1.3.0",
            nodeTypes =
                listOf(
                    GenNodeType(
                        name = "Repository",
                        description = "A git repository, the anchor for most of the graph.",
                        identity = listOf("name"),
                        properties =
                            listOf(
                                prop("name", required = true, description = "The repository's name within its organisation", example = "payments"),
                                prop("url", required = true, description = "Where the repository is served, canonicalised", example = "https://github.com/acme/payments")
                                    .copy(format = "url"),
                                prop("defaultBranch", description = "The branch a change is merged into", example = "main"),
                                prop("topics", type = "string[]", description = "Topics the forge lists for it, as labels", example = listOf("billing")),
                                prop("visibility", description = "Who may read it, as the forge reports it", example = "private")
                                    .copy(enum = listOf("public", "private", "internal")),
                                prop("serviceId", description = "The service it provides, from before PROVIDES", example = "payments-api")
                                    .copy(deprecated = GenDeprecation("1.3.0", "PROVIDES")),
                            ),
                        examples = listOf(mapOf("name" to "payments", "url" to "https://github.com/acme/payments", "topics" to listOf("billing"))),
                        questions = listOf("Which team owns this repository?"),
                    ),
                    GenNodeType(
                        name = "Team",
                        description = "A group that owns repositories and services.",
                        identity = listOf("name"),
                        properties =
                            listOf(
                                prop("name", required = true, description = "The team's name, as the organisation spells it", example = "platform"),
                                prop("email", description = "Where the team is reached, one address", example = "platform@acme.example")
                                    .copy(format = "email"),
                            ),
                        examples = listOf(mapOf("name" to "platform")),
                        questions = listOf("Which repositories does this team own?"),
                    ),
                ),
            edgeTypes =
                listOf(
                    GenEdgeType(
                        name = "OWNED_BY",
                        description = "Ownership of a repository by a team.",
                        from = listOf("Repository"),
                        to = listOf("Team"),
                        inverse = "OWNS",
                        properties =
                            listOf(
                                prop("rule", description = "Which evidence established the ownership", example = "codeowners")
                                    .copy(enum = listOf("manual", "codeowners")),
                            ),
                    ),
                    GenEdgeType(
                        name = "PROVIDES",
                        description = "A repository provides a running service.",
                        from = listOf("Repository"),
                        to = listOf("Repository"),
                        inverse = "PROVIDED_BY",
                        properties = emptyList(),
                    ),
                ),
        )

    private fun prop(
        name: String,
        type: String = "string",
        required: Boolean = false,
        description: String,
        example: Any,
    ) = GenProperty(name, type, required, description, examples = listOf(example))
}
