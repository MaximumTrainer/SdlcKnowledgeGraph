package com.repodatagraph.application.neighbourhood

import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.ReachedNode
import com.repodatagraph.domain.model.SubgraphEdgeView
import com.repodatagraph.domain.model.SubgraphNodeView
import com.repodatagraph.domain.model.SubgraphView
import com.repodatagraph.domain.ontology.OntologyRegistry
import org.springframework.stereotype.Component

/**
 * Turns a walked neighbourhood into what the graph view draws (#9, FR1 to FR3).
 *
 * A node's label is the value of its type's `displayProperty` in the registry, so the client needs
 * no table of types to show `payments` for `github.com/acme/payments`; with no such value it is the
 * key, so a label is never empty. An edge's id is `type:from>to`, the same on every answer. And the
 * cap keeps the nodes nearest the root, in the order the walk reached them, with only the edges
 * between the nodes it kept.
 */
@Component
class SubgraphMapper(
    private val registry: OntologyRegistry,
) {
    fun map(
        root: GraphNode,
        reached: List<ReachedNode>,
        edges: Collection<GraphEdge>,
        limit: Int,
        truncated: Boolean,
    ): SubgraphView {
        // sortedBy is stable, so nodes at one distance keep the order the walk found them in.
        val nearest = reached.distinctBy { it.node.id }.sortedBy { it.distance }
        val kept = nearest.take(limit)
        val keptIds = kept.mapTo(hashSetOf()) { it.node.id }
        val keptEdges =
            edges
                .filter { it.from.id in keptIds && it.to.id in keptIds }
                .distinctBy(::edgeId)
                .sortedBy(::edgeId)
        return SubgraphView(
            root = root,
            nodes = kept.map { SubgraphNodeView(it.node, label(it.node), it.distance) },
            edges = keptEdges.map(::edgeView),
            truncated = truncated || nearest.size > limit,
        )
    }

    /** The node's display property as text, or its key when it has none. */
    fun label(node: GraphNode): String {
        val property = registry.nodeType(node.type)?.displayProperty ?: return node.key.key
        val text =
            when (val value = node.props[property]) {
                null -> ""
                is Collection<*> -> value.filterNotNull().joinToString(", ")
                else -> value.toString()
            }
        return text.ifBlank { node.key.key }
    }

    private fun edgeView(edge: GraphEdge) =
        SubgraphEdgeView(
            id = edgeId(edge),
            type = edge.type,
            inverse = registry.inverseOf(edge.type) ?: edge.type,
            from = edge.from.id,
            to = edge.to.id,
            confidence = edge.provenance.confidence,
            inferred = edge.provenance.inferred,
            rule = edge.props["rule"]?.toString(),
        )

    companion object {
        /** FR3: `type:from>to`, so a client can merge an expansion into what it has without duplicates. */
        fun edgeId(edge: GraphEdge): String = "${edge.type}:${edge.from.id}>${edge.to.id}"
    }
}
