package com.repodatagraph.application

import com.repodatagraph.domain.exception.EdgeNotAllowedException
import com.repodatagraph.domain.exception.EdgeValidationException
import com.repodatagraph.domain.exception.SelfEdgeException
import com.repodatagraph.domain.exception.UnknownEdgeTypeException
import com.repodatagraph.domain.model.Direction
import com.repodatagraph.domain.model.EdgeRequest
import com.repodatagraph.domain.model.EdgeView
import com.repodatagraph.domain.model.EdgeWrite
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.ontology.EdgeTypeDef
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.port.`in`.EdgeUseCase
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.observability.GraphWriteMetrics
import com.repodatagraph.observability.LogEvents
import com.repodatagraph.observability.WriteOutcome
import org.springframework.stereotype.Service

/**
 * Stating and reading relationships of any registry type.
 *
 * A relationship is stored once and read from both ends. What differs between the two readings is
 * only the name: the edge's own name looking outward, the declared inverse looking back. Storing it
 * twice would let the two disagree, which is the whole reason the ontology declares an inverse
 * rather than expecting someone to write both.
 */
@Service
class EdgeService(
    private val registry: OntologyRegistry,
    private val validator: PropertyValidator,
    private val graphStore: GraphStore,
    private val metrics: GraphWriteMetrics,
    private val statedProvenance: StatedProvenance,
) : EdgeUseCase {
    override fun create(request: EdgeRequest): EdgeWrite {
        val edgeType = declared(request.type)
        // Whether the principal may state this at all, before anything else is looked at (#117).
        val provenance = statedProvenance.forWrite(request.sourceSystem)
        val from = NodeKey.parse(request.fromId)
        val to = NodeKey.parse(request.toId)

        if (from == to) refused(edgeType, SelfEdgeException(from.id))
        requireAllowed(edgeType, from.type, to.type)

        val errors = validator.validate(edgeType.properties, request.props)
        if (errors.isNotEmpty()) refused(edgeType, EdgeValidationException(errors))

        // Asking first is what lets the API say 201 or 200 honestly. The write itself is a MERGE, so
        // the answer would be the same either way; the caller is the one who cannot tell.
        val existing = graphStore.findEdge(edgeType.name, from, to)
        val merged = (existing?.props ?: emptyMap()) + request.props

        val written =
            graphStore.upsertEdge(
                GraphEdge(
                    type = edgeType.name,
                    from = from,
                    to = to,
                    props = merged,
                    provenance = provenance,
                ),
            )

        if (existing == null) LogEvents.edgeCreated(edgeType.name, from.id, to.id)
        metrics.edge(edgeType.name, if (existing == null) WriteOutcome.CREATED else WriteOutcome.UPDATED)
        return EdgeWrite(written, edgeType.inverse, created = existing == null)
    }

    override fun delete(
        type: String,
        fromId: String,
        toId: String,
    ): Boolean {
        val edgeType = declared(type)
        return graphStore.deleteEdge(edgeType.name, NodeKey.parse(fromId), NodeKey.parse(toId)).also { deleted ->
            if (deleted) metrics.edge(edgeType.name, WriteOutcome.DELETED)
        }
    }

    override fun forNode(
        type: String,
        key: String,
        direction: Direction,
        edgeType: String?,
    ): List<EdgeView> {
        edgeType?.let { declared(it) }
        val node = nodeKey(type, key)

        return graphStore
            .findEdges(node, direction, edgeType)
            .map { incident ->
                val declaredEdge = registry.edgeType(incident.edge.type)
                val inverse = declaredEdge?.inverse ?: incident.edge.type
                EdgeView(
                    type = incident.edge.type,
                    inverse = inverse,
                    direction = incident.direction,
                    // Looking outward an edge keeps its name; looking back it is read as its inverse.
                    displayName = if (incident.direction == Direction.OUTGOING) incident.edge.type else inverse,
                    other = incident.other,
                    props = incident.edge.props,
                    provenance = incident.edge.provenance,
                )
            }
            // Stable order, so a panel does not reshuffle itself between two identical reads.
            .sortedWith(compareBy({ it.type }, { it.other.key.key }))
    }

    private fun declared(type: String): EdgeTypeDef = registry.edgeType(type) ?: throw UnknownEdgeTypeException(type)

    private fun requireAllowed(
        edgeType: EdgeTypeDef,
        fromType: String,
        toType: String,
    ) {
        if (edgeType.connects(fromType, toType)) return
        refused(
            edgeType,
            EdgeNotAllowedException(
                type = edgeType.name,
                from = fromType,
                to = toType,
                allowed = edgeType.from.flatMap { start -> edgeType.to.map { end -> start to end } },
            ),
        )
    }

    /** The ontology refused the edge: counted as rejected, then thrown. */
    private fun refused(
        edgeType: EdgeTypeDef,
        refusal: RuntimeException,
    ): Nothing {
        metrics.edge(edgeType.name, WriteOutcome.REJECTED)
        throw refusal
    }

    /** Accepts either a derived key or a full `Type:key` id, as the node API does. */
    private fun nodeKey(
        type: String,
        keyOrId: String,
    ): NodeKey = NodeKey(type, keyOrId.removePrefix("$type:"))
}
