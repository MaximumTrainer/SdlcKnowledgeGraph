package com.repodatagraph.domain.port.out

import com.repodatagraph.domain.model.Direction
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.IncidentEdge
import com.repodatagraph.domain.model.NeighbourStep
import com.repodatagraph.domain.model.Neighbours
import com.repodatagraph.domain.model.NodeKey

/**
 * The single way in and out of the graph.
 *
 * One store, driven by the ontology registry, replaces a repository interface per type and Cypher
 * written per query. That is what makes identity, provenance and endpoint validation properties of
 * the system instead of things each query has to remember, and it is why adding a node type no
 * longer means adding a class and a set of queries.
 */
@Suppress("TooManyFunctions") // One per graph operation; each adapter implements them all.
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
     * The node of [type] whose alias properties hold exactly [alias] (#88), such as a Repository's
     * provider and provider id, or null. A constraint keeps an alias unique, so there is at most one.
     */
    fun findNodeByAlias(
        type: String,
        alias: Map<String, Any?>,
    ): GraphNode?

    /**
     * The node of [key]'s type that was known by [key] before a rename (#88), or null. Several nodes
     * may once have had one key; the one of them that left it last is not tracked, so the first in
     * key order answers.
     */
    fun findNodeByPreviousKey(key: NodeKey): GraphNode?

    /**
     * Moves the node at [from] to [node]'s key, with [node]'s properties and provenance, keeping its
     * relationships: the one write in which a node's key changes (#88). [node]'s provenance carries the
     * keys it had, which this stores as they are; any other write leaves them where they are.
     *
     * @return the node as stored
     * @throws com.repodatagraph.domain.exception.NodeNotFoundException if nothing is at [from]
     */
    fun renameNode(
        from: NodeKey,
        node: GraphNode,
    ): GraphNode

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

    /** The edge with this exact triple, or null. Edges have no id, so the triple is the address. */
    fun findEdge(
        type: String,
        from: NodeKey,
        to: NodeKey,
    ): GraphEdge?

    /**
     * Every edge touching a node, with the node at the other end.
     *
     * The other end comes back with the edge because a caller listing relationships always needs
     * something to show for the far side, and fetching them one at a time would be a query per row.
     */
    fun findEdges(
        key: NodeKey,
        direction: Direction = Direction.BOTH,
        edgeType: String? = null,
    ): List<IncidentEdge>

    /**
     * One hop out from every node of [frontier] (#9): each current edge the [step] allows, with the
     * node at its far end, closed facts skipped, at most [NeighbourStep.limit] of them in a stable
     * order. The walk itself - how far, and when to stop - is the caller's.
     */
    fun neighbourhood(
        frontier: Collection<NodeKey>,
        step: NeighbourStep,
    ): Neighbours
}
