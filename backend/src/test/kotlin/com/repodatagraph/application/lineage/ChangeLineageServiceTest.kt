package com.repodatagraph.application.lineage

import com.repodatagraph.adapter.out.ontology.YamlOntologyLoader
import com.repodatagraph.application.impact.TraversalFilterBuilder
import com.repodatagraph.domain.exception.InvalidQueryParameterException
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.model.CarriedChange
import com.repodatagraph.domain.model.CarriedWorkItem
import com.repodatagraph.domain.model.DeployedChange
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.LineageStatus
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.model.WorkItemCarrier
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.port.out.ChangeLineagePort
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.ImpactQueryPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.core.io.DefaultResourceLoader
import java.time.Instant
import java.time.ZoneOffset

/**
 * The two lineage questions (#85) in front of a faked [ChangeLineagePort]: where a work item is live,
 * and what intent a deployment carries. The port finds the rows; this groups them, places each
 * deployment in its environment, orders everything so the same graph gives the same answer, and says
 * `unknown` rather than an empty list when a deployment's artifact has no CONTAINS edge at all.
 */
class ChangeLineageServiceTest {
    private val registry = YamlOntologyLoader(DefaultResourceLoader()).load()
    private val traversals = TraversalFilterBuilder(registry)
    private val graphStore = mock<GraphStore>()
    private val lineage = mock<ChangeLineagePort>()
    private val impactQueries = mock<ImpactQueryPort>()
    private val service = ChangeLineageService(graphStore, lineage, impactQueries, traversals, IdentityResolver(), registry)

    private val at = Instant.parse("2026-09-13T10:00:00Z")

    private fun node(
        id: String,
        props: Map<String, Any?> = emptyMap(),
    ) = GraphNode(NodeKey.parse(id), props, Provenance.manual(at))

    private val workItem = node("ExternalWorkItem:chorus://task/01JABC", mapOf("uri" to "chorus://task/01JABC", "system" to "chorus"))
    private val otherWorkItem = node("ExternalWorkItem:chorus://task/01JXYZ", mapOf("uri" to "chorus://task/01JXYZ", "system" to "chorus"))
    private val fix = node("Change:github.com/acme/payments@a1b2c3", mapOf("sha" to "a1b2c3"))
    private val followUp = node("Change:github.com/acme/payments@d4e5f6", mapOf("sha" to "d4e5f6"))
    private val artifact = node("Artifact:acme/payments:1.4.0")
    private val nextArtifact = node("Artifact:acme/payments:1.5.0")

    // Stored as the API writes an instant (a string) and as the ingest writes one (a date-time), and
    // ordered by when they happened whichever it was.
    private val staging = node("Deployment:staging-1", mapOf("deployedAt" to "2026-09-13T09:00:00Z"))
    private val production = node("Deployment:prod-1", mapOf("deployedAt" to Instant.parse("2026-09-13T11:00:00Z").atZone(ZoneOffset.UTC)))
    private val productionEnvironment = node("Environment:production", mapOf("tier" to "production"))
    private val stagingEnvironment = node("Environment:staging", mapOf("tier" to "pre_production"))

    @BeforeEach
    fun graph() {
        whenever(graphStore.findNode(workItem.key)).thenReturn(workItem)
        whenever(graphStore.findNode(production.key)).thenReturn(production)
        whenever(impactQueries.placements(any(), any())).thenReturn(
            mapOf(production.key to listOf(productionEnvironment), staging.key to listOf(stagingEnvironment)),
        )
    }

    @Test
    fun `a work item is live wherever an artifact containing a change that implements it was deployed`() {
        whenever(lineage.carriersOf(workItem.key, traversals.lineage())).thenReturn(
            listOf(
                WorkItemCarrier(staging, artifact, fix),
                WorkItemCarrier(production, nextArtifact, followUp),
                WorkItemCarrier(production, artifact, fix),
                WorkItemCarrier(production, nextArtifact, fix),
            ),
        )

        val live = service.deploymentsOfWorkItem("chorus://task/01JABC")

        assertEquals(workItem, live.workItem)
        // Most recent first, each deployment once, with every artifact and change that carries it there.
        assertEquals(listOf(production, staging), live.deployments.map { it.deployment })
        assertEquals(Instant.parse("2026-09-13T11:00:00Z"), live.deployments[0].deployedAt)
        assertEquals(productionEnvironment, live.deployments[0].environment)
        assertEquals(listOf(artifact, nextArtifact), live.deployments[0].artifacts)
        assertEquals(listOf(fix, followUp), live.deployments[0].changes)
        assertEquals(stagingEnvironment, live.deployments[1].environment)
        assertEquals(listOf(fix), live.deployments[1].changes)
    }

