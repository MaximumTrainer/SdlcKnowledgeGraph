package com.repodatagraph.acceptance.steps

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.repodatagraph.acceptance.support.ApiWorld
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.out.GraphStore
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.data.neo4j.core.Neo4jClient
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Freshness guarantees (#93): the stale flag on a read, each source's lag in health, and reads as of
 * an instant.
 *
 * What the API cannot be asked to state - when a fact was ingested, when it began to hold, when a
 * source last synced - is written straight into the graph, the way connector-freshness sets up an
 * old success: nobody waits nine hours for a fact to go stale. Everything else goes through HTTP. The
 * graph is emptied before every scenario, so nothing written here outlives the scenario.
 */
class FreshnessGuaranteeSteps(
    private val world: ApiWorld,
    private val neo4jClient: Neo4jClient,
    private val graphStore: GraphStore,
    private val restTemplate: TestRestTemplate,
    private val objectMapper: ObjectMapper,
) {
    /** The key of the CloudResource the scenario reads, once one exists. */
    private var cloudResourceKey: String? = null

    /** Deployment keys by the letter the scenario calls them. */
    private val deployments = mutableMapOf<String, String>()

    private var graphQlBody: JsonNode? = null

    @Given("source {string} has a freshness window of {int} hours")
    fun sourceHasAWindow(
        source: String,
        hours: Int,
    ) = assertPublishedWindow(source, hours)

    @Given("{string} has a freshness window of {int} hours")
    fun hasAWindow(
        source: String,
        hours: Int,
    ) = assertPublishedWindow(source, hours)

    @Given("a CloudResource ingested from {string} {int} hours ago")
    fun aCloudResourceIngestedHoursAgo(
        source: String,
        hours: Int,
    ) {
        world.post(
            "/api/v1/nodes/CloudResource",
            mapOf(
                "props" to
                    mapOf(
                        "provider" to "aws",
                        "resourceId" to "arn:aws:s3:::acme-logs",
                        "resourceType" to "s3-bucket",
                        "name" to "acme-logs",
                    ),
                "provenance" to mapOf("sourceSystem" to source),
            ),
        )
        assertEquals(201, world.lastStatus()) { world.lastResponse().body }
        val key = world.lastBody().path("key").asText()
        setOnNode("CloudResource", key, "prov_ingestedAt", Instant.now().minus(Duration.ofHours(hours.toLong())))
        cloudResourceKey = key
    }

    @When("the node is read")
    fun theNodeIsRead() {
        world.getExpanding("/api/v1/nodes/CloudResource/by-key?key={key}", checkNotNull(cloudResourceKey))
        assertEquals(200, world.lastStatus()) { world.lastResponse().body }
    }

    @Then("the provenance block contains stale {word}")
    fun theProvenanceBlockContainsStale(expected: String) {
        val stale = world.lastBody().path("provenance").path("stale")
        assertTrue(stale.isBoolean) { "provenance.stale is not a boolean: " + world.lastResponse().body }
        assertEquals(expected.toBooleanStrict(), stale.asBoolean())
    }

    @Given("the last successful SyncRun for {string} ended {int} hours ago")
    fun theLastSuccessfulRunEnded(
        source: String,
        hours: Int,
    ) {
        val finished = Instant.now().minus(Duration.ofHours(hours.toLong()))
        val id = UUID.randomUUID().toString()
        graphStore.upsertNode(
            GraphNode(
                key = NodeKey("SyncRun", id),
                props =
                    mapOf(
                        "id" to id,
                        "connector" to source,
                        "sourceSystem" to source,
                        "mode" to "FULL",
                        "status" to "SUCCESS",
                        "startedAt" to finished.minusSeconds(RUN_SECONDS),
                        "finishedAt" to finished,
                    ),
                provenance = Provenance.stated("sdlc-knowledge-graph"),
            ),
        )
    }

    @Then("the {string} component reports WARN with source {string} lag over {int}h")
    fun theComponentReportsWarn(
        component: String,
        source: String,
        hours: Int,
    ) {
        val health = world.lastBody().path("components").path(component)
        assertEquals("WARN", health.path("status").asText(null)) { "the $component component: $health" }
        val lag = health.path("details").path("sources").path(source)
        assertTrue(lag.path("lagging").asBoolean(false)) { "$source is not lagging: $health" }
        assertTrue(lag.path("lagSeconds").asLong() > hours * SECONDS_PER_HOUR) { "lag of $source: $lag" }
        assertEquals(hours * SECONDS_PER_HOUR, lag.path("windowSeconds").asLong()) { "window of $source: $lag" }
        assertTrue(lag.path("lastSuccessAt").isTextual) { "lastSuccessAt of $source: $lag" }
        assertTrue(health.path("details").path("lagging").any { it.asText() == source }) { "lagging: $health" }
    }

    @Given("Deployment {word} to production validFrom {word} validTo {word}")
    fun aDeploymentValidBetween(
        name: String,
        from: String,
        to: String,
    ) {
        ensureEnvironment("production")
        val deployedAt = at(from)
        world.post(
            "/api/v1/nodes/Deployment",
            mapOf(
                "props" to
                    mapOf(
                        "artifactKey" to "ghcr.io/acme/payments@sha256:$name",
                        "artifactId" to "ghcr.io/acme/payments@sha256:$name",
                        "environmentKey" to "production",
                        "environmentId" to "production",
                        "deployedAt" to deployedAt.toString(),
                        "status" to "SUCCESS",
                    ),
            ),
        )
        assertEquals(201, world.lastStatus()) { world.lastResponse().body }
        val key = world.lastBody().path("key").asText()
        deployments[name] = key
        world.post(
            "/api/v1/edges",
            mapOf("type" to "TO_ENVIRONMENT", "fromId" to "Deployment:$key", "toId" to "Environment:production"),
        )
        assertTrue(world.lastStatus() in SUCCESSFUL) { world.lastResponse().body }

        val validTo = if (to == "null") null else at(to)
        setOnNode("Deployment", key, "prov_validFrom", deployedAt)
        setOnNode("Deployment", key, "prov_validTo", validTo)
        neo4jClient
            .query(
                """
                MATCH (:Deployment { key: ${'$'}key })-[r:TO_ENVIRONMENT]->(:Environment { key: 'production' })
                SET r.prov_validFrom = ${'$'}from, r.prov_validTo = ${'$'}to
                """.trimIndent(),
            ).bindAll(mapOf("key" to key, "from" to deployedAt.atZone(ZoneOffset.UTC), "to" to validTo?.atZone(ZoneOffset.UTC)))
            .run()
    }

    @When("edges for the Environment {string} are read asOf {word}")
    fun edgesAreReadAsOf(
        environment: String,
        time: String,
    ) {
        world.getExpanding("/api/v1/edges?nodeId={id}&asOf={asOf}", "Environment:$environment", at(time).toString())
        assertEquals(200, world.lastStatus()) { world.lastResponse().body }
    }

    @When("edges for the Environment {string} are read without asOf")
    fun edgesAreReadWithoutAsOf(environment: String) {
        world.getExpanding("/api/v1/edges?nodeId={id}", "Environment:$environment")
        assertEquals(200, world.lastStatus()) { world.lastResponse().body }
    }

    @Then("only Deployment {word} is returned")
    fun onlyDeploymentIsReturned(name: String) {
        assertEquals(listOf(deployments.getValue(name)), listedDeployments())
    }

    @Then("Deployments {word} and {word} are returned")
    fun deploymentsAreReturned(
        first: String,
        second: String,
    ) {
        assertEquals(listOf(deployments.getValue(first), deployments.getValue(second)).sorted(), listedDeployments().sorted())
    }

    @When("Deployment {word} is read asOf {word}")
    fun aDeploymentIsReadAsOf(
        name: String,
        time: String,
    ) {
        world.getExpanding("/api/v1/nodes/Deployment/{key}?asOf={asOf}", deployments.getValue(name), at(time).toString())
    }

    @When("Deployment {word} is read by key asOf {word}")
    fun aDeploymentIsReadByKeyAsOf(
        name: String,
        time: String,
    ) {
        world.getExpanding("/api/v1/nodes/Deployment/by-key?key={key}&asOf={asOf}", deployments.getValue(name), at(time).toString())
    }

    @When("Deployment {word} is read without asOf")
    fun aDeploymentIsReadWithoutAsOf(name: String) {
        world.getExpanding("/api/v1/nodes/Deployment/by-key?key={key}", deployments.getValue(name))
    }

    @When("GraphQL asks for Deployment {word} asOf {word}")
    fun graphQlAsksAsOf(
        name: String,
        time: String,
    ) {
        val query = "query(\$id: ID!, \$asOf: String) { node(id: \$id, asOf: \$asOf) { id key provenance { validFrom validTo stale } } }"
        val response =
            restTemplate.postForEntity(
                "/graphql",
                mapOf(
                    "query" to query,
                    "variables" to mapOf("id" to "Deployment:" + deployments.getValue(name), "asOf" to at(time).toString()),
                ),
                String::class.java,
            )
        assertEquals(200, response.statusCode.value()) { response.body }
        graphQlBody = objectMapper.readTree(response.body)
    }

    @Then("GraphQL returns the node")
    fun graphQlReturnsTheNode() {
        val body = checkNotNull(graphQlBody)
        assertTrue(body.path("errors").isMissingNode || body.path("errors").isEmpty) { "errors: $body" }
        assertTrue(
            body
                .path("data")
                .path("node")
                .path("key")
                .isTextual,
        ) { "no node: $body" }
        assertTrue(
            body
                .path("data")
                .path("node")
                .path("provenance")
                .path("stale")
                .isBoolean,
        ) { "no stale flag: $body" }
    }

    @Then("GraphQL returns no node")
    fun graphQlReturnsNoNode() {
        val body = checkNotNull(graphQlBody)
        assertTrue(body.path("errors").isMissingNode || body.path("errors").isEmpty) { "errors: $body" }
        assertTrue(body.path("data").path("node").isNull) { "a node came back: $body" }
    }

    @Given("the Team {string} was valid from {string}")
    fun theTeamWasValidFrom(
        name: String,
        from: String,
    ) = setOnNode("Team", name, "prov_validFrom", Instant.parse(from))

    @When("I PUT the Team {string} with validTo {string}")
    fun iPutTheTeamWithValidTo(
        name: String,
        validTo: String,
    ) {
        world.put(
            "/api/v1/nodes/Team/$name",
            mapOf("props" to mapOf("name" to name), "provenance" to mapOf("validTo" to validTo)),
        )
    }

    @When("the Team {string} is read asOf {string}")
    fun theTeamIsReadAsOf(
        name: String,
        asOf: String,
    ) {
        world.getExpanding("/api/v1/nodes/Team/{key}?asOf={asOf}", name, asOf)
    }

    @Then("the provenance field {string} is {string}")
    fun theProvenanceFieldIs(
        field: String,
        value: String,
    ) {
        assertEquals(
            value,
            world
                .lastBody()
                .path("provenance")
                .path(field)
                .asText(null),
        ) { world.lastResponse().body }
    }

    @Then("an error names the field {string}")
    fun anErrorNamesTheField(field: String) {
        val fields = world.lastBody().path("errors").map { it.path("field").asText() }
        assertTrue(field in fields) { "expected an error on $field, got: " + world.lastResponse().body }
    }

    private fun assertPublishedWindow(
        source: String,
        hours: Int,
    ) {
        world.get("/api/v1/ontology")
        assertEquals(200, world.lastStatus())
        val window =
            world
                .lastBody()
                .path("freshness")
                .path("windows")
                .path(source)
                .asText(null)
        assertEquals(Duration.ofHours(hours.toLong()).toString(), window) { "published freshness: " + world.lastBody().path("freshness") }
    }

    private fun listedDeployments(): List<String> =
        world
            .lastBody()
            .path("items")
            .filter { it.path("other").path("type").asText() == "Deployment" }
            .map { it.path("other").path("key").asText() }

    private fun ensureEnvironment(name: String) {
        world.getExpanding("/api/v1/nodes/Environment/{name}", name)
        if (world.lastStatus() == 404) {
            world.post("/api/v1/nodes/Environment", mapOf("props" to mapOf("name" to name, "type" to "production")))
            assertEquals(201, world.lastStatus()) { world.lastResponse().body }
        }
    }

    private fun setOnNode(
        label: String,
        key: String,
        property: String,
        value: Instant?,
    ) {
        // The label and property are this class's own constants, never a scenario's text.
        val updated =
            neo4jClient
                .query("MATCH (n:$label { key: ${'$'}key }) SET n.$property = ${'$'}value RETURN count(n) AS c")
                .bindAll(mapOf("key" to key, "value" to value?.atZone(ZoneOffset.UTC)))
                .fetchAs(Long::class.javaObjectType)
                .one()
                .orElse(0L)
        assertEquals(1L, updated) { "no $label with key $key" }
    }

    /** "01:30" on the scenario's day, in UTC. */
    private fun at(time: String): Instant = Instant.parse("${DAY}T$time:00Z")

    private companion object {
        const val DAY = "2026-09-30"
        const val RUN_SECONDS = 90L
        const val SECONDS_PER_HOUR = 3600L
        val SUCCESSFUL = 200..299
    }
}
