package com.repodatagraph.application

import com.repodatagraph.application.impact.ImpactAnalysisService
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.model.AffectedNode
import com.repodatagraph.domain.model.AuditEvent
import com.repodatagraph.domain.model.CloudResource
import com.repodatagraph.domain.model.Deployment
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.ImpactDirection
import com.repodatagraph.domain.model.ImpactResult
import com.repodatagraph.domain.model.ImpactSpec
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.PathStep
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.model.Repository
import com.repodatagraph.domain.port.out.FactStorePort
import com.repodatagraph.domain.port.out.RepositoryGraphPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant

class GraphQueryServiceTest {
    private val graphPort = mock<RepositoryGraphPort>()
    private val factStorePort = mock<FactStorePort>()
    private val impactAnalysis = mock<ImpactAnalysisService>()
    private val service = GraphQueryService(graphPort, factStorePort, impactAnalysis)

    @Test
    fun `getCloudResourcesForRepo delegates to graph port`() {
        val resources = listOf(CloudResource(id = "c1", provider = "AWS", resourceType = "Lambda", name = "fn-xyz"))
        whenever(graphPort.findCloudResourcesForRepo("repoId")).thenReturn(resources)

        val result = service.getCloudResourcesForRepo("repoId")

        assertEquals(resources, result)
    }

    @Test
    fun `getDependencies returns upstream repos`() {
        val deps = listOf(Repository(id = "dep1", url = "https://github.com/org/dep1", host = "github.com", org = "org", name = "dep1"))
        whenever(graphPort.findDependencies("repoId")).thenReturn(deps)

        val result = service.getDependencies("repoId")

        assertEquals(deps, result)
    }

    @Test
    fun `getDependents returns downstream repos`() {
        val dependents = listOf(Repository(id = "d1", url = "https://github.com/org/d1", host = "github.com", org = "org", name = "d1"))
        whenever(graphPort.findDependents("repoId")).thenReturn(dependents)

        val result = service.getDependents("repoId")

        assertEquals(dependents, result)
    }

    private val at = Instant.parse("2026-09-01T10:00:00Z")

    private fun node(
        id: String,
        props: Map<String, Any?> = emptyMap(),
    ) = GraphNode(NodeKey.parse(id), props, Provenance(sourceSystem = "manual", ingestedAt = at, validFrom = at))

    private fun affected(
        node: GraphNode,
        vararg edges: String,
    ) = AffectedNode(node, edges.size, 1.0, false, edges.map { PathStep(it, "Repository:repoId", node.id, 1.0, false) })

    /**
     * The repository impact endpoint is deprecated (#21) and answers from the new traversal: its three
     * lists are the direct dependents, the resources it owns and the deployments of what it builds.
     */
    @Test
    fun `getImpactAnalysis answers its three lists from the new traversal`() {
        val root = node("Repository:repoId")
        val dependent =
            node(
                "Repository:github.com/org/d1",
                mapOf(
                    "url" to "https://github.com/org/d1",
                    "host" to "github.com",
                    "org" to "org",
                    "name" to "d1",
                ),
            )
        val transitive = node("Repository:github.com/org/d2", mapOf("url" to "https://github.com/org/d2"))
        val resource = node("CloudResource:aws:fn", mapOf("provider" to "aws", "resourceType" to "Lambda", "name" to "fn"))
        val deployment =
            node(
                "Deployment:a1#staging#1",
                mapOf("artifactId" to "a1", "environmentId" to "staging", "deployedAt" to at, "status" to "FAILED"),
            )
        whenever(impactAnalysis.impact(ImpactSpec(NodeKey("Repository", "repoId"), depth = 2, minConfidence = 0.0))).thenReturn(
            ImpactResult(
                root = root,
                depth = 2,
                direction = ImpactDirection.DOWNSTREAM,
                minConfidence = 0.0,
                truncated = false,
                affected =
                    listOf(
                        affected(dependent, "DEPENDED_ON_BY"),
                        affected(transitive, "DEPENDED_ON_BY", "DEPENDED_ON_BY"),
                        affected(resource, "OWNS_RESOURCE"),
                        affected(deployment, "BUILDS", "DEPLOYED_TO"),
                        affected(node("Pipeline:p1"), "HAS_PIPELINE"),
                    ),
                byType = emptyMap(),
            ),
        )

        val result = service.getImpactAnalysis("repoId")

        assertEquals(
            listOf(Repository(id = dependent.id, url = "https://github.com/org/d1", host = "github.com", org = "org", name = "d1")),
            result["dependents"],
        )
        assertEquals(
            listOf(CloudResource(id = resource.id, provider = "aws", resourceType = "Lambda", name = "fn")),
            result["cloudResources"],
        )
        val deployed = result["deployments"]!!.single() as Deployment
        assertEquals(deployment.id, deployed.id)
        assertEquals("a1", deployed.artifactId)
        assertEquals(at, deployed.deployedAt)
        assertEquals("FAILED", deployed.status)
    }

    @Test
    fun `getImpactAnalysis of a repository that is not there is three empty lists, as it always was`() {
        whenever(impactAnalysis.impact(ImpactSpec(NodeKey("Repository", "gone"), depth = 2, minConfidence = 0.0)))
            .thenThrow(NodeNotFoundException(listOf(NodeKey("Repository", "gone"))))

        val result = service.getImpactAnalysis("Repository:gone")

        assertEquals(mapOf("dependents" to emptyList<Any>(), "cloudResources" to emptyList(), "deployments" to emptyList()), result)
    }

    @Test
    fun `getAuditEventsForRepo delegates to factstore`() {
        val events = listOf(AuditEvent(id = "e1", eventType = "DEPLOY"))
        whenever(factStorePort.queryEvents("repoId")).thenReturn(events)

        val result = service.getAuditEventsForRepo("repoId")

        assertEquals(events, result)
    }
}
