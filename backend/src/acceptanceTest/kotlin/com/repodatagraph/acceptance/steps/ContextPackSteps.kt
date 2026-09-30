package com.repodatagraph.acceptance.steps

import com.fasterxml.jackson.databind.JsonNode
import com.repodatagraph.acceptance.support.ApiWorld
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.port.out.GraphStore
import io.cucumber.java.en.But
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.time.Instant

/**
 * Context packs (#96), `POST /api/v1/context-pack`: seeded straight through [GraphStore], as the
 * impact steps are, so every fact carries the provenance - and the validity - the scenario states.
 *
 * A repository named on its own, `settlement-api`, is `github.com/acme/settlement-api`.
 */
class ContextPackSteps(
    private val world: ApiWorld,
    private val graphStore: GraphStore,
    private val identityResolver: IdentityResolver,
) {
    private var start: NodeKey? = null
    private val dependants = mutableListOf<NodeKey>()
    private val unrelated = mutableListOf<NodeKey>()
    private val currentDeployments = mutableListOf<NodeKey>()
    private val supersededDeployments = mutableListOf<NodeKey>()
    private val owners = mutableSetOf<NodeKey>()
    private var restNodeIds: List<String> = emptyList()

    /**
     * Four repositories depend on the start directly, two on the first of those and one on the fifth,
     * so the seven are one, two and three hops away. Two of them run in production, the first with a
     * deployment a later one superseded. The forty unrelated ones depend on what the start depends on
     * and share a team with the dependants, so a walk that strays - upstream, or back from an owner -
     * would find them.
     */
    @Given("Repository {string} with {int} transitive dependants and {int} unrelated repositories in the graph")
    fun repositoryWithDependants(
        name: String,
        count: Int,
        unrelatedCount: Int,
    ) {
        check(count == SEEDED_DEPENDANTS) { "the seed shapes exactly $SEEDED_DEPENDANTS dependants" }
        val root = repository(name)
        start = root
        ownedBy(root, "settlement")
        val direct = (1..4).map { repository("$name-consumer-$it") }
        val second = (5..6).map { repository("$name-consumer-$it") }
        val third = repository("$name-consumer-7")
        direct.forEach { dependsOn(it, root) }
        second.forEach { dependsOn(it, direct.first()) }
        dependsOn(third, second.first())
        dependants += direct + second + third
        direct.forEach { ownedBy(it, "ledger") }
        (second + third).forEach { ownedBy(it, "reports") }

        val first = direct.first()
        supersededDeployments += deploy(first, "production", version = "1.0.0", at = Instant.parse("2026-09-01T10:00:00Z"))
        currentDeployments += deploy(first, "production", version = "1.1.0", at = Instant.parse("2026-09-02T10:00:00Z"))
        currentDeployments += deploy(second.first(), "production", version = "2.0.0", at = Instant.parse("2026-09-03T10:00:00Z"))

        val common = repository("common-lib")
        dependsOn(root, common)
        repeat(unrelatedCount) { index ->
            val other = repository("unrelated-%02d".format(index))
            dependsOn(other, common)
            ownedBy(other, "ledger")
            unrelated += other
        }
    }

    @Given("a start node whose change-impact traversal reaches {int} nodes")
    fun aStartNodeReaching(count: Int) {
        val root = repository("core")
        start = root
        repeat(count) { index -> dependsOn(repository("core-consumer-%02d".format(index)), root) }
    }

    @Given("a DEPENDS_ON edge with manifest {string}")
    fun aDependsOnEdgeWithManifest(manifest: String) {
        val uiKit = repository("ui-kit")
        start = uiKit
        graphStore.upsertEdge(
            GraphEdge(
                type = "DEPENDS_ON",
                from = repository("web"),
                to = uiKit,
                props = mapOf("kind" to "library", "manifest" to manifest, "version" to "^3.5.0"),
                provenance =
                    Provenance(
                        sourceSystem = "github",
                        ingestedAt = SEEDED_AT,
                        observedAt = OBSERVED_AT,
                        validFrom = SEEDED_AT,
                        confidence = 0.9,
                        inferred = false,
                    ),
            ),
        )
    }

    @Given("CloudResource {string} owned by Repository {string} with its pipeline, CI and a production deployment")
    fun aCloudResourceOwnedByRepository(
        resource: String,
        repositoryName: String,
    ) {
        val resourceKey = cloudResource(resource)
        val repositoryKey = repository(repositoryName)
        start = resourceKey
        graphStore.upsertEdge(GraphEdge("OWNS_RESOURCE", repositoryKey, resourceKey, mapOf("rule" to "iac"), stated()))
        val pipelineProps =
            mapOf(
                "provider" to "github-actions",
                "repoKey" to repositoryKey.key,
                "workflowPath" to ".github/workflows/ci.yml",
                "name" to "CI",
                "repoId" to repositoryKey.key,
            )
        val pipeline = identityResolver.keyFor("Pipeline", pipelineProps)
        graphStore.upsertNode(GraphNode(pipeline, pipelineProps, stated()))
        graphStore.upsertEdge(GraphEdge("HAS_PIPELINE", repositoryKey, pipeline, emptyMap(), stated()))
        val ciProps =
            mapOf(
                "sourceSystem" to "servicenow",
                "instance" to "acme.service-now.com",
                "sysId" to "ci-$repositoryName",
                "ciName" to repositoryName,
            )
        val ci = identityResolver.keyFor("ConfigurationItem", ciProps)
        graphStore.upsertNode(GraphNode(ci, ciProps, stated()))
        graphStore.upsertEdge(GraphEdge("RELATES_TO_CI", repositoryKey, ci, emptyMap(), stated()))
        supersededDeployments += deploy(repositoryKey, "production", version = "3.0.0", at = Instant.parse("2026-09-01T10:00:00Z"))
        currentDeployments += deploy(repositoryKey, "production", version = "3.1.0", at = Instant.parse("2026-09-04T10:00:00Z"))
    }

    @Given("Repository {string} DEPENDS_ON Repository {string} as an api, deployed to production")
    fun repositoryDependsOnAnApi(
        from: String,
        to: String,
    ) {
        val upstream = repository(to)
        dependsOn(repository(from), upstream, kind = "api")
        currentDeployments += deploy(upstream, "production", version = "9.0.0", at = Instant.parse("2026-09-05T10:00:00Z"))
    }

    @Given("Repository {string} DEPENDS_ON Repository {string} as a library")
    fun repositoryDependsOnALibrary(
        from: String,
        to: String,
    ) {
        dependsOn(repository(from), repository(to), kind = "library")
    }

    @Given("CloudResource {string} read by Repository {string} owned by Team {string} as data")
    fun aCloudResourceReadAsData(
        resource: String,
        repositoryName: String,
        team: String,
    ) {
        val resourceKey = cloudResource(resource)
        start = resourceKey
        val consumer = repository(repositoryName)
        dependsOn(consumer, resourceKey, kind = "data")
        ownedBy(consumer, team)
    }

    @Given("CloudResource {string} called by Repository {string} as an api")
    fun aCloudResourceCalledAsAnApi(
        resource: String,
        repositoryName: String,
    ) {
        dependsOn(repository(repositoryName), cloudResource(resource), kind = "api")
    }

    @Given("Repository {string} began to depend on {string} on {string}")
    fun repositoryBeganToDependOn(
        from: String,
        to: String,
        at: String,
    ) {
        val began = Instant.parse(at)
        val validity = Provenance(sourceSystem = "manual", ingestedAt = began, validFrom = began)
        val late = NodeKey("Repository", "$ORG/$from")
        graphStore.upsertNode(GraphNode(late, repositoryProps(late.key), validity))
        graphStore.upsertEdge(GraphEdge("DEPENDS_ON", late, repository(to), mapOf("kind" to "api"), validity))
    }

    @When("a context pack is requested from {word} {string} with template {string} and budget {int}")
    fun aContextPackIsRequested(
        type: String,
        name: String,
        template: String,
        budget: Int,
    ) {
        request(mapOf("startId" to idOf(type, name), "template" to template, "budget" to budget))
    }

    @When("a context pack is requested from {word} {string} with template {string}, budget {int} and asOf {string}")
    fun aContextPackIsRequestedAsOf(
        type: String,
        name: String,
        template: String,
        budget: Int,
        asOf: String,
    ) {
        request(mapOf("startId" to idOf(type, name), "template" to template, "budget" to budget, "asOf" to asOf))
    }

    @When("a context pack is requested from that node with template {string} and budget {int}")
    fun aContextPackIsRequestedFromThatNode(
        template: String,
        budget: Int,
    ) {
        request(mapOf("startId" to startId(), "template" to template, "budget" to budget))
    }

    @When("it appears in a pack")
    fun itAppearsInAPack() {
        request(mapOf("startId" to startId(), "template" to "change-impact", "budget" to 20))
        assertEquals(200, world.lastStatus(), world.lastResponse().body)
    }

    @Then("the pack contains the {int} dependants, their owners and current production deployments")
    fun thePackContainsTheDependants(count: Int) {
        assertEquals(count, dependants.size)
        val ids = nodeIds()
        dependants.forEach { assertTrue(it.id in ids) { "${it.id} is not in the pack: $ids" } }
        listOf("ledger", "reports", "settlement").forEach { team ->
            assertTrue("Team:$team" in ids) { "owner $team is not in the pack: $ids" }
        }
        currentDeployments.forEach { assertTrue(it.id in ids) { "current deployment ${it.id} is not in the pack: $ids" } }
        assertTrue("Environment:production" in ids) { "production is not in the pack: $ids" }
    }

    @Then("it contains no repository outside that traversal")
    fun itContainsNoRepositoryOutsideThatTraversal() {
        val repositories = nodes().filter { it.path("type").asText() == "Repository" }.map { it.path("id").asText() }.toSet()
        assertEquals(dependants.map { it.id }.toSet(), repositories)
        assertEquals(
            startId(),
            world
                .lastBody()
                .path("start")
                .path("id")
                .asText(),
        )
    }

    @Then("it contains no deployment a later one has superseded")
    fun itContainsNoSupersededDeployment() {
        val ids = nodeIds()
        supersededDeployments.forEach { assertFalse(it.id in ids) { "superseded ${it.id} is in the pack" } }
    }

    @Then("the pack says it is not truncated")
    fun thePackIsNotTruncated() {
        val body = world.lastBody()
        assertFalse(body.path("truncated").booleanValue(), world.lastResponse().body)
        assertEquals(0, body.path("cut").intValue(), world.lastResponse().body)
    }

    @Then("the pack's nodes are nearest first")
    fun thePacksNodesAreNearestFirst() {
        val distances = nodes().map { it.path("distance").intValue() }
        assertEquals(distances.sorted(), distances, "distances in pack order")
        assertEquals(1, distances.first())
    }

    @Then("{int} nodes are returned with truncated {word} and cut {int}")
    fun nodesAreReturnedWithTruncatedAndCut(
        count: Int,
        truncated: String,
        cut: Int,
    ) {
        assertEquals(200, world.lastStatus(), world.lastResponse().body)
        val body = world.lastBody()
        assertEquals(count, nodes().size, world.lastResponse().body)
        assertEquals(truncated.toBoolean(), body.path("truncated").booleanValue())
        assertEquals(cut, body.path("cut").intValue())
    }

    @Then("the edge carries manifest {string} and its provenance summary")
    fun theEdgeCarriesManifest(manifest: String) {
        val edge =
            edges().firstOrNull { it.path("type").asText() == "DEPENDS_ON" }
                ?: error("no DEPENDS_ON edge in the pack: ${world.lastResponse().body}")
        assertEquals(manifest, edge.path("manifest").asText(null))
        assertEquals("DEPENDS_ON:$ORG_ID/web>${startId()}", edge.path("id").asText())
        val provenance = edge.path("provenance")
        assertEquals("github", provenance.path("source").asText(null), "$provenance")
        assertEquals(OBSERVED_AT.toString(), provenance.path("observedAt").asText(null), "$provenance")
        assertEquals(0.9, provenance.path("confidence").doubleValue(), "$provenance")
        assertTrue(provenance.path("inferred").isBoolean) { "$provenance" }
        assertTrue(provenance.path("stale").isBoolean) { "$provenance" }
    }

    @Then("every node in the pack carries a provenance summary")
    fun everyNodeCarriesAProvenanceSummary() {
        // listOf: a JsonNode is itself Iterable, and adding it would add its fields rather than the start.
        (nodes() + listOf(world.lastBody().path("start"))).forEach { node ->
            val provenance = node.path("provenance")
            assertTrue(provenance.path("source").isTextual) { "${node.path("id")}: $provenance" }
            assertTrue(provenance.path("confidence").isNumber) { "${node.path("id")}: $provenance" }
            assertTrue(provenance.path("stale").isBoolean) { "${node.path("id")}: $provenance" }
        }
    }

    @Then("the pack holds Repository {string}, its pipeline, its CI and its current deployment")
    fun thePackHoldsTheRepositoryAndItsLineage(name: String) {
        val types = nodes().groupBy({ it.path("type").asText() }, { it.path("id").asText() })
        assertTrue("Repository:$ORG/$name" in types["Repository"].orEmpty()) { "types: $types" }
        assertEquals(1, types["Pipeline"].orEmpty().size, "pipelines: $types")
        assertEquals(1, types["ConfigurationItem"].orEmpty().size, "CIs: $types")
        assertTrue(currentDeployments.first().id in nodeIds()) { "types: $types" }
        supersededDeployments.forEach { assertFalse(it.id in nodeIds()) { "superseded ${it.id} is in the pack" } }
    }

    @Then("the pack holds Repository {string} and its current deployment")
    fun thePackHoldsTheRepositoryAndItsCurrentDeployment(name: String) {
        val ids = nodeIds()
        assertTrue("Repository:$ORG/$name" in ids) { "ids: $ids" }
        assertTrue(currentDeployments.last().id in ids) { "ids: $ids" }
    }

    @Then("the pack does not hold Repository {string}")
    fun thePackDoesNotHoldRepository(name: String) {
        assertFalse("Repository:$ORG/$name" in nodeIds()) { "ids: ${nodeIds()}" }
    }

    @Then("the pack holds Repository {string} and Team {string}")
    fun thePackHoldsRepositoryAndTeam(
        name: String,
        team: String,
    ) {
        val ids = nodeIds()
        assertTrue("Repository:$ORG/$name" in ids) { "ids: $ids" }
        assertTrue("Team:$team" in ids) { "ids: $ids" }
    }

    @Then("the pack's BUILT_FROM edges carry their commitSha")
    fun theBuiltFromEdgesCarryTheirCommitSha() {
        val builtFrom = edges().filter { it.path("type").asText() == "BUILT_FROM" }
        assertTrue(builtFrom.isNotEmpty()) { "no BUILT_FROM edge: ${world.lastResponse().body}" }
        builtFrom.forEach { assertTrue(it.path("commitSha").isTextual) { "$it" } }
    }

    @Then("the pack's asOf is {string}")
    fun thePacksAsOfIs(asOf: String) {
        assertEquals(asOf, world.lastBody().path("asOf").asText(null), world.lastResponse().body)
    }

    @But("a context pack requested now holds Repository {string}")
    fun aContextPackRequestedNowHolds(name: String) {
        request(mapOf("startId" to startId(), "template" to "change-impact", "budget" to 50))
        assertEquals(200, world.lastStatus(), world.lastResponse().body)
        assertTrue("Repository:$ORG/$name" in nodeIds()) { "ids: ${nodeIds()}" }
    }

    @Then("the ontology declares the templates {string}, {string} and {string}")
    fun theOntologyDeclaresTheTemplates(
        first: String,
        second: String,
        third: String,
    ) {
        val templates = world.lastBody().path("templates")
        val names = templates.map { it.path("name").asText() }
        assertEquals(listOf(first, second, third), names, world.lastResponse().body)
        templates.forEach { template ->
            assertTrue(template.path("description").isTextual) { "$template" }
            assertTrue(template.path("start").isArray && !template.path("start").isEmpty) { "$template" }
            assertTrue(template.path("steps").isArray && !template.path("steps").isEmpty) { "$template" }
        }
    }

    @Then("the refusal names the field {string} and mentions {string}")
    fun theRefusalNamesTheField(
        field: String,
        word: String,
    ) {
        val body = world.lastBody()
        assertEquals(field, body.path("field").asText(null), world.lastResponse().body)
        assertTrue(body.path("error").asText().contains(word)) { "error: ${body.path("error")}" }
    }

    @When("I query GraphQL for the {string} context pack of Repository {string} with budget {int}")
    fun iQueryGraphqlForTheContextPack(
        template: String,
        name: String,
        budget: Int,
    ) {
        request(mapOf("startId" to idOf("Repository", name), "template" to template, "budget" to budget))
        restNodeIds = nodes().map { it.path("id").asText() }
        world.post(
            "/graphql",
            mapOf(
                "query" to
                    """
                    query Pack(${'$'}input: ContextPackInput!) {
                      contextPack(input: ${'$'}input) {
                        template budget truncated cut reached
                        scoring { version }
                        start { id }
                        nodes {
                          id type key label distance confidence score tier
                          node { __typename id }
                          provenance { source observedAt confidence inferred stale }
                          via { edge from to }
                        }
                        edges { id type inverse from to manifest rule commitSha provenance { source stale } }
                      }
                    }
                    """.trimIndent(),
                "variables" to mapOf("input" to mapOf("startId" to idOf("Repository", name), "template" to template, "budget" to budget)),
            ),
        )
    }

    @Then("the GraphQL context pack holds the same nodes as the REST one, in the same order")
    fun theGraphqlContextPackHoldsTheSameNodes() {
        val body = world.lastBody()
        assertTrue(body.path("errors").isMissingNode) { "GraphQL errors: ${body.path("errors")}" }
        val pack = body.path("data").path("contextPack")
        assertTrue(restNodeIds.isNotEmpty()) { "the REST pack was empty" }
        assertEquals(restNodeIds, pack.path("nodes").map { it.path("id").asText() })
        assertEquals("1", pack.path("scoring").path("version").asText())
    }

    private fun request(body: Map<String, Any?>) {
        world.post("/api/v1/context-pack", body)
    }

    private fun nodes(): List<JsonNode> = world.lastBody().path("nodes").toList()

    private fun edges(): List<JsonNode> = world.lastBody().path("edges").toList()

    private fun nodeIds(): Set<String> = nodes().map { it.path("id").asText() }.toSet()

    private fun startId(): String = checkNotNull(start) { "no start node yet" }.id

    private fun idOf(
        type: String,
        name: String,
    ): String =
        when (type) {
            "Repository" -> "Repository:$ORG/$name"
            "CloudResource" -> cloudResourceKey(name).id
            else -> "$type:$name"
        }

    private fun repository(name: String): NodeKey {
        val key = NodeKey("Repository", "$ORG/$name")
        if (graphStore.findNode(key) == null) graphStore.upsertNode(GraphNode(key, repositoryProps(key.key), stated()))
        return key
    }

    private fun repositoryProps(key: String): Map<String, Any?> {
        val (host, org, name) = key.split('/')
        return mapOf(
            "url" to "https://$key",
            "host" to host,
            "org" to org,
            "name" to name,
            "defaultBranch" to "main",
            "topics" to emptyList<String>(),
            "codeowners" to emptyList<String>(),
        )
    }

    private fun cloudResourceProps(name: String): Map<String, Any?> =
        mapOf("provider" to "aws", "resourceId" to "arn:aws:s3:::$name", "resourceType" to "s3-bucket", "name" to name)

    private fun cloudResourceKey(name: String): NodeKey = identityResolver.keyFor("CloudResource", cloudResourceProps(name))

    private fun cloudResource(name: String): NodeKey {
        val key = cloudResourceKey(name)
        if (graphStore.findNode(key) == null) graphStore.upsertNode(GraphNode(key, cloudResourceProps(name), stated()))
        return key
    }

    private fun ownedBy(
        owned: NodeKey,
        team: String,
    ) {
        val teamKey = NodeKey("Team", team)
        graphStore.upsertNode(GraphNode(teamKey, mapOf("name" to team), stated()))
        graphStore.upsertEdge(GraphEdge("OWNED_BY", owned, teamKey, emptyMap(), stated()))
        owners += teamKey
    }

    private fun dependsOn(
        from: NodeKey,
        to: NodeKey,
        kind: String = "library",
    ) {
        graphStore.upsertEdge(GraphEdge("DEPENDS_ON", from, to, mapOf("kind" to kind), stated()))
    }

    /** An artifact of [repository] at [version], deployed to [environment] at [at]; the deployment's key. */
    private fun deploy(
        repository: NodeKey,
        environment: String,
        version: String,
        at: Instant,
    ): NodeKey {
        val sha = "c" + version.replace(".", "")
        val artifactProps =
            mapOf(
                "registry" to "ghcr.io",
                "name" to repository.key.substringAfter('/'),
                "digest" to "sha256:" +
                    (repository.key + version)
                        .hashCode()
                        .toUInt()
                        .toString(16)
                        .padStart(64, '0'),
                "version" to version,
                "commitSha" to sha,
                "artifactType" to "container-image",
            )
        val artifact = identityResolver.keyFor("Artifact", artifactProps)
        graphStore.upsertNode(GraphNode(artifact, artifactProps, stated()))
        graphStore.upsertEdge(GraphEdge("BUILT_FROM", artifact, repository, mapOf("commitSha" to sha), stated()))
        val environmentKey = identityResolver.keyFor("Environment", mapOf("name" to environment))
        graphStore.upsertNode(
            GraphNode(environmentKey, mapOf("name" to environment, "type" to environment, "tier" to environment), stated()),
        )
        val props =
            mapOf(
                "artifactKey" to artifact.key,
                "environmentKey" to environmentKey.key,
                "deployedAt" to at,
                "artifactId" to artifact.key,
                "environmentId" to environmentKey.key,
                "status" to "SUCCESS",
            )
        val deployment = identityResolver.keyFor("Deployment", props)
        graphStore.upsertNode(GraphNode(deployment, props, stated()))
        graphStore.upsertEdge(GraphEdge("DEPLOYED_TO", artifact, deployment, emptyMap(), stated()))
        graphStore.upsertEdge(GraphEdge("TO_ENVIRONMENT", deployment, environmentKey, emptyMap(), stated()))
        return deployment
    }

    private fun stated(): Provenance = Provenance(sourceSystem = "manual", ingestedAt = SEEDED_AT, validFrom = SEEDED_AT)

    private companion object {
        const val SEEDED_DEPENDANTS = 7
        const val ORG = "github.com/acme"
        const val ORG_ID = "Repository:github.com/acme"
        val SEEDED_AT: Instant = Instant.parse("2026-09-01T09:00:00Z")
        val OBSERVED_AT: Instant = Instant.parse("2026-08-31T12:00:00Z")
    }
}
