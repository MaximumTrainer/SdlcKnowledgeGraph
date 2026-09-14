package com.repodatagraph.acceptance.steps

import com.repodatagraph.acceptance.support.ApiWorld
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.port.out.connector.EdgeUpsert
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.NodeUpsert
import com.repodatagraph.support.connector.FakeConnector
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.time.Duration
import java.time.Instant

/**
 * Drives the connector mechanism over HTTP, with a scripted connector and no system behind it.
 *
 * What is being proved here is not that any particular source system works. It is that a fact
 * arriving through a connector is recorded as part of a run, stamped with where it came from, and
 * that a fact the source stops reporting is closed rather than erased. Those hold for every
 * connector, so they are proved once.
 */
class ConnectorSteps(
    private val world: ApiWorld,
    private val fake: FakeConnector,
) {
    private var syncRunId: String? = null

    @Before
    fun resetTheScript() {
        fake.reset()
    }

    @Given("the fake connector is registered with capabilities FULL, INCREMENTAL and WEBHOOK")
    fun theFakeConnectorIsRegistered() {
        world.get("/api/v1/connectors/fake")
        assertEquals(200, world.lastStatus()) { "the fake connector is not registered: " + world.lastResponse().body }
    }

    @Given("the fake connector will return {int} Repository nodes and {int} DEPENDS_ON edge with watermark {string}")
    fun theFakeConnectorWillReturn(
        nodeCount: Int,
        edgeCount: Int,
        watermark: String,
    ) {
        val nodes = (1..nodeCount).map { repositoryUpsert("github.com/acme/sync-$it") }
        val edges =
            (1..edgeCount).map {
                EdgeUpsert(
                    type = "DEPENDS_ON",
                    from = NodeKey("Repository", "github.com/acme/sync-1"),
                    to = NodeKey("Repository", "github.com/acme/sync-2"),
                )
            }
        fake.pages = listOf({ GraphDelta(nodes = nodes, edges = edges, watermark = Instant.parse(watermark)) })
    }

    @Given("the connector state for {string} has watermark {string}")
    fun theConnectorStateHasWatermark(
        connector: String,
        watermark: String,
    ) {
        fake.pages = listOf({ GraphDelta(watermark = Instant.parse(watermark)) })
        world.post("/api/v1/connectors/$connector/sync?mode=full", null)
        awaitRun(world.lastBody().path("syncRunId").asText(), "SUCCESS")
        fake.requests.clear()
    }

    @Given("a sync run for {string} is already RUNNING")
    fun aSyncRunIsAlreadyRunning(connector: String) {
        // A page that never finishes, so the run is still RUNNING when the next request arrives.
        fake.pages =
            listOf({
                Thread.sleep(RUN_HOLD.toMillis())
                GraphDelta()
            })
        world.post("/api/v1/connectors/$connector/sync?mode=full", null)
        assertEquals(202, world.lastStatus())
    }

    @Given("the fake connector previously produced a Repository {string}")
    fun theFakeConnectorPreviouslyProduced(key: String) {
        fake.pages = listOf({ GraphDelta(nodes = listOf(repositoryUpsert(key))) })
        world.post("/api/v1/connectors/fake/sync?mode=full", null)
        awaitRun(world.lastBody().path("syncRunId").asText(), "SUCCESS")
    }

    @Given("the fake connector will return a tombstone for Repository {string}")
    fun theFakeConnectorWillReturnATombstone(key: String) {
        fake.pages = listOf({ GraphDelta(tombstones = listOf(NodeKey("Repository", key))) })
    }

    @Given("the fake connector will return one good delta and then fail")
    fun oneGoodDeltaThenFail() {
        fake.pages =
            listOf(
                { GraphDelta(nodes = listOf(repositoryUpsert("github.com/acme/good"))) },
                { throw IllegalStateException("the source system fell over mid-page") },
            )
    }

    @When("I ask the fake connector for a full sync")
    fun iAskForAFullSync() {
        requestSync("fake", "full")
    }

    @When("I ask the fake connector for an incremental sync")
    fun iAskForAnIncrementalSync() {
        requestSync("fake", "incremental")
    }

    private fun requestSync(
        connector: String,
        mode: String,
    ) {
        world.post("/api/v1/connectors/$connector/sync?mode=$mode", null)
        if (world.lastStatus() == ACCEPTED) syncRunId = world.lastBody().path("syncRunId").asText()
    }

    @When("I list the connectors")
    fun iGetConnectors() {
        world.get("/api/v1/connectors")
    }

    @When("I ask for a connector named {string}")
    fun iGetAConnector(name: String) {
        world.get("/api/v1/connectors/$name")
    }

    @When("a webhook arrives for the fake connector signed with {string}")
    fun aWebhookSignedWith(secret: String) {
        postWebhook("fake", "{}", secret)
    }

    @When("a webhook arrives for the fake connector signed correctly")
    fun aWebhookSignedCorrectly() {
        postWebhook("fake", "{}", fake.webhookSecret)
        if (world.lastStatus() == ACCEPTED) syncRunId = world.lastBody().path("syncRunId").asText()
    }

    @Then("the body field {string} is present")
    fun theBodyFieldIsPresent(field: String) {
        assertTrue(
            world
                .lastBody()
                .path(field)
                .asText()
                .isNotBlank(),
        ) { "missing $field in " + world.lastResponse().body }
    }

    @Then("the sync run finishes with status {string}")
    fun theSyncRunFinishesWith(status: String) {
        awaitRun(requireNotNull(syncRunId) { "no sync run was started" }, status)
    }

    @Then("the sync run recorded {int} nodes and {int} edge")
    fun theSyncRunRecorded(
        nodes: Int,
        edges: Int,
    ) {
        val run = runNode(requireNotNull(syncRunId))
        assertEquals(nodes, run.path("props").path("nodesUpserted").asInt())
        assertEquals(edges, run.path("props").path("edgesUpserted").asInt())
    }

    @Then("the sync run has mode {string}")
    fun theSyncRunHasMode(mode: String) {
        assertEquals(mode, runNode(requireNotNull(syncRunId)).path("props").path("mode").asText())
    }

    @Then("every node it wrote has provenance sourceSystem {string} and the run's id")
    fun everyNodeHasProvenance(sourceSystem: String) {
        val written = writtenRepositories()
        assertTrue(written.isNotEmpty()) { "the run wrote no Repository nodes" }
        written.forEach {
            assertEquals(sourceSystem, it.path("provenance").path("sourceSystem").asText())
            assertEquals(syncRunId, it.path("provenance").path("syncRunId").asText())
        }
    }

    @Then("every node it wrote has confidence 1.0 and is not inferred")
    fun everyNodeIsReportedNotGuessed() {
        writtenRepositories().forEach {
            assertEquals(1.0, it.path("provenance").path("confidence").asDouble())
            assertFalse(it.path("provenance").path("inferred").asBoolean())
        }
    }

    @Then("the stored watermark for {string} is {string}")
    fun theStateHasWatermark(
        connector: String,
        watermark: String,
    ) {
        world.get("/api/v1/connectors/$connector")
        assertEquals(
            watermark,
            world
                .lastBody()
                .path("state")
                .path("watermark")
                .asText(),
        )
    }

    @Then("the fake connector was asked for changes since {string}")
    fun theConnectorWasAskedForChangesSince(since: String) {
        val request = fake.requests.lastOrNull()
        assertNotNull(request) { "the connector was never asked to sync" }
        assertEquals(Instant.parse(since), request!!.since)
    }

    @Then("the fake connector was asked for changes since nothing")
    fun theConnectorWasAskedForEverything() {
        val request = fake.requests.lastOrNull()
        assertNotNull(request) { "the connector was never asked to sync" }
        assertEquals(null, request!!.since)
    }

    @Then("the tombstoned Repository {string} still exists")
    fun theRepositoryStillExists(key: String) {
        world.get("/api/v1/nodes/Repository/$key")
        assertEquals(200, world.lastStatus()) { "the tombstone deleted the node instead of closing it" }
    }

    @Then("its provenance validTo is set")
    fun itsValidToIsSet() {
        assertTrue(
            world
                .lastBody()
                .path("provenance")
                .path("validTo")
                .asText()
                .isNotBlank(),
        ) {
            "validTo was not set: " + world.lastResponse().body
        }
    }

    @Then("the listed connectors include {string} with sourceSystem {string}")
    fun theListedConnectorsInclude(
        name: String,
        sourceSystem: String,
    ) {
        val entry = world.lastBody().firstOrNull { it.path("name").asText() == name }
        assertNotNull(entry) { "no connector named $name in " + world.lastResponse().body }
        assertEquals(sourceSystem, entry!!.path("sourceSystem").asText())
    }

    @Then("the fake connector was not asked to handle a webhook")
    fun theConnectorWasNotAskedToHandleAWebhook() {
        assertTrue(fake.handledWebhooks.isEmpty()) { "a webhook reached the connector despite a bad signature" }
    }

    private fun postWebhook(
        connector: String,
        body: String,
        secret: String,
    ) {
        world.postSigned(
            "/api/v1/webhooks/$connector",
            body,
            mapOf(FakeConnector.SIGNATURE_HEADER to FakeConnector.sign(body.toByteArray(), secret)),
        )
    }

    private fun repositoryUpsert(key: String) =
        NodeUpsert(
            type = "Repository",
            props =
                mapOf(
                    "url" to "https://$key",
                    "defaultBranch" to "main",
                    "topics" to emptyList<String>(),
                    "codeowners" to emptyList<String>(),
                ),
        )

    private fun writtenRepositories() =
        fake.pages
            .let { world.get("/api/v1/nodes/Repository?limit=50") }
            .let { world.lastBody().path("items").toList() }
            .filter { it.path("provenance").path("sourceSystem").asText() == "fake" }

    private fun runNode(id: String) = world.get("/api/v1/nodes/SyncRun/" + id.substringAfter(":")).let { world.lastBody() }

    /** Polls rather than sleeps: a sync is asynchronous, and a fixed wait is either slow or flaky. */
    private fun awaitRun(
        id: String,
        expected: String,
    ) {
        syncRunId = id
        val deadline = Instant.now().plus(RUN_TIMEOUT)
        var last = ""
        while (Instant.now().isBefore(deadline)) {
            last = runNode(id).path("props").path("status").asText()
            if (last == expected) return
            Thread.sleep(POLL.toMillis())
        }
        assertEquals(expected, last) { "the run did not reach $expected within $RUN_TIMEOUT" }
    }

    private companion object {
        const val ACCEPTED = 202
        val RUN_TIMEOUT: Duration = Duration.ofSeconds(10)
        val POLL: Duration = Duration.ofMillis(100)
        val RUN_HOLD: Duration = Duration.ofSeconds(3)
    }
}
