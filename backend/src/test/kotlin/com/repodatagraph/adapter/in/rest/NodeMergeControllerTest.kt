package com.repodatagraph.adapter.`in`.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.repodatagraph.application.freshness.FactFreshness
import com.repodatagraph.domain.exception.InvalidMergeException
import com.repodatagraph.domain.exception.MergeConflictException
import com.repodatagraph.domain.exception.MergeIntoRetiredException
import com.repodatagraph.domain.exception.NodeAlreadyMergedException
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.identity.EdgeMoves
import com.repodatagraph.domain.identity.MergeConflict
import com.repodatagraph.domain.identity.MergeOutcome
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.`in`.NodeMergeUseCase
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * The HTTP contract of merging two nodes (#98): `POST /api/v1/nodes/{type}/{key}/merge` with
 * `{into, dryRun}`, what a merge answers, and the shape of each refusal. What a merge does to the
 * graph is the service's and the store's, tested there.
 */
@WebMvcTest(NodeMergeController::class)
@Import(NodeRestExceptionHandler::class, RestExceptionHandler::class)
class NodeMergeControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoBean
    private lateinit var merges: NodeMergeUseCase

    /** Whether a fact read back is stale (#93); never, unless a test says otherwise. */
    @MockitoBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var factFreshness: FactFreshness

    private val from = NodeKey("Repository", "github.com/acme/payments")
    private val into = NodeKey("Repository", "github.com/acme/payments-service")

    private val merged =
        MergeOutcome(
            from = from,
            into = into,
            dryRun = false,
            node =
                GraphNode(
                    into,
                    mapOf("url" to "https://github.com/acme/payments-service", "description" to "Card payments"),
                    Provenance.manual(Instant.parse("2026-01-01T00:00:00Z")).copy(previousKeys = listOf(from.key)),
                ),
            gained = listOf("description"),
            kept = listOf("defaultBranch"),
            edges = EdgeMoves(moved = 3, collapsed = 1, dropped = 0),
            previousKeys = listOf(from.key),
            redirected = 0,
        )

    @Test
    fun `a merge answers 200 with the node that stays and what moved onto it`() {
        whenever(merges.merge("Repository", from.key, into.key, false)).thenReturn(merged)

        mockMvc
            .perform(json(post("/api/v1/nodes/Repository/github.com/acme/payments/merge"), mapOf("into" to into.key)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.dryRun").value(false))
            .andExpect(jsonPath("$.from").value(from.id))
            .andExpect(jsonPath("$.into").value(into.id))
            .andExpect(jsonPath("$.node.key").value(into.key))
            .andExpect(jsonPath("$.node.provenance.previousKeys[0]").value(from.key))
            .andExpect(jsonPath("$.gained[0]").value("description"))
            .andExpect(jsonPath("$.kept[0]").value("defaultBranch"))
            .andExpect(jsonPath("$.edges.moved").value(3))
            .andExpect(jsonPath("$.edges.collapsed").value(1))
            .andExpect(jsonPath("$.edges.dropped").value(0))
            .andExpect(jsonPath("$.previousKeys[0]").value(from.key))
            .andExpect(jsonPath("$.redirected").value(0))
    }

    @Test
    fun `a dry run is passed on, and answered as one`() {
        whenever(merges.merge("Repository", from.key, into.key, true)).thenReturn(merged.copy(dryRun = true))

        mockMvc
            .perform(
                json(
                    post("/api/v1/nodes/Repository/github.com/acme/payments/merge"),
                    mapOf("into" to into.key, "dryRun" to true),
                ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.dryRun").value(true))

        verify(merges).merge("Repository", from.key, into.key, true)
    }

    @Test
    fun `a node no path can carry is merged by key`() {
        val uri = "chorus://task/42"
        whenever(merges.merge(eq("ExternalWorkItem"), eq(uri), any(), any())).thenReturn(merged)

        mockMvc
            .perform(
                json(post("/api/v1/nodes/ExternalWorkItem/by-key/merge").param("key", uri), mapOf("into" to "chorus://task/43")),
            ).andExpect(status().isOk)

        verify(merges).merge("ExternalWorkItem", uri, "chorus://task/43", false)
    }

    @Test
    fun `a merge without into is refused with 400, naming the field`() {
        mockMvc
            .perform(json(post("/api/v1/nodes/Repository/github.com/acme/payments/merge"), mapOf("dryRun" to true)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("into"))

        verifyNoInteractions(merges)
    }

    @Test
    fun `a POST under a node that is not a merge is not a route`() {
        mockMvc
            .perform(json(post("/api/v1/nodes/Repository/github.com/acme/payments"), mapOf("into" to into.key)))
            .andExpect(status().isNotFound)

        verifyNoInteractions(merges)
    }

    @Test
    fun `conflicting identities are refused with 409 naming each field and both values`() {
        whenever(merges.merge(any(), any(), any(), any()))
            .thenThrow(MergeConflictException(listOf(MergeConflict("host", "github.com", "gitlab.com"))))

        mockMvc
            .perform(json(post("/api/v1/nodes/Repository/github.com/acme/payments/merge"), mapOf("into" to "gitlab.com/acme/payments")))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("identity conflict"))
            .andExpect(jsonPath("$.fields[0]").value("host"))
            .andExpect(jsonPath("$.conflicts[0].field").value("host"))
            .andExpect(jsonPath("$.conflicts[0].from").value("github.com"))
            .andExpect(jsonPath("$.conflicts[0].into").value("gitlab.com"))
    }

    @Test
    fun `a merge into itself or across types is refused with 400 saying why`() {
        whenever(merges.merge(any(), any(), any(), any()))
            .thenThrow(InvalidMergeException("a Repository cannot be merged into a Team"))

        mockMvc
            .perform(json(post("/api/v1/nodes/Repository/github.com/acme/payments/merge"), mapOf("into" to "Team:billing")))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid merge"))
            .andExpect(jsonPath("$.reason").value("a Repository cannot be merged into a Team"))
    }

    @Test
    fun `a merge into a retired node is refused with 409 naming it`() {
        whenever(merges.merge(any(), any(), any(), any())).thenThrow(MergeIntoRetiredException(into))

        mockMvc
            .perform(json(post("/api/v1/nodes/Repository/github.com/acme/payments/merge"), mapOf("into" to into.key)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("merge into a retired node"))
            .andExpect(jsonPath("$.into").value(into.id))
    }

    @Test
    fun `a node already merged is refused with 409 naming where it went`() {
        whenever(merges.merge(any(), any(), any(), any())).thenThrow(NodeAlreadyMergedException(from, into))

        mockMvc
            .perform(json(post("/api/v1/nodes/Repository/github.com/acme/payments/merge"), mapOf("into" to into.key)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("node already merged"))
            .andExpect(jsonPath("$.nodeId").value(from.id))
            .andExpect(jsonPath("$.mergedInto").value(into.id))
    }

    @Test
    fun `a node that does not exist is 404 naming it`() {
        whenever(merges.merge(any(), any(), any(), anyOrNull())).thenThrow(NodeNotFoundException(listOf(into)))

        mockMvc
            .perform(json(post("/api/v1/nodes/Repository/github.com/acme/payments/merge"), mapOf("into" to into.key)))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("node not found"))
            .andExpect(jsonPath("$.missing[0]").value(into.id))
    }

    private fun json(
        request: MockHttpServletRequestBuilder,
        body: Any,
    ): MockHttpServletRequestBuilder = request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))
}
