package com.repodatagraph.acceptance.steps

import com.repodatagraph.acceptance.support.ApiWorld
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.support.TestPrincipalConfig
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.http.HttpMethod
import java.time.Instant

/**
 * The authorisation policy (#30, #95): callers with roles, teams and scopes of their own, minted per
 * step as tokens the suite's test decoder accepts ([TestPrincipalConfig.tokenWith]); the facts an
 * agent cites seeded straight through [GraphStore], so each carries the provenance the scenario says.
 */
class PolicySteps(
    private val world: ApiWorld,
    private val graphStore: GraphStore,
) {
    private fun caller(
        subject: String,
        scopes: String,
        roles: String? = null,
        groups: String? = null,
    ): String {
        val claims = mutableMapOf<String, Any?>("sub" to subject, "scope" to scopes)
        roles?.let { claims["sdlc_roles"] = it.split(",").map(String::trim) }
        groups?.let { claims["groups"] = it.split(",").map(String::trim) }
        return TestPrincipalConfig.authorizationWith(claims)
    }

    private fun team(
        name: String,
        email: String? = null,
    ) = mapOf("props" to mapOf("name" to name, "email" to email).filterValues { it != null })

    @Given("a Team named {string} exists")
    fun aTeamExists(name: String) {
        world.post("/api/v1/nodes/Team", team(name))
        assertEquals(201, world.lastStatus(), world.lastResponse().body)
    }

    @Given("a Team named {string} with email {string} exists")
    fun aTeamWithEmailExists(
        name: String,
        email: String,
    ) {
        world.post("/api/v1/nodes/Team", team(name, email))
        assertEquals(201, world.lastStatus(), world.lastResponse().body)
    }

    @Given("the repository {string} is owned by the Team {string}")
    fun theRepositoryIsOwnedBy(
        url: String,
        team: String,
    ) {
        val props = mapOf("url" to url, "defaultBranch" to "main", "topics" to emptyList<String>(), "codeowners" to emptyList<String>())
        world.post("/api/v1/nodes/Repository", mapOf("props" to props))
        assertEquals(201, world.lastStatus(), world.lastResponse().body)
        val repository = NodeKey("Repository", world.lastBody().path("key").asText())
        graphStore.upsertEdge(GraphEdge("OWNED_BY", repository, NodeKey("Team", team), provenance = stated()))
    }

    @When("{string} with scopes {string} and no roles creates a Team named {string}")
    fun createsATeam(
        subject: String,
        scopes: String,
        name: String,
    ) {
        world.exchangeAs(caller(subject, scopes), HttpMethod.POST, "/api/v1/nodes/Team", team(name))
    }

    @When("{string} with scopes {string} and roles {string} renames the Team {string}")
    fun renamesATeam(
        subject: String,
        scopes: String,
        roles: String,
        name: String,
    ) {
        world.exchangeAs(
            caller(subject, scopes, roles),
            HttpMethod.PUT,
            "/api/v1/nodes/Team/$name",
            mapOf(
                "props" to mapOf("name" to name, "email" to "x@acme.example"),
            ),
        )
    }

    @When("{string} with scopes {string}, roles {string} and groups {string} changes the default branch of {string}")
    fun changesTheDefaultBranch(
        subject: String,
        scopes: String,
        roles: String,
        groups: String,
        key: String,
    ) {
        val props =
            mapOf(
                "url" to "https://$key",
                "defaultBranch" to "trunk",
                "topics" to emptyList<String>(),
                "codeowners" to emptyList<String>(),
            )
        world.exchangeAs(caller(subject, scopes, roles, groups), HttpMethod.PUT, "/api/v1/nodes/Repository/$key", mapOf("props" to props))
    }

    @When("{string} with scopes {string} and roles {string} reads the Team {string}")
    fun readsATeamWithRoles(
        subject: String,
        scopes: String,
        roles: String,
        name: String,
    ) {
        world.exchangeAs(caller(subject, scopes, roles), HttpMethod.GET, "/api/v1/nodes/Team/$name")
    }

    @When("{string} with scopes {string} and no roles reads the Team {string}")
    fun readsATeam(
        subject: String,
        scopes: String,
        name: String,
    ) {
        world.exchangeAs(caller(subject, scopes), HttpMethod.GET, "/api/v1/nodes/Team/$name")
    }

    @When("{string} with scopes {string} and roles {string} lists the service principals")
    fun listsTheServicePrincipals(
        subject: String,
        scopes: String,
        roles: String,
    ) {
        world.exchangeAs(caller(subject, scopes, roles), HttpMethod.GET, "/api/v1/service-principals")
    }

    @When("{string} with scopes {string} and roles {string} asks the policy to explain {string} on {string}")
    fun asksThePolicyToExplain(
        subject: String,
        scopes: String,
        roles: String,
        action: String,
        type: String,
    ) {
        world.exchangeAs(
            caller(subject, scopes, roles),
            HttpMethod.POST,
            "/api/v1/policy/explain",
            mapOf("action" to action, "resource" to mapOf("type" to type)),
        )
    }

    @Then("the node has no property {string}")
    fun theNodeHasNoProperty(property: String) {
        assertTrue(
            world
                .lastBody()
                .path("props")
                .path(property)
                .isMissingNode,
            world.lastResponse().body,
        )
    }

    @Then("the node's redacted properties are {string}")
    fun theRedactedPropertiesAre(properties: String) {
        assertEquals(properties.split(",").map(String::trim), world.lastBody().path("redacted").map { it.asText() })
    }

    @Then("the node's property {string} is {string}")
    fun theNodesPropertyIs(
        property: String,
        value: String,
    ) {
        assertEquals(
            value,
            world
                .lastBody()
                .path("props")
                .path(property)
                .asText(),
            world.lastResponse().body,
        )
    }

    @Given("a deployment {string} to {string} reported {int} minutes ago, after {string}")
    fun aDeploymentAfter(
        key: String,
        environment: String,
        minutes: Int,
        earlier: String,
    ) {
        val now = Instant.now()
        deployment(earlier, "payments:1.0.0", environment, now.minusSeconds(SECONDS_A_DAY))
        deployment(key, "payments:2.0.0", environment, now.minusSeconds(minutes * SECONDS_A_MINUTE))
    }

    @Given("an incident {string} inferred at confidence {double} to be caused by {string}")
    fun anIncidentInferredToBeCausedBy(
        number: String,
        confidence: Double,
        deployment: String,
    ) {
        val incident = NodeKey("Incident", number)
        graphStore.upsertNode(
            GraphNode(
                incident,
                mapOf("sourceSystem" to "servicenow", "instance" to "acme.service-now.com", "sysId" to number, "number" to number),
                stated("servicenow"),
            ),
        )
        val inferred =
            Provenance(
                sourceSystem = "link-engine",
                ingestedAt = Instant.now(),
                validFrom = Instant.now(),
                confidence = confidence,
                inferred = true,
            )
        graphStore.upsertEdge(GraphEdge("CAUSED_BY", incident, NodeKey("Deployment", deployment), provenance = inferred))
    }

    @When("an agent asks the policy whether it may {string} on {string} and the cause of {string}")
    fun anAgentAsksWithCause(
        action: String,
        deployment: String,
        incident: String,
    ) = evaluate(action, listOf("Deployment:$deployment", "CAUSED_BY:Incident:$incident>Deployment:$deployment"))

    @When("an agent asks the policy whether it may {string} on {string}")
    fun anAgentAsks(
        action: String,
        deployment: String,
    ) = evaluate(action, listOf("Deployment:$deployment"))

    @Then("a reason is {string}")
    fun aReasonIs(reason: String) {
        val reasons = world.lastBody().path("reasons").map { it.asText() }
        assertTrue(reason in reasons, "reasons: $reasons")
    }

    private fun evaluate(
        action: String,
        facts: List<String>,
    ) {
        world.post("/api/v1/policy/evaluate", mapOf("action" to action, "facts" to facts))
    }

    private fun deployment(
        key: String,
        artifact: String,
        environment: String,
        at: Instant,
    ) {
        graphStore.upsertNode(
            GraphNode(
                NodeKey("Deployment", key),
                mapOf(
                    "artifactId" to artifact,
                    "environmentId" to environment,
                    "deployedAt" to at,
                    "status" to "SUCCESS",
                ),
                stated("github-actions", at),
            ),
        )
    }

    private fun stated(
        source: String = "manual",
        at: Instant = Instant.now(),
    ) = Provenance(sourceSystem = source, ingestedAt = at, validFrom = at)

    private companion object {
        const val SECONDS_A_MINUTE = 60L
        const val SECONDS_A_DAY = 86_400L
    }
}
