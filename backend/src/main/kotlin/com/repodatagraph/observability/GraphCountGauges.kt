package com.repodatagraph.observability

import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.port.out.GraphCensus
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * How big the graph is, as `sdlc_graph_nodes{type}` and `sdlc_graph_edges{type}` (#29, FR1;
 * docs/OBSERVABILITY.md). A connector that writes nothing and one that writes a thousand duplicates
 * look alike in its own counters; the size of the graph is what tells them apart.
 *
 * One series per type the ontology declares, and no others, so the `type` label is bounded by the
 * registry. The counts are taken on a timer, every `observability.graph-count-interval`, and a scrape
 * reads the last ones: a scrape that queried Neo4j would be as slow as Neo4j, and would query it as
 * often as anything cared to scrape.
 *
 * Until the first count every series reads NaN rather than zero, because zero would claim the graph
 * is empty when nobody has looked. A count that fails keeps the last one whole and logs
 * graph.count.failed, since a gap, or half the types refreshed, would look like the graph changing.
 */
@Component
class GraphCountGauges(
    private val registry: OntologyRegistry,
    private val census: GraphCensus,
    meters: MeterRegistry,
) {
    /** Replaced whole by each successful count, so a scrape never sees two counts mixed. */
    @Volatile
    private var last = Counts(emptyMap(), emptyMap())

    init {
        registry.allNodeTypes().forEach { type ->
            Gauge
                .builder(NODES, this) { it.last.nodes[type.name]?.toDouble() ?: Double.NaN }
                .description("Nodes of this type in the graph, closed facts included, as last counted")
                .tag(TYPE, type.name)
                .strongReference(true)
                .register(meters)
        }
        registry.allEdgeTypes().forEach { type ->
            Gauge
                .builder(EDGES, this) { it.last.edges[type.name]?.toDouble() ?: Double.NaN }
                .description("Relationships of this type in the graph, closed facts included, as last counted")
                .tag(TYPE, type.name)
                .strongReference(true)
                .register(meters)
        }
    }

    /**
     * Counts every declared type now. Runs at startup and then once each interval has passed since
     * the last count ended; public so a test can count without waiting for the timer.
     */
    @Scheduled(fixedDelayString = "\${observability.graph-count-interval:PT5M}")
    fun refresh() {
        runCatching {
            Counts(
                nodes = registry.allNodeTypes().associate { it.name to census.countNodes(it.name) },
                edges = registry.allEdgeTypes().associate { it.name to census.countEdges(it.name) },
            )
        }.onSuccess { last = it }
            .onFailure { LogEvents.graphCountFailed(it) }
    }

    private data class Counts(
        val nodes: Map<String, Long>,
        val edges: Map<String, Long>,
    )

    private companion object {
        const val NODES = "sdlc.graph.nodes"
        const val EDGES = "sdlc.graph.edges"
        const val TYPE = "type"
    }
}
