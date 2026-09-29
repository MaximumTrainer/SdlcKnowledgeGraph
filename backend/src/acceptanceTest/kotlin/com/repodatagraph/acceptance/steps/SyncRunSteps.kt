package com.repodatagraph.acceptance.steps

import com.fasterxml.jackson.databind.JsonNode
import com.repodatagraph.acceptance.support.ApiWorld
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.out.GraphStore
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.context.ApplicationContext
import java.time.Duration
import java.time.Instant

/**
 * Browsing recorded runs (#29, FR5) and pruning the old ones (#29, FR6).
 *
 * Runs are written straight into the graph, shaped the way SyncRunRecorder writes them, rather than
 * produced by syncing: a scenario about ordering and retention has to say exactly when each run
 * started and finished, and a real run only ever starts now. The graph is emptied before every
 * scenario, so the runs a scenario counts are the ones it wrote.
 */
class SyncRunSteps(
    private val world: ApiWorld,
    private val graphStore: GraphStore,
    private val context: ApplicationContext,
) {
    @Given("three SyncRuns exist for {string}")
    fun threeSyncRunsExist(connector: String) {
        val now = Instant.now()
        seedRun("$connector-run-1", connector, "SUCCESS", now.minus(Duration.ofHours(3)), finishedAfter = Duration.ofMinutes(1))
        seedRun(
            "$connector-run-2",
            connector,
            "PARTIAL",
            now.minus(Duration.ofHours(2)),
            finishedAfter = Duration.ofMinutes(2),
            error = "page 2 failed: " + "x".repeat(LONG_ERROR - "page 2 failed: ".length),
        )
        seedRun(
            "$connector-run-3",
            connector,
            "FAILED",
            now.minus(Duration.ofHours(1)),
            finishedAfter = Duration.ofSeconds(5),
            error = "boom",
        )
        // Another connector's run, newer than all three, so a list that ignored the filter would show it.
        seedRun("another-connector-run", "another-connector", "SUCCESS", now.minus(Duration.ofMinutes(30)), Duration.ofMinutes(1))
    }

    @Given("a SyncRun {string} for {string} that finished {int} days ago")
    fun aRunFinishedDaysAgo(
        id: String,
        connector: String,
        days: Int,
    ) {
        val finished = Instant.now().minus(Duration.ofDays(days.toLong()))
        seedRun(id, connector, "SUCCESS", finished.minus(Duration.ofMinutes(1)), Duration.ofMinutes(1))
    }

    @Given("a SyncRun {string} for {string} still RUNNING since {int} days ago")
    fun aRunStillRunning(
        id: String,
        connector: String,
        days: Int,
    ) {
        seedRun(id, connector, "RUNNING", Instant.now().minus(Duration.ofDays(days.toLong())), finishedAfter = null)
    }

    @Given("the SyncRun {string} PRODUCED Repository {string}")
    fun theRunProduced(
        runId: String,
        key: String,
    ) {
        val now = Instant.now()
        val provenance = Provenance(sourceSystem = "fake", ingestedAt = now, validFrom = now, syncRunId = runId)
        graphStore.upsertNode(
            GraphNode(NodeKey("Repository", key), mapOf("url" to "https://$key", "defaultBranch" to "main"), provenance),
        )
        graphStore.upsertEdge(GraphEdge("PRODUCED", NodeKey(SYNC_RUN, runId), NodeKey("Repository", key), provenance = provenance))
    }

    @When("I GET the first listed sync run")
    fun iGetTheFirstListedRun() {
        val id =
            world
                .lastBody()
                .path("items")
                .path(0)
                .path("id")
                .asText()
        assertTrue(id.isNotBlank()) { "no sync run listed: " + world.lastResponse().body }
        world.get("/api/v1/sync-runs/$id")
    }

    @When("I GET the sync runs for {string} that started in the last {int} minutes")
    fun iGetRunsStartedRecently(
        connector: String,
        minutes: Int,
    ) {
        val from = Instant.now().minus(Duration.ofMinutes(minutes.toLong()))
        world.get("/api/v1/sync-runs?connector=$connector&from=$from")
    }

    @When("the sync run retention job runs")
    fun theRetentionJobRuns() {
        // Called directly rather than waited for: the job is nightly, and what is being proved is what
        // it deletes, not that a cron expression fires.
        val job = context.getBean("syncRunRetentionJob")
        job.javaClass.getMethod("prune").invoke(job)
    }

    @Then("the response has {int} sync runs sorted by startedAt descending and totalElements {int}")
    fun theResponseHasSortedRuns(
        count: Int,
        total: Int,
    ) {
        val body = world.lastBody()
        val started = body.path("items").map { Instant.parse(it.path("startedAt").asText()) }
        assertEquals(count, started.size) { "items in " + world.lastResponse().body }
        assertEquals(started.sortedDescending(), started) { "startedAt order in " + world.lastResponse().body }
        assertEquals(total, body.path("totalElements").asInt(-1)) { "totalElements in " + world.lastResponse().body }
    }

    @Then("the listed sync runs are {string}")
    fun theListedRunsAre(ids: String) {
        val listed = world.lastBody().path("items").map { it.path("id").asText() }
        assertEquals(ids.split(",").map { it.trim() }, listed) { world.lastResponse().body }
    }

    @Then("the listed sync run {string} has an error of at most {int} characters")
    fun theListedErrorIsShort(
        id: String,
        max: Int,
    ) {
        val error = listed(id).path("error")
        assertTrue(error.isTextual && error.asText().length <= max) { "error of $id was $error" }
    }

    @Then("the sync run has the fields {string}, {string} and {string}")
    fun theRunHasFields(
        first: String,
        second: String,
        third: String,
    ) {
        val body = world.lastBody()
        listOf(first, second, third).forEach { field ->
            assertTrue(body.has(field)) { "no $field in " + world.lastResponse().body }
        }
    }

    @Then("the sync run's error is {int} characters long")
    fun theErrorIsLong(length: Int) {
        assertEquals(
            length,
            world
                .lastBody()
                .path("error")
                .asText()
                .length,
        ) { world.lastResponse().body }
    }

    @Then("the SyncRun {string} is gone")
    fun theRunIsGone(id: String) {
        assertNull(graphStore.findNode(NodeKey(SYNC_RUN, id))) { "$id was not pruned" }
    }

    @Then("the SyncRun {string} still exists")
    fun theRunStillExists(id: String) {
        assertNotNull(graphStore.findNode(NodeKey(SYNC_RUN, id))) { "$id was pruned" }
    }

    @Then("the Repository {string} still names sync run {string} in its provenance")
    fun theRepositoryStillNamesTheRun(
        key: String,
        runId: String,
    ) {
        val repository = graphStore.findNode(NodeKey("Repository", key))
        assertNotNull(repository) { "Repository $key was deleted with the run that produced it" }
        assertEquals(runId, repository!!.provenance.syncRunId)
    }

    private fun listed(id: String): JsonNode {
        val entry = world.lastBody().path("items").firstOrNull { it.path("id").asText() == id }
        assertNotNull(entry) { "no sync run $id in " + world.lastResponse().body }
        return entry!!
    }

    /** A run as SyncRunRecorder writes one; a RUNNING run has no finishedAt. */
    private fun seedRun(
        id: String,
        connector: String,
        status: String,
        startedAt: Instant,
        finishedAfter: Duration?,
        error: String? = null,
    ) {
        val finishedAt = finishedAfter?.let { startedAt.plus(it) }
        graphStore.upsertNode(
            GraphNode(
                key = NodeKey(SYNC_RUN, id),
                props =
                    mapOf(
                        "id" to id,
                        "connector" to connector,
                        "sourceSystem" to connector,
                        "mode" to "FULL",
                        "status" to status,
                        "startedAt" to startedAt,
                        "finishedAt" to finishedAt,
                        "nodesUpserted" to 3,
                        "edgesUpserted" to 2,
                        "tombstones" to 0,
                        "error" to error,
                    ).filterValues { it != null },
                provenance =
                    Provenance(
                        sourceSystem = "sdlc-knowledge-graph",
                        ingestedAt = startedAt,
                        validFrom = startedAt,
                        syncRunId = id,
                    ),
            ),
        )
    }

    private companion object {
        const val SYNC_RUN = "SyncRun"
        const val LONG_ERROR = 500
    }
}
