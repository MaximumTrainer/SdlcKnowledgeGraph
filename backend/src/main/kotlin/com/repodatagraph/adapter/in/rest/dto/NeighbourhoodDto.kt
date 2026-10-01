package com.repodatagraph.adapter.`in`.rest.dto

import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.model.SubgraphEdgeView
import com.repodatagraph.domain.model.SubgraphNodeView
import com.repodatagraph.domain.model.SubgraphView

/** A node as the graph view draws it (#9): its label, how far from the root, and what its drawer shows. */
data class SubgraphNodeResponse(
    val id: String,
    val type: String,
    val key: String,
    /** The registry's displayProperty of the node, or its key: never empty. */
    val label: String,
    val distance: Int,
    val props: Map<String, Any?>,
    val provenance: Provenance,
) {
    companion object {
        fun from(view: SubgraphNodeView) =
            SubgraphNodeResponse(
                id = view.node.id,
                type = view.node.type,
                key = view.node.key.key,
                label = view.label,
                distance = view.distance,
                props = view.node.props,
                provenance = view.node.provenance,
            )
    }
}

/** An edge as the graph view draws it: `id` is `type:from>to`; an inferred one is drawn dashed. */
data class SubgraphEdgeResponse(
    val id: String,
    val type: String,
    val inverse: String,
    val from: String,
    val to: String,
    val confidence: Double,
    val inferred: Boolean,
    /** The link rule an inferred ownership rests on (#28), such as iac; absent for an edge with none. */
    val rule: String? = null,
) {
    companion object {
        fun from(view: SubgraphEdgeView) =
            SubgraphEdgeResponse(view.id, view.type, view.inverse, view.from, view.to, view.confidence, view.inferred, view.rule)
    }
}

/** `GET /api/v1/graph/neighbourhood` (#9, FR1): a bounded subgraph, and whether the cap cut it short. */
data class NeighbourhoodResponse(
    val root: String,
    val nodes: List<SubgraphNodeResponse>,
    val edges: List<SubgraphEdgeResponse>,
    val truncated: Boolean,
) {
    companion object {
        fun from(view: SubgraphView) =
            NeighbourhoodResponse(
                root = view.root.id,
                nodes = view.nodes.map(SubgraphNodeResponse::from),
                edges = view.edges.map(SubgraphEdgeResponse::from),
                truncated = view.truncated,
            )
    }
}
