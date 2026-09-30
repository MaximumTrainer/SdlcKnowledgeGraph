package com.repodatagraph.adapter.`in`.rest.dto

import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodePage
import com.repodatagraph.domain.model.Provenance

/**
 * What a client may send: properties, and optionally the source system it states them as.
 *
 * There is deliberately no `id` or `key` field. Identity is derived from the properties by the
 * server, so a client cannot claim one, and a client that tries has its request refused by the
 * validator rather than silently ignored.
 */
data class NodeRequest(
    val props: Map<String, Any?> = emptyMap(),
    val provenance: ProvenanceRequest? = null,
)

/**
 * The part of a fact's provenance a caller states (#117): the system of record it speaks for, which
 * must be declared in sources.yaml and, unless it is `manual`, allowed by a `graph:write:<source>`
 * scope. Left out, the write is `manual`. The rest of the envelope - who wrote it, when, how sure -
 * is the server's to record, so it is not accepted from a caller.
 */
data class ProvenanceRequest(
    val sourceSystem: String? = null,
) {
    companion object {
        fun sourceOf(provenance: ProvenanceRequest?): String = provenance?.sourceSystem ?: Provenance.MANUAL
    }
}

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
