package com.repodatagraph.domain.exception

import com.repodatagraph.domain.model.NodeKey

/**
 * One or both ends of a relationship do not exist.
 *
 * Writing an edge to a missing node used to be a silent no-op: the MATCH found nothing, the MERGE
 * never ran, and the caller was told everything was fine. Naming the missing side turns a data
 * quality problem into an error the caller can act on.
 */
class NodeNotFoundException(
    val missing: List<NodeKey>,
) : RuntimeException("node not found: ${missing.joinToString { it.id }}")

/**
 * A node or edge type that the ontology does not declare.
 *
 * Labels and relationship types cannot be parameterised in Cypher, so they are interpolated. This
 * exception is what guarantees only registry-declared names ever reach that interpolation.
 */
class UnknownNodeTypeException(
    val type: String,
) : RuntimeException("unknown node type '$type'")

class UnknownEdgeTypeException(
    val type: String,
) : RuntimeException("unknown edge type '$type'")

/** An edge whose endpoints are not the types the ontology allows for it. */
class InvalidEdgeException(
    message: String,
) : RuntimeException(message)
