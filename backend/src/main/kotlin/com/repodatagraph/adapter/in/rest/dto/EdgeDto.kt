package com.repodatagraph.adapter.`in`.rest.dto

import com.repodatagraph.domain.model.Direction
import com.repodatagraph.domain.model.EdgeView
import com.repodatagraph.domain.model.EdgeWrite
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance

/** What a caller sends to state a relationship. Ids may be a full `Type:key` or a bare key. */
data class EdgeRequestBody(
    val type: String,
    val fromId: String,
    val toId: String,
    val props: Map<String, Any?> = emptyMap(),
)

/** Just enough of a node to identify it and show it, without inlining the whole thing at both ends. */
data class NodeRefResponse(
    val id: String,
    val type: String,
    val key: String,
    val props: Map<String, Any?>? = null,
) {
    companion object {
        fun from(key: NodeKey) = NodeRefResponse(key.id, key.type, key.key)

        fun from(node: GraphNode) = NodeRefResponse(node.id, node.type, node.key.key, node.props)
    }
}

/**
 * A relationship as stated.
 *
 * The inverse is returned even though it is not stored, because a caller that has just written
 * `DEPENDS_ON` usually wants to tell the user what the other end will now call it.
 */
data class EdgeResponse(
    val type: String,
    val inverse: String,
    val from: NodeRefResponse,
    val to: NodeRefResponse,
    val props: Map<String, Any?>,
    val provenance: Provenance,
) {
    companion object {
        fun from(written: EdgeWrite) =
            EdgeResponse(
                type = written.edge.type,
                inverse = written.inverse,
                from = NodeRefResponse.from(written.edge.from),
                to = NodeRefResponse.from(written.edge.to),
                props = written.edge.props,
                provenance = written.edge.provenance,
            )
    }
}

/** A relationship as one end sees it. [displayName] is what to label it with on that node's page. */
data class EdgeViewResponse(
    val type: String,
    val inverse: String,
    val direction: String,
    val displayName: String,
    val other: NodeRefResponse,
    val props: Map<String, Any?>,
    val provenance: Provenance,
) {
    companion object {
        fun from(view: EdgeView) =
            EdgeViewResponse(
                type = view.type,
                inverse = view.inverse,
                direction = if (view.direction == Direction.OUTGOING) "out" else "in",
                displayName = view.displayName,
                other = NodeRefResponse.from(view.other),
                props = view.props,
                provenance = view.provenance,
            )
    }
}

data class EdgeListResponse(
    val items: List<EdgeViewResponse>,
)
