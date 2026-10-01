package com.repodatagraph.application.links

import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.model.Direction
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.IncidentEdge
import com.repodatagraph.domain.model.NeighbourStep
import com.repodatagraph.domain.model.Neighbours
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.port.out.GraphStore
import java.time.Instant

/**
 * Just enough of [GraphStore] for the link engine's tests, with the semantics of the Neo4j store
 * the engine relies on: an edge is addressed by its triple, a write merges its properties into the
 * edge's (a null removes one), and a fact that stays current, or that a write closes, keeps the
 * validFrom it began with (#93). Everything the engine never calls fails loudly.
 */
@Suppress("TooManyFunctions")
class InMemoryGraphStore : GraphStore {
    val nodes = linkedMapOf<NodeKey, GraphNode>()
    private val edges = linkedMapOf<Triple<String, NodeKey, NodeKey>, GraphEdge>()

    /** How many times an edge was written, so a test can tell a run that wrote nothing. */
    var edgeWrites = 0
        private set

    fun allEdges(type: String? = null): List<GraphEdge> = edges.values.filter { type == null || it.type == type }

    fun edge(
        type: String,
        from: NodeKey,
        to: NodeKey,
    ): GraphEdge? = edges[Triple(type, from, to)]

    override fun upsertNode(node: GraphNode): GraphNode {
        nodes[node.key] = node
        return node
    }

    override fun upsertEdge(edge: GraphEdge): GraphEdge {
        if (edge.from !in nodes || edge.to !in nodes) throw NodeNotFoundException(listOf(edge.from, edge.to).filter { it !in nodes })
        val address = Triple(edge.type, edge.from, edge.to)
        val before = edges[address]
        val props = (before?.props.orEmpty() + edge.props).filterValues { it != null }
        val keepsBegan = before != null && (before.provenance.current || edge.provenance.validTo != null)
        val provenance = if (keepsBegan) edge.provenance.copy(validFrom = before!!.provenance.validFrom) else edge.provenance
        val stored = edge.copy(props = props, provenance = provenance)
        edges[address] = stored
        edgeWrites++
        return stored
    }

    override fun findNode(key: NodeKey): GraphNode? = nodes[key]

    override fun findNode(
        key: NodeKey,
        asOf: Instant,
    ): GraphNode? = unsupported()

    override fun findNodeByAlias(
        type: String,
        alias: Map<String, Any?>,
    ): GraphNode? = unsupported()

    override fun findNodeByPreviousKey(key: NodeKey): GraphNode? = null

    override fun mergedInto(key: NodeKey): NodeKey? = null

    override fun renameNode(
        from: NodeKey,
        node: GraphNode,
    ): GraphNode = unsupported()

    override fun findNodes(
        type: String,
        filter: Map<String, Any?>,
        afterKey: String?,
        limit: Int?,
    ): List<GraphNode> =
        nodes.values
            .filter { it.type == type }
            .filter { node -> filter.all { (name, value) -> node.props[name] == value } }
            .filter { afterKey == null || it.key.key > afterKey }
            .sortedBy { it.key.key }
            .let { if (limit == null) it else it.take(limit) }

    override fun countEdges(key: NodeKey): Long = edges.values.count { it.from == key || it.to == key }.toLong()

    override fun deleteNode(
        key: NodeKey,
        cascade: Boolean,
    ): Boolean = unsupported()

    override fun deleteEdge(
        type: String,
        from: NodeKey,
        to: NodeKey,
    ): Boolean = edges.remove(Triple(type, from, to)) != null

    override fun findEdge(
        type: String,
        from: NodeKey,
        to: NodeKey,
    ): GraphEdge? = edges[Triple(type, from, to)]

    override fun findEdges(
        key: NodeKey,
        direction: Direction,
        edgeType: String?,
    ): List<IncidentEdge> {
        val typed = edges.values.filter { edgeType == null || it.type == edgeType }
        val outgoing =
            if (direction == Direction.INCOMING) {
                emptyList()
            } else {
                typed.filter { it.from == key }.map { IncidentEdge(it, Direction.OUTGOING, nodes.getValue(it.to)) }
            }
        val incoming =
            if (direction == Direction.OUTGOING) {
                emptyList()
            } else {
                typed.filter { it.to == key }.map { IncidentEdge(it, Direction.INCOMING, nodes.getValue(it.from)) }
            }
        return outgoing + incoming
    }

    override fun findEdges(
        key: NodeKey,
        direction: Direction,
        edgeType: String?,
        asOf: Instant,
    ): List<IncidentEdge> = unsupported()

    override fun neighbourhood(
        frontier: Collection<NodeKey>,
        step: NeighbourStep,
    ): Neighbours = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("not used by the link engine")
}
