package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.exception.InvalidQueryParameterException
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.model.CarriedChange
import com.repodatagraph.domain.model.CarriedWorkItem
import com.repodatagraph.domain.model.DeploymentWorkItems
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.LineageStatus
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.`in`.ChangeLineageUseCase
import org.junit.jupiter.api.Test
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
 * The HTTP contract of `GET /api/v1/deployments/work-items` (#85): what intent a deployment carries.
 * The deployment is named in a query parameter, since its key holds `/` and `#`. `lineage` says
 * whether the answer can be trusted to be complete: `unknown` when the deployment's artifact has no
 * CONTAINS edge, rather than an empty list that would read as "carries nothing".
 */
@WebMvcTest(DeploymentLineageController::class)
class DeploymentLineageControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var useCase: ChangeLineageUseCase

    private val at = Instant.parse("2026-09-13T10:00:00Z")

    private fun node(
        id: String,
        props: Map<String, Any?> = emptyMap(),
    ) = GraphNode(NodeKey.parse(id), props, Provenance.manual(at))

    private val deploymentId = "Deployment:acme/payments:1.4.0#production#1789297200"
    private val deployment = node(deploymentId, mapOf("deployedAt" to Instant.parse("2026-09-13T11:00:00Z"), "status" to "SUCCESS"))
    private val production = node("Environment:production", mapOf("name" to "production"))
    private val artifact = node("Artifact:acme/payments:1.4.0")
    private val change =
        node("Change:github.com/acme/payments@a1b2c3", mapOf("repositoryKey" to "github.com/acme/payments", "sha" to "a1b2c3"))
    private val workItem = node("ExternalWorkItem:chorus://task/01JABC", mapOf("uri" to "chorus://task/01JABC", "system" to "chorus"))

    @Test
    fun `answers the changes the deployment carries and the work items they implement`() {
        whenever(useCase.workItemsOfDeployment(deploymentId)).thenReturn(
            DeploymentWorkItems(
                deployment = deployment,
                environment = production,
                lineage = LineageStatus.KNOWN,
                changes = listOf(CarriedChange(change, artifact)),
                workItems = listOf(CarriedWorkItem(workItem, listOf(change))),
            ),
        )

        mockMvc
            .perform(get("/api/v1/deployments/work-items").param("deploymentId", deploymentId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deployment.id").value(deploymentId))
            .andExpect(jsonPath("$.deployment.status").value("SUCCESS"))
            .andExpect(jsonPath("$.deployment.deployedAt").value("2026-09-13T11:00:00Z"))
            .andExpect(jsonPath("$.deployment.environment.key").value("production"))
            .andExpect(jsonPath("$.deployment.environment.tier").value("other"))
            .andExpect(jsonPath("$.lineage").value("known"))
            .andExpect(jsonPath("$.changes[0].id").value(change.id))
            .andExpect(jsonPath("$.changes[0].sha").value("a1b2c3"))
            .andExpect(jsonPath("$.changes[0].artifact").value(artifact.id))
            .andExpect(jsonPath("$.workItems.length()").value(1))
            .andExpect(jsonPath("$.workItems[0].id").value(workItem.id))
            .andExpect(jsonPath("$.workItems[0].uri").value("chorus://task/01JABC"))
            .andExpect(jsonPath("$.workItems[0].system").value("chorus"))
            .andExpect(jsonPath("$.workItems[0].changes[0]").value(change.id))
    }

    @Test
    fun `a deployment with no change lineage says unknown, with an empty list`() {
        whenever(useCase.workItemsOfDeployment(deploymentId)).thenReturn(
            DeploymentWorkItems(deployment, production, LineageStatus.UNKNOWN, emptyList(), emptyList()),
        )

        mockMvc
            .perform(get("/api/v1/deployments/work-items").param("deploymentId", deploymentId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.lineage").value("unknown"))
            .andExpect(jsonPath("$.changes").isEmpty)
            .andExpect(jsonPath("$.workItems").isEmpty)
    }

    @Test
    fun `a deployment whose changes implement nothing is known, with an empty list`() {
        whenever(useCase.workItemsOfDeployment(deploymentId)).thenReturn(
            DeploymentWorkItems(deployment, production, LineageStatus.KNOWN, listOf(CarriedChange(change, artifact)), emptyList()),
        )

        mockMvc
            .perform(get("/api/v1/deployments/work-items").param("deploymentId", deploymentId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.lineage").value("known"))
            .andExpect(jsonPath("$.workItems").isEmpty)
    }

    @Test
    fun `a deployment the graph does not hold is 404 naming it`() {
        whenever(useCase.workItemsOfDeployment("Deployment:nothing"))
            .thenThrow(NodeNotFoundException(listOf(NodeKey("Deployment", "nothing"))))

        mockMvc
            .perform(get("/api/v1/deployments/work-items").param("deploymentId", "Deployment:nothing"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.missing[0]").value("Deployment:nothing"))
    }

    @Test
    fun `a missing or mistyped deploymentId is 400 naming it`() {
        whenever(useCase.workItemsOfDeployment(null))
            .thenThrow(InvalidQueryParameterException("deploymentId", "deploymentId is required"))
        whenever(useCase.workItemsOfDeployment("Repository:github.com/acme/payments"))
            .thenThrow(InvalidQueryParameterException("deploymentId", "deploymentId must name a Deployment, not a Repository"))

        mockMvc
            .perform(get("/api/v1/deployments/work-items"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.field").value("deploymentId"))
        mockMvc
            .perform(get("/api/v1/deployments/work-items").param("deploymentId", "Repository:github.com/acme/payments"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.field").value("deploymentId"))
    }
}
