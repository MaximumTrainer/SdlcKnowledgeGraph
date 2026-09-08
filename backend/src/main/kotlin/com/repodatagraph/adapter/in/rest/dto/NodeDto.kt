package com.repodatagraph.adapter.`in`.rest.dto

import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodePage
import com.repodatagraph.domain.model.Provenance

/**
 * What a client may send: properties only.
 *
 * There is deliberately no `id` or `key` field. Identity is derived from the properties by the
 * server, so a client cannot claim one, and a client that tries has its request refused by the
 * validator rather than silently ignored.
 */
data class NodeRequest(
    val props: Map<String, Any?> = emptyMap(),
)

/** A node as the API renders it: the derived identity, the properties, and who said so. */
data class NodeResponse(
    val id: String,
    val type: String,
    val key: String,
    val props: Map<String, Any?>,
    val provenance: Provenance,
) {
    companion object {
        fun from(node: GraphNode) =
            NodeResponse(
                id = node.id,
                type = node.type,
                key = node.key.key,
                props = node.props,
                provenance = node.provenance,
            )
    }
}

/** One page of nodes. [nextCursor] is null on the last page. */
data class NodePageResponse(
    val items: List<NodeResponse>,
    val nextCursor: String?,
) {
    companion object {
        fun from(page: NodePage) = NodePageResponse(page.items.map { NodeResponse.from(it) }, page.nextCursor)
    }
}
