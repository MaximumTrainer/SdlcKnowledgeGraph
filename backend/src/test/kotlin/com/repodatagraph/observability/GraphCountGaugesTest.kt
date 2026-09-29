package com.repodatagraph.observability

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.repodatagraph.domain.ontology.EdgeTypeDef
import com.repodatagraph.domain.ontology.NodeTypeDef
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.ontology.PropertyDef
import com.repodatagraph.domain.ontology.PropertyType
import com.repodatagraph.domain.port.out.GraphCensus
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * How big the graph is, per declared type (#29, FR1): one series per node and edge type the ontology
 * declares, and no others, counted when asked rather than on every scrape. A count that fails keeps
 * what the last one said and logs graph.count.failed, since a gap in the series would read as an
 * emptied graph.
 */
class GraphCountGaugesTest {
    private val registry =
        OntologyRegistry(
            version = "1.0.0",
            nodeTypes =
                listOf(
                    NodeTypeDef("Team", null, listOf("name"), listOf(PropertyDef("name", PropertyType.STRING, required = true))),
                    NodeTypeDef("Repository", null, listOf("url"), listOf(PropertyDef("url", PropertyType.STRING, required = true))),
                ),
            edgeTypes = listOf(EdgeTypeDef("OWNED_BY", null, listOf("Repository"), listOf("Team"), inverse = "OWNS")),
        )
    private val census = FakeCensus()
    private val meters = SimpleMeterRegistry()
    private val gauges = GraphCountGauges(registry, census, meters)

    private val appender = ListAppender<ILoggingEvent>()
    private val eventLogger = LoggerFactory.getLogger("${EventLog.LOGGER_PREFIX}.graph.count.failed") as Logger

    @BeforeEach
    fun capture() {
        appender.start()
        eventLogger.addAppender(appender)
    }

    @AfterEach
    fun release() {
        eventLogger.detachAppender(appender)
    }

    private fun nodes(type: String) = checkNotNull(meters.find("sdlc.graph.nodes").tag("type", type).gauge()).value()

    private fun edges(type: String) = checkNotNull(meters.find("sdlc.graph.edges").tag("type", type).gauge()).value()

    @Test
    fun `every declared type has a series before anything is counted, reading NaN`() {
        assertThat(meters.find("sdlc.graph.nodes").gauges().map { it.id.getTag("type") })
            .containsExactlyInAnyOrder("Team", "Repository")
        assertThat(meters.find("sdlc.graph.edges").gauges().map { it.id.getTag("type") })
            .containsExactly("OWNED_BY")
        assertThat(nodes("Team")).isNaN()
        assertThat(edges("OWNED_BY")).isNaN()
        assertThat(census.asked).isEmpty()
    }

    @Test
    fun `a refresh publishes each type's count, zero included`() {
        census.nodes = mapOf("Team" to 2L, "Repository" to 0L)
        census.edges = mapOf("OWNED_BY" to 1L)

        gauges.refresh()

        assertThat(nodes("Team")).isEqualTo(2.0)
        assertThat(nodes("Repository")).isEqualTo(0.0)
        assertThat(edges("OWNED_BY")).isEqualTo(1.0)
    }

    @Test
    fun `a scrape reads what the last refresh counted and does not ask the graph`() {
        census.nodes = mapOf("Team" to 2L, "Repository" to 1L)
        census.edges = mapOf("OWNED_BY" to 1L)
        gauges.refresh()
        val asked = census.asked.size

        repeat(3) { nodes("Team") }

        assertThat(census.asked).hasSize(asked)
    }

    @Test
    fun `only the types the ontology declares are counted`() {
        census.nodes = mapOf("Team" to 1L, "Repository" to 1L)
        census.edges = mapOf("OWNED_BY" to 1L)

        gauges.refresh()

        assertThat(census.asked).containsExactlyInAnyOrder("node:Team", "node:Repository", "edge:OWNED_BY")
    }

    @Test
    fun `a refresh that fails keeps the last counts, publishes nothing partial, and is logged`() {
        census.nodes = mapOf("Team" to 2L, "Repository" to 1L)
        census.edges = mapOf("OWNED_BY" to 1L)
        gauges.refresh()

        census.nodes = mapOf("Team" to 5L, "Repository" to 4L)
        census.failOn = "edge:OWNED_BY"
        gauges.refresh()

        assertThat(nodes("Team")).isEqualTo(2.0)
        assertThat(nodes("Repository")).isEqualTo(1.0)
        assertThat(edges("OWNED_BY")).isEqualTo(1.0)
        val logged = appender.list.single()
        assertThat(logged.keyValuePairs.associate { it.key to it.value }).containsEntry("event", "graph.count.failed")
        assertThat(logged.throwableProxy.message).isEqualTo("connection refused")
    }

    @Test
    fun `a refresh that fails before any has succeeded leaves the series at NaN`() {
        census.failOn = "node:Team"

        gauges.refresh()

        assertThat(nodes("Team")).isNaN()
        assertThat(edges("OWNED_BY")).isNaN()
    }

    /** Counts from maps, remembers what it was asked, and fails on the one count it is told to. */
    private class FakeCensus : GraphCensus {
        var nodes: Map<String, Long> = emptyMap()
        var edges: Map<String, Long> = emptyMap()
        var failOn: String? = null
        val asked = mutableListOf<String>()

        override fun countNodes(type: String): Long = count("node:$type") { nodes.getValue(type) }

        override fun countEdges(type: String): Long = count("edge:$type") { edges.getValue(type) }

        private fun count(
            what: String,
            value: () -> Long,
        ): Long {
            asked += what
            check(what != failOn) { "connection refused" }
            return value()
        }
    }
}
