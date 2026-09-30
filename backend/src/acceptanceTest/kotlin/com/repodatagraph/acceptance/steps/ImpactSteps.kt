package com.repodatagraph.acceptance.steps

import com.fasterxml.jackson.databind.JsonNode
import com.repodatagraph.acceptance.support.ApiWorld
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.port.out.GraphStore
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.time.Instant

/**
 * Impact analysis, "why did it fail" and ownership (#21), seeded straight through [GraphStore] so
 * every edge carries the provenance - and so the confidence - the scenario states.
 */
class ImpactSteps(
    private val world: ApiWorld,
    private val graphStore: GraphStore,
    private val identityResolver: IdentityResolver,
) {
    private val deployments = mutableListOf<NodeKey>()

    @Given("Repository {string} owned by Team {string}")
    fun repositoryOwnedByTeam(
        repository: String,
        team: String,
    ) {
        val repositoryKey = repository(repository)
        val teamKey = NodeKey("Team", team)
        graphStore.upsertNode(GraphNode(teamKey, mapOf("name" to team, "email" to "$team@acme.test"), provenance(1.0)))
        graphStore.upsertEdge(GraphEdge(type = "OWNED_BY", from = repositoryKey, to = teamKey, provenance = provenance(1.0)))
    }

    @Given("Repository {string} DEPENDS_ON {string} with confidence {double}")
    fun repositoryDependsOn(
        from: String,
        to: String,
        confidence: Double,
    ) {
        graphStore.upsertEdge(
            GraphEdge(
                type = "DEPENDS_ON",
                from = repository(from),
                to = repository(to),
                props = mapOf("kind" to "library"),
                provenance = provenance(confidence),
            ),
        )
    }

    @Given("Repository {string} OWNS_RESOURCE CloudResource {string} with confidence {double} inferred")
    fun repositoryOwnsResource(
        repository: String,
        resource: String,
        confidence: Double,
    ) {
        val resourceKey = NodeKey("CloudResource", resource)
        graphStore.upsertNode(
            GraphNode(
                key = resourceKey,
                props =
                    mapOf(
                        "provider" to resource.substringBefore(':'),
                        "resourceId" to resource.substringAfter(':'),
                        "resourceType" to "rds",
                        "name" to resource.substringAfterLast(':'),
                    ),
                provenance = provenance(1.0),
            ),
        )
        graphStore.upsertEdge(
            GraphEdge(
                type = "OWNS_RESOURCE",
                from = repository(repository),
                to = resourceKey,
                props = mapOf("rule" to "tag"),
                provenance = provenance(confidence, inferred = true),
            ),
        )
    }

    @Given("Artifact {string} BUILT_FROM {string} at commit {string} deployed to {string} with status {string} at {string}")
    fun artifactDeployed(
        artifact: String,
        repository: String,
        commitSha: String,
        environment: String,
        status: String,
        deployedAt: String,
    ) {
        val artifactKey = NodeKey("Artifact", artifact)
        graphStore.upsertNode(
            GraphNode(
                key = artifactKey,
                props =
                    mapOf(
                        "registry" to artifact.substringBefore('/'),
                        "name" to artifact.substringAfter('/').substringBefore('@'),
                        "digest" to artifact.substringAfter('@'),
                        "version" to commitSha,
                        "commitSha" to commitSha,
                        "artifactType" to "container-image",
                    ),
                provenance = provenance(1.0),
            ),
        )
        graphStore.upsertEdge(
            GraphEdge(
                type = "BUILT_FROM",
                from = artifactKey,
                to = repository(repository),
                props = mapOf("commitSha" to commitSha),
                provenance = provenance(1.0),
            ),
        )

        val environmentKey = NodeKey("Environment", environment)
        graphStore.upsertNode(GraphNode(environmentKey, mapOf("name" to environment, "type" to environment), provenance(1.0)))

        val deploymentProps =
            mapOf(
                "artifactKey" to artifact,
                "environmentKey" to environment,
                "deployedAt" to Instant.parse(deployedAt),
                "artifactId" to artifact,
                "environmentId" to environment,
                "status" to status,
            )
        val deploymentKey = identityResolver.keyFor("Deployment", deploymentProps)
        graphStore.upsertNode(GraphNode(deploymentKey, deploymentProps, provenance(1.0)))
        graphStore.upsertEdge(GraphEdge(type = "DEPLOYED_TO", from = artifactKey, to = deploymentKey, provenance = provenance(1.0)))
        graphStore.upsertEdge(GraphEdge(type = "TO_ENVIRONMENT", from = deploymentKey, to = environmentKey, provenance = provenance(1.0)))
        deployments += deploymentKey
    }

    @When("I ask why the last deployment failed")
    fun iAskWhyTheLastDeploymentFailed() {
        // The id goes in as a URI variable, so the '#' and '/' of a Deployment key are encoded rather
        // than read as a fragment and a path.
        world.getExpanding("/api/v1/graph/why-failed?deploymentId={id}", deployments.last().id)
    }

    @Then("the impact root is {string}")
    fun theImpactRootIs(id: String) {
        assertEquals(
            id,
            world
                .lastBody()
                .path("root")
                .path("id")
                .asText(),
        )
    }

    @Then("affected contains {string} at distance {int} with confidence {double}")
    fun affectedContainsAtDistance(
        id: String,
        distance: Int,
        confidence: Double,
    ) {
        val affected = affected(id)
        assertEquals(distance, affected.path("distance").asInt(), "distance of $id")
        assertEquals(confidence, affected.path("confidence").asDouble(), TOLERANCE, "confidence of $id")
    }

    @Then("affected contains {string} with inferred {word} and confidence {double}")
    fun affectedContainsInferred(
        id: String,
        inferred: String,
        confidence: Double,
    ) {
        val affected = affected(id)
        assertEquals(inferred.toBoolean(), affected.path("inferred").asBoolean(), "inferred of $id")
        assertEquals(confidence, affected.path("confidence").asDouble(), TOLERANCE, "confidence of $id")
    }

    @Then("the path to {string} is {string}")
    fun thePathToIs(
        id: String,
        edges: String,
    ) {
        assertEquals(list(edges), affected(id).path("path").map { it.path("edge").asText() })
    }

    @Then("affected does not contain {string}")
    fun affectedDoesNotContain(id: String) {
        assertFalse(affectedIds().contains(id)) { "$id is affected: ${world.lastResponse().body}" }
    }

    @Then("byType.{word} equals {int}")
    fun byTypeEquals(
        type: String,
        count: Int,
    ) {
        assertEquals(
            count,
            world
                .lastBody()
                .path("byType")
                .path(type)
                .asInt(),
            "byType.$type in ${world.lastResponse().body}",
        )
    }

    @Then("status is {string} and commitSha is {string}")
    fun statusAndCommit(
        status: String,
        commitSha: String,
    ) {
        val body = world.lastBody()
        assertEquals(status, body.path("status").asText(), "status in ${world.lastResponse().body}")
        assertEquals(commitSha, body.path("commitSha").asText(), "commitSha in ${world.lastResponse().body}")
    }

    @Then("precedingSuccessfulDeployment.commitSha is {string}")
    fun precedingCommit(commitSha: String) {
        assertEquals(
            commitSha,
            world
                .lastBody()
                .path("precedingSuccessfulDeployment")
                .path("commitSha")
                .asText(),
        )
    }

    @Then("changedDependencies contains {string} with commitSha {string}")
    fun changedDependenciesContain(
        repository: String,
        commitSha: String,
    ) {
        val changed = world.lastBody().path("changedDependencies")
        assertTrue(
            changed.any { it.path("repository").path("key").asText() == repository && it.path("commitSha").asText() == commitSha },
        ) { "no change to $repository at $commitSha in $changed" }
    }

    @Then("there are no reasons")
    fun thereAreNoReasons() {
        val body = world.lastBody()
        assertTrue(body.path("reasons").isArray && body.path("reasons").isEmpty) { "reasons in ${world.lastResponse().body}" }
        assertTrue(body.path("changedDependencies").isEmpty) { "changedDependencies in ${world.lastResponse().body}" }
    }

    @Then("owners contains Team {string} via {string} with confidence {double}")
    fun ownersContain(
        team: String,
        via: String,
        confidence: Double,
    ) {
        val owner =
            owners().firstOrNull { it.path("team").path("key").asText() == team }
                ?: error("Team $team is not an owner: ${world.lastResponse().body}")
        assertEquals(list(via), owner.path("via").map { it.path("edge").asText() })
        assertEquals(confidence, owner.path("confidence").asDouble(), TOLERANCE)
    }

    @Then("owners does not contain Team {string}")
    fun ownersDoNotContain(team: String) {
        assertFalse(owners().any { it.path("team").path("key").asText() == team }) { "Team $team is an owner" }
    }

    @Then("there are no owners")
    fun thereAreNoOwners() {
        val owners = world.lastBody().path("owners")
        assertTrue(owners.isArray && owners.isEmpty) { "owners: $owners" }
    }

    @Then("the response status is {int} and field is {string}")
    fun theResponseStatusAndField(
        status: Int,
        field: String,
    ) {
        assertEquals(status, world.lastStatus(), "body: ${world.lastResponse().body}")
        assertEquals(field, world.lastBody().path("field").asText())
        assertTrue(
            world
                .lastBody()
                .path("error")
                .asText()
                .isNotBlank(),
        )
    }

    @When("I query GraphQL for the impact of {string} with minConfidence {double}")
    fun iQueryGraphqlForImpact(
        nodeId: String,
        minConfidence: Double,
    ) {
        world.post(
            "/graphql",
            mapOf(
                "query" to
                    """
                    query Impact(${'$'}nodeId: ID!, ${'$'}min: Float) {
                      impact(nodeId: ${'$'}nodeId, minConfidence: ${'$'}min) {
                        root { id }
                        truncated
                        affected { node { __typename id key } distance confidence inferred path { edge from to } }
                        byType { type count }
                      }
                    }
                    """.trimIndent(),
                "variables" to mapOf("nodeId" to nodeId, "min" to minConfidence),
            ),
        )
    }

    @Then("the GraphQL impact lists {string} as a {string} with confidence {double}")
    fun theGraphqlImpactLists(
        id: String,
        typename: String,
        confidence: Double,
    ) {
        val body = world.lastBody()
        assertTrue(body.path("errors").isMissingNode) { "GraphQL errors: ${body.path("errors")}" }
        val affected =
            body
                .path("data")
                .path("impact")
                .path("affected")
                .firstOrNull { it.path("node").path("id").asText() == id }
                ?: error("$id not in ${world.lastResponse().body}")
        assertEquals(typename, affected.path("node").path("__typename").asText())
        assertEquals(confidence, affected.path("confidence").asDouble(), TOLERANCE)
    }

    @When("I query GraphQL for why the last deployment failed")
    fun iQueryGraphqlForWhyFailed() {
        world.post(
            "/graphql",
            mapOf(
                "query" to
                    """
                    query WhyFailed(${'$'}id: ID!) {
                      whyDeploymentFailed(id: ${'$'}id) {
                        status commitSha
                        deployment { id }
                        precedingSuccessfulDeployment { commitSha }
                        reasons { kind detail }
                      }
                    }
                    """.trimIndent(),
                "variables" to mapOf("id" to deployments.last().id),
            ),
        )
    }

    @Then("the GraphQL answer has status {string} and preceding commit {string}")
    fun theGraphqlAnswer(
        status: String,
        precedingCommit: String,
    ) {
        val body = world.lastBody()
        assertTrue(body.path("errors").isMissingNode) { "GraphQL errors: ${body.path("errors")}" }
        val answer = body.path("data").path("whyDeploymentFailed")
        assertEquals(status, answer.path("status").asText())
        assertEquals(precedingCommit, answer.path("precedingSuccessfulDeployment").path("commitSha").asText())
    }

    private fun affected(id: String): JsonNode =
        world.lastBody().path("affected").firstOrNull { it.path("node").path("id").asText() == id }
            ?: error("$id is not affected: ${world.lastResponse().body}")

    private fun affectedIds(): List<String> = world.lastBody().path("affected").map { it.path("node").path("id").asText() }

    private fun owners(): List<JsonNode> = world.lastBody().path("owners").toList()

    /** Creates the repository when it is not there yet, with every property its GraphQL type requires. */
    private fun repository(key: String): NodeKey {
        val nodeKey = NodeKey("Repository", key)
        if (graphStore.findNode(nodeKey) == null) {
            val (host, org, name) = key.split('/')
            graphStore.upsertNode(
                GraphNode(
                    key = nodeKey,
                    props =
                        mapOf(
                            "url" to "https://$key",
                            "host" to host,
                            "org" to org,
                            "name" to name,
                            "defaultBranch" to "main",
                            "topics" to emptyList<String>(),
                            "codeowners" to emptyList<String>(),
                        ),
                    provenance = provenance(1.0),
                ),
            )
        }
        return nodeKey
    }

    private fun provenance(
        confidence: Double,
        inferred: Boolean = false,
    ): Provenance {
        val now = Instant.now()
        return Provenance(
            sourceSystem = if (inferred) "aws" else "manual",
            ingestedAt = now,
            validFrom = now,
            confidence = confidence,
            inferred = inferred,
        )
    }

    private fun list(commaSeparated: String): List<String> = commaSeparated.split(",").map { it.trim() }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
