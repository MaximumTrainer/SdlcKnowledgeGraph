package com.repodatagraph.application.connector

import com.repodatagraph.adapter.out.ontology.YamlOntologyLoader
import com.repodatagraph.domain.identity.DerivedProperties
import com.repodatagraph.domain.identity.GitRemoteParser
import com.repodatagraph.domain.lifecycle.RetiredReason
import com.repodatagraph.domain.lifecycle.Supersession
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.port.out.FactLifecycle
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.ConnectorDescriptor
import com.repodatagraph.domain.port.out.connector.EdgeUpsert
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.NodeUpsert
import com.repodatagraph.domain.port.out.connector.plus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.core.io.DefaultResourceLoader
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The two things a connector may now say about time beyond when it saw a fact (#90): when the fact
 * began, for a deployment that began when it was deployed rather than when it was read, and which
 * facts a newer one superseded, and at what instant. Who says so is still the writer's to stamp.
 */
class GraphDeltaWriterSupersessionTest {
    private val now = Instant.parse("2026-09-30T12:00:00Z")
    private val deployedAt = Instant.parse("2026-09-30T11:00:00Z")
    private val graphStore: GraphStore = mock()
    private val lifecycle: FactLifecycle = mock()
    private val writer =
        GraphDeltaWriter(
            graphStore,
            lifecycle,
            IdentityResolver(),
            DerivedProperties(GitRemoteParser()),
            Clock.fixed(now, ZoneOffset.UTC),
            YamlOntologyLoader(DefaultResourceLoader()).load(),
        )
    private val actions =
        ConnectorDescriptor("github-actions", "github-actions", setOf("Environment"), setOf("TO_ENVIRONMENT"), setOf(Capability.WEBHOOK))
    private val earlier = NodeKey("Deployment", "ghcr.io/acme/payments@sha256:a#production#1790000000")

    @Test
    fun `a superseded fact is retired at the instant given, as superseded`() {
        whenever(lifecycle.retire(any(), any(), any())).thenReturn(true)

        val result = writer.apply(GraphDelta(supersessions = listOf(Supersession(earlier, deployedAt))), actions, "run-1")

        verify(lifecycle).retire(earlier, deployedAt, RetiredReason.SUPERSEDED)
        assertThat(result.tombstones).isEqualTo(1)
    }

    @Test
    fun `superseding happens after the page's nodes and edges are written`() {
        whenever(lifecycle.retire(any(), any(), any())).thenReturn(true)
        val environment = NodeUpsert("Environment", mapOf("name" to "production", "type" to "production"))

        writer.apply(GraphDelta(nodes = listOf(environment), supersessions = listOf(Supersession(earlier, deployedAt))), actions, "run-1")

        inOrder(graphStore, lifecycle) {
            verify(graphStore).upsertNode(any())
            verify(lifecycle).retire(eq(earlier), eq(deployedAt), eq(RetiredReason.SUPERSEDED))
        }
    }

    @Test
    fun `a fact already superseded is not counted again`() {
        whenever(lifecycle.retire(any(), any(), any())).thenReturn(false)

        val result = writer.apply(GraphDelta(supersessions = listOf(Supersession(earlier, deployedAt))), actions, "run-1")

        assertThat(result.tombstones).isZero()
    }

    @Test
    fun `a fact begins when its source says it did`() {
        val environment = NodeUpsert("Environment", mapOf("name" to "production", "type" to "production"), validFrom = deployedAt)

        writer.apply(GraphDelta(nodes = listOf(environment)), actions, "run-1")

        val written = argumentCaptor<GraphNode>()
        verify(graphStore).upsertNode(written.capture())
        assertThat(written.firstValue.provenance.validFrom).isEqualTo(deployedAt)
        assertThat(written.firstValue.provenance.ingestedAt).isEqualTo(now)
    }

    @Test
    fun `a beginning in the future is read as now`() {
        val future = now.plusSeconds(3600)
        val edge =
            EdgeUpsert(
                type = "TO_ENVIRONMENT",
                from = earlier,
                to = NodeKey("Environment", "production"),
                validFrom = future,
            )

        writer.apply(GraphDelta(edges = listOf(edge)), actions, "run-1")

        val written = argumentCaptor<GraphEdge>()
        verify(graphStore).upsertEdge(written.capture())
        assertThat(written.firstValue.provenance.validFrom).isEqualTo(now)
    }

    @Test
    fun `two pages' supersessions add up`() {
        val other = NodeKey("Deployment", "ghcr.io/acme/ledger@sha256:l#production#1790000000")
        val combined =
            GraphDelta(supersessions = listOf(Supersession(earlier, deployedAt))) +
                GraphDelta(supersessions = listOf(Supersession(other, deployedAt)))

        assertThat(combined.supersessions.map { it.key }).containsExactly(earlier, other)
    }
}
