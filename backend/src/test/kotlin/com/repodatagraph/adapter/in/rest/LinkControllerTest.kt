package com.repodatagraph.adapter.`in`.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.repodatagraph.domain.exception.CandidateDecidedException
import com.repodatagraph.domain.exception.CandidateNotFoundException
import com.repodatagraph.domain.exception.ManualLinkExistsException
import com.repodatagraph.domain.exception.ManualLinkNotFoundException
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.model.CandidateLink
import com.repodatagraph.domain.model.CandidatePage
import com.repodatagraph.domain.model.CandidateQuery
import com.repodatagraph.domain.model.CandidateStatus
import com.repodatagraph.domain.model.LinkScope
import com.repodatagraph.domain.model.LinkedRepository
import com.repodatagraph.domain.model.LinkedResource
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.OwnerLink
import com.repodatagraph.domain.model.ResolutionStarted
import com.repodatagraph.domain.port.`in`.LinkUseCase
import com.repodatagraph.domain.port.out.connector.SyncMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * The HTTP contract of the link engine (#28): starting a resolution, listing candidates, accepting
 * and rejecting one, and stating or closing a manual link. Which scope each route needs is
 * [com.repodatagraph.adapter.in.security.ScopePolicy]'s, tested there and in `LinkScopesWebTest`;
 * what a resolution writes is the engine's, tested there.
 */
@WebMvcTest(LinkController::class)
@Import(RestExceptionHandler::class)
class LinkControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoBean
    private lateinit var links: LinkUseCase

    private val queue = LinkedResource("aws:arn:aws:sqs:eu-west-1:1:billing-prod", "billing-prod", "aws", "1")
    private val billing = LinkedRepository("github.com/acme/billing", "billing")

    private val candidate =
        CandidateLink(
            id = "c0ffee00c0ffee00",
            resource = queue,
            repository = billing,
            confidence = 0.4,
            rule = "naming",
            evidence = mapOf("name" to "billing-prod", "normalised" to "billing"),
            status = CandidateStatus.PENDING,
            createdAt = Instant.parse("2026-09-30T12:00:00Z"),
        )

    private val owner =
        OwnerLink(
            resource = queue,
            repository = billing,
            rule = "manual",
            confidence = 1.0,
            inferred = false,
            evidence = mapOf("name" to "billing-prod"),
            acceptedBy = "dan",
            sourceSystem = "manual",
        )

    @Test
    fun `a resolution is started and answered 202 with its sync run`() {
        whenever(links.resolve(any())).thenReturn(ResolutionStarted("run-1", SyncMode.FULL))

        mockMvc
            .perform(json(post("/api/v1/links/resolve"), emptyMap<String, Any>()))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.syncRunId").value("run-1"))
            .andExpect(jsonPath("$.mode").value("FULL"))

        verify(links).resolve(LinkScope())
    }

    @Test
    fun `a resolution without a body is a full one`() {
        whenever(links.resolve(any())).thenReturn(ResolutionStarted("run-1", SyncMode.FULL))

        mockMvc.perform(post("/api/v1/links/resolve")).andExpect(status().isAccepted)

        verify(links).resolve(LinkScope())
    }

    @Test
    fun `a scoped resolution passes its scope on and runs incrementally`() {
        whenever(links.resolve(any())).thenReturn(ResolutionStarted("run-2", SyncMode.INCREMENTAL))

        mockMvc
            .perform(
                json(
                    post("/api/v1/links/resolve"),
                    mapOf("scope" to mapOf("provider" to "aws", "accountId" to "1", "repoKey" to "github.com/acme/billing")),
                ),
            ).andExpect(status().isAccepted)
            .andExpect(jsonPath("$.mode").value("INCREMENTAL"))

        verify(links).resolve(LinkScope(provider = "aws", accountId = "1", repoKey = "github.com/acme/billing"))
    }

    @Test
    fun `candidates are listed as a page, with each candidate's resource, repository and evidence`() {
        whenever(links.candidates(any())).thenReturn(CandidatePage(listOf(candidate), totalElements = 1))

        mockMvc
            .perform(get("/api/v1/links/candidates"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].id").value("c0ffee00c0ffee00"))
            .andExpect(jsonPath("$.items[0].resource.key").value(queue.key))
            .andExpect(jsonPath("$.items[0].resource.name").value("billing-prod"))
            .andExpect(jsonPath("$.items[0].resource.provider").value("aws"))
            .andExpect(jsonPath("$.items[0].resource.accountId").value("1"))
            .andExpect(jsonPath("$.items[0].repository.key").value(billing.key))
            .andExpect(jsonPath("$.items[0].repository.name").value("billing"))
            .andExpect(jsonPath("$.items[0].confidence").value(0.4))
            .andExpect(jsonPath("$.items[0].rule").value("naming"))
            .andExpect(jsonPath("$.items[0].evidence.normalised").value("billing"))
            .andExpect(jsonPath("$.items[0].status").value("pending"))
            .andExpect(jsonPath("$.items[0].createdAt").value("2026-09-30T12:00:00Z"))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(CandidateQuery.DEFAULT_SIZE))
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.totalPages").value(1))

        verify(links).candidates(CandidateQuery())
    }

    @Test
    fun `the filters are read into the query`() {
        whenever(links.candidates(any())).thenReturn(CandidatePage(emptyList(), totalElements = 0))

        mockMvc
            .perform(
                get("/api/v1/links/candidates")
                    .param("status", "conflict")
                    .param("provider", "aws")
                    .param("minConfidence", "0.5")
                    .param("q", "billing")
                    .param("page", "2")
                    .param("size", "10"),
            ).andExpect(status().isOk)

        val query = argumentCaptor<CandidateQuery>()
        verify(links).candidates(query.capture())
        assertEquals(
            CandidateQuery(
                statuses = setOf(CandidateStatus.CONFLICT),
                provider = "aws",
                minConfidence = 0.5,
                search = "billing",
                page = 2,
                size = 10,
            ),
            query.firstValue,
        )
    }

    @Test
    fun `several statuses may be asked for at once`() {
        whenever(links.candidates(any())).thenReturn(CandidatePage(emptyList(), totalElements = 0))

        mockMvc.perform(get("/api/v1/links/candidates").param("status", "pending,rejected")).andExpect(status().isOk)

        verify(links).candidates(CandidateQuery(statuses = setOf(CandidateStatus.PENDING, CandidateStatus.REJECTED)))
    }

    @Test
    fun `an unknown status, a confidence outside 0 to 1 and an oversized page are refused with 400`() {
        mockMvc.perform(get("/api/v1/links/candidates").param("status", "maybe")).andExpect(status().isBadRequest)
        mockMvc.perform(get("/api/v1/links/candidates").param("minConfidence", "1.5")).andExpect(status().isBadRequest)
        mockMvc
            .perform(get("/api/v1/links/candidates").param("size", (CandidateQuery.MAX_SIZE + 1).toString()))
            .andExpect(status().isBadRequest)

        verifyNoInteractions(links)
    }

    @Test
    fun `accepting answers 200 with the stated owner`() {
        whenever(links.accept("c0ffee00c0ffee00")).thenReturn(owner)

        mockMvc
            .perform(post("/api/v1/links/candidates/c0ffee00c0ffee00/accept"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.resource.key").value(queue.key))
            .andExpect(jsonPath("$.repository.key").value(billing.key))
            .andExpect(jsonPath("$.rule").value("manual"))
            .andExpect(jsonPath("$.confidence").value(1.0))
            .andExpect(jsonPath("$.inferred").value(false))
            .andExpect(jsonPath("$.acceptedBy").value("dan"))
            .andExpect(jsonPath("$.sourceSystem").value("manual"))
    }

    @Test
    fun `rejecting answers 200 with the candidate, now rejected`() {
        whenever(links.reject("c0ffee00c0ffee00"))
            .thenReturn(
                candidate.copy(status = CandidateStatus.REJECTED, rejectedBy = "dan", rejectedAt = Instant.parse("2026-10-01T00:00:00Z")),
            )

        mockMvc
            .perform(post("/api/v1/links/candidates/c0ffee00c0ffee00/reject"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("rejected"))
            .andExpect(jsonPath("$.rejectedBy").value("dan"))
            .andExpect(jsonPath("$.rejectedAt").value("2026-10-01T00:00:00Z"))
    }

    @Test
    fun `an unknown candidate is 404 and a decided one 409, for either decision`() {
        doThrow(CandidateNotFoundException("nope")).whenever(links).accept("nope")
        doThrow(CandidateDecidedException("done", CandidateStatus.REJECTED)).whenever(links).reject("done")

        mockMvc
            .perform(post("/api/v1/links/candidates/nope/accept"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").exists())
        mockMvc
            .perform(post("/api/v1/links/candidates/done/reject"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.status").value("rejected"))
    }

    @Test
    fun `a manual link is created with 201`() {
        whenever(links.link(queue.key, billing.key)).thenReturn(owner.copy(acceptedBy = null))

        mockMvc
            .perform(json(post("/api/v1/links/manual"), mapOf("resourceKey" to queue.key, "repoKey" to billing.key)))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.rule").value("manual"))
            .andExpect(jsonPath("$.repository.key").value(billing.key))
    }

    @Test
    fun `a manual link names both ends, or is refused with 400`() {
        mockMvc
            .perform(json(post("/api/v1/links/manual"), mapOf("resourceKey" to queue.key)))
            .andExpect(status().isBadRequest)

        verifyNoInteractions(links)
    }

    @Test
    fun `a manual link to a missing node is 404, and one stated twice 409`() {
        doThrow(NodeNotFoundException(listOf(NodeKey("Repository", "github.com/acme/nowhere"))))
            .whenever(links)
            .link(queue.key, "github.com/acme/nowhere")
        doThrow(ManualLinkExistsException(queue.key, billing.key)).whenever(links).link(queue.key, billing.key)

        mockMvc
            .perform(json(post("/api/v1/links/manual"), mapOf("resourceKey" to queue.key, "repoKey" to "github.com/acme/nowhere")))
            .andExpect(status().isNotFound)
        mockMvc
            .perform(json(post("/api/v1/links/manual"), mapOf("resourceKey" to queue.key, "repoKey" to billing.key)))
            .andExpect(status().isConflict)
    }

    @Test
    fun `a manual link is closed with 204, and one that is not there is 404`() {
        doThrow(ManualLinkNotFoundException(queue.key, "github.com/acme/payments"))
            .whenever(links)
            .unlink(queue.key, "github.com/acme/payments")

        mockMvc
            .perform(delete("/api/v1/links/manual").param("resourceKey", queue.key).param("repoKey", billing.key))
            .andExpect(status().isNoContent)
        mockMvc
            .perform(delete("/api/v1/links/manual").param("resourceKey", queue.key).param("repoKey", "github.com/acme/payments"))
            .andExpect(status().isNotFound)

        verify(links).unlink(queue.key, billing.key)
    }

    private fun json(
        request: MockHttpServletRequestBuilder,
        body: Any,
    ): MockHttpServletRequestBuilder = request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))
}
