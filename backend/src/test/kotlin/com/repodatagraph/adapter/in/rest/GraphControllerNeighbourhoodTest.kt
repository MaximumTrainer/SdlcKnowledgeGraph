package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.exception.InvalidQueryParameterException
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.model.Direction
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NeighbourhoodSpec
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.model.SubgraphEdgeView
import com.repodatagraph.domain.model.SubgraphNodeView
import com.repodatagraph.domain.model.SubgraphView
import com.repodatagraph.domain.port.`in`.GraphQueryUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * `GET /api/v1/graph/neighbourhood` (#9, FR1 to FR3): the shape the graph view renders, the defaults
 * the parameters take, and how a bad parameter or an unknown node is answered. The use case is
 * mocked; what the subgraph holds is the service's business and the acceptance suite's.
 */
@WebMvcTest(GraphController::class)
class GraphControllerNeighbourhoodTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var graphQueryUseCase: GraphQueryUseCase

    private val at = Instant.parse("2026-09-01T10:00:00Z")
    private val stated = Provenance(sourceSystem = "manual", ingestedAt = at, validFrom = at)

    private val payments = GraphNode(NodeKey("Repository", "github.com/acme/payments"), mapOf("name" to "payments"), stated)
    private val bucket = GraphNode(NodeKey("CloudResource", "aws:arn:aws:s3:::acme-logs"), mapOf("name" to "acme-logs"), stated)

    private val subgraph =
        SubgraphView(
            root = payments,
            nodes =
                listOf(
                    SubgraphNodeView(payments, label = "payments", distance = 0),
                    SubgraphNodeView(bucket, label = "acme-logs", distance = 1),
                ),
            edges =
                listOf(
                    SubgraphEdgeView(
                        id = "OWNS_RESOURCE:${payments.id}>${bucket.id}",
                        type = "OWNS_RESOURCE",
                        inverse = "OWNED_BY_REPO",
                        from = payments.id,
                        to = bucket.id,
                        confidence = 0.7,
                        inferred = true,
                        rule = "iac",
                    ),
                ),
            truncated = false,
        )

    @Test
    fun `answers the root, the labelled nodes and the edges with their confidence`() {
        whenever(graphQueryUseCase.neighbourhood(any())).thenReturn(subgraph)

        mockMvc
            .perform(get("/api/v1/graph/neighbourhood").param("nodeId", payments.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.root").value(payments.id))
            .andExpect(jsonPath("$.truncated").value(false))
            .andExpect(jsonPath("$.nodes[0].id").value(payments.id))
            .andExpect(jsonPath("$.nodes[0].type").value("Repository"))
            .andExpect(jsonPath("$.nodes[0].key").value("github.com/acme/payments"))
            .andExpect(jsonPath("$.nodes[0].label").value("payments"))
            .andExpect(jsonPath("$.nodes[0].props.name").value("payments"))
            .andExpect(jsonPath("$.nodes[0].provenance.sourceSystem").value("manual"))
            .andExpect(jsonPath("$.nodes[1].distance").value(1))
            .andExpect(jsonPath("$.edges[0].id").value("OWNS_RESOURCE:${payments.id}>${bucket.id}"))
            .andExpect(jsonPath("$.edges[0].type").value("OWNS_RESOURCE"))
            .andExpect(jsonPath("$.edges[0].inverse").value("OWNED_BY_REPO"))
            .andExpect(jsonPath("$.edges[0].from").value(payments.id))
            .andExpect(jsonPath("$.edges[0].to").value(bucket.id))
            .andExpect(jsonPath("$.edges[0].confidence").value(0.7))
            .andExpect(jsonPath("$.edges[0].inferred").value(true))
            .andExpect(jsonPath("$.edges[0].rule").value("iac"))
    }

    @Test
    fun `defaults to depth 1, both directions and no filters`() {
        whenever(graphQueryUseCase.neighbourhood(any())).thenReturn(subgraph)

        mockMvc.perform(get("/api/v1/graph/neighbourhood").param("nodeId", payments.id)).andExpect(status().isOk)

        val spec = argumentCaptor<NeighbourhoodSpec>().apply { verify(graphQueryUseCase).neighbourhood(capture()) }.firstValue
        assertEquals(NeighbourhoodSpec(payments.key), spec)
        assertEquals(1, spec.depth)
        assertEquals(Direction.BOTH, spec.direction)
    }

    @Test
    fun `passes the filters through, comma separated`() {
        whenever(graphQueryUseCase.neighbourhood(any())).thenReturn(subgraph)

        mockMvc
            .perform(
                get("/api/v1/graph/neighbourhood")
                    .param("nodeId", payments.id)
                    .param("depth", "3")
                    .param("nodeTypes", "Repository,Team")
                    .param("edgeTypes", "DEPENDS_ON, OWNED_BY")
                    .param("direction", "out"),
            ).andExpect(status().isOk)

        val spec = argumentCaptor<NeighbourhoodSpec>().apply { verify(graphQueryUseCase).neighbourhood(capture()) }.firstValue
        assertEquals(
            NeighbourhoodSpec(
                nodeId = payments.key,
                depth = 3,
                nodeTypes = setOf("Repository", "Team"),
                edgeTypes = setOf("DEPENDS_ON", "OWNED_BY"),
                direction = Direction.OUTGOING,
            ),
            spec,
        )
    }

    @Test
    fun `a depth outside 1 to 3 is a 400 naming depth, and the graph is not asked`() {
        listOf("0", "4", "two").forEach { depth ->
            mockMvc
                .perform(get("/api/v1/graph/neighbourhood").param("nodeId", payments.id).param("depth", depth))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.field").value("depth"))
                .andExpect(jsonPath("$.error").isNotEmpty)
        }
        verify(graphQueryUseCase, never()).neighbourhood(any())
    }

    @Test
    fun `a direction other than in, out or both is a 400 naming direction`() {
        mockMvc
            .perform(get("/api/v1/graph/neighbourhood").param("nodeId", payments.id).param("direction", "sideways"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.field").value("direction"))
    }

    @Test
    fun `a missing or malformed node id is a 400 naming nodeId`() {
        mockMvc.perform(get("/api/v1/graph/neighbourhood")).andExpect(status().isBadRequest).andExpect(jsonPath("$.field").value("nodeId"))
        mockMvc
            .perform(get("/api/v1/graph/neighbourhood").param("nodeId", "not-a-node-id"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.field").value("nodeId"))
    }

    @Test
    fun `an undeclared type the service refuses is a 400 naming the filter`() {
        whenever(graphQueryUseCase.neighbourhood(any())).thenThrow(InvalidQueryParameterException("edgeTypes", "unknown edge type 'KNOWS'"))

        mockMvc
            .perform(get("/api/v1/graph/neighbourhood").param("nodeId", payments.id).param("edgeTypes", "KNOWS"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.field").value("edgeTypes"))
    }

    @Test
    fun `a node the graph does not hold is a 404`() {
        whenever(graphQueryUseCase.neighbourhood(any())).thenThrow(NodeNotFoundException(listOf(payments.key)))

        mockMvc
            .perform(get("/api/v1/graph/neighbourhood").param("nodeId", payments.id))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("node not found"))
    }
}
