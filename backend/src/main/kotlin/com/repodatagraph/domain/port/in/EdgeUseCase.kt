package com.repodatagraph.domain.port.`in`

import com.repodatagraph.domain.model.Direction
import com.repodatagraph.domain.model.EdgeRequest
import com.repodatagraph.domain.model.EdgeView
import com.repodatagraph.domain.model.EdgeWrite

/**
 * Stating and removing relationships of any registry type.
 *
 * One use case rather than one per edge type, for the same reason there is one node use case: the
 * rules are declared in the ontology, so a relationship added there needs no new port or adapter.
 */
interface EdgeUseCase {
    /**
     * @throws com.repodatagraph.domain.exception.UnknownEdgeTypeException if the type is undeclared
     * @throws com.repodatagraph.domain.exception.EdgeNotAllowedException if the ontology forbids the pair
     * @throws com.repodatagraph.domain.exception.SelfEdgeException if both ends are the same node
     * @throws com.repodatagraph.domain.exception.EdgeValidationException if the properties do not satisfy the registry
     * @throws com.repodatagraph.domain.exception.NodeNotFoundException if either end does not exist
     */
    fun create(request: EdgeRequest): EdgeWrite

    /** False when there was no such edge; deleting is by exact triple, as edges have no id. */
    fun delete(
        type: String,
        fromId: String,
        toId: String,
    ): Boolean

    /** Every edge touching a node, each rendered under the name this end sees. */
    fun forNode(
        type: String,
        key: String,
        direction: Direction,
        edgeType: String?,
    ): List<EdgeView>
}
