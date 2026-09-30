package com.repodatagraph.application.impact

import com.repodatagraph.adapter.out.ontology.YamlOntologyLoader
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.model.CandidatePath
import com.repodatagraph.domain.model.ChangeImpactQuery
import com.repodatagraph.domain.model.ChangeScope
import com.repodatagraph.domain.model.EnvironmentTier
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.ImpactDirection
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.OwnerPath
import com.repodatagraph.domain.model.PathFilter
import com.repodatagraph.domain.model.PathIndexEntry
import com.repodatagraph.domain.model.PathIndexKind
import com.repodatagraph.domain.model.PathSearch
import com.repodatagraph.domain.model.PathStep
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.ImpactQueryPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.core.io.DefaultResourceLoader
import java.time.Instant

/**
 * The change-impact use case (#87) in front of #21's [ImpactQueryPort], faked here: it walks the
 * registry's downstream traversal from the repository, keeps each node's nearest path, places each
 * hit in an environment, scores and ranks, cuts at the limit and resolves owners for what it keeps.
 */
class ChangeImpactServiceTest {
    private val registry = YamlOntologyLoader(DefaultResourceLoader()).load()
    private val queries = mock<ImpactQueryPort>()
    private val graphStore = mock<GraphStore>()
    private val traversals = TraversalFilterBuilder(registry)
    private val service = ChangeImpactService(queries, graphStore, traversals, OwnerResolver())

    private val at = Instant.parse("2026-09-01T10:00:00Z")
    private val stated = Provenance(sourceSystem = "manual", ingestedAt = at, validFrom = at)

    private fun node(
        id: String,
        props: Map<String, Any?> = emptyMap(),
    ) = GraphNode(NodeKey.parse(id), props, stated)

    private val payments = node("Repository:github.com/acme/payments")
    private val checkout = node("Service:checkout")
    private val artifact = node("Artifact:ghcr.io/acme/payments@sha256:aaa")
    private val prodDeployment = node("Deployment:prod")
    private val stagingDeployment = node("Deployment:staging")
    private val production = node("Environment:production", mapOf("tier" to "production"))
    private val staging = node("Environment:staging", mapOf("tier" to "pre_production"))
    private val database = node("CloudResource:aws:arn:db", mapOf("resourceId" to "arn:db", "name" to "db"))
    private val billing = node("Team:billing")
    private val storefront = node("Team:storefront")

    private fun step(
        edge: String,
        from: GraphNode,
        to: GraphNode,
        confidence: Double = 1.0,
    ) = PathStep(edge, from.id, to.id, confidence, false)

    private val toCheckout = CandidatePath(checkout, listOf(step("DEPENDED_ON_BY", payments, checkout)))
    private val toArtifact = CandidatePath(artifact, listOf(step("BUILDS", payments, artifact)))
    private val toProd = CandidatePath(prodDeployment, toArtifact.steps + step("DEPLOYED_TO", artifact, prodDeployment, 0.9))
    private val toStaging = CandidatePath(stagingDeployment, toArtifact.steps + step("DEPLOYED_TO", artifact, stagingDeployment))
    private val toDatabase = CandidatePath(database, listOf(step("OWNS_RESOURCE", payments, database)))

    @BeforeEach
    fun graph() {
        whenever(graphStore.findNode(payments.key)).thenReturn(payments)
        whenever(queries.paths(any(), any(), any(), any())).thenReturn(PathSearch(listOf(toStaging, toCheckout, toProd, toArtifact), false))
        whenever(queries.placements(any(), any())).thenReturn(
            mapOf(prodDeployment.key to listOf(production), stagingDeployment.key to listOf(staging)),
        )
        whenever(queries.ownerPathsOf(any(), any(), any(), any())).thenReturn(
            mapOf(
                checkout.key to listOf(OwnerPath(storefront, listOf(step("OWNED_BY", checkout, storefront)))),
                prodDeployment.key to
                    listOf(
                        OwnerPath(
                            billing,
                            listOf(
                                step("DEPLOYMENT_OF", prodDeployment, artifact),
                                step("BUILT_FROM", artifact, payments),
                                step("OWNED_BY", payments, billing),
                            ),
                        ),
                    ),
            ),
        )
        whenever(queries.pathIndex(any())).thenReturn(emptyList())
    }

