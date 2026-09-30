package com.repodatagraph.acceptance.steps

import com.fasterxml.jackson.databind.ObjectMapper
import com.repodatagraph.acceptance.support.ApiWorld
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.assertj.core.api.Assertions.assertThat
import org.springframework.data.neo4j.core.Neo4jClient

/**
 * Plays `scripts/dogfood-seed.mjs`: posts seed batches to `POST /api/v1/ingest/seed` and reads back
 * what the graph made of them.
 *
 * A batch is nodes and edges, the edges naming their ends by position in the node list, which is
 * how the script sends everything it read in one request.
 */
class SeedIngestSteps(
    private val world: ApiWorld,
    private val neo4jClient: Neo4jClient,
    private val objectMapper: ObjectMapper,
) {
    private var token = ""
    private var lastRepository = ""
    private var lastTeam = ""
    private var lastWorkflow = ""

    @Given("the seed ingest token is {string}")
    fun theTokenIs(configured: String) {
        token = configured
    }

    @Given("the seed posted repository {string} owned by team {string} with pipeline {string}")
    fun theSeedPosted(
        repository: String,
        team: String,
        workflow: String,
    ) {
        theSeedPosts(repository, team, workflow)
        assertThat(world.lastStatus()).describedAs(world.lastResponse().body).isEqualTo(ACCEPTED)
    }

    @When("the seed posts repository {string} owned by team {string} with pipeline {string}")
    fun theSeedPosts(
        repository: String,
        team: String,
        workflow: String,
    ) {
        lastRepository = repository
        lastTeam = team
        lastWorkflow = workflow
        post(ownership(repository, team, workflow, lastRunStatus = "success"))
    }

    @When("the seed posts it again with the pipeline's last run status {string}")
    fun theSeedPostsAgain(status: String) {
        post(ownership(lastRepository, lastTeam, lastWorkflow, lastRunStatus = status))
    }

    @When("the seed posts repository {string} depending on {string} read from {string}")
    fun theSeedPostsADependency(
        repository: String,
        dependency: String,
        manifest: String,
    ) {
        post(
            batch(
                nodes = listOf(repositoryNode(repository), repositoryNode(dependency)),
                edges = listOf(edge("DEPENDS_ON", 0, 1, mapOf("kind" to "library", "manifest" to manifest))),
            ),
        )
    }

    @When("the seed posts without an Authorization header")
    fun withoutAuthorization() {
        val body = objectMapper.writeValueAsString(batch(nodes = listOf(teamNode("platform"))))
        world.postSigned(PATH, body, emptyMap())
    }

    @When("the seed posts a Repository with no defaultBranch")
    fun withoutDefaultBranch() {
        post(batch(nodes = listOf(repositoryNode("github.com/acme/web").withoutProperty("defaultBranch"))))
    }

    @When("the seed posts a node of type {string}")
    fun aNodeOfType(type: String) {
        post(batch(nodes = listOf(mapOf("type" to type, "props" to mapOf("name" to "anything")))))
    }

    @Then("the seed is accepted and created is {word}")
    fun theSeedIsAccepted(created: String) {
        assertThat(world.lastStatus()).describedAs(world.lastResponse().body).isEqualTo(ACCEPTED)
        assertThat(world.lastBody().path("created").asBoolean()).isEqualTo(created.toBooleanStrict())
    }

    @Then("Repository {string} has provenance sourceSystem {string}")
    fun repositoryHasProvenance(
        key: String,
        sourceSystem: String,
    ) {
        world.get("/api/v1/nodes/Repository/$key")
        assertThat(world.lastStatus()).describedAs(world.lastResponse().body).isEqualTo(OK)
        assertThat(
            world
                .lastBody()
                .path("provenance")
                .path("sourceSystem")
                .asText(),
        ).isEqualTo(sourceSystem)
    }

    @Then("Repository {string} is OWNED_BY Team {string}")
    fun repositoryIsOwnedBy(
        repository: String,
        team: String,
    ) {
        val teams = keys("MATCH (:Repository { key: \$repo })-[:OWNED_BY]->(t:Team) RETURN t.key AS key", repository)
        assertThat(teams).containsExactly(team)
    }

    @Then("Repository {string} HAS_PIPELINE {string}")
    fun repositoryHasPipeline(
        repository: String,
        workflow: String,
    ) {
        val paths = keys("MATCH (:Repository { key: \$repo })-[:HAS_PIPELINE]->(p:Pipeline) RETURN p.workflowPath AS key", repository)
        assertThat(paths).containsExactly(workflow)
    }

    @Then("Repository {string} DEPENDS_ON {string} with kind {string} and manifest {string}")
    fun repositoryDependsOn(
        repository: String,
        dependency: String,
        kind: String,
        manifest: String,
    ) {
        val rows =
            neo4jClient
                .query(
                    "MATCH (:Repository { key: \$repo })-[d:DEPENDS_ON]->(t:Repository) " +
                        "RETURN t.key AS key, d.kind AS kind, d.manifest AS manifest",
                ).bind(repository)
                .to("repo")
                .fetch()
                .all()
        assertThat(rows).hasSize(1)
        assertThat(rows.single()["key"]).isEqualTo(dependency)
        assertThat(rows.single()["kind"]).isEqualTo(kind)
        assertThat(rows.single()["manifest"]).isEqualTo(manifest)
    }

    @Then("there are {int} seeded nodes and {int} seeded edges")
    fun seededCounts(
        nodes: Int,
        edges: Int,
    ) {
        assertThat(
            count("MATCH (n) WHERE n.prov_sourceSystem = 'dogfood-seed' AND NOT n:SyncRun AND NOT n:NodeVersion RETURN count(n) AS n"),
        ).isEqualTo(nodes.toLong())
        assertThat(count("MATCH ()-[e]->() WHERE e.prov_sourceSystem = 'dogfood-seed' AND type(e) <> 'PRODUCED' RETURN count(e) AS n"))
            .isEqualTo(edges.toLong())
    }

    @Then("there is no Team node")
    fun noTeam() {
        assertThat(count("MATCH (t:Team) RETURN count(t) AS n")).isZero()
    }

    private fun keys(
        cypher: String,
        repository: String,
    ): List<Any?> =
        neo4jClient
            .query(cypher)
            .bind(repository)
            .to("repo")
            .fetch()
            .all()
            .map { it["key"] }

    private fun count(cypher: String): Long =
        neo4jClient
            .query(cypher)
            .fetch()
            .one()
            .map { (it["n"] as Number).toLong() }
            .orElse(0L)

    private fun post(batch: Map<String, Any?>) {
        world.postSigned(PATH, objectMapper.writeValueAsString(batch), mapOf("Authorization" to "Bearer $token"))
    }

    private fun ownership(
        repository: String,
        team: String,
        workflow: String,
        lastRunStatus: String,
    ): Map<String, Any?> {
        val repoKey = repository.removePrefix("https://").lowercase()
        return batch(
            nodes =
                listOf(
                    repositoryNode(repository),
                    teamNode(team),
                    mapOf(
                        "type" to "Pipeline",
                        "props" to
                            mapOf(
                                "provider" to "github-actions",
                                "repoKey" to repoKey,
                                "workflowPath" to workflow,
                                "name" to workflow.substringAfterLast('/'),
                                "repoId" to repoKey,
                                "lastRunStatus" to lastRunStatus,
                            ),
                    ),
                ),
            edges = listOf(edge("OWNED_BY", 0, 1, mapOf("pathPatterns" to listOf("*"))), edge("HAS_PIPELINE", 0, 2)),
        )
    }

    private fun repositoryNode(url: String) =
        mapOf(
            "type" to "Repository",
            "props" to mapOf("url" to url, "defaultBranch" to "main", "topics" to emptyList<String>(), "codeowners" to emptyList<String>()),
            "sourceId" to url,
        )

    private fun Map<String, Any?>.withoutProperty(property: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val props = this["props"] as Map<String, Any?>
        return this + ("props" to props - property)
    }

    private fun teamNode(name: String) = mapOf("type" to "Team", "props" to mapOf("name" to name))

    private fun edge(
        type: String,
        from: Int,
        to: Int,
        props: Map<String, Any?> = emptyMap(),
    ) = mapOf("type" to type, "from" to from, "to" to to, "props" to props)

    private fun batch(
        nodes: List<Map<String, Any?>>,
        edges: List<Map<String, Any?>> = emptyList(),
    ) = mapOf("nodes" to nodes, "edges" to edges)

    private companion object {
        const val PATH = "/api/v1/ingest/seed"
        const val ACCEPTED = 202
        const val OK = 200
    }
}
