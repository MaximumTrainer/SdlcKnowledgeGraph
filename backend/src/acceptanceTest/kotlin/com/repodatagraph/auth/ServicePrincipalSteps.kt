package com.repodatagraph.auth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.http.HttpMethod

/**
 * Steps for machine principals (#115): connectors and agents holding client-credentials tokens, and
 * the registry that turns a Keycloak client into a principal the graph knows.
 *
 * A registration is always made the way an operator would make one - by a signed-in user, through
 * the API - so a scenario never reaches into the store behind it.
 */
class ServicePrincipalSteps(
    private val world: AuthWorld,
    private val objectMapper: ObjectMapper,
) {
    @Given("service principal {string} is registered with ownedBy {string}")
    fun isRegistered(
        name: String,
        team: String,
    ) {
        teamExists(team)
        val response = register(user(), name, team)
        assertEquals(201, response.statusCode.value(), "registering $name: ${response.body}")
    }

    @Given("team {string} exists")
    fun teamExists(team: String) {
        val response = world.send(HttpMethod.POST, "/api/v1/nodes/Team", user(), mapOf("props" to mapOf("name" to team)))
        assertTrue(response.statusCode.value() in setOf(201, 409), "creating team $team: ${response.body}")
    }

    @Given("{string} holds a client-credentials token")
    @Given("{string} holds a token")
    @Given("Keycloak client {string} holds a valid token")
    fun holdsAToken(clientId: String) {
        world.hold(clientId, ClientCredentials(DevRealmKeycloak.issuer, objectMapper).accessToken(clientId))
    }

    @Given("no service principal {string} is registered")
    fun noServicePrincipal(name: String) {
        assertFalse(names(listed()).contains(name), "$name is registered")
    }

    @When("it POSTs a Deployment node")
    fun itPostsADeployment() {
        world.send(
            HttpMethod.POST,
            "/api/v1/nodes/Deployment",
            world.actorToken(),
            mapOf(
                "props" to
                    mapOf(
                        "artifactKey" to "ghcr.io/acme/payments@sha256:1",
                        "environmentKey" to "production",
                        "deployedAt" to "2026-09-30T12:00:00Z",
                        "artifactId" to "Artifact:ghcr.io/acme/payments@sha256:1",
                        "environmentId" to "Environment:production",
                        "status" to "SUCCESS",
                    ),
            ),
        )
    }

    @When("it calls GET {word}")
    fun itCallsGet(path: String) {
        world.send(HttpMethod.GET, path, world.actorToken())
    }

    @When("it sends the GraphQL query {string}")
    fun itSendsGraphQl(document: String) {
        world.send(HttpMethod.POST, "/graphql", world.actorToken(), mapOf("query" to document))
    }

    /** A registration, as the only thing this suite POSTs to a bare path. */
    @When("it POSTs {word}")
    fun itRegistersAPrincipal(path: String) {
        world.send(
            HttpMethod.POST,
            path,
            world.actorToken(),
            mapOf("name" to "github-connector", "ownedBy" to "team-payments", "description" to "self-registered"),
        )
    }

    @When("{string} registers service principal {string} with ownedBy {string}")
    fun registers(
        user: String,
        name: String,
        team: String,
    ) {
        register(userToken(user), name, team)
    }

    @When("{string} deregisters service principal {string}")
    fun deregisters(
        user: String,
        name: String,
    ) {
        world.send(HttpMethod.DELETE, "$REGISTRY/$name", userToken(user))
    }

    @Then("the response is {int} with error {string}")
    fun theResponseIsWithError(
        status: Int,
        error: String,
    ) {
        val last = world.last()
        assertEquals(status, last.statusCode.value(), "body: ${last.body}")
        assertEquals(error, world.json(last).path("error").asText(null), "body: ${last.body}")
    }

    @Then("the refusal names client {string}")
    fun theRefusalNamesClient(clientId: String) {
        assertEquals(clientId, world.json(world.last()).path("clientId").asText(null), "body: ${world.last().body}")
    }

    @Then("the error is not {string}")
    fun theErrorIsNot(error: String) {
        assertNotEquals(error, world.json(world.last()).path("error").asText(null), "body: ${world.last().body}")
    }

    @Then("the node's provenance has writtenBy {string}, principalType {string}, onBehalfOfTeam {string}")
    fun theNodesProvenance(
        writtenBy: String,
        principalType: String,
        team: String,
    ) {
        val created = world.json(world.last())
        assertServiceProvenance(created.path("provenance"), writtenBy, principalType, team, "in the create response")
        // And as stored, not only as echoed back.
        val stored =
            world.json(world.send(HttpMethod.GET, "/api/v1/nodes/Deployment/${created.path("key").asText()}", world.actorToken()))
        assertServiceProvenance(stored.path("provenance"), writtenBy, principalType, team, "when read back")
    }

    @Then("the service principals listed include {string} owned by {string}, registered by {string}")
    fun theListIncludes(
        name: String,
        team: String,
        registeredBy: String,
    ) {
        val entry = entry(name)
        assertEquals(team, entry.path("ownedBy").asText(null), "ownedBy: $entry")
        assertEquals(registeredBy, entry.path("registeredBy").asText(null), "registeredBy: $entry")
        assertTrue(entry.path("validTo").isMissingOrNullNode(), "a current registration has no validTo: $entry")
    }

    @Then("the service principals listed include {string} with a validTo")
    fun theListIncludesDeregistered(name: String) {
        val entry = entry(name)
        assertNotNull(entry.path("validTo").asText(null), "a deregistered principal keeps its record, with a validTo: $entry")
    }

    private fun assertServiceProvenance(
        provenance: JsonNode,
        writtenBy: String,
        principalType: String,
        team: String,
        where: String,
    ) {
        assertEquals(writtenBy, provenance.path("writtenBy").asText(null), "writtenBy $where: $provenance")
        assertEquals(principalType, provenance.path("principalType").asText(null), "principalType $where: $provenance")
        assertEquals(team, provenance.path("onBehalfOfTeam").asText(null), "onBehalfOfTeam $where: $provenance")
    }

    private fun register(
        bearer: String,
        name: String,
        team: String,
    ) = world.send(
        HttpMethod.POST,
        REGISTRY,
        bearer,
        mapOf("name" to name, "ownedBy" to team, "description" to "registered by the acceptance suite"),
    )

    private fun entry(name: String): JsonNode =
        listed().path("items").firstOrNull { it.path("name").asText() == name }
            ?: error("$name is not among the service principals listed: ${listed()}")

    private fun listed(): JsonNode {
        // Listed as the user, whatever the scenario's actor is, so the listing is not itself refused.
        val response = world.send(HttpMethod.GET, REGISTRY, user())
        assertEquals(200, response.statusCode.value(), "listing service principals: ${response.body}")
        return world.json(response)
    }

    private fun names(listing: JsonNode): List<String> = listing.path("items").map { it.path("name").asText() }

    /** A user's token for the registry, signing "dan" in when the scenario has not already. */
    private fun user(): String = userToken(USER)

    private fun userToken(user: String): String =
        world.tokenOf(user) ?: PkceSignIn(DevRealmKeycloak.issuer, objectMapper).accessToken(user, user).also {
            // Not the actor: signing a user in behind the scenario's back must not change who "it" is.
            val actor = world.actor
            val actorToken = actor?.let(world::tokenOf)
            world.hold(user, it)
            if (actor != null && actorToken != null) world.hold(actor, actorToken)
        }

    private fun JsonNode.isMissingOrNullNode(): Boolean = isMissingNode || isNull

    private companion object {
        const val REGISTRY = "/api/v1/service-principals"
        const val USER = "dan"
    }
}
