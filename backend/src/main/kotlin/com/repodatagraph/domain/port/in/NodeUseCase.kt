package com.repodatagraph.domain.port.`in`

import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodePage

/**
 * Maintaining nodes of any registry type by hand.
 *
 * There is one use case rather than one per type because the rules are the same for all of them:
 * validate against the registry, derive the identity rather than accept it, and record who said so.
 * A type added to the ontology becomes editable without a new port, adapter or screen.
 *
 * Throughout, `key` accepts either a derived key or a full `Type:key` id.
 */
interface NodeUseCase {
    /**
     * @throws com.repodatagraph.domain.exception.NodeTypeNotFoundException if the type is undeclared
     * @throws com.repodatagraph.domain.exception.NodeValidationException if the properties do not satisfy the registry
     * @throws com.repodatagraph.domain.exception.NodeExistsException if another node already holds the derived key
     */
    fun create(
        type: String,
        props: Map<String, Any?>,
    ): GraphNode

    fun get(
        type: String,
        key: String,
    ): GraphNode?

    fun list(
        type: String,
        limit: Int,
        cursor: String?,
    ): NodePage

    /**
     * @throws com.repodatagraph.domain.exception.NodeNotFoundException if there is no such node
     * @throws com.repodatagraph.domain.exception.ImmutableIdentityException if the properties derive to a different key
     */
    fun update(
        type: String,
        key: String,
        props: Map<String, Any?>,
    ): GraphNode

    /**
     * @throws com.repodatagraph.domain.exception.NodeHasEdgesException if edges remain and [cascade] is false
     */
    fun delete(
        type: String,
        key: String,
        cascade: Boolean,
    )
}
