package com.repodatagraph.acceptance.steps

import com.fasterxml.jackson.databind.ObjectMapper
import com.repodatagraph.acceptance.support.ApiWorld
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.assertj.core.api.Assertions.assertThat
import org.springframework.data.neo4j.core.Neo4jClient

/**
 * Plays the deploy pipeline: posts deployment reports to `POST /api/v1/ingest/deployment` and reads
 * back what the graph made of them.
 *
 * The token is the one `application-test.yml` configures. The step naming it exists so that a reader
 * of the feature can see where the 401 scenarios' "wrong" token differs from the right one.
 */
class DeploymentIngestSteps(
    private val world: ApiWorld,
    private val neo4jClient: Neo4jClient,
    private val objectMapper: ObjectMapper,
) {
    private var token = ""
    private var lastBody = ""

    @Given("the deployment ingest token is {string}")
    fun theTokenIs(configured: String) {
        token = configured
    }

    @Given("the pipeline reported artifact {string} from commit {string} deployed to {string} with status {string}")
    fun thePipelineReported(
        artifact: String,
        commit: String,
        environment: String,
        status: String,
    ) {
        thePipelineReports(artifact, commit, environment, status)
        assertThat(world.lastStatus()).isEqualTo(ACCEPTED)
    }

    @When("the pipeline reports artifact {string} from commit {string} deployed to {string} with status {string}")
    fun thePipelineReports(
        artifact: String,
        commit: String,
        environment: String,
        status: String,
    ) {
        val (name, digest) = artifact.split("@", limit = 2)
        post(report(commitSha = commit, environment = environment, status = status, artifacts = listOf(artifactOf(name, digest))))
    }

    @When("the pipeline reports exactly the same deployment again")
    fun thePipelineReportsTheSameAgain() {
        world.postSigned(PATH, lastBody, bearer(token))
    }

    @When("the pipeline reports a deployment with status {string} from run {string}")
    fun thePipelineReportsFromRun(
        status: String,
        runUrl: String,
    ) {
        post(report(status = status, runUrl = runUrl))
    }

    @When("the pipeline reports a deployment without an Authorization header")
    fun withoutAuthorization() {
        lastBody = objectMapper.writeValueAsString(report())
        world.postSigned(PATH, lastBody, emptyMap())
    }

    @When("the pipeline reports a deployment with the token {string}")
    fun withToken(wrong: String) {
        lastBody = objectMapper.writeValueAsString(report())
        world.postSigned(PATH, lastBody, bearer(wrong))
    }

    @When("the pipeline reports a deployment with no artifacts")
    fun withNoArtifacts() {
        post(report(artifacts = emptyList()))
    }

    @Then("the ingest is accepted and created is {word}")
    fun theIngestIsAccepted(created: String) {
        assertThat(world.lastStatus()).describedAs(world.lastResponse().body).isEqualTo(ACCEPTED)
        assertThat(world.lastBody().path("created").asBoolean()).isEqualTo(created.toBooleanStrict())
        assertThat(world.lastBody().path("deploymentIds").size()).isPositive()
    }

    @Then("Artifact {string} is BUILT_FROM {string} at commit {string}")
    fun artifactIsBuiltFrom(
        artifactKey: String,
        repositoryKey: String,
        commit: String,
    ) {
        val commits =
            neo4jClient
                .query("MATCH (:Artifact { key: \$artifact })-[b:BUILT_FROM]->(:Repository { key: \$repo }) RETURN b.commitSha AS sha")
                .bindAll(mapOf("artifact" to artifactKey, "repo" to repositoryKey))
                .fetch()
                .all()
                .map { it["sha"] }
        assertThat(commits).containsExactly(commit)
    }

    @Then("a Deployment of that artifact is TO_ENVIRONMENT {string}")
    fun aDeploymentIsToEnvironment(environment: String) {
        val environments =
            neo4jClient
                .query("MATCH (:Artifact)-[:DEPLOYED_TO]->(:Deployment)-[:TO_ENVIRONMENT]->(e:Environment) RETURN e.key AS key")
                .fetch()
                .all()
                .map { it["key"] }
        assertThat(environments).containsExactly(environment)
    }

    @Then("the repository's deployments list {int} deployment with status {string} from {string}")
    fun theRepositoryDeployments(
        count: Int,
        status: String,
        sourceSystem: String,
    ) {
        world.get("/api/v1/graph/deployments?repoId=Repository:$REPOSITORY")
        assertThat(world.lastStatus()).isEqualTo(OK)
        val deployments = world.lastBody()
        assertThat(deployments.size()).isEqualTo(count)
        assertThat(deployments[0].path("status").asText()).isEqualTo(status)
        assertThat(deployments[0].path("provenance").path("sourceSystem").asText()).isEqualTo(sourceSystem)
    }

    @Then("there is exactly {int} Deployment node and {int} BUILT_FROM edge")
    fun exactlyOne(
        deployments: Int,
        builtFrom: Int,
    ) {
        assertThat(count("MATCH (d:Deployment) RETURN count(d) AS n")).isEqualTo(deployments.toLong())
        assertThat(count("MATCH ()-[b:BUILT_FROM]->() RETURN count(b) AS n")).isEqualTo(builtFrom.toLong())
    }

    @Then("there is exactly one Environment node, with key {string}")
    fun exactlyOneEnvironment(key: String) {
        val keys =
            neo4jClient
                .query("MATCH (e:Environment) RETURN e.key AS key")
                .fetch()
                .all()
                .map { it["key"] }
        assertThat(keys).containsExactly(key)
    }

    @Then("the Deployment has status {string} and provenance sourceId {string}")
    fun theDeploymentHas(
        status: String,
        sourceId: String,
    ) {
        val rows =
            neo4jClient
                .query("MATCH (d:Deployment) RETURN d.status AS status, d.prov_sourceId AS sourceId, d.prov_sourceSystem AS system")
                .fetch()
                .all()
        assertThat(rows).hasSize(1)
        assertThat(rows.single()["status"]).isEqualTo(status)
        assertThat(rows.single()["sourceId"]).isEqualTo(sourceId)
        assertThat(rows.single()["system"]).isEqualTo("github-actions")
    }

    @Then("there is no Deployment node")
    fun noDeployment() {
        assertThat(count("MATCH (d:Deployment) RETURN count(d) AS n")).isZero()
    }

    @Then("the errors say {string}")
    fun theErrorsSay(message: String) {
        assertThat(world.lastResponse().body).contains(message)
    }

    private fun count(cypher: String): Long =
        neo4jClient
            .query(cypher)
            .fetch()
            .one()
            .map { (it["n"] as Number).toLong() }
            .orElse(0L)

    private fun post(report: Map<String, Any?>) {
        lastBody = objectMapper.writeValueAsString(report)
        world.postSigned(PATH, lastBody, bearer(token))
    }

    private fun bearer(value: String) = mapOf("Authorization" to "Bearer $value")

    private fun artifactOf(
        name: String,
        digest: String,
    ) = mapOf("name" to name, "digest" to digest, "tag" to "v1.0.0")

    private fun report(
        commitSha: String = "c1",
        environment: String = "staging",
        status: String = "SUCCESS",
        runUrl: String = "https://github.com/maximumtrainer/sdlcknowledgegraph/actions/runs/1",
        artifacts: List<Map<String, Any?>> = listOf(artifactOf("ghcr.io/maximumtrainer/sdlc-graph-backend", "sha256:abc")),
    ): Map<String, Any?> =
        mapOf(
            "repository" to REPOSITORY,
            "commitSha" to commitSha,
            "artifacts" to artifacts,
            "environment" to environment,
            "status" to status,
            "deployedAt" to "2026-09-29T12:00:00Z",
            "deployedBy" to "dan",
            "runUrl" to runUrl,
            "pipeline" to mapOf("provider" to "github-actions", "workflowPath" to ".github/workflows/deploy-dogfood.yml"),
        )

    private companion object {
        const val PATH = "/api/v1/ingest/deployment"
        const val REPOSITORY = "github.com/maximumtrainer/sdlcknowledgegraph"
        const val ACCEPTED = 202
        const val OK = 200
    }
}
