package com.repodatagraph.domain.port.`in`

import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodePage
import com.repodatagraph.domain.model.Provenance
import java.time.Instant

/**
 * Maintaining nodes of any registry type by hand.
 *
 * There is one use case rather than one per type because the rules are the same for all of them:
 * validate against the registry, derive the identity rather than accept it, and record who said so.
 * A type added to the ontology becomes editable without a new port, adapter or screen.
 *
 * Throughout, `key` accepts either a derived key or a full `Type:key` id, and `sourceSystem` is the
 * system of record a write states its facts as (#117): `manual` unless the caller names another.
 */
interface NodeUseCase {
    /**
     * @throws com.repodatagraph.domain.exception.NodeTypeNotFoundException if the type is undeclared
     * @throws com.repodatagraph.domain.exception.NodeValidationException if the properties do not satisfy the registry
     * @throws com.repodatagraph.domain.exception.NodeExistsException if another node already holds the derived key
     * @throws com.repodatagraph.domain.exception.UnknownSourceSystemException if the registry does not declare the source
     * @throws com.repodatagraph.domain.exception.SourceNotPermittedException if the principal may not write as the source
     */
    fun create(
        type: String,
        props: Map<String, Any?>,
        sourceSystem: String = Provenance.MANUAL,
    ): GraphNode

    /**
     * The node, or null. With [asOf] (#93, FR-4), the node as the graph held it then: null unless its
     * validity contains the instant. Without, the current view, which includes a closed node.
     */
    fun get(
        type: String,
        key: String,
        asOf: Instant? = null,
    ): GraphNode?

    fun list(
        type: String,
        limit: Int,
        cursor: String?,
    ): NodePage

    /**
     * @throws com.repodatagraph.domain.exception.NodeNotFoundException if there is no such node
     * @throws com.repodatagraph.domain.exception.ImmutableIdentityException if the properties derive to a different key
     * @throws com.repodatagraph.domain.exception.UnknownSourceSystemException if the registry does not declare the source
     * @throws com.repodatagraph.domain.exception.SourceNotPermittedException if the principal may not write as the source
     * @throws com.repodatagraph.domain.exception.NodeValidationException if [validTo] is before the node began
     *
     * [validTo] (#93, FR-5) closes the node at that instant, or keeps it closed; left out, the write
     * states the node holds now. Either way a node keeps the validFrom it began with while it holds.
     */
    fun update(
        type: String,
        key: String,
        props: Map<String, Any?>,
        sourceSystem: String = Provenance.MANUAL,
        validTo: Instant? = null,
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
