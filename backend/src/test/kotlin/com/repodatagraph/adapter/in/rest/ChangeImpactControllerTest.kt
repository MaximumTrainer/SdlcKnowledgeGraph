package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.model.ChangeImpactQuery
import com.repodatagraph.domain.model.ChangeImpactResult
import com.repodatagraph.domain.model.ChangeScope
import com.repodatagraph.domain.model.Citation
import com.repodatagraph.domain.model.EnvironmentTier
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.ImpactHit
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Owner
import com.repodatagraph.domain.model.PathFilter
import com.repodatagraph.domain.model.PathStep
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.`in`.ChangeImpactUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * The HTTP contract of `POST /api/v1/impact` (#87): the request body, the bounds a bad one is
 * refused with (`400 {error, field}`, the error naming the bound), and the shape of the ranked answer
 * an agent's context pack is built from. The use case is mocked; what the hits are is the service's
 * and the acceptance suite's business.
 */
@WebMvcTest(ChangeImpactController::class)
class ChangeImpactControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var useCase: ChangeImpactUseCase

    private val at = Instant.parse("2026-09-01T10:00:00Z")
    private val stated = Provenance(sourceSystem = "github", sourceId = "R_1", ingestedAt = at, validFrom = at, syncRunId = "run-1")

    private fun node(
        id: String,
        props: Map<String, Any?> = emptyMap(),
    ) = GraphNode(NodeKey.parse(id), props, stated)

    private val payments = node("Repository:github.com/acme/payments")
    private val artifact = node("Artifact:ghcr.io/acme/payments@sha256:aaa")
    private val deployment = node("Deployment:prod-1", mapOf("status" to "SUCCESS", "artifactId" to "a"))
    private val production = node("Environment:production", mapOf("name" to "production", "tier" to "production"))
    private val billing = node("Team:billing", mapOf("name" to "billing", "email" to "billing@acme.test"))

    private val builds = PathStep("BUILDS", payments.id, artifact.id, 1.0, false)
    private val deployedAs = PathStep("DEPLOYED_TO", artifact.id, deployment.id, 0.9, false)

    private val result =
        ChangeImpactResult(
            repository = payments,
            depth = 2,
            limit = 50,
            pathFilter = PathFilter.NOT_APPLIED,
            matchedPaths = emptyList(),
            changeScope = ChangeScope.UNKNOWN,
            truncated = true,
            hits =
                listOf(
                    ImpactHit(
                        node = deployment,
                        hops = 2,
                        score = 1.0 / 3,
                        confidence = 0.9,
                        inferred = false,
                        environment = production,
                        tier = EnvironmentTier.PRODUCTION,
                        pathMatched = false,
                        owners = listOf(Owner(billing, listOf(PathStep("OWNED_BY", payments.id, billing.id, 1.0, false)), 1.0)),
                        citation = Citation(deployment.id, listOf(builds, deployedAs), stated),
                    ),
                ),
        )

    private fun impact(body: String): ResultActions =
        mockMvc.perform(post("/api/v1/impact").contentType(MediaType.APPLICATION_JSON).content(body))

    @Test
    fun `answers the ranked hits with their scores, environments, owners and citations`() {
        whenever(useCase.changeImpact(any())).thenReturn(result)

        impact("""{"repositoryKey":"github.com/acme/payments"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.repository.id").value(payments.id))
            .andExpect(jsonPath("$.depth").value(2))
            .andExpect(jsonPath("$.limit").value(50))
            .andExpect(jsonPath("$.scoring.version").value("1"))
            .andExpect(jsonPath("$.scoring.formula").isNotEmpty)
            .andExpect(jsonPath("$.scoring.tierWeights.production").value(1.0))
            .andExpect(jsonPath("$.scoring.tierWeights.other").value(0.5))
            .andExpect(jsonPath("$.pathFilter").value("not_applied"))
            .andExpect(jsonPath("$.matchedPaths").isEmpty)
            .andExpect(jsonPath("$.changeScope").value("unknown"))
            .andExpect(jsonPath("$.truncated").value(true))
            .andExpect(jsonPath("$.hits[0].node.id").value(deployment.id))
            .andExpect(jsonPath("$.hits[0].node.type").value("Deployment"))
            .andExpect(jsonPath("$.hits[0].node.props.status").value("SUCCESS"))
            .andExpect(jsonPath("$.hits[0].hops").value(2))
            .andExpect(jsonPath("$.hits[0].score").value(1.0 / 3))
            .andExpect(jsonPath("$.hits[0].confidence").value(0.9))
            .andExpect(jsonPath("$.hits[0].tier").value("production"))
            .andExpect(jsonPath("$.hits[0].environment.id").value(production.id))
            .andExpect(jsonPath("$.hits[0].environment.key").value("production"))
            .andExpect(jsonPath("$.hits[0].environment.tier").value("production"))
            .andExpect(jsonPath("$.hits[0].pathMatched").value(false))
            .andExpect(jsonPath("$.hits[0].owners[0].team.key").value("billing"))
            .andExpect(jsonPath("$.hits[0].owners[0].via[0].edge").value("OWNED_BY"))
            .andExpect(jsonPath("$.hits[0].citation.nodeKey").value(deployment.id))
            .andExpect(jsonPath("$.hits[0].citation.edgePath[0].edge").value("BUILDS"))
            .andExpect(jsonPath("$.hits[0].citation.edgePath[1].to").value(deployment.id))
            .andExpect(jsonPath("$.hits[0].citation.edgePath[1].confidence").value(0.9))
            .andExpect(jsonPath("$.hits[0].citation.provenance.sourceSystem").value("github"))
            .andExpect(jsonPath("$.hits[0].citation.provenance.sourceId").value("R_1"))
            .andExpect(jsonPath("$.hits[0].citation.provenance.syncRunId").value("run-1"))
            // FR6: a truncated answer does not count what it left out.
            .andExpect(jsonPath("$.total").doesNotExist())
            .andExpect(jsonPath("$.count").doesNotExist())
    }

    @Test
    fun `the body becomes the query, each absent field taking its documented default`() {
        whenever(useCase.changeImpact(any())).thenReturn(result)

        impact("""{"repositoryKey":"github.com/acme/payments"}""").andExpect(status().isOk)
        impact("""{"repositoryKey":"github.com/acme/payments","paths":["infra/db.tf"],"sha":"4f1c2d9","depth":4,"limit":500}""")
            .andExpect(status().isOk)

        val queries = argumentCaptor<ChangeImpactQuery>()
        verify(useCase, org.mockito.kotlin.times(2)).changeImpact(queries.capture())
        assertEquals(ChangeImpactQuery("github.com/acme/payments"), queries.firstValue)
        assertEquals(2, queries.firstValue.depth)
        assertEquals(50, queries.firstValue.limit)
        assertEquals(
            ChangeImpactQuery("github.com/acme/payments", listOf("infra/db.tf"), "4f1c2d9", depth = 4, limit = 500),
            queries.secondValue,
        )
    }

    @ParameterizedTest(name = "{0} {1} is refused naming the bound")
    @CsvSource("depth, 0", "depth, 5", "depth, 9", "limit, 0", "limit, 501")
    fun `a depth or limit out of bounds is 400 naming the field and its bound, and nothing is queried`(
        field: String,
        value: Int,
    ) {
        impact("""{"repositoryKey":"github.com/acme/payments","$field":$value}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.field").value(field))
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString(if (field == "depth") "1 and 4" else "1 and 500")))

        verify(useCase, never()).changeImpact(any())
    }

    @Test
    fun `a sha a Change in the graph carries is reported as applied (#85)`() {
        whenever(useCase.changeImpact(any())).thenReturn(result.copy(changeScope = ChangeScope.APPLIED))

        impact("""{"repositoryKey":"github.com/acme/payments","sha":"4f1c2d9"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.changeScope").value("applied"))
    }

    @Test
    fun `a missing repositoryKey is 400 naming it`() {
        impact("""{"depth":2}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.field").value("repositoryKey"))
    }

    @Test
    fun `a repository the graph does not hold is 404`() {
        whenever(useCase.changeImpact(any())).thenThrow(NodeNotFoundException(listOf(payments.key)))

        impact("""{"repositoryKey":"github.com/acme/payments"}""")
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.missing[0]").value(payments.id))
    }

    @Test
    fun `the same answer is the same bytes, whatever order the node's properties came in`() {
        val reordered =
            result.copy(
                hits = result.hits.map { it.copy(node = node(deployment.id, linkedMapOf("artifactId" to "a", "status" to "SUCCESS"))) },
            )
        whenever(useCase.changeImpact(any())).thenReturn(result, reordered)

        val first = impact("""{"repositoryKey":"github.com/acme/payments"}""").andReturn().response.contentAsString
        val second = impact("""{"repositoryKey":"github.com/acme/payments"}""").andReturn().response.contentAsString

        assertEquals(first, second)
    }
}
