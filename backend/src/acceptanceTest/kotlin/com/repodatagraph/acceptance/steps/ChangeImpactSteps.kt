package com.repodatagraph.acceptance.steps

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.data.neo4j.core.Neo4jClient
import java.time.Instant

/**
 * Impact analysis for a change (#87), `POST /api/v1/impact`: seeded straight through [GraphStore],
 * as #21's steps are, so every fact carries the provenance the scenario states.
 *
 * The repository a scenario is about is the one its background names; a request that does not name
 * one asks about it.
 */
class ChangeImpactSteps(
    private val world: ApiWorld,
    private val graphStore: GraphStore,
    private val identityResolver: IdentityResolver,
    private val neo4jClient: Neo4jClient,
    private val objectMapper: ObjectMapper,
) {
    private var repositoryKey: String = DEFAULT_REPOSITORY
    private var artifact: NodeKey? = null
    private var bodies: List<String> = emptyList()

    @Given("a Repository {string} owned by Team {string}")
    fun aRepositoryOwnedByTeam(
        repository: String,
        team: String,
    ) {
        repositoryKey = repository
        graphStore.upsertEdge(GraphEdge(type = "OWNED_BY", from = repository(repository), to = team(team), provenance = stated()))
    }

    @Given("a Service {string} that DEPENDS_ON Repository {string} and is owned by Team {string}")
    fun aServiceThatDependsOn(
        service: String,
        repository: String,
        team: String,
    ) {
        val serviceKey = service(service)
        graphStore.upsertEdge(
            GraphEdge(
                type = "DEPENDS_ON",
                from = serviceKey,
                to = repository(repository),
                props = mapOf("kind" to "api"),
                provenance = stated(),
            ),
        )
        graphStore.upsertEdge(GraphEdge(type = "OWNED_BY", from = serviceKey, to = team(team), provenance = stated()))
    }

    @Given("an Artifact from {string} deployed to Environment {string} with tier {string}")
    fun anArtifactDeployedTo(
        repository: String,
        environment: String,
        tier: String,
    ) {
        val props =
            mapOf(
                "registry" to "ghcr.io",
                "name" to repository.substringAfter('/'),
                "digest" to "sha256:aaa",
                "version" to "1.0.0",
                "commitSha" to "c1",
                "artifactType" to "container-image",
            )
        val artifactKey = identityResolver.keyFor("Artifact", props)
        graphStore.upsertNode(GraphNode(artifactKey, props, stated()))
        graphStore.upsertEdge(
            GraphEdge(
                type = "BUILT_FROM",
                from = artifactKey,
                to = repository(repository),
                props = mapOf("commitSha" to "c1"),
                provenance = stated(),
            ),
        )
        artifact = artifactKey
        deploy(artifactKey, environment, tier)
    }

    @Given("the same Artifact deployed to Environment {string} with tier {string}")
    fun theSameArtifactDeployedTo(
        environment: String,
        tier: String,
    ) {
        deploy(checkNotNull(artifact) { "no artifact yet" }, environment, tier)
    }

    @Given("{int} services DEPENDS_ON the repository")
    fun servicesDependOnTheRepository(count: Int) {
        repeat(count) { index ->
            graphStore.upsertEdge(
                GraphEdge(
                    type = "DEPENDS_ON",
                    from = service("consumer-%03d".format(index)),
                    to = repository(repositoryKey),
                    props = mapOf("kind" to "api"),
                    provenance = stated(),
                ),
            )
        }
    }

    @Given("the repository has no manifest or IaC index")
    fun theRepositoryHasNoIndex() {
        val entries =
            neo4jClient
                .query(
                    "MATCH (r:Repository { key: \$key })-[x]->() WHERE type(x) = 'CONTAINS_IAC' OR x.manifest IS NOT NULL RETURN count(x)",
                ).bind(repositoryKey)
                .to("key")
                .fetchAs(Long::class.java)
                .one()
                .orElse(0L)
        assertEquals(0L, entries, "index entries of $repositoryKey")
    }

    @Given("the repository holds IaC file {string} naming {string}")
    fun theRepositoryHoldsIacFile(
        path: String,
        resourceRef: String,
    ) {
        val props = mapOf("repoKey" to repositoryKey, "path" to path, "format" to "terraform", "resourceRefs" to listOf(resourceRef))
        val file = identityResolver.keyFor("IacFile", props)
        graphStore.upsertNode(GraphNode(file, props, stated()))
        graphStore.upsertEdge(GraphEdge(type = "CONTAINS_IAC", from = repository(repositoryKey), to = file, provenance = stated()))
    }

    @Given("the repository OWNS_RESOURCE CloudResource {string}")
    fun theRepositoryOwnsResource(resourceId: String) {
        val props =
            mapOf(
                "provider" to "aws",
                "resourceId" to resourceId,
                "resourceType" to "rds",
                "name" to resourceId.substringAfterLast(':'),
            )
        val resource = identityResolver.keyFor("CloudResource", props)
        graphStore.upsertNode(GraphNode(resource, props, stated()))
        graphStore.upsertEdge(
            GraphEdge(
                type = "OWNS_RESOURCE",
                from = repository(repositoryKey),
                to = resource,
                props = mapOf("rule" to "iac"),
                provenance = stated(),
            ),
        )
    }

    @When("^I POST /api/v1/impact with repositoryKey \"([^\"]*)\" and depth (\\d+)$")
    fun iPostWithRepositoryAndDepth(
        repository: String,
        depth: Int,
    ) {
        post(mapOf("repositoryKey" to repository, "depth" to depth))
    }

    @When("^I POST /api/v1/impact with repositoryKey \"([^\"]*)\"$")
    fun iPostWithRepository(repository: String) {
        post(mapOf("repositoryKey" to repository))
    }

    @When("^I POST /api/v1/impact with (depth|limit) (\\d+)$")
    fun iPostWithBound(
        field: String,
        value: Int,
    ) {
        post(mapOf("repositoryKey" to repositoryKey, field to value))
    }

    @When("^I POST /api/v1/impact with paths (\\[.*])$")
    fun iPostWithPaths(paths: String) {
        post(mapOf("repositoryKey" to repositoryKey, "paths" to objectMapper.readValue(paths, List::class.java)))
    }

    @When("^I POST /api/v1/impact with sha \"([^\"]*)\"$")
    fun iPostWithSha(sha: String) {
        post(mapOf("repositoryKey" to repositoryKey, "sha" to sha))
    }

    @When("^I POST /api/v1/impact twice with the same body$")
    fun iPostTwice() {
        val body = mapOf("repositoryKey" to repositoryKey, "paths" to listOf("src/billing/invoice.kt"), "depth" to 3)
        bodies = (1..2).map { post(body) }
    }

    @Then("the first hit is the {string} deployment")
    fun theFirstHitIsTheDeployment(environment: String) {
        assertEquals(deploymentHitIndex(environment), 0, "hits: ${hits().map { it.path("node").path("id").asText() }}")
    }

    @Then("the {string} deployment appears after it")
    fun theDeploymentAppearsAfterIt(environment: String) {
        assertTrue(deploymentHitIndex(environment) > 0) { "hits: ${world.lastResponse().body}" }
    }

    @Then("every hit has a non-empty {string}")
    fun everyHitHasANonEmpty(dottedPath: String) {
        assertTrue(hits().isNotEmpty()) { "no hits: ${world.lastResponse().body}" }
        hits().forEach { hit ->
            val value = dottedPath.split('.').fold(hit) { node, field -> node.path(field) }
            assertTrue(value.isArray && !value.isEmpty) { "$dottedPath of ${hit.path("node").path("id")} is $value" }
        }
    }

    @Then("the scoring version is {string}")
    fun theScoringVersionIs(version: String) {
        assertEquals(
            version,
            world
                .lastBody()
                .path("scoring")
                .path("version")
                .asText(null),
            world.lastResponse().body,
        )
    }

    @Then("the hit for Service {string} lists owner {string}")
    fun theHitForServiceListsOwner(
        service: String,
        team: String,
    ) {
        val hit = hits().firstOrNull { it.path("node").path("id").asText() == "Service:$service" } ?: error("no hit for $service")
        assertOwner(hit, team)
    }

    @Then("the hit for the {string} deployment lists owner {string}")
    fun theHitForTheDeploymentListsOwner(
        environment: String,
        team: String,
    ) {
        assertOwner(hits()[deploymentHitIndex(environment)], team)
    }

    @Then("both response bodies are identical")
    fun bothResponseBodiesAreIdentical() {
        assertEquals(2, bodies.size)
        assertTrue(hits().isNotEmpty()) { "no hits: ${bodies.first()}" }
        assertEquals(bodies[0], bodies[1])
    }

    @Then("the problem detail mentions {string}")
    fun theProblemDetailMentions(word: String) {
        val body = world.lastBody()
        assertEquals(word, body.path("field").asText(null), world.lastResponse().body)
        assertTrue(body.path("error").asText().contains(word)) { "error: ${body.path("error")}" }
    }

    @Then("{string} is {string}")
    fun fieldIs(
        field: String,
        value: String,
    ) {
        assertEquals(value, world.lastBody().path(field).asText(null), world.lastResponse().body)
    }

    @Then("the body has {int} hits and {string} is {word}")
    fun theBodyHasHits(
        count: Int,
        field: String,
        value: String,
    ) {
        assertEquals(count, hits().size, world.lastResponse().body)
        val flag = world.lastBody().path(field)
        assertTrue(flag.isBoolean && flag.booleanValue() == value.toBoolean()) { "$field is $flag" }
    }

    @Then("the matched paths are {string}")
    fun theMatchedPathsAre(paths: String) {
        assertEquals(paths.split(",").map { it.trim() }, world.lastBody().path("matchedPaths").map { it.asText() })
    }

    @Then("the first hit is CloudResource {string} with pathMatched true")
    fun theFirstHitIsCloudResource(key: String) {
        val first = hits().firstOrNull() ?: error("no hits: ${world.lastResponse().body}")
        assertEquals("CloudResource:$key", first.path("node").path("id").asText(), world.lastResponse().body)
        assertTrue(first.path("pathMatched").booleanValue()) { "pathMatched of $first" }
    }

    @When("I query GraphQL for the change impact of {string}")
    fun iQueryGraphqlForTheChangeImpact(repository: String) {
        world.post(
            "/graphql",
            mapOf(
                "query" to
                    """
                    query ChangeImpact(${'$'}input: ChangeImpactInput!) {
                      changeImpact(input: ${'$'}input) {
                        scoring { version }
                        pathFilter changeScope truncated
                        hits {
                          node { __typename id }
                          hops score tier
                          environment { id key tier }
                          owners { team { key } }
                          citation { nodeKey edgePath { edge from to } }
                        }
                      }
                    }
                    """.trimIndent(),
                "variables" to mapOf("input" to mapOf("repositoryKey" to repository)),
            ),
        )
    }

    @Then("the GraphQL change impact's first hit is the {string} deployment")
    fun theGraphqlChangeImpactsFirstHit(environment: String) {
        val body = world.lastBody()
        assertTrue(body.path("errors").isMissingNode) { "GraphQL errors: ${body.path("errors")}" }
        val first =
            body
                .path("data")
                .path("changeImpact")
                .path("hits")
                .firstOrNull() ?: error("no hits: ${world.lastResponse().body}")
        assertEquals("DeploymentNode", first.path("node").path("__typename").asText())
        assertEquals(environment, first.path("environment").path("key").asText())
    }

    private fun post(body: Map<String, Any?>): String = world.post("/api/v1/impact", body).body.orEmpty()

    private fun hits(): List<JsonNode> = world.lastBody().path("hits").toList()

    /** Where the deployment to [environment] is in the hits, or a failure naming what is there. */
    private fun deploymentHitIndex(environment: String): Int {
        val index =
            hits().indexOfFirst {
                it.path("node").path("type").asText() == "Deployment" && it.path("environment").path("key").asText() == environment
            }
        assertTrue(index >= 0) { "no deployment to $environment in ${world.lastResponse().body}" }
        return index
    }

    private fun assertOwner(
        hit: JsonNode,
        team: String,
    ) {
        val owners = hit.path("owners").map { it.path("team").path("key").asText() }
        assertTrue(team in owners) { "owners of ${hit.path("node").path("id")} are $owners" }
    }

    private fun deploy(
        artifactKey: NodeKey,
        environment: String,
        tier: String,
    ) {
        val environmentKey = identityResolver.keyFor("Environment", mapOf("name" to environment))
        graphStore.upsertNode(GraphNode(environmentKey, mapOf("name" to environment, "type" to environment, "tier" to tier), stated()))
        val props =
            mapOf(
                "artifactKey" to artifactKey.key,
                "environmentKey" to environmentKey.key,
                "deployedAt" to Instant.parse("2026-09-01T10:00:00Z"),
                "artifactId" to artifactKey.key,
                "environmentId" to environmentKey.key,
                "status" to "SUCCESS",
            )
        val deployment = identityResolver.keyFor("Deployment", props)
        graphStore.upsertNode(GraphNode(deployment, props, stated()))
        graphStore.upsertEdge(GraphEdge(type = "DEPLOYED_TO", from = artifactKey, to = deployment, provenance = stated()))
        graphStore.upsertEdge(GraphEdge(type = "TO_ENVIRONMENT", from = deployment, to = environmentKey, provenance = stated()))
    }

    /** Creates the repository when it is not there yet, with every property its GraphQL type requires. */
    private fun repository(key: String): NodeKey {
        val nodeKey = NodeKey("Repository", key)
        if (graphStore.findNode(nodeKey) == null) {
            val (host, org, name) = key.split('/')
            val props =
                mapOf(
                    "url" to "https://$key",
                    "host" to host,
                    "org" to org,
                    "name" to name,
                    "defaultBranch" to "main",
                    "topics" to emptyList<String>(),
                    "codeowners" to emptyList<String>(),
                )
            graphStore.upsertNode(GraphNode(nodeKey, props, stated()))
        }
        return nodeKey
    }

    private fun team(name: String): NodeKey {
        val key = identityResolver.keyFor("Team", mapOf("name" to name))
        graphStore.upsertNode(GraphNode(key, mapOf("name" to name), stated()))
        return key
    }

    private fun service(name: String): NodeKey {
        val key = identityResolver.keyFor("Service", mapOf("name" to name))
        graphStore.upsertNode(GraphNode(key, mapOf("name" to name), stated()))
        return key
    }

    private fun stated(): Provenance {
        val at = Instant.parse("2026-09-01T10:00:00Z")
        return Provenance(sourceSystem = "manual", ingestedAt = at, validFrom = at)
    }

    private companion object {
        const val DEFAULT_REPOSITORY = "github.com/acme/payments"
    }
}
