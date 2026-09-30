package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.exception.InvalidQueryParameterException
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.model.AffectedNode
import com.repodatagraph.domain.model.DeploymentRecord
import com.repodatagraph.domain.model.FailureReason
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.ImpactDirection
import com.repodatagraph.domain.model.ImpactResult
import com.repodatagraph.domain.model.ImpactSpec
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Owner
import com.repodatagraph.domain.model.OwnersResult
import com.repodatagraph.domain.model.PathStep
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.model.WhyFailedResult
import com.repodatagraph.domain.port.`in`.GraphQueryUseCase
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * The shapes of the impact, why-failed and owners answers, and how the controller maps a bad
 * parameter or an unresolved node (#21, FR1, FR4, FR6, FR7). The use case is mocked: what the
 * answers contain is the service's and the adapter's business, and the acceptance suite's.
 */
@WebMvcTest(GraphController::class)
class GraphControllerImpactTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var graphQueryUseCase: GraphQueryUseCase

    private val at = Instant.parse("2026-09-01T10:00:00Z")
    private val stated = Provenance(sourceSystem = "manual", ingestedAt = at, validFrom = at)

    private fun node(
        type: String,
        key: String,
        props: Map<String, Any?> = emptyMap(),
    ) = GraphNode(NodeKey(type, key), props, stated)

    private val sharedLib = node("Repository", "github.com/acme/shared-lib")
    private val payments = node("Repository", "github.com/acme/payments", mapOf("name" to "payments"))
    private val database = node("CloudResource", "aws:arn:db")

    private val impact =
        ImpactResult(
            root = sharedLib,
            depth = 3,
            direction = ImpactDirection.DOWNSTREAM,
            minConfidence = 0.3,
            truncated = false,
            affected =
                listOf(
                    AffectedNode(
                        node = payments,
                        distance = 1,
                        confidence = 1.0,
                        inferred = false,
                        path = listOf(PathStep("DEPENDED_ON_BY", sharedLib.id, payments.id, 1.0, false)),
                    ),
                    AffectedNode(
                        node = database,
                        distance = 2,
                        confidence = 0.4,
                        inferred = true,
                        path =
                            listOf(
                                PathStep("DEPENDED_ON_BY", sharedLib.id, payments.id, 1.0, false),
                                PathStep("OWNS_RESOURCE", payments.id, database.id, 0.4, true),
                            ),
                    ),
                ),
            byType = mapOf("Repository" to 1, "CloudResource" to 1, "excluded" to 0),
        )

    @Test
    fun `impact answers the root, the affected nodes with their paths, and counts by type`() {
        whenever(graphQueryUseCase.impact(any())).thenReturn(impact)

        mockMvc
            .perform(get("/api/v1/graph/impact").param("nodeId", sharedLib.id).param("minConfidence", "0.3"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.root.id").value(sharedLib.id))
            .andExpect(jsonPath("$.root.type").value("Repository"))
            .andExpect(jsonPath("$.root.key").value("github.com/acme/shared-lib"))
            .andExpect(jsonPath("$.depth").value(3))
            .andExpect(jsonPath("$.truncated").value(false))
            .andExpect(jsonPath("$.affected[0].node.id").value(payments.id))
            .andExpect(jsonPath("$.affected[0].node.props.name").value("payments"))
            .andExpect(jsonPath("$.affected[0].distance").value(1))
            .andExpect(jsonPath("$.affected[0].confidence").value(1.0))
            .andExpect(jsonPath("$.affected[0].inferred").value(false))
            .andExpect(jsonPath("$.affected[1].inferred").value(true))
            .andExpect(jsonPath("$.affected[1].path[1].edge").value("OWNS_RESOURCE"))
            .andExpect(jsonPath("$.affected[1].path[1].from").value(payments.id))
            .andExpect(jsonPath("$.affected[1].path[1].to").value(database.id))
            .andExpect(jsonPath("$.affected[1].path[1].confidence").value(0.4))
            .andExpect(jsonPath("$.byType.Repository").value(1))
            .andExpect(jsonPath("$.byType.excluded").value(0))
    }

    @Test
    fun `impact passes the parameters through as a spec, with the documented defaults`() {
        whenever(graphQueryUseCase.impact(any())).thenReturn(impact)

        mockMvc.perform(get("/api/v1/graph/impact").param("nodeId", sharedLib.id)).andExpect(status().isOk)

        val spec = argumentCaptor<ImpactSpec>().also { verify(graphQueryUseCase).impact(it.capture()) }.firstValue
        assert(spec == ImpactSpec(NodeKey("Repository", "github.com/acme/shared-lib"))) { "spec was $spec" }
        assert(spec.depth == 3 && spec.minConfidence == 0.5 && spec.direction == ImpactDirection.DOWNSTREAM)
    }

    @Test
    fun `impact reads direction upstream`() {
        whenever(graphQueryUseCase.impact(any())).thenReturn(impact)

        mockMvc
            .perform(get("/api/v1/graph/impact").param("nodeId", sharedLib.id).param("direction", "upstream").param("depth", "5"))
            .andExpect(status().isOk)

        val spec = argumentCaptor<ImpactSpec>().also { verify(graphQueryUseCase).impact(it.capture()) }.firstValue
        assert(spec.direction == ImpactDirection.UPSTREAM && spec.depth == 5) { "spec was $spec" }
    }

    @Test
    fun `a depth outside 1 to 5 is 400 naming the field, and nothing is queried`() {
        listOf("0", "6", "9", "three").forEach { depth ->
            mockMvc
                .perform(get("/api/v1/graph/impact").param("nodeId", sharedLib.id).param("depth", depth))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.field").value("depth"))
                .andExpect(jsonPath("$.error").isNotEmpty)
        }
        verify(graphQueryUseCase, never()).impact(any())
    }

    @Test
    fun `a minConfidence outside 0 to 1 is 400 naming the field`() {
        listOf("-0.1", "1.5", "high").forEach { confidence ->
            mockMvc
                .perform(get("/api/v1/graph/impact").param("nodeId", sharedLib.id).param("minConfidence", confidence))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.field").value("minConfidence"))
        }
    }

    @Test
    fun `a malformed or missing nodeId is 400 naming the field`() {
        mockMvc
            .perform(get("/api/v1/graph/impact").param("nodeId", "no-type-here"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.field").value("nodeId"))
        mockMvc
            .perform(get("/api/v1/graph/impact"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.field").value("nodeId"))
    }

    @Test
    fun `an unknown direction is 400 naming the field`() {
        mockMvc
            .perform(get("/api/v1/graph/impact").param("nodeId", sharedLib.id).param("direction", "sideways"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.field").value("direction"))
    }

    @Test
    fun `a node the service reports as invalid is 400 naming the field`() {
        whenever(graphQueryUseCase.impact(any())).thenThrow(InvalidQueryParameterException("nodeId", "unknown node type 'Widget'"))

        mockMvc
            .perform(get("/api/v1/graph/impact").param("nodeId", "Widget:w1"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.field").value("nodeId"))
            .andExpect(jsonPath("$.error").value("unknown node type 'Widget'"))
    }

    @Test
    fun `a nodeId that resolves to nothing is 404`() {
        whenever(graphQueryUseCase.impact(any())).thenThrow(NodeNotFoundException(listOf(sharedLib.key)))

        mockMvc
            .perform(get("/api/v1/graph/impact").param("nodeId", sharedLib.id))
            .andExpect(status().isNotFound)
    }

    private val deployment = node("Deployment", "ghcr.io/acme/payments@sha256:ccc#staging#1756807200", mapOf("status" to "FAILED"))

    @Test
    fun `why-failed answers the deployment's lineage, the window and the reasons`() {
        val preceding = DeploymentRecord("Deployment:p#staging#1", payments.key, "c1", at, "SUCCESS")
        val change = DeploymentRecord("Deployment:s#staging#2", sharedLib.key, "s9", at.plusSeconds(60), "SUCCESS")
        whenever(graphQueryUseCase.whyDeploymentFailed(deployment.id)).thenReturn(
            WhyFailedResult(
                deployment = deployment,
                status = "FAILED",
                deployedAt = at.plusSeconds(120),
                artifact = node("Artifact", "ghcr.io/acme/payments@sha256:ccc"),
                commitSha = "c2",
                repository = payments,
                pipeline = node("Pipeline", "github-actions:github.com/acme/payments:deploy.yml", mapOf("lastRunStatus" to "FAILED")),
                environment = node("Environment", "staging"),
                changedDependencies = listOf(change),
                precedingSuccessfulDeployment = preceding,
                reasons = listOf(FailureReason("DEPENDENCY_CHANGED", "github.com/acme/shared-lib deployed s9")),
            ),
        )

        mockMvc
            .perform(get("/api/v1/graph/why-failed").param("deploymentId", deployment.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deployment.id").value(deployment.id))
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.commitSha").value("c2"))
            .andExpect(jsonPath("$.artifact.key").value("ghcr.io/acme/payments@sha256:ccc"))
            .andExpect(jsonPath("$.repository.key").value("github.com/acme/payments"))
            .andExpect(jsonPath("$.pipeline.id").value("Pipeline:github-actions:github.com/acme/payments:deploy.yml"))
            .andExpect(jsonPath("$.pipeline.lastRunStatus").value("FAILED"))
            .andExpect(jsonPath("$.environment.key").value("staging"))
            .andExpect(jsonPath("$.changedDependencies[0].repository.key").value("github.com/acme/shared-lib"))
            .andExpect(jsonPath("$.changedDependencies[0].commitSha").value("s9"))
            .andExpect(jsonPath("$.changedDependencies[0].deployedAt").value("2026-09-01T10:01:00Z"))
            .andExpect(jsonPath("$.precedingSuccessfulDeployment.commitSha").value("c1"))
            .andExpect(jsonPath("$.reasons[0].kind").value("DEPENDENCY_CHANGED"))
    }

    @Test
    fun `why-failed sends null, not nothing, when there was no earlier success`() {
        whenever(graphQueryUseCase.whyDeploymentFailed(deployment.id)).thenReturn(
            WhyFailedResult(
                deployment = deployment,
                status = "SUCCESS",
                deployedAt = at,
                artifact = null,
                commitSha = null,
                repository = null,
                pipeline = null,
                environment = null,
                changedDependencies = emptyList(),
                precedingSuccessfulDeployment = null,
                reasons = emptyList(),
            ),
        )

        mockMvc
            .perform(get("/api/v1/graph/why-failed").param("deploymentId", deployment.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.precedingSuccessfulDeployment").isEmpty)
            .andExpect(jsonPath("$.reasons").isEmpty)
    }

    @Test
    fun `why-failed for an unknown deployment is 404`() {
        whenever(
            graphQueryUseCase.whyDeploymentFailed("Deployment:none"),
        ).thenThrow(NodeNotFoundException(listOf(NodeKey("Deployment", "none"))))

        mockMvc
            .perform(get("/api/v1/graph/why-failed").param("deploymentId", "Deployment:none"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `owners answers each team with the path that reached it and its confidence`() {
        val team = node("Team", "payments-team", mapOf("name" to "payments-team", "email" to "payments@acme.test"))
        whenever(graphQueryUseCase.owners(database.id)).thenReturn(
            OwnersResult(
                node = database,
                owners =
                    listOf(
                        Owner(
                            team = team,
                            via =
                                listOf(
                                    PathStep("OWNED_BY_REPO", database.id, payments.id, 0.4, true),
                                    PathStep("OWNED_BY", payments.id, team.id, 1.0, false),
                                ),
                            confidence = 0.4,
                        ),
                    ),
            ),
        )

        mockMvc
            .perform(get("/api/v1/graph/owners").param("nodeId", database.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.node.id").value(database.id))
            .andExpect(jsonPath("$.owners[0].team.id").value("Team:payments-team"))
            .andExpect(jsonPath("$.owners[0].team.key").value("payments-team"))
            .andExpect(jsonPath("$.owners[0].team.name").value("payments-team"))
            .andExpect(jsonPath("$.owners[0].team.email").value("payments@acme.test"))
            .andExpect(jsonPath("$.owners[0].via[0].edge").value("OWNED_BY_REPO"))
            .andExpect(jsonPath("$.owners[0].via[1].edge").value("OWNED_BY"))
            .andExpect(jsonPath("$.owners[0].confidence").value(0.4))
    }

    @Test
    fun `owners of an unowned node is 200 with none`() {
        whenever(graphQueryUseCase.owners(payments.id)).thenReturn(OwnersResult(payments, emptyList()))

        mockMvc
            .perform(get("/api/v1/graph/owners").param("nodeId", payments.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.owners").isEmpty)
    }

    @Test
    fun `the repository impact endpoint still answers its old shape, marked deprecated with a successor`() {
        whenever(graphQueryUseCase.getImpactAnalysis("R1"))
            .thenReturn(mapOf("dependents" to emptyList(), "cloudResources" to emptyList(), "deployments" to emptyList()))

        mockMvc
            .perform(get("/api/v1/graph/repositories/R1/impact"))
            .andExpect(status().isOk)
            .andExpect(header().string("Deprecation", "true"))
            .andExpect(header().string("Link", "</api/v1/graph/impact?nodeId=Repository:R1>; rel=\"successor-version\""))
            .andExpect(jsonPath("$.repoId").value("R1"))
            .andExpect(jsonPath("$.dependents").isArray)
            .andExpect(jsonPath("$.cloudResources").isArray)
            .andExpect(jsonPath("$.deployments").isArray)
    }
}
