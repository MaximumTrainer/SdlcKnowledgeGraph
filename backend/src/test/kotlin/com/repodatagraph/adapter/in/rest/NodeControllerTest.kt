package com.repodatagraph.adapter.`in`.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.repodatagraph.domain.exception.ImmutableIdentityException
import com.repodatagraph.domain.exception.ManagedNodeTypeException
import com.repodatagraph.domain.exception.NodeExistsException
import com.repodatagraph.domain.exception.NodeHasEdgesException
import com.repodatagraph.domain.exception.NodeTypeNotFoundException
import com.repodatagraph.domain.exception.NodeValidationException
import com.repodatagraph.domain.exception.PropertyError
import com.repodatagraph.domain.exception.UnknownSourceSystemException
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
    fun `a node's provenance says who wrote it`() {
        val written = platform.copy(provenance = platform.provenance.copy(writtenBy = "dan", principalType = "user"))
        whenever(nodeUseCase.get("Team", "platform")).thenReturn(written)

        mockMvc
            .perform(get("/api/v1/nodes/Team/platform"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.provenance.writtenBy").value("dan"))
            .andExpect(jsonPath("$.provenance.principalType").value("user"))
    }

    @Test
    fun `a key holding characters a URI reserves is encoded in the Location, not refused`() {
        val key = "ghcr.io/acme/payments@sha256:1#production#1790769600"
        whenever(nodeUseCase.create(eq("Deployment"), any(), any()))
            .thenReturn(GraphNode(NodeKey("Deployment", key), mapOf("status" to "SUCCESS"), Provenance.manual()))

        mockMvc
            .perform(body(post("/api/v1/nodes/Deployment"), mapOf("props" to mapOf("status" to "SUCCESS"))))
            .andExpect(status().isCreated)
            .andExpect(header().string("Location", "/api/v1/nodes/Deployment/ghcr.io/acme/payments@sha256:1%23production%231790769600"))
    }

    @Test
    fun `a node a service principal wrote names the team it acted for`() {
        val written =
            platform.copy(
                provenance =
                    platform.provenance.copy(writtenBy = "triage-agent", principalType = "service", onBehalfOfTeam = "team-payments"),
            )
        whenever(nodeUseCase.get("Team", "platform")).thenReturn(written)

        mockMvc
            .perform(get("/api/v1/nodes/Team/platform"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.provenance.writtenBy").value("triage-agent"))
            .andExpect(jsonPath("$.provenance.principalType").value("service"))
            .andExpect(jsonPath("$.provenance.onBehalfOfTeam").value("team-payments"))
    }

    @Test
    fun `a type with an API of its own is not written through the generic one`() {
        whenever(nodeUseCase.create(eq("ServicePrincipal"), any(), any()))
            .thenThrow(ManagedNodeTypeException("ServicePrincipal", "/api/v1/service-principals"))

        mockMvc
            .perform(body(post("/api/v1/nodes/ServicePrincipal"), mapOf("props" to mapOf("name" to "rogue-agent"))))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("managed node type"))
            .andExpect(jsonPath("$.type").value("ServicePrincipal"))
            .andExpect(jsonPath("$.managedAt").value("/api/v1/service-principals"))
    }

    @Test
    fun `POST returns 201, the derived identity and a Location header`() {
        whenever(nodeUseCase.create(eq("Team"), any(), any())).thenReturn(platform)

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
        whenever(nodeUseCase.create(eq("Widget"), any(), any())).thenThrow(NodeTypeNotFoundException("Widget"))

        mockMvc
            .perform(body(post("/api/v1/nodes/Widget"), mapOf("props" to mapOf("name" to "x"))))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("unknown node type"))
            .andExpect(jsonPath("$.type").value("Widget"))
    }

    @Test
    fun `a validation failure returns 400 naming every field at fault`() {
        whenever(nodeUseCase.create(eq("Team"), any(), any()))
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
        whenever(nodeUseCase.create(eq("Team"), any(), any())).thenThrow(NodeExistsException("Team:platform"))

        mockMvc
            .perform(body(post("/api/v1/nodes/Team"), mapOf("props" to mapOf("name" to "platform"))))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("node exists"))
            .andExpect(jsonPath("$.existingId").value("Team:platform"))
    }

    /**
     * A provider id is an alias beside the key (#88): a second node may not take one another holds,
     * and the refusal names the node that holds it and the alias that collided, so a caller can tell
     * this collision from a key collision and open the node that already exists.
     */
    @Test
    fun `a provider id another node holds returns 409 with that node's id and the alias (#88)`() {
        whenever(nodeUseCase.create(eq("Repository"), any(), any())).thenThrow(
            NodeExistsException("Repository:github.com/acme/payments", mapOf("provider" to "github", "providerId" to "123456")),
        )

        mockMvc
            .perform(
                body(
                    post("/api/v1/nodes/Repository"),
                    mapOf("props" to mapOf("url" to "https://github.com/other/thing", "providerId" to "123456")),
                ),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("node exists"))
            .andExpect(jsonPath("$.existingId").value("Repository:github.com/acme/payments"))
            .andExpect(jsonPath("$.alias.provider").value("github"))
            .andExpect(jsonPath("$.alias.providerId").value("123456"))
    }

    @Test
    fun `a key collision says nothing about an alias (#88)`() {
        whenever(nodeUseCase.create(eq("Team"), any(), any())).thenThrow(NodeExistsException("Team:platform"))

        mockMvc
            .perform(body(post("/api/v1/nodes/Team"), mapOf("props" to mapOf("name" to "platform"))))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.alias").doesNotExist())
    }

    /**
     * A PUT carrying the provider id the node already holds, with a different url, renames it in
     * place (#88, FR2): the answer is the node under its new key, with the old one recorded.
     */
    @Test
    fun `a PUT that renames through the provider id answers the node under its new key (#88)`() {
        val renamed =
            GraphNode(
                key = NodeKey("Repository", "github.com/acme-platform/payments-service"),
                props = mapOf("url" to "https://github.com/acme-platform/payments-service", "providerId" to "123456"),
                provenance =
                    Provenance
                        .manual(Instant.parse("2026-01-01T00:00:00Z"))
                        .copy(previousKeys = listOf("github.com/acme/payments")),
            )
        whenever(nodeUseCase.update(eq("Repository"), eq("github.com/acme/payments"), any(), any())).thenReturn(renamed)

        mockMvc
            .perform(
                body(
                    put("/api/v1/nodes/Repository/github.com/acme/payments"),
                    mapOf("props" to mapOf("url" to "https://github.com/acme-platform/payments-service", "providerId" to "123456")),
                ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value("Repository:github.com/acme-platform/payments-service"))
            .andExpect(jsonPath("$.key").value("github.com/acme-platform/payments-service"))
            .andExpect(jsonPath("$.provenance.previousKeys[0]").value("github.com/acme/payments"))
    }

    @Test
    fun `PUT returns 200 and the updated node`() {
        whenever(nodeUseCase.update(eq("Team"), eq("platform"), any(), any())).thenReturn(platform)

        mockMvc
            .perform(body(put("/api/v1/nodes/Team/platform"), mapOf("props" to mapOf("name" to "platform"))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.key").value("platform"))
    }

    @Test
    fun `the source a POST names is handed to the use case (#117)`() {
        whenever(nodeUseCase.create(eq("Team"), any(), any())).thenReturn(platform)

        mockMvc
            .perform(
                body(
                    post("/api/v1/nodes/Team"),
                    mapOf("props" to mapOf("name" to "platform"), "provenance" to mapOf("sourceSystem" to "github")),
                ),
            ).andExpect(status().isCreated)

        verify(nodeUseCase).create("Team", mapOf("name" to "platform"), "github")
    }

    @Test
    fun `a POST naming no source states a manual fact (#117)`() {
        whenever(nodeUseCase.create(eq("Team"), any(), any())).thenReturn(platform)

        mockMvc
            .perform(body(post("/api/v1/nodes/Team"), mapOf("props" to mapOf("name" to "platform"))))
            .andExpect(status().isCreated)

        verify(nodeUseCase).create("Team", mapOf("name" to "platform"), "manual")
    }

    @Test
    fun `the source a PUT names is handed to the use case (#117)`() {
        whenever(nodeUseCase.update(eq("Team"), eq("platform"), any(), any())).thenReturn(platform)

        mockMvc
            .perform(
                body(
                    put("/api/v1/nodes/Team/platform"),
                    mapOf("props" to mapOf("name" to "platform"), "provenance" to mapOf("sourceSystem" to "servicenow")),
                ),
            ).andExpect(status().isOk)

        verify(nodeUseCase).update("Team", "platform", mapOf("name" to "platform"), "servicenow")
    }

    @Test
    fun `a source the registry does not declare is a 400 listing the known ones (#117)`() {
        whenever(nodeUseCase.create(eq("Team"), any(), eq("jira")))
            .thenThrow(UnknownSourceSystemException("jira", listOf("manual", "github", "aws")))

        mockMvc
            .perform(
                body(
                    post("/api/v1/nodes/Team"),
                    mapOf("props" to mapOf("name" to "platform"), "provenance" to mapOf("sourceSystem" to "jira")),
                ),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("unknown source system"))
            .andExpect(jsonPath("$.sourceSystem").value("jira"))
            .andExpect(jsonPath("$.known.length()").value(3))
            .andExpect(jsonPath("$.known[0]").value("manual"))
            .andExpect(jsonPath("$.known[2]").value("aws"))
    }

    @Test
    fun `PUT that would move the node to another identity returns 409 naming the fields`() {
        whenever(nodeUseCase.update(eq("Team"), eq("platform"), any(), any()))
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
                props = mapOf("url" to "https://github.com/acme/payments"),
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

    private val workItem =
        GraphNode(
            key = NodeKey("ExternalWorkItem", "chorus://task/01JABC"),
            props = mapOf("uri" to "chorus://task/01JABC", "system" to "chorus"),
            provenance = Provenance.manual(Instant.parse("2026-01-01T00:00:00Z")),
        )

    @Test
    fun `a key no path segment can carry is read by key as a query parameter (#85)`() {
        whenever(nodeUseCase.get("ExternalWorkItem", "chorus://task/01JABC")).thenReturn(workItem)

        mockMvc
            .perform(get("/api/v1/nodes/ExternalWorkItem/by-key").param("key", "chorus://task/01JABC"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value("ExternalWorkItem:chorus://task/01JABC"))
            .andExpect(jsonPath("$.key").value("chorus://task/01JABC"))
    }

    @Test
    fun `reading by key answers 404 for a key nothing holds, and 400 without a key (#85)`() {
        whenever(nodeUseCase.get("ExternalWorkItem", "chorus://task/nothing")).thenReturn(null)

        mockMvc
            .perform(get("/api/v1/nodes/ExternalWorkItem/by-key").param("key", "chorus://task/nothing"))
            .andExpect(status().isNotFound)
        mockMvc
            .perform(get("/api/v1/nodes/ExternalWorkItem/by-key"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `a node addressed by key is updated and deleted the same way (#85)`() {
        whenever(nodeUseCase.update(eq("ExternalWorkItem"), eq("chorus://task/01JABC"), any(), any())).thenReturn(workItem)

        mockMvc
            .perform(
                body(put("/api/v1/nodes/ExternalWorkItem/by-key").param("key", "chorus://task/01JABC"), mapOf("props" to workItem.props)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.key").value("chorus://task/01JABC"))
        mockMvc
            .perform(delete("/api/v1/nodes/ExternalWorkItem/by-key").param("key", "chorus://task/01JABC").param("cascade", "true"))
            .andExpect(status().isNoContent)

        verify(nodeUseCase).delete("ExternalWorkItem", "chorus://task/01JABC", true)
    }

    @Test
    fun `a node whose key holds a double slash is located by key rather than by a path it cannot travel in (#85)`() {
        whenever(nodeUseCase.create(eq("ExternalWorkItem"), any(), any())).thenReturn(workItem)

        mockMvc
            .perform(body(post("/api/v1/nodes/ExternalWorkItem"), mapOf("props" to workItem.props)))
            .andExpect(status().isCreated)
            .andExpect(header().string("Location", "/api/v1/nodes/ExternalWorkItem/by-key?key=chorus://task/01JABC"))
    }

    private fun body(
        builder: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder,
        payload: Any,
    ) = builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(payload))
}
