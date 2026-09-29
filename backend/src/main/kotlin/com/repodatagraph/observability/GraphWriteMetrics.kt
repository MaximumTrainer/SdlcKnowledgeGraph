package com.repodatagraph.observability

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * Counts graph writes made through the API by type and outcome (#44, FR7), published as
 * `sdlc_node_writes_total` and `sdlc_edge_writes_total`. The type label is always one the ontology
 * declares, because an undeclared type is refused before anything is counted, so its cardinality is
 * the size of the ontology.
 */
@Component
class GraphWriteMetrics(
    private val meters: MeterRegistry,
) {
    fun node(
        type: String,
        outcome: WriteOutcome,
    ) = meters.counter(NODE_WRITES, TYPE, type, OUTCOME, outcome.label).increment()

    fun edge(
        type: String,
        outcome: WriteOutcome,
    ) = meters.counter(EDGE_WRITES, TYPE, type, OUTCOME, outcome.label).increment()

    private companion object {
        const val NODE_WRITES = "sdlc.node.writes"
        const val EDGE_WRITES = "sdlc.edge.writes"
        const val TYPE = "type"
        const val OUTCOME = "outcome"
    }
}

/** What became of a write. `rejected` means the ontology refused it and nothing was stored. */
enum class WriteOutcome {
    CREATED,
    UPDATED,
    DELETED,
    REJECTED,
    ;

    val label: String = name.lowercase()
}
