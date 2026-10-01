package com.repodatagraph.application.connector

import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.connector.SyncMode
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/**
 * Writes down what a run did, and what the graph should remember about a connector afterwards.
 *
 * Separate from [SyncService] because they answer different questions. One runs a connector and
 * decides what happened; this one records it. Keeping the bookkeeping here is also what stops the
 * shape of a SyncRun node leaking into the logic that produces it.
 *
 * These are meta nodes - the graph describing itself - so their provenance names this application
 * rather than any source system.
 */
@Suppress("TooManyFunctions") // Runs and connector state, and their reads; the archive's run (#33) is one more way in.
@Component
class SyncRunRecorder(
    private val graphStore: GraphStore,
    private val clock: Clock,
) {
    fun recordRun(
        runId: String,
        registered: RegisteredConnector,
        mode: SyncMode,
        status: RunStatus,
        totals: DeltaResult,
        watermark: Instant?,
        error: String?,
        sourceId: String? = null,
    ) = write(
        runId,
        registered.name,
        registered.descriptor.sourceSystem,
        mode,
        status,
        totals,
        watermark,
        error,
        sourceId,
        connectorVersion = registered.descriptor.version,
    )

    /**
     * A run of the application's own - the archive (#33) - recorded as a connector's is, so every bulk
     * change to the graph is answered from one list. Its source is this application.
     */
    fun recordInternalRun(
        runId: String,
        name: String,
        status: RunStatus,
        totals: DeltaResult,
        startedAt: Instant,
        error: String?,
    ) = write(runId, name, SELF, SyncMode.FULL, status, totals, null, error, null, startedAt)

    @Suppress("LongParameterList")
    private fun write(
        runId: String,
        connector: String,
        sourceSystem: String,
        mode: SyncMode,
        status: RunStatus,
        totals: DeltaResult,
        watermark: Instant?,
        error: String?,
        sourceId: String?,
        startedAtIfNew: Instant? = null,
        connectorVersion: String? = null,
    ) {
        val now = Instant.now(clock)
        // Kept from the first write, so a finished run still says when it began.
        val startedAt = graphStore.findNode(NodeKey(SYNC_RUN, runId))?.props?.get("startedAt") ?: startedAtIfNew ?: now
        graphStore.upsertNode(
            GraphNode(
                key = NodeKey(SYNC_RUN, runId),
                props =
                    mapOf(
                        "id" to runId,
                        "connector" to connector,
                        "sourceSystem" to sourceSystem,
                        "mode" to mode.name,
                        "sourceId" to sourceId,
                        "status" to status.name,
                        "startedAt" to startedAt,
                        "finishedAt" to if (status == RunStatus.RUNNING) null else now,
                        "nodesUpserted" to totals.nodesUpserted,
                        "edgesUpserted" to totals.edgesUpserted,
                        "tombstones" to totals.tombstones,
                        // What moved, what was only said again, and what could not be read or
                        // written (#86, FR-6), and which version of the connector did it.
                        "written" to totals.written,
                        "unchanged" to totals.unchanged,
                        "failed" to totals.failed,
                        "connectorVersion" to connectorVersion,
                        "watermark" to watermark,
                        "error" to error,
                    ).filterValues { it != null },
                provenance = internalProvenance(now, runId),
            ),
        )
    }

    /**
     * What the graph remembers about a connector once a scheduled or manual run has finished (#29,
     * FR3), whatever its status: a state that only heard about successes could not count the
     * failures since the last one.
     *
     * `lastRunId`, `lastStatus`, `lastRunStatus` and `lastFinishedAt` describe that run.
     * `lastStatus` and `lastRunStatus` always agree; the first is kept because the API and graphs
     * written before #29 already use it. The watermark and `lastSuccessAt` only move on a success,
     * and `consecutiveFailures` counts every other ending since then. A PARTIAL run counts as a
     * failure: it read only part of the estate, so it cannot vouch for the rest being current.
     */
    fun recordState(
        connector: String,
        runId: String,
        status: RunStatus,
        watermark: Instant?,
    ) {
        val now = Instant.now(clock)
        val existing = graphStore.findNode(NodeKey(CONNECTOR_STATE, connector))?.props.orEmpty()
        val succeeded = status == RunStatus.SUCCESS
        graphStore.upsertNode(
            GraphNode(
                key = NodeKey(CONNECTOR_STATE, connector),
                props =
                    mapOf(
                        "connector" to connector,
                        // Kept when a run reports none, so a connector that cannot say where it got
                        // to does not reset every later run to the beginning of time. And only ever
                        // moved by a success: moving it past a failed page would skip that page for
                        // good.
                        "watermark" to ((if (succeeded) watermark else null) ?: existing["watermark"]),
                        "lastRunId" to runId,
                        "lastStatus" to status.name,
                        "lastRunStatus" to status.name,
                        "lastFinishedAt" to now,
                        "lastSuccessAt" to if (succeeded) now else lastSuccessIn(existing),
                        "consecutiveFailures" to if (succeeded) 0 else failuresIn(existing) + 1,
                    ).filterValues { it != null },
                provenance = internalProvenance(now, runId),
            ),
        )
    }

    /**
     * The run a delivery already produced, if this one has been seen before.
     *
     * Asked of the graph rather than of memory: a redelivery can arrive after a restart, or at
     * another instance, and an in-process set would let both of those apply the same event twice.
     */
    fun runForDelivery(
        connector: String,
        sourceId: String,
    ): String? =
        graphStore
            .findNodes(SYNC_RUN, mapOf("connector" to connector, "sourceId" to sourceId), limit = 1)
            .firstOrNull()
            // The key, not the `id` property: the store overwrites `id` with the node's qualified
            // identity, so a SyncRun's own `id` reads back as "SyncRun:<uuid>" rather than the run id.
            ?.key
            ?.key

    /**
     * Where the last successful run of this connector got to.
     *
     * Neo4j's driver refuses an [Instant] on the way in, so temporal properties are written as
     * [ZonedDateTime] and read back as one. A watermark that did not survive that round trip would
     * silently become null, and every incremental run would quietly become a full one - working,
     * slowly, for ever, with nothing failing to say so.
     */
    fun watermarkFor(connector: String): Instant? = stateInstant(connector, "watermark")

    /**
     * When this connector's last successful run finished, which is what its freshness counts from
     * (#29). Null means the connector has never succeeded, or not since the graph was emptied.
     */
    fun lastSuccessAt(connector: String): Instant? =
        lastSuccessIn(graphStore.findNode(NodeKey(CONNECTOR_STATE, connector))?.props.orEmpty())

    /**
     * The last success a state records. A state written before #29 has no `lastSuccessAt`, but it was
     * only ever written for a success, so its `lastFinishedAt` is one - provided `lastStatus` agrees,
     * since a state written since then may carry a failure's finish time there.
     */
    private fun lastSuccessIn(state: Map<String, Any?>): Instant? =
        instantOf(state["lastSuccessAt"])
            ?: instantOf(state["lastFinishedAt"]).takeIf { state["lastStatus"] == RunStatus.SUCCESS.name }

    /** Neo4j hands integers back as Long; anything unreadable counts as none. */
    private fun failuresIn(state: Map<String, Any?>): Int = (state["consecutiveFailures"] as? Number)?.toInt() ?: 0

    private fun stateInstant(
        connector: String,
        property: String,
    ): Instant? = instantOf(graphStore.findNode(NodeKey(CONNECTOR_STATE, connector))?.props?.get(property))

    private fun instantOf(stored: Any?): Instant? =
        when (stored) {
            is Instant -> stored
            is ZonedDateTime -> stored.toInstant()
            is OffsetDateTime -> stored.toInstant()
            is String -> runCatching { Instant.parse(stored) }.getOrNull()
            else -> null
        }

    private fun internalProvenance(
        now: Instant,
        runId: String,
    ) = Provenance(
        sourceSystem = SELF,
        ingestedAt = now,
        observedAt = now,
        validFrom = now,
        syncRunId = runId,
    )

    private companion object {
        const val SYNC_RUN = "SyncRun"
        const val CONNECTOR_STATE = "ConnectorState"
        const val SELF = "sdlc-knowledge-graph"
    }
}
