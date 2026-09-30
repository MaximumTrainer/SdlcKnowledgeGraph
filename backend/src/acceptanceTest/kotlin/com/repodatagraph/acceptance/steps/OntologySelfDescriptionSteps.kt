package com.repodatagraph.acceptance.steps

import com.fasterxml.jackson.databind.JsonNode
import com.repodatagraph.acceptance.support.ApiWorld
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.support.EnumConformanceReport
import com.repodatagraph.support.NonConformingValue
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.data.neo4j.core.Neo4jClient
import java.io.File

/**
 * What the registry tells a machine reader about each property (#81), what the API enforces from it,
 * and the Markdown rendering of it an agent is handed.
 */
class OntologySelfDescriptionSteps(
    private val world: ApiWorld,
    private val graphStore: GraphStore,
    private val identityResolver: IdentityResolver,
    private val registry: OntologyRegistry,
    private val neo4jClient: Neo4jClient,
) {
    private val markdownBodies = mutableListOf<String>()
    private var report: List<NonConformingValue> = emptyList()
    private var deploymentKey: NodeKey? = null

    @Then("every property of every node and edge type has a description and at least one example")
    fun everyPropertyIsDescribed() {
        val undescribed =
            ownersOfProperties().flatMap { (owner, properties) ->
                properties.filter { it.path("description").asText("").isBlank() }.map { "$owner.${it.path("name").asText()}" }
            }
        val withoutExample =
            ownersOfProperties().flatMap { (owner, properties) ->
                properties.filter { it.path("examples").size() == 0 }.map { "$owner.${it.path("name").asText()}" }
            }

        assertEquals(emptyList<String>(), undescribed, "properties served without a description")
        assertEquals(emptyList<String>(), withoutExample, "properties served without an example")
    }

    @Then("every node type has an example node")
    fun everyNodeTypeHasAnExample() {
        val without = nodeTypes().filter { it.path("examples").size() == 0 }.map { it.path("name").asText() }

        assertEquals(emptyList<String>(), without, "node types served without an example node")
    }

    @Then("the core node types each name the questions they help answer")
    fun theCoreTypesNameTheirQuestions() {
        val without =
            nodeTypes()
                .filter { it.path("name").asText() in CORE_TYPES }
                .filter { it.path("questions").size() == 0 }
                .map { it.path("name").asText() }

        assertEquals(CORE_TYPES.size, nodeTypes().count { it.path("name").asText() in CORE_TYPES })
        assertEquals(emptyList<String>(), without, "core node types served without questions")
    }

    @When("I POST a Deployment with status {string} and otherwise valid properties")
    fun iPostADeploymentWithStatus(status: String) {
        world.post("/api/v1/nodes/Deployment", mapOf("props" to deploymentProps(status)))
    }

    @When("I POST a PullRequest with url {string}")
    fun iPostAPullRequestWithUrl(url: String) {
        world.post(
            "/api/v1/nodes/PullRequest",
            mapOf("props" to mapOf("repositoryKey" to "github.com/acme/payments", "number" to 42, "url" to url)),
        )
    }

    @When("I POST a Team named {string} with email {string}")
    fun iPostATeamWithEmail(
        name: String,
        email: String,
    ) {
        world.post("/api/v1/nodes/Team", mapOf("props" to mapOf("name" to name, "email" to email)))
    }

    @When("I POST a Team named {string} with no email")
    fun iPostATeamWithNoEmail(name: String) {
        world.post("/api/v1/nodes/Team", mapOf("props" to mapOf("name" to name)))
    }

    @Then("the errors name the field {string} with the message {string}")
    fun theErrorsNameTheField(
        field: String,
        message: String,
    ) {
        val errors = world.lastBody().path("errors").map { it.path("field").asText() to it.path("message").asText() }

        assertTrue(field to message in errors) { "expected $field: $message among $errors" }
    }

    @Then("the generated GraphQL declares {string} deprecated")
    fun theGeneratedGraphqlDeclaresDeprecated(path: String) {
        val (type, field) = path.split('.')
        val body = read(SDL).substringAfter("type $type implements GraphNode {").substringBefore("\n}")

        assertTrue(
            body.lines().any { it.trim().startsWith("$field:") && it.contains("@deprecated(") },
            "$SDL does not declare $path with @deprecated:\n$body",
        )
    }

    @Then("the generated TypeScript marks {string} deprecated")
    fun theGeneratedTypescriptMarksDeprecated(path: String) {
        val (type, field) = path.split('.')
        val body = read(TYPESCRIPT).substringAfter("export interface $type {").substringBefore("\n}")
        val doc = body.substringBefore("\n  $field").substringAfterLast("/**")

        assertTrue(doc.contains("@deprecated"), "$TYPESCRIPT does not mark $path with @deprecated:\n$body")
    }

    @Then("an enum {string} is generated in both the GraphQL and the TypeScript")
    fun anEnumIsGeneratedInBoth(name: String) {
        assertTrue(read(SDL).contains("enum $name {"), "$SDL declares no enum $name")
        assertTrue(read(TYPESCRIPT).contains("export type $name ="), "$TYPESCRIPT declares no union $name")
    }

    @When("I GET the ontology as Markdown twice")
    fun iGetTheOntologyAsMarkdownTwice() {
        repeat(2) { markdownBodies += world.get(MARKDOWN).body.orEmpty() }
    }

    @Then("the two Markdown bodies are byte-identical")
    fun theTwoBodiesAreIdentical() {
        assertEquals(2, markdownBodies.size)
        assertTrue(markdownBodies[0].isNotBlank(), "the Markdown body is empty")
        assertEquals(markdownBodies[0], markdownBodies[1])
        assertTrue(
            world.lastHeader("Content-Type").orEmpty().startsWith("text/markdown"),
            "Content-Type is ${world.lastHeader("Content-Type")}",
        )
    }

    @Then("the Markdown body is under {int} characters")
    fun theMarkdownBodyIsUnder(limit: Int) {
        assertTrue(markdownBodies.last().length < limit, "the Markdown is ${markdownBodies.last().length} characters")
    }

    @Then("the Markdown contains {string} under {string}")
    fun theMarkdownContainsUnder(
        text: String,
        type: String,
    ) {
        val section = markdownBodies.last().substringAfter("\n## $type\n", "").substringBefore("\n## ")

        assertTrue(section.contains(text), "the $type section does not contain '$text':\n$section")
    }

    @Given("a Deployment node exists with status {string} written before the enum was declared")
    fun aDeploymentExistsWithStatus(status: String) {
        // Straight to the store, as a writer that predates the enum did: the API would refuse it now.
        val props = deploymentProps(status)
        val key = identityResolver.keyFor("Deployment", props)
        graphStore.upsertNode(GraphNode(key, props, Provenance.manual()))
        deploymentKey = key
    }

    @When("the enum conformance report runs")
    fun theEnumConformanceReportRuns() {
        report = EnumConformanceReport(neo4jClient, registry).run()
    }

    @Then("the report lists {string} value {string} with count {int}")
    fun theReportLists(
        path: String,
        value: String,
        count: Int,
    ) {
        val row = report.firstOrNull { it.path == path && it.value == value }

        assertEquals(count.toLong(), row?.count, "the report was $report")
    }

    @Then("reading that Deployment still returns status {string}")
    fun readingThatDeploymentStillReturns(status: String) {
        val key = checkNotNull(deploymentKey) { "no Deployment was written in this scenario" }
        world.getExpanding("/api/v1/nodes/Deployment/by-key?key={key}", key.key)

        assertEquals(200, world.lastStatus(), world.lastResponse().body)
        assertEquals(
            status,
            world
                .lastBody()
                .path("props")
                .path("status")
                .asText(),
        )
    }

    private fun nodeTypes(): List<JsonNode> = world.lastBody().path("nodeTypes").toList()

    private fun ownersOfProperties(): List<Pair<String, List<JsonNode>>> =
        (world.lastBody().path("nodeTypes") + world.lastBody().path("edgeTypes"))
            .map { it.path("name").asText() to it.path("properties").toList() }

    private fun deploymentProps(status: String) =
        mapOf(
            "artifactKey" to "ghcr.io/acme/payments@sha256:1",
            "environmentKey" to "production",
            "deployedAt" to "2026-09-30T12:00:00Z",
            "artifactId" to "Artifact:ghcr.io/acme/payments@sha256:1",
            "environmentId" to "Environment:production",
            "status" to status,
        )

    /** Paths are relative to the backend project directory, which is the test's working directory. */
    private fun read(path: String): String = File(path).readText()

    private companion object {
        const val MARKDOWN = "/api/v1/ontology?format=markdown"
        const val SDL = "src/main/resources/graphql/schema.generated.graphqls"
        const val TYPESCRIPT = "../frontend/src/generated/ontology.ts"
        val CORE_TYPES =
            setOf(
                "Repository",
                "Team",
                "Service",
                "Pipeline",
                "Artifact",
                "Deployment",
                "Environment",
                "CloudResource",
                "ConfigurationItem",
            )
    }
}
