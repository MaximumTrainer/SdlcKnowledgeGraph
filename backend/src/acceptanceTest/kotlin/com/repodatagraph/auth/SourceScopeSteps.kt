package com.repodatagraph.auth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.http.HttpMethod
import java.util.Base64

/**
 * Steps for source-scoped writes (#117): a write naming the system of record it speaks for, and the
 * `graph:write:<source>` scope that naming one needs.
 *
 * Whether a principal holds a scope is read off the token Keycloak issued it, not assumed, so a
 * scenario also proves the development realm grants what docs/AUTH.md says it does.
 */
class SourceScopeSteps(
    private val world: AuthWorld,
    private val objectMapper: ObjectMapper,
) {
    @Given("^\"([^\"]+)\" holds (?:scopes )?(\\S+) and (\\S+)$")
    fun holdsScopes(
        principal: String,
        first: String,
        second: String,
    ) {
        val token =
            if (principal in USERS) {
                PkceSignIn(DevRealmKeycloak.issuer, objectMapper).accessToken(principal, principal)
            } else {
                ClientCredentials(DevRealmKeycloak.issuer, objectMapper).accessToken(principal)
            }
        world.hold(principal, token)
        val held = scopesIn(token)
        assertTrue(held.containsAll(listOf(first, second)), "$principal was issued $held")
    }

    @When("^(?:it|he) POSTs an? (\\w+) with sourceSystem \"([^\"]*)\"$")
    fun postsWithSource(
        type: String,
        sourceSystem: String,
    ) {
        world.send(HttpMethod.POST, "/api/v1/nodes/$type", world.actorToken(), node(type, sourceSystem))
    }

    @When("it relates a Repository to a Team with sourceSystem {string}")
    fun relatesWithSource(sourceSystem: String) {
        // Both ends are stated as the principal's own, manual, facts: only the relationship names a source.
        val repository = created("Repository")
        val team = created("Team")
        world.send(
            HttpMethod.POST,
            "/api/v1/edges",
            world.actorToken(),
            mapOf(
                "type" to "OWNED_BY",
                "fromId" to repository,
                "toId" to team,
                "provenance" to mapOf("sourceSystem" to sourceSystem),
            ),
        )
    }

    @When("a write names sourceSystem {string}")
    fun aWriteNames(sourceSystem: String) {
        postsWithSource("Team", sourceSystem)
    }

    @Then("the node's provenance has sourceSystem {string} and writtenBy {string}")
    fun theNodesProvenance(
        sourceSystem: String,
        writtenBy: String,
    ) {
        val created = world.json(world.last())
        assertProvenance(created.path("provenance"), sourceSystem, writtenBy, "in the create response")
        // And as stored, not only as echoed back.
        val stored = world.json(world.send(HttpMethod.GET, "/api/v1/nodes/Repository/${created.path("key").asText()}", world.actorToken()))
        assertProvenance(stored.path("provenance"), sourceSystem, writtenBy, "when read back")
    }

    @Then("the response is 400 and lists the known sources")
    fun refusedListingTheKnownSources() {
        val last = world.last()
        assertEquals(400, last.statusCode.value(), "body: ${last.body}")
        val body = world.json(last)
        assertEquals("unknown source system", body.path("error").asText(null), "body: ${last.body}")
        assertEquals("jira", body.path("sourceSystem").asText(null), "body: ${last.body}")

        // The list is the registry's, the same one the ontology publishes.
        val published =
            world
                .json(world.send(HttpMethod.GET, "/api/v1/ontology", bearer = null))
                .path("sources")
                .map { it.path("name").asText() }
        val known = body.path("known").map { it.asText() }
        assertEquals(published, known, "body: ${last.body}")
        assertTrue(known.containsAll(MINIMUM), "the known sources $known lack some of $MINIMUM")
    }

    private fun created(type: String): String {
        val response = world.send(HttpMethod.POST, "/api/v1/nodes/$type", world.actorToken(), node(type, sourceSystem = null))
        assertEquals(201, response.statusCode.value(), "creating a $type: ${response.body}")
        return world.json(response).path("id").asText()
    }

    private fun node(
        type: String,
        sourceSystem: String?,
    ): Map<String, Any> =
        mapOf("props" to (SAMPLES[type] ?: error("no sample $type"))) +
            (sourceSystem?.let { mapOf("provenance" to mapOf("sourceSystem" to it)) } ?: emptyMap())

    private fun assertProvenance(
        provenance: JsonNode,
        sourceSystem: String,
        writtenBy: String,
        where: String,
    ) {
        assertEquals(sourceSystem, provenance.path("sourceSystem").asText(null), "sourceSystem $where: $provenance")
        assertEquals(writtenBy, provenance.path("writtenBy").asText(null), "writtenBy $where: $provenance")
    }

    /** The scopes a JWT's `scope` claim names. Read without verifying: Keycloak just issued it. */
    private fun scopesIn(token: String): List<String> {
        val payload = objectMapper.readTree(Base64.getUrlDecoder().decode(token.split(".")[1]))
        return payload
            .path("scope")
            .asText("")
            .split(" ")
            .filter { it.isNotBlank() }
    }

    private companion object {
        /** The development realm's users, who sign in through the UI; everyone else is a client. */
        val USERS = setOf("dan", "reader", "visitor")

        /** What #117 requires sources.yaml to declare, at the least. */
        val MINIMUM = listOf("manual", "github", "github-actions", "aws", "servicenow")

        val SAMPLES: Map<String, Map<String, Any>> =
            mapOf(
                "Repository" to
                    mapOf(
                        "url" to "https://github.com/acme/payments",
                        "defaultBranch" to "main",
                        "topics" to emptyList<String>(),
                        "codeowners" to emptyList<String>(),
                    ),
                "CloudResource" to
                    mapOf(
                        "provider" to "aws",
                        "resourceId" to "arn:aws:s3:::acme-payments",
                        "resourceType" to "s3-bucket",
                        "name" to "acme-payments",
                    ),
                "Team" to mapOf("name" to "platform"),
            )
    }
}