    @Test
    fun `walks the registry's downstream traversal from the repository to the asked depth`() {
        service.changeImpact(ChangeImpactQuery(payments.key.key, depth = 3))

        verify(queries).paths(eq(payments.key), eq(traversals.impact(ImpactDirection.DOWNSTREAM)), eq(3), any())
    }

    @Test
    fun `ranks production above what is not in an environment, and that above staging`() {
        val result = service.changeImpact(ChangeImpactQuery(payments.key.key))

        assertEquals(listOf(prodDeployment.id, artifact.id, checkout.id, stagingDeployment.id), result.hits.map { it.node.id })
        val prod = result.hits.first()
        assertEquals(2, prod.hops)
        assertEquals(EnvironmentTier.PRODUCTION, prod.tier)
        assertEquals(production, prod.environment)
        assertEquals(1.0 / 3, prod.score, 1e-12)
        assertEquals(0.9, prod.confidence, 1e-12)
        assertEquals(EnvironmentTier.OTHER, result.hits[1].tier)
        assertNull(result.hits[1].environment)
    }

    @Test
    fun `places hits in environments along the registry's placement edges`() {
        service.changeImpact(ChangeImpactQuery(payments.key.key))

        val nodes = argumentCaptor<Collection<NodeKey>>()
        verify(queries).placements(nodes.capture(), eq(traversals.placement()))
        assertEquals(setOf(checkout.key, artifact.key, prodDeployment.key, stagingDeployment.key), nodes.firstValue.toSet())
    }

    @Test
    fun `an environment the walk reaches is weighted by its own tier, and a missing tier reads as other`() {
        val untiered = node("Environment:qa")
        val toProduction = CandidatePath(production, toProd.steps + step("HOSTS", prodDeployment, production))
        val toQa = CandidatePath(untiered, listOf(step("HOSTS", payments, untiered)))
        whenever(queries.paths(any(), any(), any(), any())).thenReturn(PathSearch(listOf(toProduction, toQa), false))
        whenever(queries.placements(any(), any())).thenReturn(emptyMap())

        val hits = service.changeImpact(ChangeImpactQuery(payments.key.key, depth = 3)).hits.associateBy { it.node.id }

        assertEquals(EnvironmentTier.PRODUCTION, hits.getValue(production.id).tier)
        assertEquals(production, hits.getValue(production.id).environment)
        assertEquals(EnvironmentTier.OTHER, hits.getValue(untiered.id).tier)
    }

    @Test
    fun `a node in several environments counts as in the most critical one`() {
        whenever(queries.placements(any(), any())).thenReturn(mapOf(checkout.key to listOf(staging, production)))

        val hit = service.changeImpact(ChangeImpactQuery(payments.key.key)).hits.single { it.node.id == checkout.id }

        assertEquals(EnvironmentTier.PRODUCTION, hit.tier)
        assertEquals(production, hit.environment)
    }

    @Test
    fun `every hit carries its nearest owners and a citation of the path and the node's provenance`() {
        val hits = service.changeImpact(ChangeImpactQuery(payments.key.key)).hits.associateBy { it.node.id }

        assertEquals(listOf(storefront.id), hits.getValue(checkout.id).owners.map { it.team.id })
        assertEquals(listOf(billing.id), hits.getValue(prodDeployment.id).owners.map { it.team.id })
        assertEquals(emptyList<Any>(), hits.getValue(artifact.id).owners)
        val citation = hits.getValue(prodDeployment.id).citation
        assertEquals(prodDeployment.id, citation.nodeKey)
        assertEquals(toProd.steps, citation.edgePath)
        assertEquals(stated, citation.provenance)
    }

    @Test
    fun `owners are resolved only for the hits kept, along the registry's inheritance`() {
        service.changeImpact(ChangeImpactQuery(payments.key.key, limit = 2))

        val nodes = argumentCaptor<Collection<NodeKey>>()
        verify(queries).ownerPathsOf(nodes.capture(), eq(traversals.ownershipInheritance()), eq(traversals.ownerEdges()), any())
        assertEquals(setOf(prodDeployment.key, artifact.key), nodes.firstValue.toSet())
    }

