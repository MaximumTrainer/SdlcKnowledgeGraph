package com.repodatagraph.acceptance.steps

import com.fasterxml.jackson.databind.JsonNode
import com.repodatagraph.acceptance.support.ApiWorld
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.data.neo4j.core.Neo4jClient
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Changes, pull requests and the work items they implement (#85), and the two lineage queries.
 *
 * Every fact is written through the API a connector or a person would use - the generic node and
 * edge endpoints - so a scenario proves the registry-driven store accepts the new types, not only
 * that the queries read them. Steps that name a path are regular expressions, because a Cucumber
 * expression reads `/` as an alternative.
 */
class ChangeLineageSteps(
    private val world: ApiWorld,
    private val neo4jClient: Neo4jClient,
) {
    private var changeId: String? = null
    private var pullRequestId: String? = null
    private var workItemUri: String? = null
    private var artifactId: String? = null
    private var deploymentId: String? = null

    @Given("a Repository node exists with key {string}")
    fun aRepositoryNodeExistsWithKey(key: String) {
        created(
            "Repository",
            mapOf(
                "url" to "https://$key",
                "defaultBranch" to "main",
                "topics" to emptyList<String>(),
                "codeowners" to emptyList<String>(),
            ),
        )
    }

    @Given("an ExternalWorkItem node exists with uri {string} and system {string}")
    fun anExternalWorkItemNodeExists(
        uri: String,
        system: String,
    ) {
        created(WORK_ITEM, mapOf("uri" to uri, "system" to system))
        workItemUri = uri
    }

    @When("^I POST /api/v1/nodes/Change with sha \"([^\"]*)\", repositoryKey \"([^\"]*)\", committedAt \"([^\"]*)\"$")
    fun iPostAChange(
        sha: String,
        repositoryKey: String,
        committedAt: String,
    ) {
        world.post(NODES + "Change", body(mapOf("sha" to sha, "repositoryKey" to repositoryKey, "committedAt" to committedAt)))
        assertEquals(CREATED, world.lastStatus(), world.lastResponse().body)
        changeId = world.lastBody().path("id").asText()
    }

    @When("^I POST /api/v1/edges with type \"([^\"]*)\" from that Change to the ExternalWorkItem$")
    fun iPostAnEdgeFromThatChangeToTheWorkItem(type: String) {
        edge(type, required(changeId, "Change"), workItemId(required(workItemUri, "ExternalWorkItem")))
    }

    @Then("the ExternalWorkItem {string}, read back by its uri, lists one inbound IMPLEMENTS edge")
    fun theWorkItemListsOneInboundImplementsEdge(uri: String) {
        // A URI holds `//`, which no path segment may carry, so the node is read by its key as a query parameter.
        world.getExpanding("/api/v1/nodes/$WORK_ITEM/by-key?key={key}", uri)
        assertEquals(OK, world.lastStatus(), world.lastResponse().body)
        val id = world.lastBody().path("id").asText()
        assertEquals(workItemId(uri), id)
        assertEquals(
            uri,
            world
                .lastBody()
                .path("props")
                .path("uri")
                .asText(),
        )

        world.getExpanding("/api/v1/edges?nodeId={id}&direction=in", id)
        assertEquals(OK, world.lastStatus(), world.lastResponse().body)
        val inbound = world.lastBody().path("items").toList()
        assertEquals(1, inbound.size, world.lastResponse().body)
        assertEquals("IMPLEMENTS", inbound.single().path("type").asText())
        assertEquals(
            changeId,
            inbound
                .single()
                .path("other")
                .path("id")
                .asText(),
        )
    }

    @Given("a Change {string} in {string} exists")
    fun aChangeInExists(
        sha: String,
        repositoryKey: String,
    ) {
        changeId = created("Change", mapOf("sha" to sha, "repositoryKey" to repositoryKey, "committedAt" to COMMITTED_AT))
    }

    @When("^I POST /api/v1/nodes/PullRequest with number (\\d+), repositoryKey \"([^\"]*)\", url \"([^\"]*)\"$")
    fun iPostAPullRequest(
        number: Int,
        repositoryKey: String,
        url: String,
    ) {
        world.post(NODES + "PullRequest", body(mapOf("number" to number, "repositoryKey" to repositoryKey, "url" to url)))
        pullRequestId = world.lastBody().path("id").asText(null)
    }

    @When("^I POST /api/v1/edges with type \"([^\"]*)\" from that PullRequest to that Change$")
    fun iPostAnEdgeFromThatPullRequestToThatChange(type: String) {
        edge(type, required(pullRequestId, "PullRequest"), required(changeId, "Change"))
    }

    @When("I POST an ExternalWorkItem with uri {string} and system {string}")
    fun iPostAnExternalWorkItem(
        uri: String,
        system: String,
    ) {
        world.post(NODES + WORK_ITEM, body(mapOf("uri" to uri, "system" to system)))
        assertEquals(CREATED, world.lastStatus(), world.lastResponse().body)
    }

    @Then("{int} ExternalWorkItem nodes exist")
    fun externalWorkItemNodesExist(count: Int) {
        val found =
            neo4jClient
                .query("MATCH (n:$WORK_ITEM) RETURN count(n) AS c")
                .fetchAs(Long::class.javaObjectType)
                .one()
                .orElse(0L)
        assertEquals(count.toLong(), found)
    }

    @Given("a Change {string} IMPLEMENTS ExternalWorkItem {string}")
    fun aChangeImplementsAWorkItem(
        sha: String,
        uri: String,
    ) {
        aRepositoryNodeExistsWithKey(REPOSITORY)
        aChangeInExists(sha, REPOSITORY)
        anExternalWorkItemNodeExists(uri, "chorus")
        edge("IMPLEMENTS", required(changeId, "Change"), workItemId(uri))
    }

    @Given("an Artifact {string} CONTAINS that Change")
    fun anArtifactContainsThatChange(reference: String) {
        artifactId = artifact(reference)
        edge("CONTAINS", required(artifactId, "Artifact"), required(changeId, "Change"))
    }

    @Given("a Deployment of that Artifact to Environment {string} exists")
    fun aDeploymentOfThatArtifactExists(environment: String) {
        deploymentId = deploy(required(artifactId, "Artifact"), environment)
    }

    @Given("a Deployment whose Artifact has no CONTAINS edges")
    fun aDeploymentWhoseArtifactHasNoContainsEdges() {
        artifactId = artifact("ghcr.io/acme/payments:1.3.0")
        deploymentId = deploy(required(artifactId, "Artifact"), "production")
    }

    @When("^I GET /api/v1/work-items/deployments\\?uri=(.+)$")
    fun iGetTheDeploymentsOfAWorkItem(encodedUri: String) {
        world.getExpanding("/api/v1/work-items/deployments?uri={uri}", decoded(encodedUri))
    }

    @When("^I GET /api/v1/deployments/work-items\\?deploymentId=(.+)$")
    fun iGetTheWorkItemsOfADeploymentId(encodedId: String) {
        world.getExpanding("/api/v1/deployments/work-items?deploymentId={id}", decoded(encodedId))
    }

    @When("^I GET /api/v1/deployments/work-items for that Deployment$")
    fun iGetTheWorkItemsOfThatDeployment() {
        world.getExpanding("/api/v1/deployments/work-items?deploymentId={id}", required(deploymentId, "Deployment"))
    }

    @Then("the body lists exactly one deployment with environment {string}")
    fun theBodyListsExactlyOneDeployment(environment: String) {
        val deployments = list("deployments")
        assertEquals(1, deployments.size, world.lastResponse().body)
        assertEquals(deploymentId, deployments.single().path("id").asText())
        assertEquals(
            environment,
            deployments
                .single()
                .path("environment")
                .path("key")
                .asText(),
            world.lastResponse().body,
        )
    }

    @Then("that deployment carries the Change {string}")
    fun thatDeploymentCarriesTheChange(sha: String) {
        val changes = list("deployments").single().path("changes").map { it.path("sha").asText() }
        assertEquals(listOf(sha), changes, world.lastResponse().body)
    }

    @Then("the body has {string}: {string}")
    fun theBodyHas(
        field: String,
        value: String,
    ) {
        assertEquals(value, world.lastBody().path(field).asText(null), world.lastResponse().body)
    }

    @Then("the body lists exactly one work item with uri {string}")
    fun theBodyListsExactlyOneWorkItem(uri: String) {
        val workItems = list("workItems")
        assertEquals(1, workItems.size, world.lastResponse().body)
        assertEquals(uri, workItems.single().path("uri").asText())
        assertEquals(workItemId(uri), workItems.single().path("id").asText())
    }

    @Then("the body has {string}: {string} and an empty {string} list")
    fun theBodyHasAndAnEmptyList(
        field: String,
        value: String,
        listField: String,
    ) {
        theBodyHas(field, value)
        theBodyHasAnEmptyList(listField)
    }

    @Then("the body has an empty {string} list")
    fun theBodyHasAnEmptyList(field: String) {
        val value = world.lastBody().path(field)
        assertTrue(value.isArray && value.isEmpty) { "$field is $value in ${world.lastResponse().body}" }
    }

    @Then("the body names the missing node {string}")
    fun theBodyNamesTheMissingNode(id: String) {
        assertEquals("node not found", world.lastBody().path("error").asText(null), world.lastResponse().body)
        assertEquals(listOf(id), world.lastBody().path("missing").map { it.asText() })
    }

    @Then("^frontend/src/generated/ontology\\.ts declares (\\w+), (\\w+) and (\\w+)$")
    fun theGeneratedTypescriptDeclares(
        first: String,
        second: String,
        third: String,
    ) {
        // Relative to the backend project directory, the test's working directory.
        val typescript = File("../frontend/src/generated/ontology.ts").readText()
        listOf(first, second, third).forEach { type ->
            assertTrue(typescript.contains("export interface $type {")) { "ontology.ts declares no interface $type" }
            assertTrue(typescript.contains("  '$type'")) { "ontology.ts does not list $type in NODE_TYPES" }
        }
    }

    /** `ghcr.io/acme/payments:1.4.0` as an Artifact with a registry, a name and a version, and no digest. */
    private fun artifact(reference: String): String {
        val registry = reference.substringBefore('/')
        val name = reference.substringAfter('/').substringBeforeLast(':')
        val version = reference.substringAfterLast(':')
        return created(
            "Artifact",
            mapOf("registry" to registry, "name" to name, "version" to version, "artifactType" to "container-image"),
        )
    }

    /** Deploys [artifact] to [environment], linked as the deployment ingest links one. Returns the deployment's id. */
    private fun deploy(
        artifact: String,
        environment: String,
    ): String {
        val environmentId = created("Environment", mapOf("name" to environment, "type" to environment))
        val artifactKey = artifact.substringAfter(':')
        val environmentKey = environmentId.substringAfter(':')
        val deployment =
            created(
                "Deployment",
                mapOf(
                    "artifactKey" to artifactKey,
                    "environmentKey" to environmentKey,
                    "deployedAt" to DEPLOYED_AT,
                    "artifactId" to artifactKey,
                    "environmentId" to environmentKey,
                    "status" to "SUCCESS",
                ),
            )
        edge("DEPLOYED_TO", artifact, deployment)
        edge("TO_ENVIRONMENT", deployment, environmentId)
        return deployment
    }

    /** Creates a node through the API and returns its id, failing the scenario if it was refused. */
    private fun created(
        type: String,
        props: Map<String, Any?>,
    ): String {
        world.post(NODES + type, body(props))
        assertEquals(CREATED, world.lastStatus()) { "creating $type: ${world.lastResponse().body}" }
        return world.lastBody().path("id").asText()
    }

    private fun edge(
        type: String,
        fromId: String,
        toId: String,
    ) {
        world.post("/api/v1/edges", mapOf("type" to type, "fromId" to fromId, "toId" to toId))
        assertTrue(world.lastStatus() in SUCCESSFUL) { "linking $type: ${world.lastResponse().body}" }
    }

    private fun list(field: String): List<JsonNode> = world.lastBody().path(field).toList()

    private fun body(props: Map<String, Any?>) = mapOf("props" to props)

    private fun workItemId(uri: String) = "$WORK_ITEM:$uri"

    private fun decoded(value: String) = URLDecoder.decode(value, StandardCharsets.UTF_8)

    private fun <T> required(
        value: T?,
        what: String,
    ): T = checkNotNull(value) { "no $what has been created yet" }

    private companion object {
        const val NODES = "/api/v1/nodes/"
        const val WORK_ITEM = "ExternalWorkItem"
        const val REPOSITORY = "github.com/acme/payments"
        const val COMMITTED_AT = "2026-09-13T10:00:00Z"
        const val DEPLOYED_AT = "2026-09-13T11:00:00Z"
        const val OK = 200
        const val CREATED = 201
        val SUCCESSFUL = 200..201
    }
}
