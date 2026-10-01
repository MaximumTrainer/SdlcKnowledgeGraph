package com.repodatagraph.application.policy

import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.policy.AgentFact
import com.repodatagraph.domain.policy.ResourceKind
import com.repodatagraph.domain.port.out.GraphStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The facts an agent cites, as the graph holds them (#95 FR-4): what the agent-actions policy is
 * given, whatever the agent said.
 */
class GraphAgentFactsTest {
    private val graphStore: GraphStore = mock()
    private val facts = GraphAgentFacts(graphStore, Clock.fixed(NOW, ZoneOffset.UTC))

    private fun deployment(
        key: String,
        artifact: String,
        deployedAt: Instant,
        confidence: Double = 1.0,
    ) = GraphNode(
        NodeKey("Deployment", key),
        props = mapOf("artifactId" to artifact, "environmentId" to "production", "deployedAt" to deployedAt.toString()),
        provenance = Provenance(sourceSystem = "github-actions", ingestedAt = deployedAt, validFrom = deployedAt, confidence = confidence),
    )

    @Test
    fun `a deployment is given with its age, environment and whether there is an earlier artifact to go back to`() {
        val current = deployment("d2", "payments:2.0.0", NOW.minusSeconds(TEN_MINUTES))
        val earlier = deployment("d1", "payments:1.0.0", NOW.minusSeconds(A_DAY))
        whenever(graphStore.findNode(current.key)).thenReturn(current)
        whenever(
            graphStore.findNodes(eq("Deployment"), eq(mapOf("environmentId" to "production")), anyOrNull(), anyOrNull()),
        ).thenReturn(listOf(earlier, current))

        val fact = facts.fact("Deployment:d2")

        assertEquals(
            AgentFact("Deployment:d2", true, ResourceKind.NODE, "Deployment", 1.0, false, 10, true, "production"),
            fact,
        )
    }

    @Test
    fun `an edge is given with how sure its source was and whether a rule inferred it`() {
        val from = NodeKey("Incident", "INC1")
        val to = NodeKey("Deployment", "d2")
        val inferred = Provenance(sourceSystem = "link-engine", ingestedAt = NOW, validFrom = NOW, confidence = 0.6, inferred = true)
        whenever(graphStore.findEdge("CAUSED_BY", from, to)).thenReturn(GraphEdge("CAUSED_BY", from, to, provenance = inferred))

        val fact = facts.fact("CAUSED_BY:Incident:INC1>Deployment:d2")

        assertEquals(AgentFact("CAUSED_BY:Incident:INC1>Deployment:d2", true, ResourceKind.EDGE, "CAUSED_BY", 0.6, true, 0), fact)
    }

    @Test
    fun `a fact the graph does not hold, or that is no id at all, is not found`() {
        assertEquals(AgentFact("Deployment:made-up", false), facts.fact("Deployment:made-up"))
        assertEquals(AgentFact("nonsense", false), facts.fact("nonsense"))
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-01T12:00:00Z")
        const val TEN_MINUTES = 600L
        const val A_DAY = 86_400L
    }
}
