package com.repodatagraph.acceptance.steps

import com.repodatagraph.acceptance.support.ApiWorld
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.data.neo4j.core.Neo4jClient

/**
 * A repository addressed by the id its provider gives it rather than by its remote (#88): created
 * with one, renamed through it, and still found under the remote it had before the rename.
 *
 * Everything goes through the API, as a connector or an agent holding a GitHub App installation
 * would; only the count of nodes holding a provider id is read from the graph, because no endpoint
 * answers "how many" and "exactly one" is the whole point of the alias.
 */
class RepositoryIdentitySteps(
    private val world: ApiWorld,
    private val neo4jClient: Neo4jClient,
) {
    private var repositoryId: String? = null
    private var providerId: String? = null
    private var renamedId: String? = null

    @Given("a Repository with providerId {string} and url {string}")
    fun aRepositoryWithProviderId(
        providerId: String,
        url: String,
    ) {
        world.post(NODES, mapOf("props" to repositoryProps(url) + mapOf("provider" to "github", "providerId" to providerId)))
        assertEquals(201, world.lastStatus()) { "setting up the repository failed: " + world.lastResponse().body }
        repositoryId = world.lastBody().path("id").asText()
        this.providerId = providerId
    }

    @Given("that Repository is owned by Team {string}")
    fun thatRepositoryIsOwnedByTeam(team: String) {
        world.post(NODES_OF + "Team", mapOf("props" to mapOf("name" to team)))
        world.post(EDGES, mapOf("type" to "OWNED_BY", "fromId" to requireRepositoryId(), "toId" to "Team:$team"))
        assertTrue(world.lastStatus() in 200..299) { "linking the team failed: " + world.lastResponse().body }
    }

    @Given("that Repository has been renamed to {string}")
    fun thatRepositoryHasBeenRenamedTo(url: String) {
        rename(url, checkNotNull(providerId) { "no repository with a provider id was created" })
        assertEquals(200, world.lastStatus()) { "renaming failed: " + world.lastResponse().body }
    }

    @When("I POST a Repository with url {string}, provider {string} and providerId {string}")
    fun iPostARepositoryWithProvider(
        url: String,
        provider: String,
        providerId: String,
    ) {
        world.post(NODES, mapOf("props" to repositoryProps(url) + mapOf("provider" to provider, "providerId" to providerId)))
        repositoryId = world.lastBody().path("id").asText()
    }

    @When("I POST a Repository with url {string} and providerId {string}")
    fun iPostARepositoryWithProviderId(
        url: String,
        providerId: String,
    ) {
        world.post(NODES, mapOf("props" to repositoryProps(url) + mapOf("providerId" to providerId)))
    }

    @When("I POST a Repository with url {string} and orgRepo {string}")
    fun iPostARepositoryWithOrgRepo(
        url: String,
        orgRepo: String,
    ) {
        world.post(NODES, mapOf("props" to repositoryProps(url) + mapOf("orgRepo" to orgRepo)))
    }

    @When("I PUT that Repository with url {string} and providerId {string}")
    fun iPutThatRepositoryWithUrlAndProviderId(
        url: String,
        providerId: String,
    ) {
        rename(url, providerId)
    }

    @When("I GET \\/api\\/v1\\/repositories\\/by-provider\\/{word}\\/{word}")
    fun iGetByProvider(
        provider: String,
        providerId: String,
    ) {
        world.get("/api/v1/repositories/by-provider/$provider/$providerId")
    }

    @When("I GET \\/api\\/v1\\/repositories?url={word}")
    fun iGetRepositoriesByUrl(url: String) {
        world.getExpanding("/api/v1/repositories?url={url}", url)
    }

    @When("I POST \\/api\\/v1\\/impact with providerId {string}")
    fun iPostImpactWithProviderId(providerId: String) {
        world.post("/api/v1/impact", mapOf("providerId" to providerId))
    }

    @Then("GET \\/api\\/v1\\/repositories\\/by-provider\\/{word}\\/{word} returns that node")
    fun getByProviderReturnsThatNode(
        provider: String,
        providerId: String,
    ) {
        val created = requireRepositoryId()
        world.get("/api/v1/repositories/by-provider/$provider/$providerId")
        assertEquals(200, world.lastStatus()) { "lookup failed: " + world.lastResponse().body }
        assertEquals(created, world.lastBody().path("id").asText())
        assertEquals(providerId, world.lastBody().path("providerId").asText())
    }

    @Then("exactly one Repository node with providerId {string} exists")
    fun exactlyOneRepositoryWithProviderId(providerId: String) {
        val count =
            neo4jClient
                .query("MATCH (n:Repository { providerId: \$providerId }) RETURN count(n) AS c")
                .bindAll(mapOf("providerId" to providerId))
                .fetchAs(Long::class.javaObjectType)
                .one()
                .orElse(0L)
        assertEquals(1L, count)
    }

    @Then("its key is {string}")
    fun itsKeyIs(key: String) {
        assertEquals(key, world.lastBody().path("key").asText())
        renamedId = world.lastBody().path("id").asText()
    }

    @Then("its provenance lists previousKeys containing {string}")
    fun itsProvenanceListsPreviousKeys(key: String) {
        world.get("$NODES/" + world.lastBody().path("key").asText())
        val previous =
            world
                .lastBody()
                .path("provenance")
                .path("previousKeys")
                .map { it.asText() }
        assertTrue(key in previous) { "previousKeys are $previous in " + world.lastResponse().body }
    }

    @Then("the response returns the renamed node")
    fun theResponseReturnsTheRenamedNode() {
        assertEquals(200, world.lastStatus()) { "lookup failed: " + world.lastResponse().body }
        val found = world.lastBody().map { it.path("id").asText() }
        assertEquals(listOf(requireRenamedId()), found)
    }

    @Then("the impact is about {string}")
    fun theImpactIsAbout(id: String) {
        assertEquals(
            id,
            world
                .lastBody()
                .path("repository")
                .path("id")
                .asText(),
            world.lastResponse().body,
        )
    }

    private fun rename(
        url: String,
        providerId: String,
    ) {
        world.put(
            "$NODES/" + requireRepositoryId().removePrefix("Repository:"),
            mapOf("props" to repositoryProps(url) + mapOf("provider" to "github", "providerId" to providerId)),
        )
        if (world.lastStatus() == 200) renamedId = world.lastBody().path("id").asText()
    }

    private fun requireRepositoryId(): String = checkNotNull(repositoryId) { "no repository was created" }

    private fun requireRenamedId(): String = checkNotNull(renamedId) { "no repository was renamed" }

    /** Every property the registry marks required, so only the identity is under test. */
    private fun repositoryProps(url: String) =
        mapOf(
            "url" to url,
            "defaultBranch" to "main",
            "topics" to emptyList<String>(),
            "codeowners" to emptyList<String>(),
        )

    private companion object {
        const val NODES_OF = "/api/v1/nodes/"
        const val NODES = NODES_OF + "Repository"
        const val EDGES = "/api/v1/edges"
    }
}
