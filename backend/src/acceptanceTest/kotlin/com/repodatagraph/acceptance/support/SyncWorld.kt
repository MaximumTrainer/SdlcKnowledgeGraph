package com.repodatagraph.acceptance.support

import com.fasterxml.jackson.databind.JsonNode
import io.cucumber.spring.ScenarioScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Per-scenario state for whichever connector the scenario is driving: which run it started, and the
 * waiting that an asynchronous run needs.
 *
 * Shared rather than duplicated per connector. "The run finished with status X" and "the run
 * recorded N nodes" are claims about the sync mechanism, not about GitHub or ServiceNow, so every
 * connector's feature file should be able to say them in the same words - which only works if the
 * run id lives somewhere both step classes can see.
 */
@Component
@ScenarioScope
class SyncWorld(
    private val world: ApiWorld,
) {
    private var currentRunId: String? = null

    /** The run the scenario most recently started, if it was accepted. */
    val runId: String? get() = currentRunId

    /**
     * Asks for a sync, recording the run id only if it was actually accepted.
     *
     * Cleared rather than left alone when the sync was refused: keeping the previous run's id would
     * let a later step wait happily on a run that finished in an earlier scenario, and report success
     * for something that never started.
     */
    fun requestSync(
        connector: String,
        mode: String,
    ) {
        world.post("/api/v1/connectors/$connector/sync?mode=$mode", null)
        currentRunId = if (world.lastStatus() == ACCEPTED) world.lastBody().path("syncRunId").asText() else null
    }

    /**
     * Starts a sync that the scenario is relying on, and fails here if it was refused.
     *
     * Without this, a refused setup leaves an empty run id and the failure surfaces much later as
     * "the run did not reach SUCCESS" - which says nothing about the sync never having started.
     */
    fun startSync(
        connector: String,
        mode: String = "full",
    ): String {
        requestSync(connector, mode)
        assertEquals(ACCEPTED, world.lastStatus()) {
            "setting up a sync for '" + connector + "' was refused: " + world.lastResponse().body
        }
        return requireNotNull(runId)
    }

    /** For a run that was started by something other than a sync request, such as a webhook. */
    fun recordRun(id: String?) {
        currentRunId = id
    }

    fun runNode(id: String = requireNotNull(runId) { "no sync run was started" }): JsonNode =
        world.get("/api/v1/nodes/SyncRun/" + id.substringAfter(":")).let { world.lastBody() }

    /** Polls rather than sleeps: a sync is asynchronous, and a fixed wait is either slow or flaky. */
    fun awaitRun(
        expected: String,
        id: String = requireNotNull(runId) { "no sync run was started" },
    ) {
        currentRunId = id
        val deadline = Instant.now().plus(RUN_TIMEOUT)
        var last = ""
        while (Instant.now().isBefore(deadline)) {
            last = runNode(id).path("props").path("status").asText()
            if (last == expected) return
            Thread.sleep(POLL.toMillis())
        }
        assertEquals(expected, last) { "run $id was '$last' after $RUN_TIMEOUT, expected '$expected'" }
    }

    /**
     * Waits until a connector is idle.
     *
     * One scenario deliberately leaves a run going to prove that overlapping runs are refused. Left
     * alone, the next scenario asks for a sync, gets that 409, and fails somewhere unrelated - so the
     * suite would only pass in the order it happened to be written in.
     *
     * Asks the connector, not the stored runs. Another step class empties the graph before every
     * scenario, and Cucumber does not order hooks across classes - so a guard that read SyncRun nodes
     * would sometimes find them already deleted, conclude all was quiet, and walk into the 409 it was
     * written to avoid.
     */
    fun awaitIdle(connector: String) {
        val deadline = Instant.now().plus(RUN_TIMEOUT)
        while (Instant.now().isBefore(deadline)) {
            world.get("/api/v1/connectors/$connector")
            if (!world.lastBody().path("syncing").asBoolean()) return
            Thread.sleep(POLL.toMillis())
        }
    }

    companion object {
        const val ACCEPTED = 202

        /**
         * Generous on purpose. A run is asynchronous and writes through a Testcontainers Neo4j, which
         * on a shared CI runner is several times slower than a developer machine - so a timeout tuned
         * to the fast case fails for being slow rather than for being wrong. Polling means a fast
         * machine still finishes in milliseconds; only the patience changes.
         */
        val RUN_TIMEOUT: Duration = Duration.ofSeconds(30)
        val POLL: Duration = Duration.ofMillis(100)
    }
}
