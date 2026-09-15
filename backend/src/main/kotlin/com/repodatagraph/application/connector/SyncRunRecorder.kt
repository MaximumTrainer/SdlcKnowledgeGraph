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
    ) {
        val now = Instant.now(clock)
        // Kept from the first write, so a finished run still says when it began.
        val startedAt = graphStore.findNode(NodeKey(SYNC_RUN, runId))?.props?.get("startedAt") ?: now
        graphStore.upsertNode(
            GraphNode(
                key = NodeKey(SYNC_RUN, runId),
                props =
                    mapOf(
                        "id" to runId,
                        "connector" to registered.name,
                        "sourceSystem" to registered.descriptor.sourceSystem,
                        "mode" to mode.name,
                        "sourceId" to sourceId,
                        "status" to status.name,
                        "startedAt" to startedAt,
                        "finishedAt" to if (status == RunStatus.RUNNING) null else now,
                        "nodesUpserted" to totals.nodesUpserted,
                        "edgesUpserted" to totals.edgesUpserted,
                        "tombstones" to totals.tombstones,
                        "watermark" to watermark,
                        "error" to error,
                    ).filterValues { it != null },
                provenance = internalProvenance(now, runId),
            ),
        )
    }

    fun recordState(
        connector: String,
        runId: String,
        status: RunStatus,
        watermark: Instant?,
    ) {
        val now = Instant.now(clock)
        val existing = graphStore.findNode(NodeKey(CONNECTOR_STATE, connector))
        graphStore.upsertNode(
            GraphNode(
                key = NodeKey(CONNECTOR_STATE, connector),
                props =
                    mapOf(
                        "connector" to connector,
                        // Kept when a run reports none, so a connector that cannot say where it got
                        // to does not reset every later run to the beginning of time.
                        "watermark" to (watermark ?: existing?.props?.get("watermark")),
                        "lastRunId" to runId,
                        "lastStatus" to status.name,
                        "lastFinishedAt" to now,
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
    fun watermarkFor(connector: String): Instant? =
        when (val stored = graphStore.findNode(NodeKey(CONNECTOR_STATE, connector))?.props?.get("watermark")) {
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
