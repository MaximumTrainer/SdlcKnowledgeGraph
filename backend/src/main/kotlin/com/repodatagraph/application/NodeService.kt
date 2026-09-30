package com.repodatagraph.application

import com.repodatagraph.domain.exception.ImmutableIdentityException
import com.repodatagraph.domain.exception.ManagedNodeTypeException
import com.repodatagraph.domain.exception.NodeExistsException
import com.repodatagraph.domain.exception.NodeHasEdgesException
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.exception.NodeTypeNotFoundException
import com.repodatagraph.domain.exception.NodeValidationException
import com.repodatagraph.domain.identity.DerivedProperties
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.NodePage
import com.repodatagraph.domain.model.ServicePrincipal
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.ontology.NodeTypeDef
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.port.`in`.NodeUseCase
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.observability.GraphWriteMetrics
import com.repodatagraph.observability.LogEvents
import com.repodatagraph.observability.WriteOutcome
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
@Suppress("TooManyFunctions") // One per node operation, plus the rename a provider id allows (#88).
class NodeService(
    private val registry: OntologyRegistry,
    private val identityResolver: IdentityResolver,
    private val derivedProperties: DerivedProperties,
    private val validator: PropertyValidator,
    private val graphStore: GraphStore,
    private val metrics: GraphWriteMetrics,
    private val statedProvenance: StatedProvenance,
) : NodeUseCase {
    override fun create(
        type: String,
        props: Map<String, Any?>,
        sourceSystem: String,
    ): GraphNode {
        val nodeType = writable(type)
        // Whether the principal may state this at all, before anything else is looked at (#117).
        val provenance = statedProvenance.forWrite(sourceSystem)
        // Before validation, so the registry can require the identity properties honestly: a caller
        // supplies a Repository's remote and gets host, org and name filled in from it (#8).
        val expanded = derivedProperties.expand(type, props)
        validate(nodeType, expanded)

        val key = identityResolver.keyFor(type, expanded)
        // The alias first (#88): a repository already known by its provider id is the same repository,
        // whatever remote it is created under now.
        identityResolver.aliasFor(nodeType, expanded)?.let { alias ->
            graphStore.findNodeByAlias(type, alias)?.let { throw NodeExistsException(it.id, alias) }
        }
        graphStore.findNode(key)?.let { throw NodeExistsException(it.id) }

        return graphStore.upsertNode(GraphNode(key, expanded, provenance)).also {
            LogEvents.nodeCreated(type, key.key, props.keys.sorted())
            metrics.node(type, WriteOutcome.CREATED)
        }
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
        sourceSystem: String,
    ): GraphNode {
        val nodeType = writable(type)
        val provenance = statedProvenance.forWrite(sourceSystem)
        val addressed = nodeKey(type, key)
        val expanded = derivedProperties.expand(type, props)
        validate(nodeType, expanded)

        // The alias is consulted before the key (#88), so a writer holding a repository's provider id
        // and its new remote finds the node that has the id, wherever the path pointed.
        val alias = identityResolver.aliasFor(nodeType, expanded)
        val holder = alias?.let { graphStore.findNodeByAlias(type, it) }
        val existing = graphStore.findNode(addressed) ?: holder ?: throw NodeNotFoundException(listOf(addressed))
        if (holder != null && holder.key != existing.key) throw NodeExistsException(holder.id, alias)

        val stored = identityResolver.aliasFor(nodeType, existing.props)
        if (alias != null && stored != null && alias != stored) {
            throw ImmutableIdentityException(alias.keys.filter { alias[it] != stored[it] })
        }

        val derived = identityResolver.keyFor(type, expanded)
        if (derived == existing.key) {
            return graphStore.upsertNode(GraphNode(existing.key, expanded, provenance)).also {
                metrics.node(type, WriteOutcome.UPDATED)
            }
        }
        // A changed key renames the node only when the write carries the alias the node already
        // holds: that is the provider saying it is the same repository. Claiming an alias and moving
        // in one write would let any writer take any node, so without it the change is refused.
        if (alias == null || alias != stored) {
            throw ImmutableIdentityException(identityPropertiesChanged(type, existing.props, expanded, existing.key))
        }
        return rename(existing, GraphNode(derived, expanded, provenance.afterRename(existing.provenance, existing.key.key, derived.key)))
    }

    /** Moves [existing] to [renamed]'s key, unless another node holds that key already (#88). */
    private fun rename(
        existing: GraphNode,
        renamed: GraphNode,
    ): GraphNode {
        // Two nodes for one repository is a merge, which is the review queue's to propose (#74).
        graphStore.findNode(renamed.key)?.let { throw NodeExistsException(it.id) }
        return graphStore.renameNode(existing.key, renamed).also {
            LogEvents.nodeRenamed(renamed.type, existing.key.key, renamed.key.key)
            metrics.node(renamed.type, WriteOutcome.UPDATED)
        }
    }

    override fun delete(
        type: String,
        key: String,
        cascade: Boolean,
    ) {
        writable(type)
        val nodeKey = nodeKey(type, key)
        graphStore.findNode(nodeKey) ?: throw NodeNotFoundException(listOf(nodeKey))

        if (!cascade) {
            val edges = graphStore.countEdges(nodeKey)
            if (edges > 0) throw NodeHasEdgesException(edges.toInt())
        }

        if (graphStore.deleteNode(nodeKey, cascade)) metrics.node(type, WriteOutcome.DELETED)
    }

    private fun declared(type: String): NodeTypeDef = registry.nodeType(type) ?: throw NodeTypeNotFoundException(type)

    /**
     * A declared type this API may write. A ServicePrincipal may not be: only a user may register one,
     * and only against a team the graph holds, which is its own API's rule (#115). Reading one here is
     * fine; it is a node like any other.
     */
    private fun writable(type: String): NodeTypeDef {
        val nodeType = declared(type)
        if (type == ServicePrincipal.NODE_TYPE) throw ManagedNodeTypeException(type, ServicePrincipal.API_PATH)
        return nodeType
    }

    private fun validate(
        nodeType: NodeTypeDef,
        props: Map<String, Any?>,
    ) {
        val errors = validator.validate(nodeType, props)
        if (errors.isNotEmpty()) {
            LogEvents.nodeRejected(nodeType.name, errors.map { it.field }.distinct().sorted())
            metrics.node(nodeType.name, WriteOutcome.REJECTED)
            throw NodeValidationException(errors)
        }
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
