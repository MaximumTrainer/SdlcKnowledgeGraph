package com.repodatagraph.application.policy

import com.repodatagraph.domain.model.Direction
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.IncidentEdge
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.out.GraphStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant

/** The teams that own a node (#30 FR7), as the policy's ownership rule reads them from the graph. */
class GraphResourceOwnersTest {
    private val graphStore: GraphStore = mock()
    private val owners = GraphResourceOwners(graphStore)
    private val provenance = Provenance(sourceSystem = "manual", ingestedAt = AT, validFrom = AT)

    private val payments = NodeKey("Repository", "github.com/acme/payments")
    private val bucket = NodeKey("CloudResource", "aws:s3:payments-bucket")

    private fun owned(
        node: NodeKey,
        vararg teams: String,
    ) {
        whenever(graphStore.findEdges(eq(node), eq(Direction.OUTGOING), eq("OWNED_BY"))).thenReturn(
            teams.map { team ->
                val other = GraphNode(NodeKey("Team", team), provenance = provenance)
                IncidentEdge(GraphEdge("OWNED_BY", node, other.key, provenance = provenance), Direction.OUTGOING, other)
            },
        )
    }

    @Test
    fun `a node is owned by the teams it is owned by`() {
        owned(payments, "Payments", "platform")

        assertEquals(setOf("payments", "platform"), owners.ownerTeams("Repository", payments.key))
    }

    @Test
    fun `a cloud resource with no owner of its own is owned by whoever owns what owns it`() {
        owned(bucket)
        owned(payments, "payments")
        val repository = GraphNode(payments, provenance = provenance)
        whenever(graphStore.findEdges(eq(bucket), eq(Direction.INCOMING), eq("OWNS_RESOURCE"))).thenReturn(
            listOf(IncidentEdge(GraphEdge("OWNS_RESOURCE", payments, bucket, provenance = provenance), Direction.INCOMING, repository)),
        )

        assertEquals(setOf("payments"), owners.ownerTeams("CloudResource", bucket.key))
    }

    @Test
    fun `a team owns itself, and nothing owns what the graph does not hold`() {
        whenever(graphStore.findEdges(any(), any(), any<String>())).thenReturn(emptyList())

        assertEquals(setOf("platform"), owners.ownerTeams("Team", "Platform"))
        assertEquals(emptySet<String>(), owners.ownerTeams("Repository", "github.com/acme/nowhere"))
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-09-01T00:00:00Z")
    }
}
