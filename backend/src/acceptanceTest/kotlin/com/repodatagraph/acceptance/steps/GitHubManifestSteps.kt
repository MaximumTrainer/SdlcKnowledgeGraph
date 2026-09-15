package com.repodatagraph.acceptance.steps

import com.fasterxml.jackson.databind.JsonNode
import com.repodatagraph.acceptance.support.ApiWorld
import com.repodatagraph.acceptance.support.says
import com.repodatagraph.support.connector.FakeGitHub
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * What a repository depends on, and what infrastructure it claims, read out of files inside it.
 *
 * The claims here are about evidence rather than about HTTP. A dependency read from a manifest is
 * recorded with the file it came from, so a reviewer can go and look; a dependency guessed from a
 * naming convention is marked as a guess, so it can be told apart from one that was read.
 *
 * Shares [GitHubConnectorSteps]'s Background and sync steps: this is the same connector reading
 * more of the same repositories.
 */
class GitHubManifestSteps(
    private val world: ApiWorld,
    private val github: FakeGitHub,
) {
    @Given("the file {string} in {string} contains:")
    fun theFileContains(
        path: String,
        repo: String,
        content: String,
    ) {
        github.files.has(ORG, repo, path, content)
    }

    @Then("the graph has Library {string}")
    fun theGraphHasLibrary(key: String) {
        world.get("/api/v1/nodes/Library/$key")
        assertEquals(OK, world.lastStatus()) { "no Library $key: " + world.lastResponse().body }
    }

    @Then("the graph has no Library {string}")
    fun theGraphHasNoLibrary(key: String) {
        world.get("/api/v1/nodes/Library/$key")
        assertEquals(NOT_FOUND, world.lastStatus()) { "a Library was created for something we publish: $key" }
    }

    @Then("{string} DEPENDS_ON {string} from {string} at version {string} with scope {string}")
    @Suppress("LongParameterList")
    fun dependsOnLibrary(
        repoKey: String,
        libraryKey: String,
        manifest: String,
        version: String,
        scope: String,
    ) {
        val edge = dependency(repoKey, "Library", libraryKey)
        assertEquals(manifest, edge.path("props").path("manifest").asText())
        assertEquals(version, edge.path("props").path("version").asText())
        assertEquals(scope, edge.path("props").path("scope").asText())
        assertEquals("library", edge.path("props").path("kind").asText())
    }

    @Then("{string} DEPENDS_ON Repository {string}")
    fun dependsOnRepository(
        repoKey: String,
        otherKey: String,
    ) {
        lastDependency = dependency(repoKey, "Repository", otherKey)
    }

    @Then("that dependency is marked inferred with confidence {double}")
    fun thatDependencyIsInferred(confidence: Double) {
        val provenance = requireLastDependency().path("provenance")
        assertTrue(provenance.path("inferred").asBoolean()) { "a guess was recorded as a report" }
        assertEquals(confidence, provenance.path("confidence").asDouble())
    }

    @Then("{string} depends on nothing")
    fun dependsOnNothing(repoKey: String) {
        val edges = outgoing(repoKey, "DEPENDS_ON")
        assertTrue(edges.isEmpty()) { "expected no dependencies, found ${edges.map { it.path("other").path("key").asText() }}" }
    }

    @Then("the graph has IacFile {string} in format {string}")
    fun theGraphHasIacFile(
        key: String,
        format: String,
    ) {
        world.get("/api/v1/nodes/IacFile/$key")
        assertEquals(OK, world.lastStatus()) { "no IacFile $key: " + world.lastResponse().body }
        assertEquals(
            format,
            world
                .lastBody()
                .path("props")
                .path("format")
                .asText(),
        )
    }

    @Then("that IacFile names resource {string}")
    fun thatIacFileNamesResource(reference: String) {
        val refs =
            world
                .lastBody()
                .path("props")
                .path("resourceRefs")
                .map { it.asText() }
        assertTrue(refs.contains(reference)) { "resourceRefs were $refs" }
    }

    @Then("{string} CONTAINS_IAC {string}")
    fun containsIac(
        repoKey: String,
        iacKey: String,
    ) {
        val targets = outgoing(repoKey, "CONTAINS_IAC").map { it.path("other").path("key").asText() }
        assertTrue(targets.contains(iacKey)) { "IaC files of $repoKey were $targets" }
    }

    private var lastDependency: JsonNode? = null

    private fun requireLastDependency() = checkNotNull(lastDependency) { "no dependency has been looked up yet" }

    private fun dependency(
        repoKey: String,
        toType: String,
        toKey: String,
    ): JsonNode {
        val edges = outgoing(repoKey, "DEPENDS_ON")
        val match =
            edges.firstOrNull {
                it.path("other").path("type").asText() == toType && it.path("other").path("key").asText() == toKey
            }
        return checkNotNull(match) {
            "$repoKey does not depend on $toType $toKey; it depends on " +
                edges.map { it.path("other").path("key").asText() }
        }
    }

    /** Only what is still current: a closed edge is history, not a dependency. */
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

    private companion object {
        const val ORG = "acme"
        const val OK = 200
        const val NOT_FOUND = 404
    }
}
