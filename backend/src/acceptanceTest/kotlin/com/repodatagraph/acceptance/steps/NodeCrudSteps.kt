package com.repodatagraph.acceptance.steps

import com.repodatagraph.acceptance.support.ApiWorld
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.data.neo4j.core.Neo4jClient

/**
 * Drives the generic node API from outside, over HTTP, exactly as the editing screens do.
 *
 * State is created through the API rather than through the store, so a scenario proves the endpoint
 * a user actually reaches rather than the persistence underneath it.
 */
class NodeCrudSteps(
    private val world: ApiWorld,
    private val neo4jClient: Neo4jClient,
) {
    /** Path of the node most recently created here, so a later step can update or delete it. */
    private var lastPath: String? = null
    private var lastCreatedId: String? = null
    private var repositoryPath: String? = null
    private var teamPath: String? = null

    @Given("a Repository node exists with url {string} and description {string}")
    fun aRepositoryNodeExists(
        url: String,
        description: String,
    ) {
        create("Repository", repositoryProps(url, description))
        repositoryPath = lastPath
    }

    @Given("a CloudResource node exists with provider {string} and resourceId {string}")
    fun aCloudResourceNodeExists(
        provider: String,
        resourceId: String,
    ) {
        create("CloudResource", cloudResourceProps(provider, resourceId))
    }

    @Given("a Team node exists with name {string}")
    fun aTeamNodeExists(name: String) {
        create("Team", mapOf("name" to name))
        teamPath = lastPath
    }

    @Given("that Repository is OWNED_BY that Team")
    fun thatRepositoryIsOwnedByThatTeam() {
        world.post(
            "/api/v1/edges",
            mapOf("type" to "OWNED_BY", "fromId" to idAt(repositoryPath), "toId" to idAt(teamPath)),
        )
        assertTrue(world.lastStatus() in SUCCESSFUL) { "linking failed: " + world.lastResponse().body }
    }

    @When("I PUT that node with description {string}")
    fun iPutThatNodeWithDescription(description: String) {
        world.put(requirePath(), mapOf("props" to repositoryProps(REPOSITORY_URL, description)))
    }

    @When("I PUT that node with name {string}")
    fun iPutThatNodeWithName(name: String) {
        world.put(requirePath(), mapOf("props" to mapOf("name" to name)))
    }

    @When("I POST a CloudResource with provider {string} and resourceId {string}")
    fun iPostACloudResource(
        provider: String,
        resourceId: String,
    ) {
        world.post("/api/v1/nodes/CloudResource", mapOf("props" to cloudResourceProps(provider, resourceId)))
    }

    @When("I POST a Team with no name")
    fun iPostATeamWithNoName() {
        world.post("/api/v1/nodes/Team", mapOf("props" to mapOf("email" to "platform@acme.example")))
    }

    @When("I POST a Team named {string} with an undeclared property {string}")
    fun iPostATeamWithAnUndeclaredProperty(
        name: String,
        property: String,
    ) {
        world.post("/api/v1/nodes/Team", mapOf("props" to mapOf("name" to name, property to "blue")))
    }

    @When("I POST a node of type {string} with name {string}")
    fun iPostANodeOfType(
        type: String,
        name: String,
    ) {
        world.post("/api/v1/nodes/" + type, mapOf("props" to mapOf("name" to name)))
    }

    @When("I POST the deprecated repositories endpoint with orgRepo {string}")
    fun iPostTheDeprecatedRepositoryEndpoint(orgRepo: String) {
        world.post("/api/v1/repositories", mapOf("orgRepo" to orgRepo))
    }

    @When("I DELETE the Team node")
    fun iDeleteTheTeamNode() {
        world.delete(requireTeamPath())
    }

    @When("I DELETE the Team node with cascade")
    fun iDeleteTheTeamNodeWithCascade() {
        world.delete(requireTeamPath() + "?cascade=true")
    }

    @Then("exactly {int} node of type {string} has key {string}")
    fun exactlyNodesOfTypeHaveKey(
        expected: Int,
        type: String,
        key: String,
    ) {
        val count =
            neo4jClient
                .query("MATCH (n:" + type + " { key: \$key }) RETURN count(n) AS c")
                .bindAll(mapOf("key" to key))
                .fetchAs(Long::class.javaObjectType)
                .one()
                .orElse(0L)
        assertEquals(expected.toLong(), count)
    }

    @Then("that node has description {string}")
    fun thatNodeHasDescription(description: String) {
        world.get(requirePath())
        assertEquals(
            description,
            world
                .lastBody()
                .path("props")
                .path("description")
                .asText(),
        )
    }

    @Then("that node has provenance source {string}")
    fun thatNodeHasProvenanceSource(source: String) {
        world.get(requirePath())
        assertEquals(
            source,
            world
                .lastBody()
                .path("provenance")
                .path("sourceSystem")
                .asText(),
        )
    }

    @Then("the body field {string} is the id of the node created earlier")
    fun theBodyFieldIsTheIdOfTheNodeCreatedEarlier(field: String) {
        assertEquals(lastCreatedId, world.lastBody().path(field).asText())
    }

    @Then("the errors contain {string}")
    fun theErrorsContain(fragment: String) {
        val messages = world.lastBody().path("errors").joinToString(" ") { it.path("message").asText() }
        assertTrue(messages.contains(fragment)) { "expected an error containing " + fragment + ", got: " + messages }
    }

    @Then("the body field {string} contains {string}")
    fun theBodyFieldContains(
        field: String,
        value: String,
    ) {
        val values = world.lastBody().path(field).map { it.asText() }
        assertTrue(values.contains(value)) { "expected " + field + " to contain " + value + ", got: " + values }
    }

    @Then("the body field {string} is {int}")
    fun theBodyFieldIsInt(
        field: String,
        value: Int,
    ) {
        assertEquals(value, world.lastBody().path(field).asInt())
    }

    @Then("the body field {string} is {string}")
    fun theBodyFieldIsString(
        field: String,
        value: String,
    ) {
        assertEquals(value, world.lastBody().path(field).asText())
    }

    @Then("the listed keys are {string}")
    fun theListedKeysAre(expected: String) {
        val keys = world.lastBody().path("items").map { it.path("key").asText() }
        assertEquals(expected.split(",").map { it.trim() }, keys)
    }

    @Then("the response has header {string} with value {string}")
    fun theResponseHasHeader(
        name: String,
        value: String,
    ) {
        assertEquals(value, world.lastHeader(name))
    }

    private fun create(
        type: String,
        props: Map<String, Any?>,
    ) {
        world.post("/api/v1/nodes/" + type, mapOf("props" to props))
        assertTrue(world.lastStatus() in SUCCESSFUL) { "creating a " + type + " failed: " + world.lastResponse().body }
        lastCreatedId = world.lastBody().path("id").asText()
        lastPath = "/api/v1/nodes/" + type + "/" + world.lastBody().path("key").asText()
    }

    private fun repositoryProps(
        url: String,
        description: String,
    ) = mapOf("url" to url, "description" to description, "orgRepo" to "acme/payments")

    private fun cloudResourceProps(
        provider: String,
        resourceId: String,
    ) = mapOf(
        "provider" to provider,
        "resourceId" to resourceId,
        "resourceType" to "s3-bucket",
        "name" to "acme-logs",
    )

    private fun requirePath(): String = checkNotNull(lastPath) { "no node has been created in this scenario" }

    private fun requireTeamPath(): String = checkNotNull(teamPath) { "no Team has been created in this scenario" }

    /** Reads a node back to learn the id the server derived for it. */
    private fun idAt(path: String?): String {
        world.get(checkNotNull(path) { "no node has been created in this scenario" })
        return world.lastBody().path("id").asText()
    }

    private companion object {
        const val REPOSITORY_URL = "https://github.com/acme/payments"
        val SUCCESSFUL = 200..299
    }
}
