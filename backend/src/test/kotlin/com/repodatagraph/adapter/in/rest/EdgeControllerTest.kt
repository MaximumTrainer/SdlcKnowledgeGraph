package com.repodatagraph.adapter.`in`.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.repodatagraph.domain.exception.EdgeNotAllowedException
import com.repodatagraph.domain.exception.EdgeValidationException
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.exception.PropertyError
import com.repodatagraph.domain.exception.SelfEdgeException
import com.repodatagraph.domain.exception.UnknownEdgeTypeException
import com.repodatagraph.domain.model.Direction
import com.repodatagraph.domain.model.EdgeView
import com.repodatagraph.domain.model.EdgeWrite
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.`in`.EdgeUseCase
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * The HTTP contract of the edge API: statuses, bodies and the shape of each refusal.
 *
 * The refusals carry the information a caller needs to act — which pairs are allowed, which node was
 * missing, which property was wrong — so they are asserted on the body, not just the status.
 */
@WebMvcTest(EdgeController::class)
@Import(EdgeRestExceptionHandler::class, RestExceptionHandler::class)
class EdgeControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoBean
    private lateinit var edgeUseCase: EdgeUseCase

    private val provenance = Provenance.manual(Instant.parse("2026-01-01T00:00:00Z"))
    private val payments = NodeKey("Repository", "github.com/acme/payments")
    private val sharedLib = NodeKey("Repository", "github.com/acme/shared-lib")

    private val edge =
        GraphEdge(
            type = "DEPENDS_ON",
            from = payments,
            to = sharedLib,
            props = mapOf("kind" to "library"),
            provenance = provenance,
        )

    private fun body(payload: Any) = objectMapper.writeValueAsString(payload)

    private val createRequest =
        mapOf(
            "type" to "DEPENDS_ON",
            "fromId" to payments.id,
            "toId" to sharedLib.id,
            "props" to mapOf("kind" to "library"),
        )

    @Test
    fun `POST returns 201 with the inverse and both ends`() {
        whenever(edgeUseCase.create(any())).thenReturn(EdgeWrite(edge, "DEPENDED_ON_BY", created = true))

        mockMvc
            .perform(post("/api/v1/edges").contentType(MediaType.APPLICATION_JSON).content(body(createRequest)))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.type").value("DEPENDS_ON"))
            .andExpect(jsonPath("$.inverse").value("DEPENDED_ON_BY"))
            .andExpect(jsonPath("$.from.id").value(payments.id))
            .andExpect(jsonPath("$.from.type").value("Repository"))
            .andExpect(jsonPath("$.to.key").value("github.com/acme/shared-lib"))
            .andExpect(jsonPath("$.props.kind").value("library"))
            .andExpect(jsonPath("$.provenance.sourceSystem").value("manual"))
    }

    @Test
    fun `POSTing the same edge again returns 200, not a second edge`() {
        whenever(edgeUseCase.create(any())).thenReturn(EdgeWrite(edge, "DEPENDED_ON_BY", created = false))

        mockMvc
            .perform(post("/api/v1/edges").contentType(MediaType.APPLICATION_JSON).content(body(createRequest)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.inverse").value("DEPENDED_ON_BY"))
    }

    @Test
    fun `an undeclared edge type is refused`() {
        whenever(edgeUseCase.create(any())).thenThrow(UnknownEdgeTypeException("SMELLS_LIKE"))

        mockMvc
            .perform(post("/api/v1/edges").contentType(MediaType.APPLICATION_JSON).content(body(createRequest)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("unknown edge type"))
            .andExpect(jsonPath("$.type").value("SMELLS_LIKE"))
    }

    @Test
    fun `a disallowed pair is refused, and the response says which pairs are allowed`() {
        whenever(edgeUseCase.create(any()))
            .thenThrow(
                EdgeNotAllowedException(
                    type = "OWNED_BY",
                    from = "Environment",
                    to = "Team",
                    allowed = listOf("Repository" to "Team", "Service" to "Team"),
                ),
            )

        mockMvc
            .perform(post("/api/v1/edges").contentType(MediaType.APPLICATION_JSON).content(body(createRequest)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("edge not allowed"))
            .andExpect(jsonPath("$.allowed[0].from").value("Repository"))
            .andExpect(jsonPath("$.allowed[0].to").value("Team"))
    }

    @Test
    fun `an edge to a node that does not exist names the missing end`() {
        whenever(edgeUseCase.create(any())).thenThrow(NodeNotFoundException(listOf(NodeKey("Team", "nobody"))))

        mockMvc
            .perform(post("/api/v1/edges").contentType(MediaType.APPLICATION_JSON).content(body(createRequest)))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("node not found"))
            .andExpect(jsonPath("$.missing[0]").value("Team:nobody"))
    }

    @Test
    fun `an invalid edge property names the field and the values allowed`() {
        whenever(edgeUseCase.create(any()))
            .thenThrow(
                EdgeValidationException(
                    listOf(PropertyError("kind", "kind must be one of library, api, event, data")),
                ),
            )

        mockMvc
            .perform(post("/api/v1/edges").contentType(MediaType.APPLICATION_JSON).content(body(createRequest)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("kind"))
            .andExpect(jsonPath("$.errors[0].message").value("kind must be one of library, api, event, data"))
    }

    @Test
    fun `a self edge is refused`() {
        whenever(edgeUseCase.create(any())).thenThrow(SelfEdgeException(payments.id))

        mockMvc
            .perform(post("/api/v1/edges").contentType(MediaType.APPLICATION_JSON).content(body(createRequest)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("self edge"))
    }

    @Test
    fun `DELETE removes by the exact triple and returns 204`() {
        whenever(edgeUseCase.delete("DEPENDS_ON", payments.id, sharedLib.id)).thenReturn(true)

        mockMvc
            .perform(delete("/api/v1/edges").param("type", "DEPENDS_ON").param("fromId", payments.id).param("toId", sharedLib.id))
            .andExpect(status().isNoContent)

        verify(edgeUseCase).delete("DEPENDS_ON", payments.id, sharedLib.id)
    }

    @Test
    fun `DELETE of an edge that is not there returns 404`() {
        whenever(edgeUseCase.delete(any(), any(), any())).thenReturn(false)

        mockMvc
            .perform(delete("/api/v1/edges").param("type", "DEPENDS_ON").param("fromId", payments.id).param("toId", sharedLib.id))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `the edges of a node are listed under the name that end sees`() {
        whenever(edgeUseCase.forNode(eq("Repository"), eq("github.com/acme/shared-lib"), eq(Direction.INCOMING), eq(null)))
            .thenReturn(
                listOf(
                    EdgeView(
                        type = "DEPENDS_ON",
                        inverse = "DEPENDED_ON_BY",
                        direction = Direction.INCOMING,
                        displayName = "DEPENDED_ON_BY",
                        other = GraphNode(payments, mapOf("url" to "https://github.com/acme/payments"), provenance),
                        props = mapOf("kind" to "library"),
                        provenance = provenance,
                    ),
                ),
            )

        mockMvc
            .perform(get("/api/v1/edges").param("nodeId", sharedLib.id).param("direction", "in"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].type").value("DEPENDS_ON"))
            .andExpect(jsonPath("$.items[0].displayName").value("DEPENDED_ON_BY"))
            .andExpect(jsonPath("$.items[0].direction").value("in"))
            .andExpect(jsonPath("$.items[0].other.key").value("github.com/acme/payments"))
            .andExpect(jsonPath("$.items[0].props.kind").value("library"))
    }

    @Test
    fun `listing defaults to both directions and no type filter`() {
        whenever(edgeUseCase.forNode(any(), any(), any(), eq(null))).thenReturn(emptyList())

        mockMvc.perform(get("/api/v1/edges").param("nodeId", "Team:platform")).andExpect(status().isOk)

        verify(edgeUseCase).forNode("Team", "platform", Direction.BOTH, null)
    }

    @Test
    fun `listing can be filtered to one edge type`() {
        whenever(edgeUseCase.forNode(any(), any(), any(), eq("OWNED_BY"))).thenReturn(emptyList())

        mockMvc
            .perform(get("/api/v1/edges").param("nodeId", "Team:platform").param("edgeType", "OWNED_BY"))
            .andExpect(status().isOk)

        verify(edgeUseCase).forNode("Team", "platform", Direction.BOTH, "OWNED_BY")
    }
}
