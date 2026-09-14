package com.repodatagraph.acceptance.steps

import com.repodatagraph.acceptance.support.ApiWorld
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Drives repository registration over HTTP, in the forms a person or a connector actually supplies.
 *
 * The point of these is the normalising: six spellings of one remote have to become one node, and a
 * seventh thing that is not a remote at all has to be refused before it is stored. Both are asserted
 * through the API rather than against the parser, because the parser being right is worth nothing if
 * the endpoint does not use it.
 */
class RepositoryRemoteSteps(
    private val world: ApiWorld,
) {
    private var firstCreatedId: String? = null
    private var firstCreatedKey: String? = null

    @Given("a Repository exists for {string}")
    fun aRepositoryExistsFor(url: String) {
        world.post("/api/v1/nodes/Repository", mapOf("props" to repositoryProps(url)))
        assertTrue(world.lastStatus() in 200..299) { "setting up the repository failed: " + world.lastResponse().body }
        firstCreatedId = world.lastBody().path("id").asText()
        firstCreatedKey = world.lastBody().path("key").asText()
    }

    @When("I POST a Repository with url {string}")
    fun iPostARepositoryWithUrl(url: String) {
        world.post("/api/v1/nodes/Repository", mapOf("props" to repositoryProps(url)))
    }

    @When("I GET the repository by key {string}")
    fun iGetTheRepositoryByKey(key: String) {
        world.get("/api/v1/repositories/by-key?key=" + key.replace(" ", "%20"))
    }

    @When("I PUT that repository with url {string}")
    fun iPutThatRepositoryWithUrl(url: String) {
        world.put("/api/v1/nodes/Repository/" + firstCreatedKey, mapOf("props" to repositoryProps(url)))
    }

    @Then("the repository key is {string}")
    fun theRepositoryKeyIs(expected: String) {
        assertEquals(expected, world.lastBody().path("key").asText())
    }

    @Then("the repository url is {string}")
    fun theRepositoryUrlIs(expected: String) {
        assertEquals(expected, propertyOf("url"))
    }

    @Then("that repository has host {string} and org {string} and name {string}")
    fun thatRepositoryHasParts(
        host: String,
        org: String,
        name: String,
    ) {
        assertEquals(host, propertyOf("host"))
        assertEquals(org, propertyOf("org"))
        assertEquals(name, propertyOf("name"))
    }

    @Then("the body field {string} is the id of the repository created earlier")
    fun theBodyFieldIsTheIdOfTheRepositoryCreatedEarlier(field: String) {
        assertEquals(firstCreatedId, world.lastBody().path(field).asText())
    }

    /**
     * A node response carries its properties under `props`, but the deprecated repository endpoint
     * flattens them. Reading either keeps these steps usable from both.
     */
    private fun propertyOf(name: String): String {
        val body = world.lastBody()
        val fromProps = body.path("props").path(name)
        return if (fromProps.isMissingNode) body.path(name).asText() else fromProps.asText()
    }

    /** Every property the registry marks required, so only the remote is under test. */
    private fun repositoryProps(url: String) =
        mapOf(
            "url" to url,
            "defaultBranch" to "main",
            "topics" to emptyList<String>(),
            "codeowners" to emptyList<String>(),
        )
}
