package com.repodatagraph.domain.port.out.connector

import com.repodatagraph.domain.model.NodeKey
import java.time.Instant

/**
 * What a connector can do.
 *
 * Declared rather than discovered, so the API can refuse a request a connector cannot serve with a
 * 400 instead of failing somewhere inside it.
 */
enum class Capability {
    /** Can return everything it knows. */
    FULL,

    /** Can return only what changed since a watermark. */
    INCREMENTAL,

    /** Can accept and verify webhooks. */
    WEBHOOK,

    /** Can report what it is able to see without ingesting it. */
    DISCOVERY,
}

/**
 * Who a connector is.
 *
 * [nodeTypes] and [edgeTypes] are checked against the ontology registry at startup, so a connector
 * that intends to write a type nobody declared fails the application rather than filling the graph
 * with nodes no traversal can reach.
 *
 * @param name the key in configuration and in every URL, for example `github`
 * @param sourceSystem what goes in provenance, for example `github` or `servicenow:prod`
 */
data class ConnectorDescriptor(
    val name: String,
    val sourceSystem: String,
    val nodeTypes: Set<String>,
    val edgeTypes: Set<String>,
    val capabilities: Set<Capability>,
) {
    init {
        require(name.isNotBlank()) { "a connector needs a name" }
        require(sourceSystem.isNotBlank()) { "a connector needs a sourceSystem for provenance" }
    }

    fun supports(capability: Capability): Boolean = capability in capabilities
}

/** How a sync was asked for. */
enum class SyncMode {
    FULL,
    INCREMENTAL,
    WEBHOOK,
}

/**
 * @param since null for a full sync; otherwise the watermark the last successful run reported
 */
data class SyncRequest(
    val since: Instant? = null,
    val mode: SyncMode = if (since == null) SyncMode.FULL else SyncMode.INCREMENTAL,
)

/**
 * A node a connector is asserting.
 *
 * Carries no provenance of its own: the connector says *what* it saw and *when*, and the writer says
 * who reported it and in which run. Letting a connector construct its own provenance would let it
 * claim a source system that is not its own.
 *
 * @param observedAt when the source says this was true, which can be earlier than when we read it
 */
data class NodeUpsert(
    val type: String,
    val props: Map<String, Any?>,
    val observedAt: Instant? = null,
    val sourceId: String? = null,
    val confidence: Double = FULL_CONFIDENCE,
    val inferred: Boolean = false,
)

/**
 * An edge a connector is asserting, addressed by the keys of its ends.
 *
 * Both ends must already exist or arrive in the same delta; the writer applies nodes before edges
 * for that reason.
 */
data class EdgeUpsert(
    val type: String,
    val from: NodeKey,
    val to: NodeKey,
    val props: Map<String, Any?> = emptyMap(),
    val observedAt: Instant? = null,
    val confidence: Double = FULL_CONFIDENCE,
    val inferred: Boolean = false,
)

/**
 * One page of what a connector found.
 *
 * [tombstones] are facts the source no longer reports. They close a node's validity rather than
 * deleting it, because "this used to be true" is itself worth keeping - and because a connector
 * having a bad day should not be able to erase history.
 *
 * [watermark] is where the next incremental run should start. Null means the connector cannot say,
 * and the next run will be full.
 */
data class GraphDelta(
    val nodes: List<NodeUpsert> = emptyList(),
    val edges: List<EdgeUpsert> = emptyList(),
    val tombstones: List<NodeKey> = emptyList(),
    val watermark: Instant? = null,
)

/** A raw webhook, before anyone has decided whether to believe it. */
class WebhookEvent(
    val connector: String,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    // Not a data class: a generated equals over a ByteArray compares references, which is never what
    // is meant, and a connector matching on payload would be quietly wrong.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is WebhookEvent &&
                    connector == other.connector &&
                    headers == other.headers &&
                    body.contentEquals(other.body)
            )

    override fun hashCode(): Int = (connector.hashCode() * PRIME + headers.hashCode()) * PRIME + body.contentHashCode()

    private companion object {
        const val PRIME = 31
    }
}

enum class Health {
    UP,
    DOWN,
}

data class HealthStatus(
    val status: Health,
    val detail: String? = null,
) {
    companion object {
        fun up(detail: String? = null) = HealthStatus(Health.UP, detail)

        fun down(detail: String) = HealthStatus(Health.DOWN, detail)
    }
}

/**
 * What a connector can see, without ingesting it.
 *
 * Answering "these are the twelve accounts your credentials reach" before a first sync is how a
 * misconfigured scope is found in seconds rather than after a run that half-filled the graph.
 */
data class DiscoveryResult(
    val scopes: List<String> = emptyList(),
    val detail: Map<String, Any?> = emptyMap(),
)

/** Which account, subscription or project a cloud connector should look at. */
data class CloudScope(
    val id: String,
    val regions: List<String> = emptyList(),
)

/** Reported by the system of record itself, as opposed to guessed by a rule. */
const val FULL_CONFIDENCE = 1.0
