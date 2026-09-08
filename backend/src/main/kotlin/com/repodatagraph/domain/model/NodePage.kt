package com.repodatagraph.domain.model

/**
 * One page of nodes, in key order.
 *
 * The cursor is the key of the last item returned rather than an offset, so a page stays stable
 * while other nodes are being written: an offset would silently skip or repeat rows.
 */
data class NodePage(
    val items: List<GraphNode>,
    val nextCursor: String? = null,
)
