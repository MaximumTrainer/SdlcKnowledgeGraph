package com.repodatagraph.application.connector

import com.repodatagraph.domain.identity.DerivedProperties
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.connector.ConnectorDescriptor
import com.repodatagraph.domain.port.out.connector.GraphDelta
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/** What one page of a delta changed. */
data class DeltaResult(
    val nodesUpserted: Int = 0,
    val edgesUpserted: Int = 0,
    val tombstones: Int = 0,
) {
    operator fun plus(other: DeltaResult) =
        DeltaResult(
            nodesUpserted + other.nodesUpserted,
            edgesUpserted + other.edgesUpserted,
            tombstones + other.tombstones,
        )
}

/**
 * Writes what a connector found, stamping where it came from.
 *
 * Provenance is constructed here rather than accepted from the connector. A connector says *what* it
 * saw and *when*; who reported it and in which run is not its to claim, or a misbehaving connector
 * could attribute facts to another system.
 *
 * Nodes are written before edges because an edge needs both ends to exist, and a delta is allowed to
 * introduce a node and an edge to it in the same page.
 */
@Component
class GraphDeltaWriter(
    private val graphStore: GraphStore,
    private val identityResolver: IdentityResolver,
    private val derivedProperties: DerivedProperties,
    private val clock: Clock,
) {
    fun apply(
        delta: GraphDelta,
        descriptor: ConnectorDescriptor,
        syncRunId: String,
    ): DeltaResult {
        val now = Instant.now(clock)

        val nodes =
            delta.nodes.map { upsert ->
                val props = derivedProperties.expand(upsert.type, upsert.props)
                val key = identityResolver.keyFor(upsert.type, props)
                graphStore.upsertNode(
                    GraphNode(
                        key = key,
                        props = props,
                        provenance =
                            provenance(
                                descriptor = descriptor,
                                syncRunId = syncRunId,
                                now = now,
                                observedAt = upsert.observedAt,
                                sourceId = upsert.sourceId,
                                confidence = upsert.confidence,
                                inferred = upsert.inferred,
                            ),
                    ),
                )
                // The run that asserted a fact, as an edge as well as a property: "show me everything
                // that run wrote" is then one traversal rather than a scan over every node.
                linkToRun(syncRunId, key, descriptor, now)
                key
            }

        delta.edges.forEach { upsert ->
            graphStore.upsertEdge(
                GraphEdge(
                    type = upsert.type,
                    from = upsert.from,
                    to = upsert.to,
                    props = upsert.props,
                    provenance =
                        provenance(
                            descriptor = descriptor,
                            syncRunId = syncRunId,
                            now = now,
                            observedAt = upsert.observedAt,
                            sourceId = null,
                            confidence = upsert.confidence,
                            inferred = upsert.inferred,
                        ),
                ),
            )
        }

        val closed = delta.tombstones.count { close(it, now) }

        return DeltaResult(nodes.size, delta.edges.size, closed)
    }

    /**
     * Closes a fact rather than deleting it.
     *
     * "This used to be true" is itself worth keeping, and a connector having a bad day must not be
     * able to erase history: a source that stops reporting something is not the same as that thing
     * never having existed.
     */
    private fun close(
        key: NodeKey,
        now: Instant,
    ): Boolean {
        val existing = graphStore.findNode(key) ?: return false
        graphStore.upsertNode(existing.copy(provenance = existing.provenance.copy(validTo = now)))
        return true
    }

    private fun linkToRun(
        syncRunId: String,
        key: NodeKey,
        descriptor: ConnectorDescriptor,
        now: Instant,
    ) {
        graphStore.upsertEdge(
            GraphEdge(
                type = PRODUCED,
                from = NodeKey(SYNC_RUN, syncRunId),
                to = key,
                provenance = provenance(descriptor, syncRunId, now, null, null, FULL_CONFIDENCE, false),
            ),
        )
    }

    private fun provenance(
        descriptor: ConnectorDescriptor,
        syncRunId: String,
        now: Instant,
        observedAt: Instant?,
        sourceId: String?,
        confidence: Double,
        inferred: Boolean,
    ) = Provenance(
        sourceSystem = descriptor.sourceSystem,
        sourceId = sourceId,
        ingestedAt = now,
        observedAt = observedAt ?: now,
        confidence = confidence,
        inferred = inferred,
        validFrom = now,
        syncRunId = syncRunId,
    )

    private companion object {
        const val PRODUCED = "PRODUCED"
        const val SYNC_RUN = "SyncRun"
        const val FULL_CONFIDENCE = 1.0
    }
}
