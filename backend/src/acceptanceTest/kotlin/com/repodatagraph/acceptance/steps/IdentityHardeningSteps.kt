package com.repodatagraph.acceptance.steps

import com.repodatagraph.acceptance.support.ApiWorld
import com.repodatagraph.support.TestPrincipalConfig
import io.cucumber.datatable.DataTable
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.web.util.UriComponentsBuilder
import java.time.Instant

/**
 * Identity in a real estate (#98): a monorepo's services, an artifact seen before its digest was,
 * environments spelt per team, and two nodes that are one thing, merged.
 *
 * Everything goes through the API, as a connector or an operator would. A key holding characters a
 * path would read as structure, such as an Artifact's `:` or `@`, is addressed through `by-key`.
 */
class IdentityHardeningSteps(
    private val world: ApiWorld,
) {
    private var versionOnlyKey: String? = null
    private var digestKey: String? = null
    private var conflictFrom: String? = null
    private var conflictInto: String? = null

    @Given("Repository {string} PROVIDES Service {string} with path {string}")
    fun repositoryProvidesServiceWithPath(
        repository: String,
        service: String,
        path: String,
    ) {
        val repositoryId = ensureRepository(repository)
        world.post(NODES + "Service", mapOf("props" to mapOf("name" to service)))
        world.post(
            EDGES,
            mapOf("type" to "PROVIDES", "fromId" to repositoryId, "toId" to "Service:$service", "props" to mapOf("path" to path)),
        )
        assertTrue(world.lastStatus() in 200..299) { "linking the service failed: " + world.lastResponse().body }
    }

    @When("the edges of Repository {string} are listed")
    fun theEdgesOfRepositoryAreListed(key: String) {
        world.get(edgesOf("Repository:$key", "out", "PROVIDES"))
    }

    @Then("both PROVIDES edges are returned with their paths:")
    fun bothProvidesEdgesAreReturnedWithTheirPaths(expected: DataTable) {
        assertEquals(200, world.lastStatus()) { world.lastResponse().body }
        val listed =
            world
                .lastBody()
                .path("items")
                .associate { item ->
                    item.path("other").path("key").asText() to
                        item.path("props").path("path").asText()
                }
        val wanted = expected.asMaps().associate { it.getValue("service") to it.getValue("path") }
        assertEquals(wanted, listed) { world.lastResponse().body }
    }

    @Given("Artifact {string} with identityQuality version-only")
    fun artifactWithIdentityQualityVersionOnly(key: String) {
        createVersionOnly(key, emptyMap())
    }

    @Given("Artifact {string} with identityQuality version-only and commitSha {string}")
    fun artifactVersionOnlyWithCommit(
        key: String,
        commitSha: String,
    ) {
        createVersionOnly(key, mapOf("commitSha" to commitSha))
    }

    @Given("that Artifact is BUILT_FROM Repository {string}")
    fun thatArtifactIsBuiltFrom(repository: String) {
        val repositoryId = ensureRepository(repository)
        world.post(EDGES, mapOf("type" to "BUILT_FROM", "fromId" to "Artifact:" + requireVersionOnly(), "toId" to repositoryId))
        assertTrue(world.lastStatus() in 200..299) { "linking the artifact failed: " + world.lastResponse().body }
    }

    @When("an Artifact {string} is observed with name {string} and version {string}")
    fun anArtifactIsObserved(
        key: String,
        name: String,
        version: String,
    ) {
        observe(key, name, version, emptyMap())
    }

    @When("an Artifact {string} is observed with name {string}, version {string} and commitSha {string}")
    fun anArtifactIsObservedWithCommit(
        key: String,
        name: String,
        version: String,
        commitSha: String,
    ) {
        observe(key, name, version, mapOf("commitSha" to commitSha))
    }

    @Then("the version-only node is merged into the digest node")
    fun theVersionOnlyNodeIsMergedIntoTheDigestNode() {
        val history = history("Artifact:" + requireVersionOnly())
        val current = history.path("current")
        assertEquals("merged", current.path("retiredReason").asText()) { history.toString() }
        assertEquals("Artifact:" + requireDigest(), current.path("mergedInto").asText()) { history.toString() }
    }

    @Then("resolve_node {string} returns the digest node")
    fun resolveNodeReturnsTheDigestNode(key: String) {
        world.get(byKey("Artifact", key))
        assertEquals(200, world.lastStatus()) { world.lastResponse().body }
        assertEquals(requireDigest(), world.lastBody().path("key").asText())
    }

    @Then("the digest node is BUILT_FROM Repository {string}")
    fun theDigestNodeIsBuiltFrom(repository: String) {
        world.get(edgesOf("Artifact:" + requireDigest(), "out", "BUILT_FROM"))
        val targets = world.lastBody().path("items").map { it.path("other").path("key").asText() }
        assertEquals(listOf(repository), targets) { world.lastResponse().body }
    }

    @Then("the digest node has identityQuality {string}")
    fun theDigestNodeHasIdentityQuality(quality: String) {
        world.get(byKey("Artifact", requireDigest()))
        assertEquals(
            quality,
            world
                .lastBody()
                .path("props")
                .path("identityQuality")
                .asText(),
        ) { world.lastResponse().body }
    }

    @Then("Artifact {string} is still current")
    fun artifactIsStillCurrent(key: String) {
        assertStillCurrent("Artifact", key)
    }

    @Given("two Repository nodes with different host values")
    fun twoRepositoryNodesWithDifferentHostValues() {
        conflictFrom = createRepository("https://github.com/acme/payments")
        conflictInto = createRepository("https://gitlab.com/acme/payments")
    }

    @When("one is merged into the other")
    fun oneIsMergedIntoTheOther() {
        merge("Repository", checkNotNull(conflictFrom), checkNotNull(conflictInto), dryRun = false)
    }

    @Then("the response is 409 with the conflicting fields {string}")
    fun theResponseIs409WithTheConflictingFields(fields: String) {
        assertEquals(409, world.lastStatus()) { world.lastResponse().body }
        val named = world.lastBody().path("fields").map { it.asText() }
        assertEquals(fields.split(",").map { it.trim() }, named) { world.lastResponse().body }
    }

    @Given("the Repository at {string} is OWNED_BY Team {string}")
    fun repositoryIsOwnedByTeam(
        repository: String,
        team: String,
    ) {
        val repositoryId = ensureRepository(repository)
        world.post(NODES + "Team", mapOf("props" to mapOf("name" to team)))
        world.post(EDGES, mapOf("type" to "OWNED_BY", "fromId" to repositoryId, "toId" to "Team:$team"))
        assertTrue(world.lastStatus() in 200..299) { "linking the team failed: " + world.lastResponse().body }
    }

    @Given("a Repository exists at {string}")
    fun aRepositoryExistsAt(remote: String) {
        ensureRepository(remote)
    }

    @Given("Repository {string} has been closed")
    fun repositoryHasBeenClosed(key: String) {
        world.get(NODES + "Repository/$key")
        val props = world.lastBody().path("props")
        world.put(
            NODES + "Repository/$key",
            // Now: it began moments ago, and a validity may not end before it began.
            mapOf("props" to props, "provenance" to mapOf("validTo" to Instant.now().toString())),
        )
        assertEquals(200, world.lastStatus()) { "closing the repository failed: " + world.lastResponse().body }
    }

    @When("Repository {string} is merged into {string}")
    fun repositoryIsMergedInto(
        from: String,
        into: String,
    ) {
        merge("Repository", from, into, dryRun = false)
    }

    @When("Repository {string} is merged into {string} as a dry run")
    fun repositoryIsMergedIntoAsADryRun(
        from: String,
        into: String,
    ) {
        merge("Repository", from, into, dryRun = true)
    }

    @Then("Repository {string} has no edges left")
    fun repositoryHasNoEdgesLeft(repository: String) {
        world.get(edgesOf("Repository:$repository", "both", null))
        assertEquals(200, world.lastStatus()) { world.lastResponse().body }
        val types = world.lastBody().path("items").map { it.path("type").asText() }
        assertTrue(types.isEmpty()) { "edges left: " + world.lastResponse().body }
    }

    @Then("Repository {string} lists previousKeys containing {string}")
    fun repositoryListsPreviousKeys(
        repository: String,
        previousKey: String,
    ) {
        world.get(NODES + "Repository/$repository")
        val previous =
            world
                .lastBody()
                .path("provenance")
                .path("previousKeys")
                .map { it.asText() }
        assertTrue(previousKey in previous) { "previousKeys are $previous in " + world.lastResponse().body }
    }

    @Then("resolving Repository {string} returns {string}")
    fun resolvingRepositoryReturns(
        key: String,
        resolved: String,
    ) {
        world.get(NODES + "Repository/$key")
        assertEquals(200, world.lastStatus()) { world.lastResponse().body }
        assertEquals(resolved, world.lastBody().path("key").asText())
    }

    @Then("the history of Repository {string} shows it retired as {string} into {string} by the acting principal")
    fun theHistoryShowsItRetiredInto(
        key: String,
        reason: String,
        into: String,
    ) {
        val history = history("Repository:$key")
        val current = history.path("current")
        assertTrue(current.path("retired").asBoolean()) { history.toString() }
        assertEquals(reason, current.path("retiredReason").asText()) { history.toString() }
        assertEquals(into, current.path("mergedInto").asText()) { history.toString() }
        assertEquals(TestPrincipalConfig.SUBJECT, current.path("mergedBy").asText()) { history.toString() }
    }

    @Then("the merge preview moves {int} edge and adds previousKey {string}")
    fun theMergePreviewMoves(
        edges: Int,
        previousKey: String,
    ) {
        val body = world.lastBody()
        assertTrue(body.path("dryRun").asBoolean()) { body.toString() }
        assertEquals(edges, body.path("edges").path("moved").asInt()) { body.toString() }
        val previous = body.path("previousKeys").map { it.asText() }
        assertTrue(previousKey in previous) { body.toString() }
    }

    @Then("Repository {string} is still current")
    fun repositoryIsStillCurrent(key: String) {
        assertStillCurrent("Repository", key)
    }

    @When("I GET the ontology")
    fun iGetTheOntology() {
        world.get("/api/v1/ontology")
    }

    @Then("environment {string} is served with aliases {string}, {string} and {string}")
    fun environmentIsServedWithAliases(
        canonical: String,
        first: String,
        second: String,
        third: String,
    ) {
        assertEquals(200, world.lastStatus())
        val environment =
            world
                .lastBody()
                .path("environments")
                .firstOrNull { it.path("name").asText() == canonical }
        assertNotNull(environment) { "no environment $canonical in " + world.lastBody().path("environments") }
        assertEquals(listOf(first, second, third), environment!!.path("aliases").map { it.asText() })
    }

    @When("I POST an Environment named {string} of type {string}")
    fun iPostAnEnvironment(
        name: String,
        type: String,
    ) {
        world.post(NODES + "Environment", mapOf("props" to mapOf("name" to name, "type" to type)))
    }

    private fun createVersionOnly(
        key: String,
        extra: Map<String, Any?>,
    ) {
        val (name, version) = key.split(":", limit = 2)
        world.post(
            NODES + "Artifact",
            mapOf("props" to mapOf("name" to name, "version" to version, "artifactType" to "container-image") + extra),
        )
        assertEquals(201, world.lastStatus()) { "creating the artifact failed: " + world.lastResponse().body }
        assertEquals(key, world.lastBody().path("key").asText())
        assertEquals(
            "version-only",
            world
                .lastBody()
                .path("props")
                .path("identityQuality")
                .asText(),
        ) { world.lastResponse().body }
        versionOnlyKey = key
    }

    /** `<registry>/<name>@<digest>`, split around the name the step gives. */
    private fun observe(
        key: String,
        name: String,
        version: String,
        extra: Map<String, Any?>,
    ) {
        val registry = key.substringBefore("/$name@")
        val digest = key.substringAfter("@")
        world.post(
            NODES + "Artifact",
            mapOf(
                "props" to
                    mapOf(
                        "registry" to registry,
                        "name" to name,
                        "version" to version,
                        "digest" to digest,
                        "artifactType" to "container-image",
                    ) + extra,
            ),
        )
        assertEquals(201, world.lastStatus()) { "observing the artifact failed: " + world.lastResponse().body }
        assertEquals(key, world.lastBody().path("key").asText())
        digestKey = key
    }

    private fun merge(
        type: String,
        from: String,
        into: String,
        dryRun: Boolean,
    ) {
        world.post(NODES + "$type/$from/merge", mapOf("into" to into, "dryRun" to dryRun))
    }

    private fun history(nodeId: String) =
        world
            .get(
                UriComponentsBuilder
                    .fromPath("/api/v1/lifecycle/history")
                    .queryParam("nodeId", nodeId)
                    .build()
                    .encode()
                    .toUriString(),
            ).let {
                assertEquals(200, world.lastStatus()) { "reading the history failed: " + world.lastResponse().body }
                world.lastBody()
            }

    private fun assertStillCurrent(
        type: String,
        key: String,
    ) {
        val history = history("$type:$key")
        val current = history.path("current")
        assertTrue(!current.path("retired").asBoolean()) { history.toString() }
        assertEquals(key, history.path("nodeId").asText().removePrefix("$type:"))
    }

    /** Creates the repository at [remote] unless it exists, and returns its id. */
    private fun ensureRepository(remote: String): String {
        world.post(NODES + "Repository", mapOf("props" to repositoryProps(remote)))
        return when (world.lastStatus()) {
            201 -> world.lastBody().path("id").asText()
            409 -> world.lastBody().path("existingId").asText()
            else -> error("creating the repository failed: " + world.lastResponse().body)
        }
    }

    private fun createRepository(url: String): String {
        world.post(NODES + "Repository", mapOf("props" to repositoryProps(url)))
        assertEquals(201, world.lastStatus()) { "creating the repository failed: " + world.lastResponse().body }
        return world.lastBody().path("key").asText()
    }

    private fun requireVersionOnly(): String = checkNotNull(versionOnlyKey) { "no version-only artifact was created" }

    private fun requireDigest(): String = checkNotNull(digestKey) { "no digest artifact was observed" }

    private fun byKey(
        type: String,
        key: String,
    ): String =
        UriComponentsBuilder
            .fromPath("$NODES$type/by-key")
            .queryParam("key", key)
            .build()
            .encode()
            .toUriString()

    private fun edgesOf(
        nodeId: String,
        direction: String,
        edgeType: String?,
    ): String =
        UriComponentsBuilder
            .fromPath(EDGES)
            .queryParam("nodeId", nodeId)
            .queryParam("direction", direction)
            .apply { if (edgeType != null) queryParam("edgeType", edgeType) }
            .build()
            .encode()
            .toUriString()

    /** Every property the registry marks required, so only the identity is under test. */
    private fun repositoryProps(url: String) =
        mapOf(
            "url" to url,
            "defaultBranch" to "main",
            "topics" to emptyList<String>(),
            "codeowners" to emptyList<String>(),
        )

    private companion object {
        const val NODES = "/api/v1/nodes/"
        const val EDGES = "/api/v1/edges"
    }
}
