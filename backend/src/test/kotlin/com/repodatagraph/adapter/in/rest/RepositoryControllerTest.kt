package com.repodatagraph.adapter.`in`.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.repodatagraph.adapter.`in`.rest.dto.CreateRepositoryRequest
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.model.Repository
import com.repodatagraph.domain.ontology.IdentityResolver
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

    /**
     * Lookup by canonical key is what a connector needs: it has a remote in some notation and wants
     * the node, without knowing the id the graph happened to assign. The key it supplies goes through
     * the same normalisation as the key that was stored, or the two would only match when the caller
     * already spelled it the way we do - which is the very problem #8 exists to remove.
     */
    @Test
    fun `GET by-key returns the repository for its canonical key`() {
        whenever(nodeUseCase.get(eq("Repository"), eq("github.com/acme/payments"))).thenReturn(storedRepository())

        mockMvc
            .perform(get("/api/v1/repositories/by-key").param("key", "github.com/acme/payments"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value("Repository:github.com/acme/payments"))
    }

    @Test
    fun `GET by-key normalises the key it is given`() {
        whenever(nodeUseCase.get(eq("Repository"), eq("github.com/acme/payments"))).thenReturn(storedRepository())

        mockMvc
            .perform(get("/api/v1/repositories/by-key").param("key", "git@github.com:Acme/Payments.git"))
            .andExpect(status().isOk)
    }

    @Test
    fun `GET by-key returns 404 when nothing holds that key`() {
        whenever(nodeUseCase.get(eq("Repository"), any())).thenReturn(null)

        mockMvc
            .perform(get("/api/v1/repositories/by-key").param("key", "github.com/acme/nothing-here"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `GET by-key refuses something that is not a git remote`() {
        mockMvc
            .perform(get("/api/v1/repositories/by-key").param("key", "https://example.com/page"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid git remote"))
    }

    private fun storedRepository() =
        GraphNode(
            key = NodeKey("Repository", "github.com/acme/payments"),
            props =
                mapOf(
                    "url" to "https://github.com/acme/payments",
                    "host" to "github.com",
                    "org" to "acme",
                    "name" to "payments",
                    "defaultBranch" to "main",
                    "topics" to emptyList<String>(),
                    "codeowners" to emptyList<String>(),
                ),
            provenance = Provenance(sourceSystem = "manual", ingestedAt = Instant.EPOCH, validFrom = Instant.EPOCH),
        )
}
