package com.repodatagraph.observability

import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.model.Direction
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.IncidentEdge
import com.repodatagraph.domain.model.NeighbourhoodSpec
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Subgraph
import com.repodatagraph.domain.port.out.GraphStore
import io.micrometer.core.instrument.MeterRegistry

/**
 * The graph store with its failures made visible (#44, FR7). An operation that throws is counted in
 * `sdlc_graph_store_errors_total{operation}` and logged as `graph.store.failed` with the request's
 * id, then fails exactly as it would have. The store's own refusals, the domain exceptions such as a
 * missing end of an edge, are answers rather than failures and pass through uncounted.
 *
 * Every operation's counter exists from the start at zero, so an alert on its rate has a series to
 * read before the first failure rather than after it.
 */
@Suppress("TooManyFunctions") // One per port operation; see Neo4jGraphStore.
class InstrumentedGraphStore(
    private val delegate: GraphStore,
    private val meters: MeterRegistry,
) : GraphStore {
    init {
        GraphStore::class.java.declaredMethods
            .map { it.name }
            .distinct()
            .forEach(::errors)
    }

    override fun upsertNode(node: GraphNode) = observe("upsertNode") { delegate.upsertNode(node) }

    override fun upsertEdge(edge: GraphEdge) = observe("upsertEdge") { delegate.upsertEdge(edge) }

    override fun findNode(key: NodeKey) = observe("findNode") { delegate.findNode(key) }

    override fun findNodes(
        type: String,
        filter: Map<String, Any?>,
        afterKey: String?,
        limit: Int?,
    ) = observe("findNodes") { delegate.findNodes(type, filter, afterKey, limit) }

    override fun countEdges(key: NodeKey) = observe("countEdges") { delegate.countEdges(key) }

    override fun deleteNode(
        key: NodeKey,
        cascade: Boolean,
    ) = observe("deleteNode") { delegate.deleteNode(key, cascade) }

    override fun deleteEdge(
        type: String,
        from: NodeKey,
        to: NodeKey,
    ) = observe("deleteEdge") { delegate.deleteEdge(type, from, to) }

    override fun findEdge(
        type: String,
        from: NodeKey,
        to: NodeKey,
    ) = observe("findEdge") { delegate.findEdge(type, from, to) }

    override fun findEdges(
        key: NodeKey,
        direction: Direction,
        edgeType: String?,
    ): List<IncidentEdge> = observe("findEdges") { delegate.findEdges(key, direction, edgeType) }

    override fun neighbourhood(
        key: NodeKey,
        spec: NeighbourhoodSpec,
    ): Subgraph = observe("neighbourhood") { delegate.neighbourhood(key, spec) }

    private fun errors(operation: String) = meters.counter(ERRORS, OPERATION, operation)

    private inline fun <T> observe(
        operation: String,
        call: () -> T,
    ): T =
        try {
            call()
        } catch (
            // Anything at all can come out of a database driver; it is recorded and thrown on as it was.
            @Suppress("TooGenericExceptionCaught") failure: RuntimeException,
        ) {
            if (!isRefusal(failure)) {
                errors(operation).increment()
                LogEvents.graphStoreFailed(operation, failure)
            }
            throw failure
        }

    private fun isRefusal(failure: RuntimeException) = failure.javaClass.packageName == DOMAIN_EXCEPTIONS

    private companion object {
        const val ERRORS = "sdlc.graph.store.errors"
        const val OPERATION = "operation"
        val DOMAIN_EXCEPTIONS: String = NodeNotFoundException::class.java.packageName
    }
}
