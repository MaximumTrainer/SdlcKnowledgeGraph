package com.repodatagraph.domain.model

/** One way ownership reaches a node: the steps inherited along, ending in the edge that names [team]. */
data class OwnerPath(
    val team: GraphNode,
    val via: List<PathStep>,
)

/** A team that owns a node, the path that says so, and that path's confidence. */
data class Owner(
    val team: GraphNode,
    val via: List<PathStep>,
    val confidence: Double,
)

/** Who owns a node (#21, FR6). No owners is an answer, not an error. */
data class OwnersResult(
    val node: GraphNode,
    val owners: List<Owner>,
)
