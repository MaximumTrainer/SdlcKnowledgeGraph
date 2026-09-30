package com.repodatagraph.application.neighbourhood

import com.repodatagraph.adapter.out.ontology.YamlOntologyLoader
import com.repodatagraph.domain.exception.InvalidQueryParameterException
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.model.Direction
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.IncidentEdge
import com.repodatagraph.domain.model.NeighbourStep
import com.repodatagraph.domain.model.NeighbourhoodSpec
import com.repodatagraph.domain.model.Neighbours
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.out.GraphStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.core.io.DefaultResourceLoader
import java.time.Instant

/**
 * The walk behind the graph view (#9): breadth first from the root, one step of the store per hop,
 * each node at the distance it was first reached, stopping at the depth asked or at the cap. The
 * store is faked; which edges a step finds is the adapter's business and the acceptance suite's.
 */
class NeighbourhoodServiceTest {
    private val registry = YamlOntologyLoader(DefaultResourceLoader()).load()
    private val graphStore = mock<GraphStore>()
    private val service = NeighbourhoodService(graphStore, registry, SubgraphMapper(registry))

    private val at = Instant.parse("2026-09-01T10:00:00Z")
    private val stated = Provenance(sourceSystem = "manual", ingestedAt = at, validFrom = at)

    private fun node(id: String) = GraphNode(NodeKey.parse(id), mapOf("name" to id.substringAfterLast('/')), stated)

    private val payments = node("Repository:github.com/acme/payments")
    private val sharedLib = node("Repository:github.com/acme/shared-lib")
    private val checkout = node("Repository:github.com/acme/checkout")
    private val platform = node("Team:platform")

    /** [from] reaches [other] along [type], stored in the direction given. */
    private fun hop(
        type: String,
        from: GraphNode,
        other: GraphNode,
        outgoing: Boolean = true,
    ): IncidentEdge {
        val edge =
            if (outgoing) {
                GraphEdge(
                    type,
                    from.key,
                    other.key,
                    emptyMap(),
                    stated,
                )
            } else {
                GraphEdge(type, other.key, from.key, emptyMap(), stated)
            }
        return IncidentEdge(edge, if (outgoing) Direction.OUTGOING else Direction.INCOMING, other)
    }

    private fun stepsTaken(times: Int): Pair<List<Collection<NodeKey>>, List<NeighbourStep>> {
        val frontiers = argumentCaptor<Collection<NodeKey>>()
        val steps = argumentCaptor<NeighbourStep>()
        verify(graphStore, times(times)).neighbourhood(frontiers.capture(), steps.capture())
        return frontiers.allValues to steps.allValues
    }

    @Test
    fun `a node the graph does not hold is not found`() {
        whenever(graphStore.findNode(payments.key)).thenReturn(null)

        assertThrows<NodeNotFoundException> { service.neighbourhood(NeighbourhoodSpec(payments.key)) }
        verify(graphStore, never()).neighbourhood(any(), any())
    }

    @Test
    fun `a root of a type the registry does not declare is refused, naming nodeId`() {
        val refused = assertThrows<InvalidQueryParameterException> { service.neighbourhood(NeighbourhoodSpec(NodeKey("Nonsense", "x"))) }

        assertEquals("nodeId", refused.field)
    }

    @Test
    fun `a filter naming an undeclared type is refused, naming the filter`() {
        whenever(graphStore.findNode(payments.key)).thenReturn(payments)

        val nodeTypes =
            assertThrows<InvalidQueryParameterException> {
                service.neighbourhood(
                    NeighbourhoodSpec(payments.key, nodeTypes = setOf("Person")),
                )
            }
        val edgeTypes =
            assertThrows<InvalidQueryParameterException> {
                service.neighbourhood(
                    NeighbourhoodSpec(payments.key, edgeTypes = setOf("KNOWS")),
                )
            }

        assertEquals("nodeTypes", nodeTypes.field)
        assertEquals("edgeTypes", edgeTypes.field)
    }

    @Test
    fun `with no type filter the graph's own bookkeeping types are left out`() {
        whenever(graphStore.findNode(payments.key)).thenReturn(payments)
        whenever(graphStore.neighbourhood(any(), any())).thenReturn(Neighbours(emptyList(), false))

        service.neighbourhood(NeighbourhoodSpec(payments.key))

        val step = stepsTaken(1).second.first()
        val browsable =
            registry
                .allNodeTypes()
                .filterNot { it.meta }
                .map { it.name }
                .toSet()
        assertEquals(browsable, step.nodeTypes)
        assertFalse("SyncRun" in step.nodeTypes)
        assertEquals(emptySet<String>(), step.edgeTypes)
        assertEquals(Direction.BOTH, step.direction)
    }

