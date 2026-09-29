package com.repodatagraph.acceptance.steps

import com.repodatagraph.acceptance.support.ApiWorld
import com.repodatagraph.acceptance.support.SyncWorld
import com.repodatagraph.acceptance.support.says
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
    private val sync: SyncWorld,
    private val fake: FakeConnector,
) {
    /** The repository a scenario recorded earlier, for a later step that reports it again. */
    private var remembered: String? = null

    @Before
    fun resetTheScript() {
        fake.reset()
        sync.awaitIdle("fake")
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
        sync.awaitRun("SUCCESS", sync.startSync(connector))
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
        sync.awaitRun("SUCCESS", sync.startSync("fake"))
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

    @Given("the fake connector will find nothing in the next webhook")
    fun theFakeConnectorWillFindNothing() {
        fake.webhookDelta = null
    }

    @When("the fake connector is idle again")
    fun theFakeConnectorIsIdleAgain() {
        sync.awaitIdle("fake")
    }

    @When("I ask the fake connector for a full sync")
    fun iAskForAFullSync() {
        sync.requestSync("fake", "full")
    }

    @When("I ask the fake connector for an incremental sync")
    fun iAskForAnIncrementalSync() {
        sync.requestSync("fake", "incremental")
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
        if (world.lastStatus() == ACCEPTED) sync.recordRun(world.lastBody().path("syncRunId").asText())
    }

    @Then("the body field {string} is present")
    fun theBodyFieldIsPresent(field: String) {
        assertTrue(world.lastBody().says(field)) { "missing $field in " + world.lastResponse().body }
    }

    @Then("the sync run finishes with status {string}")
    fun theSyncRunFinishesWith(status: String) {
        sync.awaitRun(status)
    }

    @Then("the sync run recorded {int} nodes and {int} edge")
    fun theSyncRunRecorded(
        nodes: Int,
        edges: Int,
    ) {
        val run = sync.runNode()
        assertEquals(nodes, run.path("props").path("nodesUpserted").asInt())
        assertEquals(edges, run.path("props").path("edgesUpserted").asInt())
    }

    @Then("the sync run has mode {string}")
    fun theSyncRunHasMode(mode: String) {
        assertEquals(
            mode,
            sync
                .runNode()
                .path("props")
                .path("mode")
                .asText(),
        )
    }

    @Then("every node it wrote has provenance sourceSystem {string} and the run's id")
    fun everyNodeHasProvenance(sourceSystem: String) {
        val written = writtenRepositories()
        assertTrue(written.isNotEmpty()) { "the run wrote no Repository nodes" }
        written.forEach {
            assertEquals(sourceSystem, it.path("provenance").path("sourceSystem").asText())
            assertEquals(sync.runId, it.path("provenance").path("syncRunId").asText())
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
        val stored =
            world
                .lastBody()
                .path("state")
                .path("watermark")
                .asText()
        // Compared as instants, not as strings: an Instant renders 10:00:00 as "10:00", so the same
        // moment has more than one spelling and a string compare fails on formatting alone.
        assertEquals(Instant.parse(watermark), Instant.parse(stored)) { "the stored watermark was '$stored'" }
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
        assertTrue(world.lastBody().path("provenance").says("validTo")) {
            "validTo was not set: " + world.lastResponse().body
        }
    }

    @Given("a full sync of the fake connector recorded Repository {string}")
    fun aFullSyncRecorded(key: String) {
        fake.pages = listOf({ GraphDelta(nodes = listOf(repositoryUpsert(key))) })
        sync.awaitRun("SUCCESS", sync.startSync("fake", "full"))
        remembered = key
    }

    @Given("a Team {string} was created by hand")
    fun aTeamWasCreatedByHand(name: String) {
        world.post("/api/v1/nodes/Team", mapOf("props" to mapOf("name" to name)))
        assertEquals(201, world.lastStatus()) { "creating the team was refused: " + world.lastResponse().body }
    }

    @When("a later full sync of the fake connector succeeds without mentioning it")
    fun aLaterFullSyncWithoutIt() {
        // Something else, so the run has written a page and succeeded rather than seen nothing at all.
        fake.pages = listOf({ GraphDelta(nodes = listOf(repositoryUpsert("github.com/acme/unrelated"))) })
        sync.awaitRun("SUCCESS", sync.startSync("fake", "full"))
    }

    @When("a later full sync of the fake connector succeeds and reports it again")
    fun aLaterFullSyncWithIt() {
        val key = requireNotNull(remembered) { "no repository was recorded earlier in this scenario" }
        fake.pages = listOf({ GraphDelta(nodes = listOf(repositoryUpsert(key))) })
        sync.awaitRun("SUCCESS", sync.startSync("fake", "full"))
    }

    @When("a later full sync of the fake connector fails halfway")
    fun aLaterFullSyncFailsHalfway() {
        fake.pages =
            listOf(
                { GraphDelta(nodes = listOf(repositoryUpsert("github.com/acme/unrelated"))) },
                { throw IllegalStateException("the source system fell over mid-page") },
            )
        sync.awaitRun("PARTIAL", sync.startSync("fake", "full"))
    }

    @When("a later incremental sync of the fake connector succeeds without mentioning it")
    fun aLaterIncrementalSyncWithoutIt() {
        fake.pages = listOf({ GraphDelta(nodes = listOf(repositoryUpsert("github.com/acme/unrelated"))) })
        sync.awaitRun("SUCCESS", sync.startSync("fake", "incremental"))
    }

    @Then("the {word} {string} is closed but not deleted")
    fun theNodeIsClosed(
        type: String,
        key: String,
    ) {
        world.get("/api/v1/nodes/$type/$key")
        assertEquals(200, world.lastStatus()) { "the node was deleted instead of closed" }
        assertTrue(world.lastBody().path("provenance").says("validTo")) {
            "validTo was not set: " + world.lastResponse().body
        }
    }

    @Then("the {word} {string} is still open")
    fun theNodeIsStillOpen(
        type: String,
        key: String,
    ) {
        world.get("/api/v1/nodes/$type/$key")
        assertEquals(200, world.lastStatus()) { "the node is missing: " + world.lastResponse().body }
        assertFalse(world.lastBody().path("provenance").says("validTo")) {
            "validTo was set on a node that should still be open: " + world.lastResponse().body
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

    private companion object {
        const val ACCEPTED = 202

        /** Long enough that the run is still in flight when the next request arrives. */
        val RUN_HOLD: Duration = Duration.ofSeconds(3)
    }
}
