package com.repodatagraph.application

import com.repodatagraph.domain.exception.ImmutableIdentityException
import com.repodatagraph.domain.exception.NodeExistsException
import com.repodatagraph.domain.exception.NodeHasEdgesException
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.exception.NodeTypeNotFoundException
import com.repodatagraph.domain.exception.NodeValidationException
import com.repodatagraph.domain.identity.DerivedProperties
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.NodePage
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.ontology.NodeTypeDef
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.port.`in`.NodeUseCase
import com.repodatagraph.domain.port.out.GraphStore
import org.springframework.stereotype.Service

/**
 * Maintaining nodes of any registry type by hand.
 *
 * Two rules do the real work here. Identity is derived from the properties rather than accepted from
 * the caller, so the same real-world thing described twice lands on one node instead of two. And
 * because identity is derived, changing an identity property does not rename a node — it describes a
 * different thing — so an update that would move the key is refused rather than quietly performed.
 */
@Service
class NodeService(
    private val registry: OntologyRegistry,
    private val identityResolver: IdentityResolver,
    private val derivedProperties: DerivedProperties,
    private val validator: PropertyValidator,
    private val graphStore: GraphStore,
) : NodeUseCase {
    override fun create(
        type: String,
        props: Map<String, Any?>,
    ): GraphNode {
        val nodeType = declared(type)
        // Before validation, so the registry can require the identity properties honestly: a caller
        // supplies a Repository's remote and gets host, org and name filled in from it (#8).
        val expanded = derivedProperties.expand(type, props)
        validate(nodeType, expanded)

        val key = identityResolver.keyFor(type, expanded)
        graphStore.findNode(key)?.let { throw NodeExistsException(it.id) }

        return graphStore.upsertNode(GraphNode(key, expanded, Provenance.manual()))
    }

    override fun get(
        type: String,
        key: String,
    ): GraphNode? {
        declared(type)
        return graphStore.findNode(nodeKey(type, key))
    }

    override fun list(
        type: String,
        limit: Int,
        cursor: String?,
    ): NodePage {
        declared(type)
        // One more than asked for, so "is there another page" is answered without a second query
        // and without counting the whole label.
        val found = graphStore.findNodes(type, emptyMap(), cursor, limit + 1)
        val items = found.take(limit)
        return NodePage(
            items,
            nextCursor =
                items
                    .lastOrNull()
                    ?.key
                    ?.key
                    .takeIf { found.size > limit },
        )
    }

    override fun update(
        type: String,
        key: String,
        props: Map<String, Any?>,
    ): GraphNode {
        val nodeType = declared(type)
        val existingKey = nodeKey(type, key)
        val existing = graphStore.findNode(existingKey) ?: throw NodeNotFoundException(listOf(existingKey))

        val expanded = derivedProperties.expand(type, props)
        validate(nodeType, expanded)

        val derived = identityResolver.keyFor(type, expanded)
        if (derived != existingKey) {
            throw ImmutableIdentityException(identityPropertiesChanged(type, existing.props, expanded, existingKey))
        }

        return graphStore.upsertNode(GraphNode(existingKey, expanded, Provenance.manual()))
    }

    override fun delete(
        type: String,
        key: String,
        cascade: Boolean,
    ) {
        declared(type)
        val nodeKey = nodeKey(type, key)
        graphStore.findNode(nodeKey) ?: throw NodeNotFoundException(listOf(nodeKey))

        if (!cascade) {
            val edges = graphStore.countEdges(nodeKey)
            if (edges > 0) throw NodeHasEdgesException(edges.toInt())
        }

        graphStore.deleteNode(nodeKey, cascade)
    }

    private fun declared(type: String): NodeTypeDef = registry.nodeType(type) ?: throw NodeTypeNotFoundException(type)

    private fun validate(
        nodeType: NodeTypeDef,
        props: Map<String, Any?>,
    ) {
        val errors = validator.validate(nodeType, props)
        if (errors.isNotEmpty()) throw NodeValidationException(errors)
    }

    /**
     * Which submitted properties are the reason the key moved.
     *
     * Found by putting each changed property back on its own and asking whether the key returns,
     * rather than from a table of which properties feed which type's identity. A resolver that
     * starts consulting a different property therefore stays correctly reported here.
     */
    private fun identityPropertiesChanged(
        type: String,
        existing: Map<String, Any?>,
        submitted: Map<String, Any?>,
        existingKey: NodeKey,
    ): List<String> {
        val changed = submitted.keys.filter { submitted[it] != existing[it] }
        val responsible =
            changed.filter { field ->
                runCatching { identityResolver.keyFor(type, existing + (field to submitted[field])) }
                    .getOrNull() != existingKey
            }
        return responsible.ifEmpty { changed }
    }

    /** Accepts either a derived key or a full `Type:key` id, so a caller may use whichever it holds. */
    private fun nodeKey(
        type: String,
        keyOrId: String,
    ): NodeKey = NodeKey(type, keyOrId.removePrefix("$type:"))
}