    @Test
    fun `the answer is cut at the limit and says it was truncated`() {
        val result = service.changeImpact(ChangeImpactQuery(payments.key.key, limit = 3))

        assertEquals(3, result.hits.size)
        assertTrue(result.truncated)
        assertFalse(service.changeImpact(ChangeImpactQuery(payments.key.key, limit = 4)).truncated)
    }

    @Test
    fun `a walk the port cut short is truncated too`() {
        whenever(queries.paths(any(), any(), any(), any())).thenReturn(PathSearch(listOf(toCheckout), true))

        assertTrue(service.changeImpact(ChangeImpactQuery(payments.key.key)).truncated)
    }

    @Test
    fun `no paths asked is no path filter, and the index is not read`() {
        val result = service.changeImpact(ChangeImpactQuery(payments.key.key))

        assertEquals(PathFilter.NOT_REQUESTED, result.pathFilter)
        verify(queries, never()).pathIndex(any())
    }

    @Test
    fun `paths against a repository with no index are not applied, and the answer is the unfiltered one`() {
        val unfiltered = service.changeImpact(ChangeImpactQuery(payments.key.key))
        val result = service.changeImpact(ChangeImpactQuery(payments.key.key, paths = listOf("src/billing/invoice.kt")))

        assertEquals(PathFilter.NOT_APPLIED, result.pathFilter)
        assertEquals(emptyList<String>(), result.matchedPaths)
        assertEquals(unfiltered.hits, result.hits)
    }

    @Test
    fun `a path naming an IaC file boosts the hits that file names, and is reported as matched`() {
        whenever(queries.paths(any(), any(), any(), any())).thenReturn(PathSearch(listOf(toProd, toArtifact, toDatabase), false))
        whenever(queries.pathIndex(payments.key)).thenReturn(
            listOf(
                PathIndexEntry("infra/db.tf", PathIndexKind.IAC, listOf("arn:db")),
                PathIndexEntry("build.gradle.kts", PathIndexKind.MANIFEST, listOf("maven:org.slf4j:slf4j-api")),
            ),
        )

        val result = service.changeImpact(ChangeImpactQuery(payments.key.key, paths = listOf("./infra/db.tf", "src/Main.kt")))

        assertEquals(PathFilter.APPLIED, result.pathFilter)
        assertEquals(listOf("infra/db.tf"), result.matchedPaths)
        val first = result.hits.first()
        assertEquals(database.id, first.node.id)
        assertTrue(first.pathMatched)
        assertEquals(0.5, first.score, 1e-12)
        assertFalse(result.hits.drop(1).any { it.pathMatched })
    }

    @Test
    fun `an index that no requested path names is still applied, and boosts nothing`() {
        whenever(queries.pathIndex(payments.key)).thenReturn(listOf(PathIndexEntry("infra/db.tf", PathIndexKind.IAC, listOf("arn:db"))))

        val result = service.changeImpact(ChangeImpactQuery(payments.key.key, paths = listOf("src/Main.kt")))

        assertEquals(PathFilter.APPLIED, result.pathFilter)
        assertEquals(emptyList<String>(), result.matchedPaths)
        assertFalse(result.hits.any { it.pathMatched })
    }

    @Test
    fun `a sha cannot scope the answer until changes are in the graph, and says so`() {
        assertEquals(ChangeScope.NOT_REQUESTED, service.changeImpact(ChangeImpactQuery(payments.key.key)).changeScope)
        assertEquals(ChangeScope.UNKNOWN, service.changeImpact(ChangeImpactQuery(payments.key.key, sha = "4f1c2d9")).changeScope)
    }

    @Test
    fun `a repository that is not there is not found, and nothing is walked`() {
        whenever(graphStore.findNode(payments.key)).thenReturn(null)

        assertThrows<NodeNotFoundException> { service.changeImpact(ChangeImpactQuery(payments.key.key)) }
        verify(queries, never()).paths(any(), any(), any(), any())
    }

    @Test
    fun `the same graph gives the same answer, whatever order the store returned it in`() {
        val first = service.changeImpact(ChangeImpactQuery(payments.key.key))
        whenever(queries.paths(any(), any(), any(), any())).thenReturn(PathSearch(listOf(toArtifact, toProd, toCheckout, toStaging), false))

        assertEquals(first, service.changeImpact(ChangeImpactQuery(payments.key.key)))
    }
}