    @Test
    fun `the filters and the direction asked for are what each step is given`() {
        whenever(graphStore.findNode(payments.key)).thenReturn(payments)
        whenever(graphStore.neighbourhood(any(), any())).thenReturn(Neighbours(emptyList(), false))

        service.neighbourhood(
            NeighbourhoodSpec(
                payments.key,
                nodeTypes = setOf("Repository"),
                edgeTypes = setOf("DEPENDS_ON"),
                direction = Direction.INCOMING,
            ),
        )

        val step = stepsTaken(1).second.first()
        assertEquals(setOf("Repository"), step.nodeTypes)
        assertEquals(setOf("DEPENDS_ON"), step.edgeTypes)
        assertEquals(Direction.INCOMING, step.direction)
    }

    @Test
    fun `the walk goes out one hop per step, and each node keeps the distance it was first reached at`() {
        whenever(graphStore.findNode(payments.key)).thenReturn(payments)
        whenever(graphStore.neighbourhood(any(), any())).thenReturn(
            Neighbours(listOf(hop("DEPENDS_ON", payments, sharedLib), hop("OWNED_BY", payments, platform)), false),
            Neighbours(
                listOf(hop("DEPENDS_ON", sharedLib, checkout, outgoing = false), hop("DEPENDS_ON", sharedLib, payments, outgoing = false)),
                false,
            ),
            Neighbours(listOf(hop("DEPENDS_ON", checkout, sharedLib)), false),
        )

        val view = service.neighbourhood(NeighbourhoodSpec(payments.key, depth = 2))

        val (frontiers, steps) = stepsTaken(3)
        assertEquals(listOf(payments.key), frontiers[0].toList())
        assertEquals(setOf(sharedLib.key, platform.key), frontiers[1].toSet())
        // The last hop only joins up what was reached: no node beyond the depth asked for.
        assertEquals(listOf(checkout.key), frontiers[2].toList())
        assertEquals(setOf(payments.id, sharedLib.id, platform.id, checkout.id), steps[2].within)
        assertNull(steps[0].within)

        assertEquals(listOf(0, 1, 1, 2), view.nodes.map { it.distance })
        assertEquals(
            payments.id,
            view.nodes
                .first()
                .node.id,
        )
        assertEquals(
            checkout.id,
            view.nodes
                .last()
                .node.id,
        )
        assertEquals(3, view.edges.size)
        assertFalse(view.truncated)
    }

    @Test
    fun `a walk that reaches nothing new stops early`() {
        whenever(graphStore.findNode(payments.key)).thenReturn(payments)
        whenever(graphStore.neighbourhood(any(), any())).thenReturn(Neighbours(emptyList(), false))

        val view = service.neighbourhood(NeighbourhoodSpec(payments.key, depth = 3))

        stepsTaken(1)
        assertEquals(listOf(payments.id), view.nodes.map { it.node.id })
        assertTrue(view.edges.isEmpty())
    }

    @Test
    fun `past the cap the walk stops, keeps the nearest nodes and says it was cut short`() {
        whenever(graphStore.findNode(payments.key)).thenReturn(payments)
        val many = (1..5).map { node("Repository:github.com/acme/dependency-$it") }
        whenever(graphStore.neighbourhood(any(), any())).thenReturn(Neighbours(many.map { hop("DEPENDS_ON", payments, it) }, false))

        val view = service.neighbourhood(NeighbourhoodSpec(payments.key, depth = 3, limit = 3))

        // No second hop: there is already more than the cap, all of it nearer than anything further out.
        stepsTaken(1)
        assertEquals(3, view.nodes.size)
        assertEquals(listOf(payments.id, many[0].id, many[1].id), view.nodes.map { it.node.id })
        assertEquals(2, view.edges.size)
        assertTrue(view.truncated)
    }

    @Test
    fun `a step the store cut short makes the answer truncated`() {
        whenever(graphStore.findNode(payments.key)).thenReturn(payments)
        whenever(graphStore.neighbourhood(any(), any())).thenReturn(Neighbours(listOf(hop("DEPENDS_ON", payments, sharedLib)), true))

        assertTrue(service.neighbourhood(NeighbourhoodSpec(payments.key)).truncated)
    }
}
