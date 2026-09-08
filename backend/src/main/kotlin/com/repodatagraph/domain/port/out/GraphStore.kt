package com.repodatagraph.domain.port.out

import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NeighbourhoodSpec
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Subgraph

/**
 * The single way in and out of the graph.
 *
 * One store, driven by the ontology registry, replaces a repository interface per type and Cypher
 * written per query. That is what makes identity, provenance and endpoint validation properties of
 * the system instead of things each query has to remember, and it is why adding a node type no
 * longer means adding a class and a set of queries.
 */
interface GraphStore {
    /**
     * Creates or updates a node, addressed by its derived key. Re-stating a known fact is therefore
     * idempotent rather than duplicating it.
     *
     * @throws com.repodatagraph.domain.exception.UnknownNodeTypeException if the type is not declared
     */
    fun upsertNode(node: GraphNode): GraphNode

    /**
     * Creates or updates a relationship between two existing nodes.
     *
     * @throws com.repodatagraph.domain.exception.NodeNotFoundException if either end is missing
     * @throws com.repodatagraph.domain.exception.UnknownEdgeTypeException if the type is not declared
     * @throws com.repodatagraph.domain.exception.InvalidEdgeException if the ontology does not allow
     *   this relationship between these two types
     */
    fun upsertEdge(edge: GraphEdge): GraphEdge

    fun findNode(key: NodeKey): GraphNode?

    /**
     * Nodes of a type, always in key order.
     *
     * [afterKey] and [limit] page by key rather than by offset, so a page stays stable while other
     * nodes are being written: an offset would silently skip or repeat rows as the set shifts.
     */
    fun findNodes(
        type: String,
        filter: Map<String, Any?> = emptyMap(),
        afterKey: String? = null,
        limit: Int? = null,
    ): List<GraphNode>

    /**
     * How many relationships are attached to a node, in either direction.
     *
     * Deleting is refused while this is non-zero unless the caller asks to cascade, and the count is
     * reported so the refusal says how much would have been destroyed.
     */
    fun countEdges(key: NodeKey): Long

    /**
     * Removes a node. Without [cascade] a node that still has relationships is left alone and this
     * returns false, so deleting something cannot quietly destroy the edges that referenced it.
     */
    fun deleteNode(
        key: NodeKey,
        cascade: Boolean = false,
    ): Boolean

    fun deleteEdge(
        type: String,
        from: NodeKey,
        to: NodeKey,
    ): Boolean

    /** Explores outward from a node, bounded by depth and result size. */
    fun neighbourhood(
        key: NodeKey,
        spec: NeighbourhoodSpec = NeighbourhoodSpec(),
    ): Subgraph
}
