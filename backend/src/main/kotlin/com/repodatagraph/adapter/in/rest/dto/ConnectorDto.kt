package com.repodatagraph.adapter.`in`.rest.dto

import com.repodatagraph.application.connector.ConnectorFreshness
import com.repodatagraph.application.connector.RegisteredConnector
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.HealthStatus
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/** One connector as the list screen needs it: who it is, whether it runs, and how the last run went. */
data class ConnectorSummary(
    val name: String,
    val sourceSystem: String,
    val enabled: Boolean,
    val capabilities: Set<Capability>,
    /**
     * Whether a run is in flight right now.
     *
     * From the in-process guard, not from the newest SyncRun node. They are not the same thing: the
     * guard is released a moment before the run's final status is written, and the history can be
     * pruned while a run is going. A caller that inferred this from stored runs would sometimes ask
     * for a sync and get a 409 it had no way to see coming.
     */
    val syncing: Boolean,
    val health: HealthView,
    val lastRun: LastRunView?,
    val freshness: FreshnessView,
) {
    companion object {
        fun from(
            registered: RegisteredConnector,
            health: HealthStatus,
            state: GraphNode?,
            syncing: Boolean,
            freshness: ConnectorFreshness,
        ) = ConnectorSummary(
            name = registered.name,
            sourceSystem = registered.descriptor.sourceSystem,
            enabled = registered.enabled,
            capabilities = registered.descriptor.capabilities,
            syncing = syncing,
            health = HealthView(health.status.name, health.detail),
            lastRun = LastRunView.from(state),
            freshness = FreshnessView.from(freshness),
        )
    }
}

data class HealthView(
    val status: String,
    val detail: String?,
)

/**
 * How fresh a connector is (#29, FR3). Seconds rather than ISO durations, because what reads this is
 * mostly a badge or an alert comparing two numbers. `ageSeconds` is null for a connector that has
 * never succeeded; `stale` is only ever true for an enabled one.
 */
data class FreshnessView(
    val lastSuccessAt: String?,
    val ageSeconds: Long?,
    val thresholdSeconds: Long,
    val stale: Boolean,
) {
    companion object {
        fun from(freshness: ConnectorFreshness) =
            FreshnessView(
                lastSuccessAt = freshness.lastSuccessAt?.toString(),
                ageSeconds = freshness.age?.seconds,
                thresholdSeconds = freshness.threshold.seconds,
                stale = freshness.stale,
            )
    }
}

data class LastRunView(
    val id: String?,
    val status: String?,
    val finishedAt: String?,
    val watermark: String?,
) {
    companion object {
        fun from(state: GraphNode?): LastRunView? {
            val props = state?.props ?: return null
            return LastRunView(
                id = props["lastRunId"]?.toString(),
                status = props["lastStatus"]?.toString(),
                finishedAt = temporal(props["lastFinishedAt"]),
                watermark = temporal(props["watermark"]),
            )
        }
    }
}

/** One connector in detail, with what the graph remembers about it between runs. */
data class ConnectorDetail(
    val name: String,
    val sourceSystem: String,
    val enabled: Boolean,
    val capabilities: Set<Capability>,
    val nodeTypes: Set<String>,
    val edgeTypes: Set<String>,
    /** See [ConnectorSummary.syncing]. */
    val syncing: Boolean,
    val health: HealthView,
    val state: ConnectorStateView,
    val freshness: FreshnessView,
) {
    companion object {
        fun from(
            registered: RegisteredConnector,
            health: HealthStatus,
            state: GraphNode?,
            syncing: Boolean,
            freshness: ConnectorFreshness,
        ): ConnectorDetail {
            val descriptor = registered.descriptor
            return ConnectorDetail(
                name = registered.name,
                sourceSystem = descriptor.sourceSystem,
                enabled = registered.enabled,
                capabilities = descriptor.capabilities,
                nodeTypes = descriptor.nodeTypes,
                edgeTypes = descriptor.edgeTypes,
                syncing = syncing,
                health = HealthView(health.status.name, health.detail),
                state = ConnectorStateView.from(state),
                freshness = FreshnessView.from(freshness),
            )
        }
    }
}

data class ConnectorStateView(
    val watermark: String?,
    val lastRunId: String?,
    val lastStatus: String?,
    val lastFinishedAt: String?,
    /** The same as [lastStatus] for a state written since #29; null for one written before. */
    val lastRunStatus: String?,
    val lastSuccessAt: String?,
    /** Runs that ended other than SUCCESS since the last one that did; PARTIAL counts. */
    val consecutiveFailures: Int,
) {
    companion object {
        fun from(state: GraphNode?): ConnectorStateView {
            val props = state?.props ?: emptyMap()
            return ConnectorStateView(
                watermark = temporal(props["watermark"]),
                lastRunId = props["lastRunId"]?.toString(),
                lastStatus = props["lastStatus"]?.toString(),
                lastFinishedAt = temporal(props["lastFinishedAt"]),
                lastRunStatus = props["lastRunStatus"]?.toString(),
                lastSuccessAt = temporal(props["lastSuccessAt"]),
                consecutiveFailures = (props["consecutiveFailures"] as? Number)?.toInt() ?: 0,
            )
        }
    }
}

/** What a caller gets back when they ask for a sync: the run to watch, not the result. */
data class SyncAccepted(
    val syncRunId: String,
)

data class SyncRunView(
    val id: String,
    val connector: String,
    val mode: String?,
    val status: String?,
    val startedAt: String?,
    val finishedAt: String?,
    val nodesUpserted: Int,
    val edgesUpserted: Int,
    val tombstones: Int,
    val watermark: String?,
    val error: String?,
) {
    companion object {
        fun from(node: GraphNode): SyncRunView {
            val props = node.props
            return SyncRunView(
                id = props["id"]?.toString() ?: node.key.key,
                connector = props["connector"]?.toString() ?: "",
                mode = props["mode"]?.toString(),
                status = props["status"]?.toString(),
                startedAt = temporal(props["startedAt"]),
                finishedAt = temporal(props["finishedAt"]),
                nodesUpserted = intOf(props["nodesUpserted"]),
                edgesUpserted = intOf(props["edgesUpserted"]),
                tombstones = intOf(props["tombstones"]),
                watermark = temporal(props["watermark"]),
                error = props["error"]?.toString(),
            )
        }

        private fun intOf(value: Any?): Int = (value as? Number)?.toInt() ?: value?.toString()?.toIntOrNull() ?: 0
    }
}

/**
 * A stored temporal property as one canonical ISO-8601 instant.
 *
 * Neo4j's driver refuses an Instant on the way in, so temporal properties are written as
 * ZonedDateTime and read back as one - whose `toString` renders "10:00" for a whole minute and adds
 * a zone. Letting that reach the API would mean the same moment had several spellings depending on
 * the driver, and a caller comparing timestamps would be comparing formatting.
 */
private fun temporal(value: Any?): String? =
    when (value) {
        null -> null
        is Instant -> value.toString()
        is ZonedDateTime -> value.toInstant().toString()
        is OffsetDateTime -> value.toInstant().toString()
        else -> value.toString()
    }
