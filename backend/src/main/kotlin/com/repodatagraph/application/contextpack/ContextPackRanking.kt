package com.repodatagraph.application.contextpack

import com.repodatagraph.domain.model.PackNode

/**
 * How a context pack is ordered and cut to its budget (#96, FR-3). Nearest first, since what a task
 * touches directly matters before what that touches; then the most confident path, so a stated fact
 * comes before a guess at the same distance; then #87's impact score, so a production deployment
 * comes before a staging one; then the id, which makes the order total and leaves nothing to the order
 * the store answered in.
 */
object ContextPackRanking {
    private val ORDER: Comparator<PackNode> =
        compareBy<PackNode> { it.distance }
            .thenByDescending { it.confidence }
            .thenByDescending { it.score }
            .thenBy { it.node.id }

    fun rank(nodes: Collection<PackNode>): List<PackNode> = nodes.sortedWith(ORDER)

    /** The first [budget] of [nodes] in rank order, and how many the budget left out. */
    fun cut(
        nodes: Collection<PackNode>,
        budget: Int,
    ): Cut {
        val ranked = rank(nodes)
        return Cut(kept = ranked.take(budget), cut = (ranked.size - budget).coerceAtLeast(0))
    }

    data class Cut(
        val kept: List<PackNode>,
        val cut: Int,
    )
}
