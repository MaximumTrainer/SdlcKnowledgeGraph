package com.repodatagraph.application.connector

import com.repodatagraph.application.IdentityFolding
import com.repodatagraph.domain.identity.DerivedProperties
import com.repodatagraph.domain.lifecycle.RetiredReason
import com.repodatagraph.domain.lifecycle.TombstoneRules
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.port.out.FactLifecycle
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.connector.ConnectorDescriptor
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.observability.LogEvents
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/**
 * What one page of a delta changed, or a whole run once its pages are added up.
 *
 * [nodesUpserted] and [edgesUpserted] count every fact asserted, as they always have. [written] and
 * [unchanged] split the same facts by whether the graph moved (#86, FR-6): a fact stated again exactly
 * as it is held is still asserted, and is unchanged. [failed] counts what could not be read or
 * written, which a writer never sets; the run adds it.
 */
data class DeltaResult(
    val nodesUpserted: Int = 0,
    val edgesUpserted: Int = 0,
    val tombstones: Int = 0,
    val written: Int = 0,
    val unchanged: Int = 0,
    val failed: Int = 0,
) {
    operator fun plus(other: DeltaResult) =
        DeltaResult(
            nodesUpserted + other.nodesUpserted,
            edgesUpserted + other.edgesUpserted,
            tombstones + other.tombstones,
            written + other.written,
            unchanged + other.unchanged,
            failed + other.failed,
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
    private val factLifecycle: FactLifecycle,
    private val identityResolver: IdentityResolver,
    private val derivedProperties: DerivedProperties,
    private val clock: Clock,
    private val registry: OntologyRegistry,
    /** Offered every node written, to fold a digest-less artifact into its digest's node (#98, FR-2). */
    private val folding: IdentityFolding = IdentityFolding.NONE,
) {
    fun apply(
        delta: GraphDelta,
        descriptor: ConnectorDescriptor,
        syncRunId: String,
    ): DeltaResult {
        val now = Instant.now(clock)

        val nodesUnchanged =
            delta.nodes.count { upsert ->
                val props = derivedProperties.expand(upsert.type, upsert.props)
                val key = identityResolver.keyFor(upsert.type, props)
                val node =
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
                    )
                // Asked before the write, which is still made: the source stating a fact again is
                // what keeps it fresh (#93) and out of reconciliation's way (#150).
                val unchanged = unchangedNode(node)
                write(node)
                // The run that asserted a fact, as an edge as well as a property: "show me everything
                // that run wrote" is then one traversal rather than a scan over every node.
                linkToRun(syncRunId, key, descriptor, now)
                unchanged
            }

        val edgesUnchanged =
            delta.edges.count { upsert ->
                val edge =
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
                                sourceId = upsert.sourceId,
                                confidence = upsert.confidence,
                                inferred = upsert.inferred,
                            ),
                    )
                val unchanged = unchangedEdge(edge)
                graphStore.upsertEdge(edge)
                unchanged
            }

        val closed = delta.tombstones.count { close(it, now) }
        val unchanged = nodesUnchanged + edgesUnchanged

        return DeltaResult(
            nodesUpserted = delta.nodes.size,
            edgesUpserted = delta.edges.size,
            tombstones = closed,
            written = delta.nodes.size + delta.edges.size - unchanged,
            unchanged = unchanged,
        )
    }

    /** Whether the graph already holds [node] as it is about to be written (#86, FR-6). */
    private fun unchangedNode(node: GraphNode): Boolean {
        val declared = registry.nodeType(node.type)?.properties?.map { it.name }
        val held = declared?.let { graphStore.findNode(node.key) }
        return held != null && sameFact(held.props, held.provenance, node.props, node.provenance, declared)
    }

    private fun unchangedEdge(edge: GraphEdge): Boolean {
        val declared = registry.edgeType(edge.type)?.properties?.map { it.name }
        val held = declared?.let { graphStore.findEdge(edge.type, edge.from, edge.to) }
        return held != null && sameFact(held.props, held.provenance, edge.props, edge.provenance, declared)
    }

    /**
     * Writes a node where its alias says it already is (#88). A repository reported under a new remote
     * with the provider id a node holds is that node renamed, so the node moves and keeps its edges.
     *
     * When another node already holds the new remote there are two nodes for one repository, which is
     * a merge for the review queue to propose (#74) rather than something a sync decides: the reported
     * node is written without the alias, so the constraint holds and the run does not fail, and the
     * node holding the alias keeps it.
     */
    private fun write(node: GraphNode) {
        val nodeType = registry.nodeType(node.type)
        val alias = nodeType?.let { identityResolver.aliasFor(it, node.props) }
        val holder = alias?.let { graphStore.findNodeByAlias(node.type, it) }
        val written =
            when {
                holder == null || holder.key == node.key -> node.also { graphStore.upsertNode(it) }
                graphStore.findNode(node.key) != null -> node.copy(props = node.props - alias.keys).also { graphStore.upsertNode(it) }
                else -> {
                    val provenance = node.provenance.afterRename(holder.provenance, holder.key.key, node.key.key)
                    graphStore.renameNode(holder.key, node.copy(provenance = provenance))
                    LogEvents.nodeRenamed(node.type, holder.key.key, node.key.key)
                    node.copy(provenance = provenance)
                }
            }
        folding.afterWrite(written)
    }

    /**
     * Retires what [descriptor]'s source asserted before a complete run began and did not report in
     * it (#150), by the connector's own [rules] (#33, FR3): not at all when it ignores what it stops
     * reporting, and only what it last stated before its grace period otherwise. Retired, never
     * deleted, for the same reason a tombstone is.
     *
     * @return how many facts were retired
     */
    fun reconcile(
        descriptor: ConnectorDescriptor,
        runStartedAt: Instant,
        rules: TombstoneRules = TombstoneRules(),
    ): Int {
        val statedBefore = rules.retireStatedBefore(runStartedAt) ?: return 0
        return factLifecycle.closeNodesNotReasserted(
            descriptor.sourceSystem,
            statedBefore,
            Instant.now(clock),
            RetiredReason.MISSING_FROM_SYNC,
        )
    }

    /**
     * Retires a fact rather than deleting it, with its current relationships (#33, FR4).
     *
     * "This used to be true" is itself worth keeping, and a connector having a bad day must not be
     * able to erase history: a source that stops reporting something is not the same as that thing
     * never having existed.
     */
    private fun close(
        key: NodeKey,
        now: Instant,
    ): Boolean = factLifecycle.retire(key, now, RetiredReason.SOURCE_DELETED)

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
