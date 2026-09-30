package com.repodatagraph.domain.model

/**
 * A node as the store sees it: an address, a bag of properties described by the ontology, and the
 * provenance of the statement that it exists.
 */
data class GraphNode(
    val key: NodeKey,
    val props: Map<String, Any?> = emptyMap(),
    val provenance: Provenance,
) {
    val id: String get() = key.id
    val type: String get() = key.type
}

/**
 * A relationship. The inverse name is not stored: it comes from the registry, so "what depends on
 * this" and "what does this depend on" are one edge read in two directions.
 */
data class GraphEdge(
    val type: String,
    val from: NodeKey,
    val to: NodeKey,
    val props: Map<String, Any?> = emptyMap(),
    val provenance: Provenance,
)

/** Which way to walk an edge when exploring a neighbourhood. */
enum class Direction {
    OUTGOING,
    INCOMING,
    BOTH,
}

/** What a caller asks for when stating a relationship. Ids may be a full `Type:key` or a bare key. */
data class EdgeRequest(
    val type: String,
    val fromId: String,
    val toId: String,
    val props: Map<String, Any?> = emptyMap(),
    /** The system of record the relationship is stated as (#117); `manual` when the caller names none. */
    val sourceSystem: String = Provenance.MANUAL,
)

/**
 * The result of stating a relationship. [created] is false when the edge was already there, which is
 * what lets the API answer 201 the first time and 200 afterwards without the caller having to ask.
 */
data class EdgeWrite(
    val edge: GraphEdge,
    val inverse: String,
    val created: Boolean,
)

/** An edge as one node sees it: which way it points from here, and what is at the other end. */
data class IncidentEdge(
    val edge: GraphEdge,
    val direction: Direction,
    val other: GraphNode,
)

/**
 * An edge rendered for one end of it.
 *
 * [displayName] is the edge's own name when it points away from the node being viewed and the
 * declared inverse when it points at it, so a Team sees `OWNS` where a Repository sees `OWNED_BY`.
 * One stored relationship, two readings; nothing is stored twice, so the two can never disagree.
 */
data class EdgeView(
    val type: String,
    val inverse: String,
    val direction: Direction,
    val displayName: String,
    val other: GraphNode,
    val props: Map<String, Any?>,
    val provenance: Provenance,
)
