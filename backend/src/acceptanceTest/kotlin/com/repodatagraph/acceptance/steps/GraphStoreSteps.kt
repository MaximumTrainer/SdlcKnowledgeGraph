package com.repodatagraph.acceptance.steps

import com.repodatagraph.acceptance.support.ApiWorld
import com.repodatagraph.domain.exception.UnknownNodeTypeException
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.out.GraphStore
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.data.neo4j.core.Neo4jClient
import java.time.Instant

class GraphStoreSteps(
    private val world: ApiWorld,
    private val graphStore: GraphStore,
    private val neo4jClient: Neo4jClient,
) {
    private var lastArtifactKey: NodeKey? = null
    private var rejection: Exception? = null

    @Given("a Repository {string} exists")
    fun aRepositoryExists(key: String) {
        graphStore.upsertNode(
            GraphNode(
                key = NodeKey("Repository", key),
                props = mapOf("orgRepo" to key.substringAfter('/'), "defaultBranch" to "main"),
                provenance = Provenance.manual(),
            ),
        )
    }

    @Given("a Team {string} exists")
    fun aTeamExists(key: String) {
        graphStore.upsertNode(
            GraphNode(NodeKey("Team", key), mapOf("name" to key), Provenance.manual()),
        )
    }

    @Given("an Artifact {string} built from commit {string} of that repository")
    fun anArtifactBuiltFromCommit(
        artifactKey: String,
        commitSha: String,
    ) {
        val key = NodeKey("Artifact", artifactKey)
        lastArtifactKey = key
        graphStore.upsertNode(
            GraphNode(
                key = key,
                props = mapOf("name" to artifactKey, "version" to commitSha, "artifactType" to "docker"),
                provenance = Provenance.manual(),
            ),
        )
        graphStore.upsertEdge(
            GraphEdge(
                type = "BUILT_FROM",
                from = key,
                to = NodeKey("Repository", "github.com/acme/payments"),
                props = mapOf("commitSha" to commitSha),
                provenance = Provenance.manual(),
            ),
        )
    }

    @Given("a Deployment of that artifact to Environment {string} with status {string}")
    fun aDeploymentOfThatArtifact(
        environment: String,
        status: String,
    ) {
        val environmentKey = NodeKey("Environment", environment)
        graphStore.upsertNode(
            GraphNode(environmentKey, mapOf("name" to environment, "type" to environment), Provenance.manual()),
        )

        val deploymentKey = NodeKey("Deployment", "${lastArtifactKey!!.key}#$environment#1")
        graphStore.upsertNode(
            GraphNode(
                key = deploymentKey,
                props =
                    mapOf(
                        "artifactId" to lastArtifactKey!!.id,
                        "environmentId" to environmentKey.id,
                        "deployedAt" to Instant.parse("2026-03-01T00:00:00Z"),
                        "status" to status,
                    ),
                provenance = Provenance.manual(),
            ),
        )
        graphStore.upsertEdge(GraphEdge("DEPLOYED_TO", lastArtifactKey!!, deploymentKey, emptyMap(), Provenance.manual()))
        graphStore.upsertEdge(GraphEdge("TO_ENVIRONMENT", deploymentKey, environmentKey, emptyMap(), Provenance.manual()))
    }

    @When("I link Repository {string} to Team {string}")
    fun iLinkRepositoryToTeam(
        repoKey: String,
        teamKey: String,
    ) {
        world.post("/api/v1/edges", mapOf("type" to "OWNED_BY", "fromId" to "Repository:$repoKey", "toId" to "Team:$teamKey"))
    }

    @When("a Team {string} is upserted with description {string}")
    fun aTeamIsUpsertedWithDescription(
        key: String,
        description: String,
    ) {
        graphStore.upsertNode(
            GraphNode(NodeKey("Team", key), mapOf("name" to key, "email" to description), Provenance.manual()),
        )
    }

    @When("I upsert a node of type {string}")
    fun iUpsertANodeOfType(type: String) {
        rejection =
            runCatching {
                graphStore.upsertNode(GraphNode(NodeKey(type, "x"), mapOf("name" to "x"), Provenance.manual()))
            }.exceptionOrNull() as? Exception
    }

    @Then("the response contains {int} deployment with status {string}")
    fun theResponseContainsDeployments(
        count: Int,
        status: String,
    ) {
        val body = world.lastBody()
        assertEquals(count, body.size(), "deployments in $body")
        assertEquals(status, body.first().path("status").asText())
    }

    @Then("the missing nodes are {string}")
    fun theMissingNodesAre(expected: String) {
        val missing = world.lastBody().path("missing").map { it.asText() }
        assertEquals(expected.split(", "), missing)
    }

    @Then("no OWNED_BY edge exists in the graph")
    fun noOwnedByEdgeExists() {
        assertEquals(0, countOwnedByEdges())
    }

    @Then("one OWNED_BY edge exists in the graph")
    fun oneOwnedByEdgeExists() {
        assertEquals(1, countOwnedByEdges())
    }

    @Then("exactly {int} Team node exists with key {string}")
    fun exactlyTeamNodesExistWithKey(
        count: Int,
        key: String,
    ) {
        val actual =
            neo4jClient
                .query("MATCH (t:Team { key: ${'$'}key }) RETURN count(t) AS c")
                .bindAll(mapOf("key" to key))
                .fetchAs(Long::class.javaObjectType)
                .one()
                .orElse(0L)
        assertEquals(count.toLong(), actual)
    }

    @Then("that Team has description {string}")
    fun thatTeamHasDescription(expected: String) {
        val actual =
            neo4jClient
                .query("MATCH (t:Team) RETURN t.email AS email")
                .fetchAs(String::class.java)
                .one()
                .orElse(null)
        assertEquals(expected, actual)
    }

    @Then("the OWNED_BY edge has provenance source {string} and confidence {double}")
    fun theOwnedByEdgeHasProvenance(
        source: String,
        confidence: Double,
    ) {
        val row =
            neo4jClient
                .query("MATCH (:Repository)-[r:OWNED_BY]->(:Team) RETURN r.prov_sourceSystem AS s, r.prov_confidence AS c")
                .fetch()
                .one()
                .orElse(null)
        assertNotNull(row, "no OWNED_BY edge found")
        assertEquals(source, row!!["s"])
        assertEquals(confidence, row["c"])
    }

    @Then("the request is rejected as an unknown node type")
    fun theRequestIsRejectedAsUnknownNodeType() {
        assertTrue(
            rejection is UnknownNodeTypeException,
            "expected UnknownNodeTypeException but got $rejection",
        )
    }

    @Then("the Repository {string} still exists")
    fun theRepositoryStillExists(key: String) {
        val count =
            neo4jClient
                .query("MATCH (r:Repository { key: ${'$'}key }) RETURN count(r) AS c")
                .bindAll(mapOf("key" to key))
                .fetchAs(Long::class.javaObjectType)
                .one()
                .orElse(0L)
        assertEquals(1L, count, "the injection attempt must not have deleted anything")
    }

    private fun countOwnedByEdges(): Long =
        neo4jClient
            .query("MATCH (:Repository)-[r:OWNED_BY]->(:Team) RETURN count(r) AS c")
            .fetchAs(Long::class.javaObjectType)
            .one()
            .orElse(0L)
}
