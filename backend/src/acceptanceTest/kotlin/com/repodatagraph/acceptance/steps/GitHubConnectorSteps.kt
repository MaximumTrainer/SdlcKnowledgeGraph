package com.repodatagraph.acceptance.steps

import com.repodatagraph.acceptance.support.ApiWorld
import com.repodatagraph.acceptance.support.SyncWorld
import com.repodatagraph.support.connector.FakeGitHub
import com.repodatagraph.support.connector.FakeRepo
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Drives the GitHub connector against a fake GitHub over real HTTP.
 *
 * Whether a run is recorded, provenance stamped and a tombstone honoured is proved once against the
 * scripted connector in `connector-sync.feature`; those claims are about the mechanism. What is left
 * to prove here is what is specific to GitHub - that every page is read, that ownership comes from
 * CODEOWNERS rather than a guess, that an individual owner is not mistaken for a team, and that an
 * archived repository is closed rather than erased.
 */
class GitHubConnectorSteps(
    private val world: ApiWorld,
    private val sync: SyncWorld,
    private val github: FakeGitHub,
) {
    private var stubbedRepos: List<FakeRepo> = emptyList()

    @Before
    fun resetTheFake() {
        github.reset()
        stubbedRepos = emptyList()
        sync.awaitIdle(CONNECTOR)
    }

    @Given("the GitHub connector is registered for org {string}")
    fun theGitHubConnectorIsRegistered(org: String) {
        world.get("/api/v1/connectors/$CONNECTOR")
        assertEquals(200, world.lastStatus()) { "the github connector is not registered: " + world.lastResponse().body }
        assertEquals(org, ORG) { "the test profile points the connector at a different org" }
    }

    @Given("GitHub has repository {string} with topics {string} and {string}")
    fun gitHubHasRepositoryWithTwoTopics(
        name: String,
        first: String,
        second: String,
    ) {
        stubRepositories(listOf(FakeRepo(name = name, topics = listOf(first, second))))
    }

    @Given("GitHub has repository {string} with topics {string}")
    fun gitHubHasRepository(
        name: String,
        topic: String,
    ) {
        stubRepositories(listOf(FakeRepo(name = name, topics = listOf(topic))))
    }

    @Given("GitHub also has archived repository {string}")
    fun gitHubAlsoHasArchivedRepository(name: String) {
        stubRepositories(stubbedRepos + FakeRepo(name = name, archived = true))
    }

    @Given("GitHub has {int} repositories across two pages")
    fun gitHubHasPagedRepositories(total: Int) {
        val all = (1..total).map { FakeRepo(name = "repo-$it") }
        github.hasPagedRepositories(ORG, all.take(PAGE_SIZE), all.drop(PAGE_SIZE))
        all.forEach { github.hasNoCodeowners(ORG, it.name) }
    }

    @Given("GitHub has CODEOWNERS for {string} containing {string}")
    fun gitHubHasCodeowners(
        repo: String,
        content: String,
    ) {
        github.hasCodeowners(ORG, repo, content)
    }

    @When("I ask the GitHub connector for a full sync")
    fun iAskForAFullSync() {
        sync.startSync(CONNECTOR)
    }

    @Then("the graph has Repository {string}")
    fun theGraphHasRepository(key: String) {
        world.get("/api/v1/nodes/Repository/$key")
        assertEquals(200, world.lastStatus()) { "no Repository $key: " + world.lastResponse().body }
    }

    @Then("that Repository has topics {string} and {string}")
    fun thatRepositoryHasTopics(
        first: String,
        second: String,
    ) {
        val topics =
            world
                .lastBody()
                .path("props")
                .path("topics")
                .map { it.asText() }
        assertTrue(topics.containsAll(listOf(first, second))) { "topics were $topics" }
    }

    @Then("that Repository has provenance sourceSystem {string} and confidence 1.0")
    fun thatRepositoryHasProvenance(sourceSystem: String) {
        val provenance = world.lastBody().path("provenance")
        assertEquals(sourceSystem, provenance.path("sourceSystem").asText())
        assertEquals(FULL_CONFIDENCE, provenance.path("confidence").asDouble())
        assertFalse(provenance.path("inferred").asBoolean()) { "a reported fact was marked inferred" }
    }

    @Then("that Repository has its validity closed")
    fun thatRepositoryHasValidityClosed() {
        assertTrue(
            world
                .lastBody()
                .path("provenance")
                .path("validTo")
                .asText()
                .isNotBlank(),
        ) { "an archived repository was left open: " + world.lastResponse().body }
    }

    @Then("the graph has Team {string}")
    fun theGraphHasTeam(key: String) {
        world.get("/api/v1/nodes/Team/$key")
        assertEquals(200, world.lastStatus()) { "no Team $key: " + world.lastResponse().body }
    }

    @Then("the graph has no Team {string}")
    fun theGraphHasNoTeam(key: String) {
        world.get("/api/v1/nodes/Team/$key")
        assertEquals(NOT_FOUND, world.lastStatus()) { "an individual owner became a Team: $key" }
    }

    @Then("{string} is OWNED_BY {string}")
    fun isOwnedBy(
        repoKey: String,
        teamKey: String,
    ) {
        val owners = ownershipTargets(repoKey)
        assertTrue(owners.contains(teamKey)) { "owners of $repoKey were $owners" }
    }

    @Then("{string} is owned by nobody")
    fun isOwnedByNobody(repoKey: String) {
        val owners = ownershipTargets(repoKey)
        assertTrue(owners.isEmpty()) { "expected no ownership, found $owners" }
    }

    @Then("the sync run recorded {int} nodes")
    fun theSyncRunRecordedNodes(nodes: Int) {
        assertEquals(
            nodes,
            sync
                .runNode()
                .path("props")
                .path("nodesUpserted")
                .asInt(),
        )
    }

    @Then("the stored watermark for {string} is set")
    fun theStoredWatermarkIsSet(connector: String) {
        world.get("/api/v1/connectors/$connector")
        assertTrue(
            world
                .lastBody()
                .path("state")
                .path("watermark")
                .asText()
                .isNotBlank(),
        ) { "no watermark was stored: " + world.lastResponse().body }
    }

    private fun stubRepositories(repos: List<FakeRepo>) {
        stubbedRepos = repos
        github.hasRepositories(ORG, repos)
        // 404 by default, which is what GitHub answers for a repository with no CODEOWNERS. A
        // scenario that wants one stubs it afterwards, and the later stub takes precedence.
        repos.forEach { github.hasNoCodeowners(ORG, it.name) }
    }

    /** Ownership that is still current: a closed edge is history, not an owner. */
    private fun ownershipTargets(repoKey: String): List<String> {
        world.get("/api/v1/edges?nodeId=Repository:$repoKey&direction=out&edgeType=OWNED_BY")
        return world
            .lastBody()
            .path("items")
            .filter {
                it
                    .path("provenance")
                    .path("validTo")
                    .asText()
                    .isBlank()
            }.map { it.path("other").path("key").asText() }
    }

    private companion object {
        const val CONNECTOR = "github"
        const val ORG = "acme"
        const val NOT_FOUND = 404
        const val PAGE_SIZE = 100
        const val FULL_CONFIDENCE = 1.0
    }
}
