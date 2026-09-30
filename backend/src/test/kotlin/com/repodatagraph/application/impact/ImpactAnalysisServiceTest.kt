package com.repodatagraph.application.impact

import com.repodatagraph.adapter.out.ontology.YamlOntologyLoader
import com.repodatagraph.domain.exception.InvalidQueryParameterException
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.model.CandidatePath
import com.repodatagraph.domain.model.DeploymentFacts
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.ImpactDirection
import com.repodatagraph.domain.model.ImpactSpec
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.OwnerPath
import com.repodatagraph.domain.model.PathSearch
import com.repodatagraph.domain.model.PathStep
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.ImpactQueryPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.core.io.DefaultResourceLoader
import java.time.Instant

/**
 * The use case in front of [ImpactQueryPort] (#21, FR9): it resolves the node, builds the traversal
 * from the registry, and turns what the port found into an answer. No Cypher here: the port is faked.
 */
class ImpactAnalysisServiceTest {
    private val registry = YamlOntologyLoader(DefaultResourceLoader()).load()
    private val queries = mock<ImpactQueryPort>()
    private val graphStore = mock<GraphStore>()
    private val traversals = TraversalFilterBuilder(registry)
    private val service = ImpactAnalysisService(queries, graphStore, registry, traversals, WhyFailedAnalyser(), OwnerResolver())

    private val at = Instant.parse("2026-09-01T10:00:00Z")

    private fun node(id: String) =
        GraphNode(NodeKey.parse(id), emptyMap(), Provenance(sourceSystem = "manual", ingestedAt = at, validFrom = at))

    private val sharedLib = node("Repository:github.com/acme/shared-lib")
    private val payments = node("Repository:github.com/acme/payments")

    @Test
    fun `impact walks the registry's downstream traversal from the resolved node to the asked depth`() {
        whenever(graphStore.findNode(sharedLib.key)).thenReturn(sharedLib)
        whenever(queries.paths(any(), any(), any(), any())).thenReturn(
            PathSearch(listOf(CandidatePath(payments, listOf(PathStep("DEPENDED_ON_BY", sharedLib.id, payments.id, 1.0, false)))), false),
        )

        val result = service.impact(ImpactSpec(sharedLib.key, depth = 4, minConfidence = 0.5))

        verify(queries).paths(eq(sharedLib.key), eq(traversals.impact(ImpactDirection.DOWNSTREAM)), eq(4), any())
        assertEquals(sharedLib, result.root)
        assertEquals(4, result.depth)
        assertEquals(listOf(payments.id), result.affected.map { it.node.id })
        assertEquals(mapOf("Repository" to 1, "excluded" to 0), result.byType)
        assertFalse(result.truncated)
    }

    @Test
    fun `upstream asks the port for the upstream traversal`() {
        whenever(graphStore.findNode(sharedLib.key)).thenReturn(sharedLib)
        whenever(queries.paths(any(), any(), any(), any())).thenReturn(PathSearch(emptyList(), false))

        service.impact(ImpactSpec(sharedLib.key, direction = ImpactDirection.UPSTREAM))

        verify(queries).paths(eq(sharedLib.key), eq(traversals.impact(ImpactDirection.UPSTREAM)), eq(3), any())
    }

    @Test
    fun `a port that stopped early makes the answer truncated`() {
        whenever(graphStore.findNode(sharedLib.key)).thenReturn(sharedLib)
        whenever(queries.paths(any(), any(), any(), any())).thenReturn(PathSearch(emptyList(), true))

        assertTrue(service.impact(ImpactSpec(sharedLib.key)).truncated)
    }

    @Test
    fun `a node that does not exist is not found, and nothing is walked`() {
        whenever(graphStore.findNode(sharedLib.key)).thenReturn(null)

        assertThrows<NodeNotFoundException> { service.impact(ImpactSpec(sharedLib.key)) }
        verify(queries, never()).paths(any(), any(), any(), any())
    }

    @Test
    fun `a node of a type the registry does not declare is an invalid nodeId`() {
        val refused = assertThrows<InvalidQueryParameterException> { service.impact(ImpactSpec(NodeKey("Widget", "w1"))) }

        assertEquals("nodeId", refused.field)
    }

    private val deploymentKey = NodeKey("Deployment", "ghcr.io/acme/payments@sha256:ccc#staging#1756807200")

    @Test
    fun `why-failed takes a full id or a bare key, and analyses what the port found`() {
        val facts =
            DeploymentFacts(
                deployment = node(deploymentKey.id),
                status = "FAILED",
                deployedAt = at,
                artifact = null,
                commitSha = "c2",
                repository = payments,
                pipeline = null,
                environment = null,
                history = emptyList(),
                dependencyDeployments = emptyList(),
            )
        whenever(queries.deploymentFacts(eq(deploymentKey), any())).thenReturn(facts)

        assertEquals("FAILED", service.whyDeploymentFailed(deploymentKey.id).status)
        assertEquals("c2", service.whyDeploymentFailed(deploymentKey.key).commitSha)
    }

    @Test
    fun `why-failed for a deployment the port cannot find is not found`() {
        whenever(queries.deploymentFacts(any(), any())).thenReturn(null)

        assertThrows<NodeNotFoundException> { service.whyDeploymentFailed(deploymentKey.id) }
    }

    @Test
    fun `why-failed for something that is not a deployment is an invalid deploymentId`() {
        listOf("Repository:github.com/acme/payments", " ").forEach { id ->
            val refused = assertThrows<InvalidQueryParameterException> { service.whyDeploymentFailed(id) }
            assertEquals("deploymentId", refused.field, "for '$id'")
        }
        verify(queries, never()).deploymentFacts(any(), any())
    }

    @Test
    fun `owners resolves the node, walks the registry's ownership inheritance and resolves the paths`() {
        val resource = node("CloudResource:aws:db")
        val team = node("Team:payments-team")
        whenever(graphStore.findNode(resource.key)).thenReturn(resource)
        whenever(queries.ownerPaths(any(), any(), any(), any())).thenReturn(
            listOf(
                OwnerPath(
                    team,
                    listOf(
                        PathStep("OWNED_BY_REPO", resource.id, payments.id, 0.4, true),
                        PathStep("OWNED_BY", payments.id, team.id, 1.0, false),
                    ),
                ),
            ),
        )

        val owners = service.owners(resource.id)

        verify(queries).ownerPaths(eq(resource.key), eq(traversals.ownershipInheritance()), eq(setOf("OWNED_BY")), any())
        assertEquals(listOf(team.id), owners.owners.map { it.team.id })
    }

    @Test
    fun `owners of a missing node is not found, and of a malformed id is an invalid nodeId`() {
        whenever(graphStore.findNode(any())).thenReturn(null)

        assertThrows<NodeNotFoundException> { service.owners("CloudResource:aws:none") }
        assertEquals("nodeId", assertThrows<InvalidQueryParameterException> { service.owners("nonsense") }.field)
        verify(queries, never()).ownerPaths(any(), any(), anyOrNull(), any())
    }
}
