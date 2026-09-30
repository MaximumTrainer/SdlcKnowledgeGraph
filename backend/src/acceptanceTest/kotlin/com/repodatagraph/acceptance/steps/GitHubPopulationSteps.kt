package com.repodatagraph.acceptance.steps

import com.fasterxml.jackson.databind.JsonNode
import com.repodatagraph.acceptance.support.ApiWorld
import com.repodatagraph.acceptance.support.SyncWorld
import com.repodatagraph.acceptance.support.says
import com.repodatagraph.support.connector.FakeGitHub
import com.repodatagraph.support.connector.FakeRepo
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * The GitHub connector populating the graph end to end (#86), in the words the issue uses.
 *
 * What is proved here beyond `github-connector-repos.feature` is the whole of one organisation at
 * once: repositories, their owners, their workflows and what they depend on on each other, all with
 * provenance, and a second run over the same estate writing nothing new. The fake is the same
 * [FakeGitHub], so every claim is made over real HTTP rather than against a mocked client.
 */
class GitHubPopulationSteps(
    private val world: ApiWorld,
    private val sync: SyncWorld,
    private val github: FakeGitHub,
) {
    private var repos: List<FakeRepo> = emptyList()

    /** What each repository's package.json says, rendered again whenever a step adds to it. */
    private val manifests = linkedMapOf<String, NpmManifest>()

    private var repositoryKey: String? = null
    private var teamKey: String? = null
    private var dependency: JsonNode? = null
    private var nodesAfterFirstRun: Map<String, Int> = emptyMap()

    @Given("a GitHub organisation {string} with repositories {string} and {string}")
    fun anOrganisationWithRepositories(
        org: String,
        first: String,
        second: String,
    ) {
        assertEquals(ORG, org) { "the test profile points the connector at org $ORG" }
        stub(listOf(FakeRepo(name = first), FakeRepo(name = second)))
    }

    @Given("a fork {string} of {string}")
    fun aFork(
        name: String,
        upstream: String,
    ) {
        stub(repos + FakeRepo(name = name, forkOf = upstream))
    }

    @Given("{string} has CODEOWNERS {string}")
    fun hasCodeowners(
        repo: String,
        owner: String,
    ) {
        github.files.hasCodeowners(ORG, repo, "* $owner")
    }

    @Given("{string} declares {string} in package.json")
    fun declares(
        repo: String,
        packageName: String,
    ) {
        val manifest = manifests.getOrPut(repo) { NpmManifest(name = "@$ORG/$repo-app") }
        manifests[repo] = manifest.copy(dependencies = manifest.dependencies + packageName)
        publish(repo)
    }

    @Given("Repository {string} publishes package {string}")
    fun publishes(
        repositoryKey: String,
        packageName: String,
    ) {
        val repo = repositoryKey.substringAfterLast('/')
        manifests[repo] = manifests.getOrPut(repo) { NpmManifest(name = packageName) }.copy(name = packageName)
        publish(repo)
    }

    @Given("the package.json of {string} cannot be parsed")
    fun unparseable(repo: String) {
        github.files.has(ORG, repo, PACKAGE_JSON, "{ this is not json at all")
    }

    @Given("{string} has the workflow {string}")
    fun hasWorkflow(
        repo: String,
        path: String,
    ) {
        github.files.has(ORG, repo, path, "name: ${path.substringAfterLast('/')}\non: push\n")
    }

    @When("the GitHub connector runs")
    fun theConnectorRuns() {
        sync.startSync(CONNECTOR)
        sync.awaitRun(SUCCESS)
    }

    @When("the GitHub connector runs and the run finishes {string}")
    fun theConnectorRunsAndFinishes(status: String) {
        sync.startSync(CONNECTOR)
        sync.awaitRun(status)
    }

    @Given("the connector has run once")
    fun theConnectorHasRunOnce() {
        theConnectorRuns()
        nodesAfterFirstRun = nodeCounts()
    }

    @When("it runs again with no changes upstream")
    fun itRunsAgain() {
        theConnectorRuns()
    }

    @Then("a Repository {string} exists with sourceSystem {string}")
    fun aRepositoryExists(
        key: String,
        sourceSystem: String,
    ) {
        val node = node("Repository", key)
        assertEquals(sourceSystem, node.path("provenance").path("sourceSystem").asText())
        repositoryKey = key
    }

    @Then("a Pipeline {string} exists with sourceSystem {string}")
    fun aPipelineExists(
        key: String,
        sourceSystem: String,
    ) {
        val node = node("Pipeline", key)
        assertEquals(sourceSystem, node.path("provenance").path("sourceSystem").asText())
    }

    @Then("a Team {string} exists in the graph")
    fun aTeamExists(slug: String) {
        teamKey = "github.com/$ORG/$slug"
        node("Team", requireNotNull(teamKey))
    }

    @Then("an OWNED_BY edge exists from the repository to the team")
    fun anOwnedByEdgeExists() {
        val repo = requireNotNull(repositoryKey) { "no repository was looked at" }
        val team = requireNotNull(teamKey) { "no team was looked at" }
        val owners = outgoing(repo, "OWNED_BY").map { it.path("other").path("key").asText() }
        assertTrue(team in owners) { "owners of $repo were $owners" }
    }

    @Then("a DEPENDS_ON edge exists from {string} to {string} with kind {string}")
    fun aDependsOnEdgeExists(
        from: String,
        to: String,
        kind: String,
    ) {
        val edges = outgoing(keyOf(from), "DEPENDS_ON")
        val edge =
            edges.firstOrNull {
                it.path("other").path("type").asText() == "Repository" && it.path("other").path("key").asText() == keyOf(to)
            }
        dependency =
            checkNotNull(edge) {
                "$from does not depend on repository $to; it depends on " + edges.map { it.path("other").path("key").asText() }
            }
        assertEquals(kind, edge.path("props").path("kind").asText())
    }

    @Then("the edge has manifest {string} and inferred true and confidence less than {double}")
    fun theEdgeHasEvidence(
        manifest: String,
        ceiling: Double,
    ) {
        val edge = checkNotNull(dependency) { "no dependency was looked at" }
        assertEquals(manifest, edge.path("props").path("manifest").asText())
        assertTrue(edge.path("provenance").path("inferred").asBoolean()) { "an inference was recorded as a report: $edge" }
        val confidence = edge.path("provenance").path("confidence").asDouble()
        assertTrue(confidence < ceiling) { "confidence was $confidence" }
    }

    @Then("no DEPENDS_ON edge points at {string}")
    fun noDependsOnEdgePointsAt(key: String) {
        world.get("/api/v1/edges?nodeId=Repository:$key&direction=in&edgeType=DEPENDS_ON")
        val incoming = world.lastBody().path("items").filterNot { it.path("provenance").says("validTo") }
        assertTrue(incoming.isEmpty()) { "$key is depended on by ${incoming.map { it.path("other").path("key").asText() }}" }
    }

    @Then("Repository {string} has forkOf {string}")
    fun hasForkOf(
        key: String,
        upstream: String,
    ) {
        assertEquals(upstream, node("Repository", key).path("props").path("forkOf").asText())
    }

    @Then("{string} HAS_PIPELINE {string}")
    fun hasPipeline(
        repo: String,
        workflowPath: String,
    ) {
        val pipelines = pipelineKeys(repo)
        val expected = "github-actions:${keyOf(repo)}:$workflowPath"
        assertTrue(expected in pipelines) { "pipelines of $repo were $pipelines" }
    }

    @Then("{string} has {int} pipelines")
    fun hasPipelines(
        repo: String,
        count: Int,
    ) {
        val pipelines = pipelineKeys(repo)
        assertEquals(count, pipelines.size) { "pipelines of $repo were $pipelines" }
    }

    @Then("{string} and every edge out of it carry sourceSystem {string}, a sourceId and observedAt {string}")
    fun everyFactCarriesProvenance(
        repo: String,
        sourceSystem: String,
        observedAt: String,
    ) {
        val facts = listOf(node("Repository", keyOf(repo))) + OUT_EDGES.flatMap { outgoing(keyOf(repo), it) }
        assertTrue(facts.size > OUT_EDGES.size) { "expected an edge of each kind out of $repo, found ${facts.size - 1}" }
        facts.forEach { fact ->
            val provenance = fact.path("provenance")
            assertEquals(sourceSystem, provenance.path("sourceSystem").asText()) { "wrong source on $fact" }
            assertTrue(provenance.says("sourceId")) { "no sourceId on $fact" }
            assertEquals(observedAt, provenance.path("observedAt").asText()) { "observedAt not GitHub's on $fact" }
        }
    }

    @Then("every OWNED_BY and HAS_PIPELINE edge out of {string} has confidence 1.0 and is not inferred")
    fun reportedEdgesAreFacts(repo: String) {
        val reported = outgoing(keyOf(repo), "OWNED_BY") + outgoing(keyOf(repo), "HAS_PIPELINE")
        assertFalse(reported.isEmpty()) { "no reported edges out of $repo" }
        reported.forEach { edge ->
            assertEquals(FULL_CONFIDENCE, edge.path("provenance").path("confidence").asDouble()) { "on $edge" }
            assertFalse(edge.path("provenance").path("inferred").asBoolean()) { "a report was marked inferred: $edge" }
        }
    }

    @Then("the SyncRun reports zero written and the node count is unchanged")
    fun zeroWritten() {
        val run = sync.runNode().path("props")
        assertEquals(0, run.path("written").asInt(-1)) { "the re-run wrote something: $run" }
        assertTrue(run.path("unchanged").asInt(0) > 0) { "the re-run reported nothing unchanged: $run" }
        assertEquals(nodesAfterFirstRun, nodeCounts()) { "the re-run changed how many nodes there are" }
    }

    @Then("the SyncRun reports {int} failed")
    fun reportsFailed(failed: Int) {
        val run = sync.runNode().path("props")
        assertEquals(failed, run.path("failed").asInt(-1)) { "run: $run" }
    }

    @Then("the SyncRun reports more than {int} written")
    fun reportsWritten(floor: Int) {
        val run = sync.runNode().path("props")
        assertTrue(run.path("written").asInt(-1) > floor) { "run: $run" }
    }

    @Then("the SyncRun records the version the connector reports")
    fun recordsConnectorVersion() {
        val recorded =
            sync
                .runNode()
                .path("props")
                .path("connectorVersion")
                .asText()
        world.get("/api/v1/connectors/$CONNECTOR")
        val reported = world.lastBody().path("version").asText()
        assertTrue(reported.isNotBlank()) { "the connector reports no version: " + world.lastResponse().body }
        assertEquals(reported, recorded)
    }

    private fun stub(all: List<FakeRepo>) {
        repos = all
        github.hasRepositories(ORG, all)
        // 404 unless a step says otherwise, which is what GitHub answers for no CODEOWNERS.
        all.forEach { github.files.hasCodeowners(ORG, it.name, content = null) }
    }

    private fun publish(repo: String) {
        github.files.has(ORG, repo, PACKAGE_JSON, requireNotNull(manifests[repo]).json())
    }

    private fun node(
        type: String,
        key: String,
    ): JsonNode {
        world.get("/api/v1/nodes/$type/$key")
        assertEquals(OK, world.lastStatus()) { "no $type $key: " + world.lastResponse().body }
        return world.lastBody()
    }

    private fun pipelineKeys(repo: String) = outgoing(keyOf(repo), "HAS_PIPELINE").map { it.path("other").path("key").asText() }

    /** Only what is still current: a closed edge is history. */
    private fun outgoing(
        repoKey: String,
        type: String,
    ): List<JsonNode> {
        world.get("/api/v1/edges?nodeId=Repository:$repoKey&direction=out&edgeType=$type")
        return world
            .lastBody()
            .path("items")
            .filterNot { it.path("provenance").says("validTo") }
    }

    /** How many nodes of each type the connector writes, read a page at a time. */
    private fun nodeCounts(): Map<String, Int> =
        COUNTED_TYPES.associateWith { type ->
            var count = 0
            var cursor: String? = null
            do {
                world.get("/api/v1/nodes/$type?limit=$PAGE" + (cursor?.let { "&cursor=$it" } ?: ""))
                val body = world.lastBody()
                count += body.path("items").size()
                cursor = body.path("nextCursor").takeIf { it.isTextual }?.asText()
            } while (cursor != null)
            count
        }

    private fun keyOf(repo: String) = if (repo.contains('/')) repo else "github.com/$ORG/$repo"

    private data class NpmManifest(
        val name: String,
        val dependencies: List<String> = emptyList(),
    ) {
        fun json(): String =
            """{ "name": "$name", "version": "1.0.0", "dependencies": {""" +
                dependencies.joinToString(", ") { "\"$it\": \"^1.0.0\"" } + "} }"
    }

    private companion object {
        const val CONNECTOR = "github"
        const val ORG = "acme"
        const val OK = 200
        const val PAGE = 100
        const val SUCCESS = "SUCCESS"
        const val PACKAGE_JSON = "package.json"
        const val FULL_CONFIDENCE = 1.0
        val OUT_EDGES = listOf("OWNED_BY", "HAS_PIPELINE", "DEPENDS_ON")
        val COUNTED_TYPES = listOf("Repository", "Team", "Pipeline", "Library", "IacFile")
    }
}
