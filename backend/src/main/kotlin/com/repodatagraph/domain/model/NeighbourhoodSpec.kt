package com.repodatagraph.domain.model

import com.repodatagraph.domain.exception.InvalidQueryParameterException
import java.time.Instant

/**
 * What the graph view asks for (#9, FR1 and FR2): the nodes around [nodeId] within [depth] hops,
 * only of [nodeTypes] and along [edgeTypes] when those are given, walking edges in [direction].
 *
 * Bounded here, so no caller can ask for an unbounded walk: an unbounded traversal of a
 * well-connected graph happily returns everything, and a query that returns everything is a denial
 * of service rather than an answer. At most [limit] nodes are answered, the root among them.
 */
data class NeighbourhoodSpec(
    val nodeId: NodeKey,
    val depth: Int = DEFAULT_DEPTH,
    val nodeTypes: Set<String> = emptySet(),
    val edgeTypes: Set<String> = emptySet(),
    val direction: Direction = Direction.BOTH,
    val limit: Int = MAX_NODES,
) {
    init {
        if (depth !in MIN_DEPTH..MAX_DEPTH) {
            throw InvalidQueryParameterException("depth", "depth must be between $MIN_DEPTH and $MAX_DEPTH, was $depth")
        }
        require(limit in 1..MAX_NODES) { "limit must be between 1 and $MAX_NODES, was $limit" }
    }

    companion object {
        const val DEFAULT_DEPTH = 1
        const val MIN_DEPTH = 1
        const val MAX_DEPTH = 3

        /** FR2: the most nodes a neighbourhood answers, the root included. */
        const val MAX_NODES = 500

        /** Reads a spec from query parameters as given, each absent one taking its default. */
        fun of(
            nodeId: String?,
            depth: String?,
            nodeTypes: String?,
            edgeTypes: String?,
            direction: String?,
        ): NeighbourhoodSpec =
            NeighbourhoodSpec(
                nodeId = nodeIdParameter("nodeId", nodeId),
                depth =
                    depth?.let {
                        it.trim().toIntOrNull() ?: throw InvalidQueryParameterException("depth", "depth must be a whole number, was '$it'")
                    } ?: DEFAULT_DEPTH,
                nodeTypes = names(nodeTypes),
                edgeTypes = names(edgeTypes),
                direction = direction?.let(::direction) ?: Direction.BOTH,
            )

        /** `in`, `out` or `both`, in any case: the walk's direction as the API spells it. */
        fun direction(value: String): Direction =
            when (value.trim().lowercase()) {
                "in" -> Direction.INCOMING
                "out" -> Direction.OUTGOING
                "both" -> Direction.BOTH
                else -> throw InvalidQueryParameterException("direction", "direction must be in, out or both, was '$value'")
            }

        private fun names(value: String?): Set<String> =
            value
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toCollection(linkedSetOf())
                .orEmpty()
    }
}

/**
 * One hop of a neighbourhood walk, as the store takes it: from every node of a frontier, along
 * [edgeTypes] (any when empty) in [direction], to nodes of [nodeTypes] (any when empty), and when
 * [within] is given only to the nodes it names. At most [limit] rows.
 *
 * A context pack's template (#96) narrows a step further: [where] keeps only the edges whose
 * properties hold those values, and [asOf] reads the edges and far nodes that held at that instant,
 * each far node with the values it had then, rather than the current ones.
 */
data class NeighbourStep(
    val edgeTypes: Set<String>,
    val nodeTypes: Set<String>,
    val direction: Direction,
    val limit: Int,
    val within: Set<String>? = null,
    val where: Map<String, Any?> = emptyMap(),
    val asOf: Instant? = null,
)

/**
 * What one hop found: each edge with the node at its far end, [IncidentEdge.direction] read from the
 * frontier node it was found from. [truncated] says the step stopped at its limit.
 */
data class Neighbours(
    val hops: List<IncidentEdge>,
    val truncated: Boolean,
)

/** A node the walk reached, and how many hops from the root it first was. */
data class ReachedNode(
    val node: GraphNode,
    val distance: Int,
)

/** A node ready to draw: its label is the registry's displayProperty, or its key. */
data class SubgraphNodeView(
    val node: GraphNode,
    val label: String,
    val distance: Int,
)

/**
 * An edge ready to draw. [id] is `type:from>to`, the same every time it is answered, so a client
 * merging an expansion into what it has drawn can tell an edge it already has (FR3).
 */
data class SubgraphEdgeView(
    val id: String,
    val type: String,
    val inverse: String,
    val from: String,
    val to: String,
    val confidence: Double,
    val inferred: Boolean,
    /** The link rule an OWNS_RESOURCE rests on (#28), or null for an edge with none. */
    val rule: String? = null,
)

/**
 * A bounded neighbourhood (#9): the nodes nearest the root first, the edges between them, and whether
 * the cap or a step's limit cut it short - so a client can tell "nothing else is connected" from
 * "there was more than we would return".
 */
data class SubgraphView(
    val root: GraphNode,
    val nodes: List<SubgraphNodeView>,
    val edges: List<SubgraphEdgeView>,
    val truncated: Boolean,
)
