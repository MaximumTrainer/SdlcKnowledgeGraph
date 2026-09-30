package com.repodatagraph.application.connector

import com.repodatagraph.adapter.out.ontology.YamlOntologyLoader
import com.repodatagraph.domain.identity.DerivedProperties
import com.repodatagraph.domain.identity.GitRemoteParser
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.port.out.FactLifecycle
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.ConnectorDescriptor
import com.repodatagraph.domain.port.out.connector.EdgeUpsert
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.NodeUpsert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.core.io.DefaultResourceLoader
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * What a page wrote, told apart from what it only said again (#86, FR-6).
 *
 * A fact stated again exactly as the graph already holds it is still written - its source asserting it
 * again is what keeps it fresh and out of reconciliation's way - but it is counted as unchanged, so a
 * run over an estate that did not move reports that it wrote nothing new.
 */
class GraphDeltaWriterAccountingTest {
    private val now = Instant.parse("2026-09-30T12:00:00Z")
    private val earlier = Instant.parse("2026-09-29T12:00:00Z")
    private val graphStore: GraphStore = mock()
    private val writer =
        GraphDeltaWriter(
            graphStore,
            mock<FactLifecycle>(),
            IdentityResolver(),
            DerivedProperties(GitRemoteParser()),
            Clock.fixed(now, ZoneOffset.UTC),
            YamlOntologyLoader(DefaultResourceLoader()).load(),
        )
    private val github = ConnectorDescriptor("github", "github", setOf("Team"), setOf("OWNED_BY"), setOf(Capability.FULL))

    private val team = NodeKey("Team", "github.com/acme/platform")
    private val repository = NodeKey("Repository", "github.com/acme/payments")

    private fun stored(
        sourceSystem: String = "github",
        validTo: Instant? = null,
        confidence: Double = 1.0,
        sourceId: String? = "acme/platform",
    ) = Provenance(
        sourceSystem = sourceSystem,
        sourceId = sourceId,
        ingestedAt = earlier,
        observedAt = earlier,
        confidence = confidence,
        validFrom = earlier,
        validTo = validTo,
        syncRunId = "run-0",
    )

    private val teamUpsert = NodeUpsert(type = "Team", props = mapOf("name" to "github.com/acme/platform"), sourceId = "acme/platform")

    @Test
    fun `a node the graph has never held is written`() {
        val result = writer.apply(GraphDelta(nodes = listOf(teamUpsert)), github, "run-1")

        assertThat(result.written).isEqualTo(1)
        assertThat(result.unchanged).isZero()
        assertThat(result.nodesUpserted).isEqualTo(1)
    }

    @Test
    fun `a node stated again as the graph holds it is re-asserted and counted as unchanged`() {
        whenever(graphStore.findNode(team)).thenReturn(GraphNode(team, mapOf("name" to "github.com/acme/platform"), stored()))

        val result = writer.apply(GraphDelta(nodes = listOf(teamUpsert)), github, "run-1")

        assertThat(result.written).isZero()
        assertThat(result.unchanged).isEqualTo(1)
        // Still written: the source saying it again is what keeps the fact fresh (#93) and spares it
        // from reconciliation (#150). Only the count says nothing moved.
        verify(graphStore, atLeastOnce()).upsertNode(any())
    }

    @Test
    fun `a property that moved makes the node written`() {
        whenever(graphStore.findNode(repository)).thenReturn(
            GraphNode(repository, storedRepositoryProps(topics = listOf("java")), stored(sourceId = "R_payments")),
        )

        val result =
            writer.apply(
                GraphDelta(
                    nodes = listOf(NodeUpsert("Repository", repositoryProps(topics = listOf("java", "payments")), sourceId = "R_payments")),
                ),
                github,
                "run-1",
            )

        assertThat(result.written).isEqualTo(1)
        assertThat(result.unchanged).isZero()
    }

    @Test
    fun `the same values read back in the store's own types are still unchanged`() {
        // Neo4j hands integers back as Long and instants as ZonedDateTime; neither is a change.
        whenever(graphStore.findNode(repository)).thenReturn(
            GraphNode(repository, storedRepositoryProps(topics = listOf("java")), stored(sourceId = "R_payments")),
        )

        val result =
            writer.apply(
                GraphDelta(nodes = listOf(NodeUpsert("Repository", repositoryProps(topics = listOf("java")), sourceId = "R_payments"))),
                github,
                "run-1",
            )

        assertThat(result.unchanged).isEqualTo(1)
        assertThat(result.written).isZero()
        assertThat(sameValue(3, 3L)).isTrue()
        assertThat(sameValue(now, ZonedDateTime.ofInstant(now, ZoneOffset.UTC))).isTrue()
        assertThat(sameValue(listOf("a"), arrayOf("a"))).isTrue()
    }

    @Test
    fun `the same values from another source are written, since who says it is part of the fact`() {
        whenever(graphStore.findNode(team)).thenReturn(
            GraphNode(team, mapOf("name" to "github.com/acme/platform"), stored(sourceSystem = "dogfood-seed")),
        )

        assertThat(writer.apply(GraphDelta(nodes = listOf(teamUpsert)), github, "run-1").written).isEqualTo(1)
    }

    @Test
    fun `a closed node stated again is written, because it has come back`() {
        whenever(graphStore.findNode(team)).thenReturn(
            GraphNode(team, mapOf("name" to "github.com/acme/platform"), stored(validTo = earlier.plusSeconds(1))),
        )

        assertThat(writer.apply(GraphDelta(nodes = listOf(teamUpsert)), github, "run-1").written).isEqualTo(1)
    }

    @Test
    fun `an edge is counted the same way, and a changed confidence is a change`() {
        val edge =
            EdgeUpsert(
                type = "OWNED_BY",
                from = repository,
                to = team,
                props = mapOf("pathPatterns" to listOf("*")),
                sourceId = "acme/payments:CODEOWNERS",
            )
        whenever(graphStore.findEdge("OWNED_BY", repository, team)).thenReturn(
            GraphEdge("OWNED_BY", repository, team, mapOf("pathPatterns" to listOf("*")), stored(sourceId = "acme/payments:CODEOWNERS")),
        )

        assertThat(writer.apply(GraphDelta(edges = listOf(edge)), github, "run-1").unchanged).isEqualTo(1)
        assertThat(writer.apply(GraphDelta(edges = listOf(edge.copy(confidence = 0.9, inferred = true))), github, "run-2").written)
            .isEqualTo(1)
    }

    @Test
    fun `an edge carries the sourceId its connector gave it`() {
        val edge = EdgeUpsert(type = "OWNED_BY", from = repository, to = team, sourceId = "acme/payments:CODEOWNERS")

        writer.apply(GraphDelta(edges = listOf(edge)), github, "run-1")

        val written = argumentCaptor<GraphEdge>()
        verify(graphStore, atLeastOnce()).upsertEdge(written.capture())
        assertThat(
            written.allValues
                .single { it.type == "OWNED_BY" }
                .provenance.sourceId,
        ).isEqualTo("acme/payments:CODEOWNERS")
    }

    /** As the store holds it: with what the writer derives from the url, host, org and name among them. */
    private fun storedRepositoryProps(topics: List<String>) =
        DerivedProperties(GitRemoteParser()).expand("Repository", repositoryProps(topics))

    private fun repositoryProps(topics: List<String>) =
        mapOf(
            "url" to "https://github.com/acme/payments",
            "defaultBranch" to "main",
            "topics" to topics,
            "codeowners" to emptyList<String>(),
        )
}
