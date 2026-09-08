package com.repodatagraph.adapter.`in`.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.repodatagraph.domain.exception.ImmutableIdentityException
import com.repodatagraph.domain.exception.NodeExistsException
import com.repodatagraph.domain.exception.NodeHasEdgesException
import com.repodatagraph.domain.exception.NodeTypeNotFoundException
import com.repodatagraph.domain.exception.NodeValidationException
import com.repodatagraph.domain.exception.PropertyError
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.NodePage
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.`in`.NodeUseCase
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * The HTTP contract of the generic node API: statuses, bodies and the shape of each refusal.
 *
 * A form can only tell a user what to fix if the refusal names the field, so the negative cases here
 * assert on the body, not just the status.
 */
@WebMvcTest(NodeController::class)
@Import(NodeRestExceptionHandler::class)
class NodeControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoBean
    private lateinit var nodeUseCase: NodeUseCase

    private val platform =
        GraphNode(
            key = NodeKey("Team", "platform"),
            props = mapOf("name" to "platform"),
            provenance = Provenance.manual(Instant.parse("2026-01-01T00:00:00Z")),
        )

    @Test
    fun `POST returns 201, the derived identity and a Location header`() {
        whenever(nodeUseCase.create(eq("Team"), any())).thenReturn(platform)

        mockMvc
            .perform(body(post("/api/v1/nodes/Team"), mapOf("props" to mapOf("name" to "platform"))))
            .andExpect(status().isCreated)
            .andExpect(header().string("Location", "/api/v1/nodes/Team/platform"))
            .andExpect(jsonPath("$.id").value("Team:platform"))
            .andExpect(jsonPath("$.type").value("Team"))
            .andExpect(jsonPath("$.key").value("platform"))
            .andExpect(jsonPath("$.props.name").value("platform"))
            .andExpect(jsonPath("$.provenance.sourceSystem").value("manual"))
    }

    @Test
    fun `POST to a type the registry does not declare returns 404`() {
        whenever(nodeUseCase.create(eq("Widget"), any())).thenThrow(NodeTypeNotFoundException("Widget"))

        mockMvc
            .perform(body(post("/api/v1/nodes/Widget"), mapOf("props" to mapOf("name" to "x"))))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("unknown node type"))
            .andExpect(jsonPath("$.type").value("Widget"))
    }

    @Test
    fun `a validation failure returns 400 naming every field at fault`() {
        whenever(nodeUseCase.create(eq("Team"), any()))
            .thenThrow(
                NodeValidationException(
                    listOf(
                        PropertyError("name", "name is required"),
                        PropertyError("colour", "not in ontology"),
                    ),
                ),
            )

        mockMvc
            .perform(body(post("/api/v1/nodes/Team"), mapOf("props" to mapOf("colour" to "blue"))))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("name"))
            .andExpect(jsonPath("$.errors[0].message").value("name is required"))
            .andExpect(jsonPath("$.errors[1].field").value("colour"))
            .andExpect(jsonPath("$.errors[1].message").value("not in ontology"))
    }

    @Test
    fun `an identity collision returns 409 with the id that already holds the key`() {
        whenever(nodeUseCase.create(eq("Team"), any())).thenThrow(NodeExistsException("Team:platform"))

        mockMvc
            .perform(body(post("/api/v1/nodes/Team"), mapOf("props" to mapOf("name" to "platform"))))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("node exists"))
            .andExpect(jsonPath("$.existingId").value("Team:platform"))
    }

    @Test
    fun `PUT returns 200 and the updated node`() {
        whenever(nodeUseCase.update(eq("Team"), eq("platform"), any())).thenReturn(platform)

        mockMvc
            .perform(body(put("/api/v1/nodes/Team/platform"), mapOf("props" to mapOf("name" to "platform"))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.key").value("platform"))
    }

    @Test
    fun `PUT that would move the node to another identity returns 409 naming the fields`() {
        whenever(nodeUseCase.update(eq("Team"), eq("platform"), any()))
            .thenThrow(ImmutableIdentityException(listOf("name")))

        mockMvc
            .perform(body(put("/api/v1/nodes/Team/platform"), mapOf("props" to mapOf("name" to "core"))))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("identity properties are immutable"))
            .andExpect(jsonPath("$.fields[0]").value("name"))
    }

    @Test
    fun `GET one returns the node`() {
        whenever(nodeUseCase.get("Team", "platform")).thenReturn(platform)

        mockMvc
            .perform(get("/api/v1/nodes/Team/platform"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.key").value("platform"))
    }

    @Test
    fun `GET one returns 404 when there is no such node`() {
        whenever(nodeUseCase.get("Team", "nobody")).thenReturn(null)

        mockMvc.perform(get("/api/v1/nodes/Team/nobody")).andExpect(status().isNotFound)
    }

    @Test
    fun `a key containing slashes survives the path`() {
        val repository =
            GraphNode(
                key = NodeKey("Repository", "github.com/acme/payments"),
                props = mapOf("orgRepo" to "acme/payments"),
                provenance = Provenance.manual(Instant.parse("2026-01-01T00:00:00Z")),
            )
        whenever(nodeUseCase.get("Repository", "github.com/acme/payments")).thenReturn(repository)

        mockMvc
            .perform(get("/api/v1/nodes/Repository/github.com/acme/payments"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.key").value("github.com/acme/payments"))
    }

    @Test
    fun `GET many returns a page with its cursor`() {
        whenever(nodeUseCase.list("Team", 50, null)).thenReturn(NodePage(listOf(platform), nextCursor = "platform"))

        mockMvc
            .perform(get("/api/v1/nodes/Team"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].key").value("platform"))
            .andExpect(jsonPath("$.nextCursor").value("platform"))
    }

    @Test
    fun `DELETE returns 204`() {
        mockMvc.perform(delete("/api/v1/nodes/Team/platform")).andExpect(status().isNoContent)

        verify(nodeUseCase).delete("Team", "platform", false)
    }

    @Test
    fun `DELETE passes cascade through`() {
        mockMvc.perform(delete("/api/v1/nodes/Team/platform?cascade=true")).andExpect(status().isNoContent)

        verify(nodeUseCase).delete("Team", "platform", true)
    }

    @Test
    fun `DELETE of a node that still has edges returns 409 and says how many`() {
        whenever(nodeUseCase.delete("Team", "platform", false)).thenThrow(NodeHasEdgesException(3))

        mockMvc
            .perform(delete("/api/v1/nodes/Team/platform"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("node has edges"))
            .andExpect(jsonPath("$.edgeCount").value(3))
    }

    private fun body(
        builder: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder,
        payload: Any,
    ) = builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(payload))
}
