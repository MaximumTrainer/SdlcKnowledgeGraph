package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.exception.InvalidQueryParameterException
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.model.CarriedDeployment
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.model.WorkItemDeployments
import com.repodatagraph.domain.port.`in`.ChangeLineageUseCase
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
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
 * The HTTP contract of `GET /api/v1/work-items/deployments` (#85): where a work item is live. The
 * work item is named by its URI in a query parameter, since a URI holds `//`, which no path segment
 * may carry. The use case is mocked; which deployments carry a work item is the service's business
 * and the acceptance suite's.
 */
@WebMvcTest(WorkItemController::class)
class WorkItemControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var useCase: ChangeLineageUseCase

    private val at = Instant.parse("2026-09-13T10:00:00Z")

    private fun node(
        id: String,
        props: Map<String, Any?> = emptyMap(),
    ) = GraphNode(NodeKey.parse(id), props, Provenance.manual(at))

    private val workItem =
        node(
            "ExternalWorkItem:chorus://task/01JABC",
            mapOf("uri" to "chorus://task/01JABC", "system" to "chorus", "externalKey" to "CH-42", "title" to "Retry the webhook"),
        )
    private val change =
        node(
            "Change:github.com/acme/payments@a1b2c3",
            mapOf("repositoryKey" to "github.com/acme/payments", "sha" to "a1b2c3", "committedAt" to at, "title" to "Retry"),
        )
    private val artifact = node("Artifact:acme/payments:1.4.0", mapOf("name" to "acme/payments", "version" to "1.4.0"))
    private val deployment =
        node(
            "Deployment:acme/payments:1.4.0#production#1789297200",
            mapOf("deployedAt" to Instant.parse("2026-09-13T11:00:00Z"), "status" to "SUCCESS"),
        )
    private val production = node("Environment:production", mapOf("name" to "production", "tier" to "production"))

    @Test
    fun `answers the deployments carrying the work item, each with its environment and the changes that carry it`() {
        whenever(useCase.deploymentsOfWorkItem("chorus://task/01JABC"))
            .thenReturn(WorkItemDeployments(workItem, listOf(CarriedDeployment(deployment, production, listOf(artifact), listOf(change)))))

        mockMvc
            .perform(get("/api/v1/work-items/deployments").param("uri", "chorus://task/01JABC"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.workItem.id").value(workItem.id))
            .andExpect(jsonPath("$.workItem.uri").value("chorus://task/01JABC"))
            .andExpect(jsonPath("$.workItem.system").value("chorus"))
            .andExpect(jsonPath("$.workItem.externalKey").value("CH-42"))
            .andExpect(jsonPath("$.workItem.title").value("Retry the webhook"))
            .andExpect(jsonPath("$.deployments.length()").value(1))
            .andExpect(jsonPath("$.deployments[0].id").value(deployment.id))
            .andExpect(jsonPath("$.deployments[0].deployedAt").value("2026-09-13T11:00:00Z"))
            .andExpect(jsonPath("$.deployments[0].status").value("SUCCESS"))
            .andExpect(jsonPath("$.deployments[0].environment.id").value(production.id))
            .andExpect(jsonPath("$.deployments[0].environment.key").value("production"))
            .andExpect(jsonPath("$.deployments[0].environment.tier").value("production"))
            .andExpect(jsonPath("$.deployments[0].artifacts[0].id").value(artifact.id))
            .andExpect(jsonPath("$.deployments[0].changes[0].id").value(change.id))
            .andExpect(jsonPath("$.deployments[0].changes[0].sha").value("a1b2c3"))
            .andExpect(jsonPath("$.deployments[0].changes[0].repositoryKey").value("github.com/acme/payments"))
            .andExpect(jsonPath("$.deployments[0].changes[0].title").value("Retry"))
    }

    @Test
    fun `a work item live nowhere is 200 with no deployments, not 404`() {
        whenever(useCase.deploymentsOfWorkItem("chorus://task/01JABC")).thenReturn(WorkItemDeployments(workItem, emptyList()))

        mockMvc
            .perform(get("/api/v1/work-items/deployments").param("uri", "chorus://task/01JABC"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deployments").isEmpty)
    }

    @Test
    fun `a deployment in no environment says so rather than inventing one`() {
        whenever(useCase.deploymentsOfWorkItem("chorus://task/01JABC"))
            .thenReturn(WorkItemDeployments(workItem, listOf(CarriedDeployment(deployment, null, listOf(artifact), listOf(change)))))

        mockMvc
            .perform(get("/api/v1/work-items/deployments").param("uri", "chorus://task/01JABC"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deployments[0].environment").doesNotExist())
    }

    @Test
    fun `a work item the graph does not hold is 404 naming it`() {
        whenever(useCase.deploymentsOfWorkItem("chorus://task/nothing"))
            .thenThrow(NodeNotFoundException(listOf(NodeKey("ExternalWorkItem", "chorus://task/nothing"))))

        mockMvc
            .perform(get("/api/v1/work-items/deployments").param("uri", "chorus://task/nothing"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("node not found"))
            .andExpect(jsonPath("$.missing[0]").value("ExternalWorkItem:chorus://task/nothing"))
    }

    @Test
    fun `a missing uri is 400 naming it`() {
        whenever(useCase.deploymentsOfWorkItem(null)).thenThrow(InvalidQueryParameterException("uri", "uri is required"))

        mockMvc
            .perform(get("/api/v1/work-items/deployments"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.field").value("uri"))
    }

    @Test
    fun `the route is a read, so a POST is not answered by it`() {
        mockMvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/v1/work-items/deployments"),
            ).andExpect(status().isMethodNotAllowed)

        verify(useCase, never()).deploymentsOfWorkItem(any())
    }
}
