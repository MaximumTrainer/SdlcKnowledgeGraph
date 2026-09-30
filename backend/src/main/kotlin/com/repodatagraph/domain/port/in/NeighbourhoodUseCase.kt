package com.repodatagraph.domain.port.`in`

import com.repodatagraph.domain.model.NeighbourhoodSpec
import com.repodatagraph.domain.model.SubgraphView

/** The bounded neighbourhood the graph view draws (#9), which [GraphQueryUseCase] answers alongside its other reads. */
interface NeighbourhoodUseCase {
    /**
     * The nodes within [NeighbourhoodSpec.depth] hops of the root, nearest first and at most
     * [NeighbourhoodSpec.limit] of them, with the edges between them.
     *
     * @throws com.repodatagraph.domain.exception.NodeNotFoundException when the root is not in the graph
     * @throws com.repodatagraph.domain.exception.InvalidQueryParameterException when a filter names an undeclared type
     */
    fun neighbourhood(spec: NeighbourhoodSpec): SubgraphView
}
