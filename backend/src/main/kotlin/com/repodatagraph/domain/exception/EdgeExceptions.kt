package com.repodatagraph.domain.exception

/**
 * The ontology does not allow this relationship between these two node types.
 *
 * The allowed pairs travel with the refusal because the useful next action is almost always to pick
 * a different edge type or a different end, and a caller cannot do either from "no".
 */
class EdgeNotAllowedException(
    val type: String,
    val from: String,
    val to: String,
    val allowed: List<Pair<String, String>>,
) : RuntimeException("$type is not allowed from $from to $to")

/**
 * Both ends of the relationship are the same node.
 *
 * No edge type in v1 declares itself reflexive, and a node that depends on itself is a data entry
 * mistake rather than a fact, so it is refused rather than stored and puzzled over later.
 */
class SelfEdgeException(
    val nodeId: String,
) : RuntimeException("an edge cannot join $nodeId to itself")

/** The submitted edge properties do not satisfy the registry. Every problem is reported, not the first. */
class EdgeValidationException(
    val errors: List<PropertyError>,
) : RuntimeException("invalid edge: " + errors.joinToString { "${it.field} ${it.message}" })
