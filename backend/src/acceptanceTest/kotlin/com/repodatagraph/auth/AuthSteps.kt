package com.repodatagraph.auth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

/**
 * Steps for the authentication suite. Self-contained, like the read-only suite's, because the main
 * suite's step classes carry dependencies this context does not start.
 *
 * Cucumber creates a new instance of this class per scenario, so the token and the last response are
 * scenario state.
 */
class AuthSteps(
    private val restTemplate: TestRestTemplate,
    private val objectMapper: ObjectMapper,
    private val neo4jClient: Neo4jClient,
    @Value("\${ingest.token}") private val ingestToken: String,
) {
    private var token: String? = null
    private var response: ResponseEntity<String>? = null

    @Before
    fun cleanGraph() {
        neo4jClient.query("MATCH (n) WHERE NOT n:Ontology DETACH DELETE n").run()
    }

    @Given("the backend is running with the auth profile")
    fun theBackendIsRunningWithTheAuthProfile() {
        // AuthSpringConfig starts it with authentication on, trusting the realm's Keycloak.
    }

    @Given("{string} has signed in through the UI")
    fun hasSignedIn(username: String) {
        // The development realm gives each user a password equal to their name.
        token = PkceSignIn(DevRealmKeycloak.issuer, objectMapper).accessToken(username, username)
    }

    @When("GET {word} is called without a token")
    fun getWithoutToken(path: String) {
        response = send(HttpMethod.GET, path, bearer = null)
    }

    @When("GET {word} is called with that token")
    fun getWithThatToken(path: String) {
        response = send(HttpMethod.GET, path, bearer = signedIn())
    }

    @When("GET {word} is called with a forged token")
    fun getWithForgedToken(path: String) {
        response = send(HttpMethod.GET, path, bearer = PkceSignIn.forgedToken(DevRealmKeycloak.issuer, "dan"))
    }

    @When("the GraphQL query {string} is sent without a token")
    fun graphQlWithoutToken(document: String) {
        response = send(HttpMethod.POST, "/graphql", bearer = null, body = mapOf("query" to document))
    }

    @When("the UI creates a Repository")
    fun theUiCreatesARepository() {
        response = createRepository()
    }

    @When("the UI relates a Repository to a Team")
    fun theUiRelatesARepositoryToATeam() {
        val repository = json(createRepository()).path("id").asText()
        val team =
            json(send(HttpMethod.POST, "/api/v1/nodes/Team", signedIn(), mapOf("props" to mapOf("name" to "platform"))))
                .path("id")
                .asText()
        response =
            send(
                HttpMethod.POST,
                "/api/v1/edges",
                signedIn(),
                mapOf("type" to "OWNED_BY", "fromId" to repository, "toId" to team),
            )
    }

    @When("POST {word} is called with the ingest token and no OIDC token")
    fun postWithIngestToken(path: String) {
        response = send(HttpMethod.POST, path, bearer = ingestToken, body = emptyMap<String, Any>())
    }

    @Then("the response is {int}")
    fun theResponseIs(expected: Int) {
        assertEquals(expected, last().statusCode.value(), "body: ${last().body}")
    }

    @Then("the response is not {int}")
    fun theResponseIsNot(unexpected: Int) {
        assertNotEquals(unexpected, last().statusCode.value(), "body: ${last().body}")
    }

    @Then("the node's provenance has writtenBy {string} and principalType {string}")
    fun theNodesProvenance(
        writtenBy: String,
        principalType: String,
    ) {
        val created = json(last())
        assertProvenance(created.path("provenance"), writtenBy, principalType, "in the create response")
        // And as stored, not only as echoed back.
        val stored = json(send(HttpMethod.GET, "/api/v1/nodes/Repository/${created.path("key").asText()}", signedIn()))
        assertProvenance(stored.path("provenance"), writtenBy, principalType, "when read back")
    }

    @Then("the edge's provenance has writtenBy {string} and principalType {string}")
    fun theEdgesProvenance(
        writtenBy: String,
        principalType: String,
    ) {
        assertProvenance(json(last()).path("provenance"), writtenBy, principalType, "in the create response")
    }

    private fun assertProvenance(
        provenance: JsonNode,
        writtenBy: String,
        principalType: String,
        where: String,
    ) {
        assertEquals(writtenBy, provenance.path("writtenBy").asText(null), "writtenBy $where: $provenance")
        assertEquals(principalType, provenance.path("principalType").asText(null), "principalType $where: $provenance")
    }

    private fun createRepository(): ResponseEntity<String> =
        send(
            HttpMethod.POST,
            "/api/v1/nodes/Repository",
            signedIn(),
            mapOf(
                "props" to
                    mapOf(
                        "url" to "https://github.com/acme/payments",
                        "defaultBranch" to "main",
                        "topics" to emptyList<String>(),
                        "codeowners" to emptyList<String>(),
                    ),
            ),
        )

    private fun send(
        method: HttpMethod,
        path: String,
        bearer: String?,
        body: Any? = null,
    ): ResponseEntity<String> {
        val headers = HttpHeaders()
        if (body != null) headers.contentType = MediaType.APPLICATION_JSON
        bearer?.let { headers.setBearerAuth(it) }
        return restTemplate.exchange(path, method, HttpEntity(body, headers), String::class.java)
    }

    private fun signedIn(): String = checkNotNull(token) { "No one has signed in yet" }

    private fun last(): ResponseEntity<String> = checkNotNull(response) { "No request has been made yet" }

    private fun json(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body ?: "null")
}