    @Test
    fun `a work item nothing carries yet is live nowhere, which is not the same as not found`() {
        whenever(lineage.carriersOf(any(), any())).thenReturn(emptyList())

        assertEquals(emptyList<Any>(), service.deploymentsOfWorkItem("chorus://task/01JABC").deployments)
    }

    @Test
    fun `a work item is found by its uri exactly as written`() {
        whenever(graphStore.findNode(NodeKey("ExternalWorkItem", "chorus://task/01jabc"))).thenReturn(null)

        val missing = assertThrows<NodeNotFoundException> { service.deploymentsOfWorkItem("chorus://task/01jabc") }

        assertEquals(listOf(NodeKey("ExternalWorkItem", "chorus://task/01jabc")), missing.missing)
        verify(lineage, never()).carriersOf(any(), any())
    }

    @Test
    fun `a work item uri is required`() {
        listOf(null, "", "  ").forEach { uri ->
            val error = assertThrows<InvalidQueryParameterException> { service.deploymentsOfWorkItem(uri) }
            assertEquals("uri", error.field)
        }
    }

    @Test
    fun `a deployment carries the changes its artifact contains and the work items they implement`() {
        whenever(lineage.contentsOf(production.key, traversals.lineage())).thenReturn(
            listOf(
                DeployedChange(artifact, followUp, listOf(otherWorkItem, workItem)),
                DeployedChange(artifact, fix, listOf(workItem)),
            ),
        )

        val carried = service.workItemsOfDeployment(production.id)

        assertEquals(production, carried.deployment)
        assertEquals(productionEnvironment, carried.environment)
        assertEquals(LineageStatus.KNOWN, carried.lineage)
        assertEquals(listOf(CarriedChange(fix, artifact), CarriedChange(followUp, artifact)), carried.changes)
        assertEquals(
            listOf(CarriedWorkItem(workItem, listOf(fix, followUp)), CarriedWorkItem(otherWorkItem, listOf(followUp))),
            carried.workItems,
        )
    }

    @Test
    fun `a deployment whose artifact contains changes that implement nothing is known to carry no intent`() {
        whenever(lineage.contentsOf(any(), any())).thenReturn(listOf(DeployedChange(artifact, fix, emptyList())))

        val carried = service.workItemsOfDeployment(production.id)

        assertEquals(LineageStatus.KNOWN, carried.lineage)
        assertEquals(emptyList<CarriedWorkItem>(), carried.workItems)
    }

    @Test
    fun `a deployment whose artifact has no CONTAINS edge has an unknown lineage, not an empty one`() {
        whenever(lineage.contentsOf(any(), any())).thenReturn(emptyList())
        whenever(impactQueries.placements(any(), any())).thenReturn(emptyMap())

        val carried = service.workItemsOfDeployment(production.id)

        assertEquals(LineageStatus.UNKNOWN, carried.lineage)
        assertEquals(emptyList<CarriedWorkItem>(), carried.workItems)
        assertNull(carried.environment)
    }

    @Test
    fun `a deployment is addressed by its id or its bare key`() {
        whenever(lineage.contentsOf(any(), any())).thenReturn(emptyList())

        assertEquals(production, service.workItemsOfDeployment(production.id).deployment)
        assertEquals(production, service.workItemsOfDeployment(production.key.key).deployment)
    }

    @Test
    fun `a deployment the graph does not hold is not found`() {
        whenever(graphStore.findNode(NodeKey("Deployment", "nothing"))).thenReturn(null)

        val missing = assertThrows<NodeNotFoundException> { service.workItemsOfDeployment("Deployment:nothing") }

        assertEquals(listOf(NodeKey("Deployment", "nothing")), missing.missing)
    }

    @Test
    fun `a deployment id is required, and must name a deployment`() {
        listOf(null, "", "Repository:github.com/acme/payments").forEach { id ->
            val error = assertThrows<InvalidQueryParameterException> { service.workItemsOfDeployment(id) }
            assertEquals("deploymentId", error.field)
        }
    }

    @Test
    fun `the same graph gives the same answer, whatever order the store returned it in`() {
        val rows =
            listOf(
                WorkItemCarrier(staging, artifact, fix),
                WorkItemCarrier(production, artifact, fix),
                WorkItemCarrier(production, nextArtifact, followUp),
            )
        whenever(lineage.carriersOf(any(), any())).thenReturn(rows, rows.reversed())

        assertEquals(service.deploymentsOfWorkItem("chorus://task/01JABC"), service.deploymentsOfWorkItem("chorus://task/01JABC"))
    }
}
