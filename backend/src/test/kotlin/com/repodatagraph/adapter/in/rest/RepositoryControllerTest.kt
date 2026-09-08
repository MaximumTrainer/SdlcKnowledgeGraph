package com.repodatagraph.adapter.`in`.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.repodatagraph.adapter.`in`.rest.dto.CreateRepositoryRequest
import com.repodatagraph.domain.model.EdgeWrite
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.model.Repository
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.port.`in`.EdgeUseCase
import com.repodatagraph.domain.port.`in`.NodeUseCase
import com.repodatagraph.domain.port.`in`.RepositoryUseCase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

@WebMvcTest(RepositoryController::class)
@Import(IdentityResolver::class)
class RepositoryControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoBean
    private lateinit var repositoryUseCase: RepositoryUseCase

    @MockitoBean
    private lateinit var nodeUseCase: NodeUseCase

    @MockitoBean
    private lateinit var edgeUseCase: EdgeUseCase

    /**
     * The endpoint is superseded by POST /api/v1/nodes/Repository (#4) and now delegates to it, so
     * that registry validation, derived identity and provenance apply to it as well. These assert
     * both halves of that: one write path, and a response that says the endpoint is on its way out.
     */
    @Test
    fun `POST repositories registers through the node use case and returns 201`() {
        val request = CreateRepositoryRequest(orgRepo = "acme/payments")
        whenever(nodeUseCase.create(eq("Repository"), any())).thenReturn(
            GraphNode(
                key = NodeKey("Repository", "github.com/acme/payments"),
                props = mapOf("orgRepo" to "acme/payments", "url" to "https://github.com/acme/payments"),
                provenance = Provenance.manual(Instant.parse("2026-01-01T00:00:00Z")),
            ),
        )

        mockMvc
            .perform(
                post("/api/v1/repositories")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isCreated)
            .andExpect(header().string("Deprecation", "true"))
            .andExpect(header().string("Link", """</api/v1/nodes/Repository>; rel="successor-version""""))
            .andExpect(jsonPath("$.id").value("Repository:github.com/acme/payments"))
            .andExpect(jsonPath("$.orgRepo").value("acme/payments"))

        verify(repositoryUseCase, never()).registerRepository(any())
    }

    @Test
    fun `POST repositories expands the org-repo shorthand to a canonical remote`() {
        whenever(nodeUseCase.create(eq("Repository"), any())).thenReturn(
            GraphNode(
                key = NodeKey("Repository", "github.com/acme/payments"),
                props = emptyMap(),
                provenance = Provenance.manual(Instant.parse("2026-01-01T00:00:00Z")),
            ),
        )

        mockMvc
            .perform(
                post("/api/v1/repositories")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(CreateRepositoryRequest(orgRepo = "Acme/Payments"))),
            ).andExpect(status().isCreated)

        val props = argumentCaptor<Map<String, Any?>>()
        verify(nodeUseCase).create(eq("Repository"), props.capture())
        assertThat(props.firstValue["url"]).isEqualTo("https://github.com/acme/payments")
    }

    /**
     * The five bespoke link endpoints are superseded by POST /api/v1/edges (#5). They now delegate to
     * the same use case, so registry validation and provenance apply to them too, and they say so in
     * their headers.
     *
     * Note the ids here have no slashes. Under the derived identity scheme a Repository id is
     * `Repository:host/org/name`, which cannot sit in a mid-path segment, so these endpoints are in
     * practice unreachable for repositories; see the deprecation note in the controller.
     */
    @Test
    fun `linking to a team goes through the edge use case and says it is deprecated`() {
        whenever(edgeUseCase.create(any())).thenReturn(
            EdgeWrite(
                GraphEdge("OWNED_BY", NodeKey("Repository", "r1"), NodeKey("Team", "platform"), emptyMap(), Provenance.manual()),
                inverse = "OWNS",
                created = true,
            ),
        )

        mockMvc
            .perform(post("/api/v1/repositories/r1/teams/platform"))
            .andExpect(status().isOk)
            .andExpect(header().string("Deprecation", "true"))
            .andExpect(header().string("Link", """</api/v1/edges>; rel="successor-version""""))

        verify(repositoryUseCase, never()).linkToTeam(any(), any())
    }

    @Test
    fun `adding a dependency states the kind the edge type requires`() {
        whenever(edgeUseCase.create(any())).thenReturn(
            EdgeWrite(
                GraphEdge(
                    "DEPENDS_ON",
                    NodeKey("Repository", "r1"),
                    NodeKey("Repository", "r2"),
                    mapOf("kind" to "api"),
                    Provenance.manual(),
                ),
                inverse = "DEPENDED_ON_BY",
                created = true,
            ),
        )

        mockMvc
            .perform(post("/api/v1/repositories/r1/dependencies/r2").param("kind", "api"))
            .andExpect(status().isOk)
            .andExpect(header().string("Deprecation", "true"))

        val request = argumentCaptor<com.repodatagraph.domain.model.EdgeRequest>()
        verify(edgeUseCase).create(request.capture())
        assertThat(request.firstValue.props["kind"]).isEqualTo("api")
    }

    @Test
    fun `GET repositories returns list`() {
        whenever(repositoryUseCase.listRepositories()).thenReturn(
            listOf(Repository(id = "1", orgRepo = "org/repo")),
        )

        mockMvc
            .perform(get("/api/v1/repositories"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].orgRepo").value("org/repo"))
    }

    @Test
    fun `GET repository by id returns 200`() {
        whenever(repositoryUseCase.getRepository("1")).thenReturn(
            Repository(id = "1", orgRepo = "org/repo"),
        )

        mockMvc
            .perform(get("/api/v1/repositories/1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value("1"))
    }

    @Test
    fun `GET repository by id returns 404 when not found`() {
        whenever(repositoryUseCase.getRepository("999")).thenReturn(null)

        mockMvc
            .perform(get("/api/v1/repositories/999"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `DELETE repository returns 204`() {
        mockMvc
            .perform(delete("/api/v1/repositories/1"))
            .andExpect(status().isNoContent)
        verify(repositoryUseCase).deleteRepository("1")
    }
}
