package com.repodatagraph.application.contextpack

import com.repodatagraph.adapter.out.ontology.YamlOntologyLoader
import com.repodatagraph.application.freshness.FactFreshness
import com.repodatagraph.application.impact.ImpactScorer
import com.repodatagraph.application.impact.TraversalFilterBuilder
import com.repodatagraph.application.neighbourhood.SubgraphMapper
import com.repodatagraph.domain.exception.InvalidQueryParameterException
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.model.ContextPack
import com.repodatagraph.domain.model.ContextPackQuery
import com.repodatagraph.domain.model.Direction
import com.repodatagraph.domain.model.EnvironmentTier
import com.repodatagraph.domain.model.FreshnessPolicy
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.IncidentEdge
import com.repodatagraph.domain.model.NeighbourStep
import com.repodatagraph.domain.model.Neighbours
import com.repodatagraph.domain.model.NodeKey
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
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.core.io.DefaultResourceLoader
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * The walk behind a context pack (#96): a template from the registry, walked one store step per hop
 * from the start node, owners added at each hop, deployments narrowed to the current one, then
 * ranked by distance, confidence and #87's score and cut to the budget.
 *
 * The store is an in-memory fake answering each step from a list of edges, the way the adapter
 * would; which Cypher finds them is the integration suite's business.
 */
class ContextPackServiceTest {
    private val registry = YamlOntologyLoader(DefaultResourceLoader()).load()
    private val graphStore = mock<GraphStore>()
    private val impactQueries = mock<ImpactQueryPort>()
    private val now = Instant.parse("2026-09-30T12:00:00Z")
    private val freshness =
        FactFreshness(
            FreshnessPolicy(Duration.ofDays(7), emptyMap(), registry.knownSources()),
            Clock.fixed(now, ZoneOffset.UTC),
        )
    private val service =
        ContextPackService(graphStore, registry, TraversalFilterBuilder(registry), impactQueries, SubgraphMapper(registry), freshness)

    private val seeded = Instant.parse("2026-09-01T09:00:00Z")
    private val stated = Provenance(sourceSystem = "manual", ingestedAt = seeded, validFrom = seeded)
    private val fresh = Provenance(sourceSystem = "github", ingestedAt = now, observedAt = now, validFrom = seeded)

    private val nodes = mutableMapOf<String, GraphNode>()
    private val edges = mutableListOf<GraphEdge>()

    private fun node(
        id: String,
        props: Map<String, Any?> = mapOf("name" to id.substringAfterLast('/')),
        provenance: Provenance = stated,
    ): GraphNode = GraphNode(NodeKey.parse(id), props, provenance).also { nodes[it.id] = it }

    private fun edge(
        type: String,
        from: GraphNode,
        to: GraphNode,
        props: Map<String, Any?> = emptyMap(),
        confidence: Double = 1.0,
        provenance: Provenance = stated.copy(confidence = confidence, inferred = confidence < 1.0),
    ) {
        edges += GraphEdge(type, from.key, to.key, props, provenance)
    }

    private fun repository(name: String) = node("Repository:github.com/acme/$name")

    private fun deployment(
        name: String,
        deployedAt: String,
        environment: String = "production",
        status: String = "SUCCESS",
    ) = node(
        "Deployment:$name",
        mapOf(
            "deployedAt" to Instant.parse(deployedAt),
            "status" to status,
            "environmentKey" to environment,
            "environmentId" to environment,
        ),
    )

    private val settlement = repository("settlement-api")
    private val production = node("Environment:production", mapOf("name" to "production", "type" to "production", "tier" to "production"))

    /** Answers a step as the adapter does: each current edge of the step's types, in its direction, whose properties match. */
    private fun answer(
        frontier: Collection<NodeKey>,
        step: NeighbourStep,
    ): Neighbours {
        val hops =
            frontier.flatMap { key ->
                edges
                    .filter { it.type in step.edgeTypes && step.where.all { (name, value) -> it.props[name] == value } }
                    .mapNotNull { edge ->
                        when {
                            step.direction != Direction.INCOMING && edge.from == key ->
                                IncidentEdge(edge, Direction.OUTGOING, nodes.getValue(edge.to.id))
                            step.direction != Direction.OUTGOING && edge.to == key ->
                                IncidentEdge(edge, Direction.INCOMING, nodes.getValue(edge.from.id))
                            else -> null
                        }
                    }
            }
        return Neighbours(hops.sortedBy { it.other.id }.take(step.limit), truncated = hops.size > step.limit)
    }

    @BeforeEach
    fun store() {
        whenever(graphStore.findNode(any())).thenAnswer { nodes[(it.arguments[0] as NodeKey).id] }
        whenever(graphStore.findNode(any(), any())).thenAnswer { nodes[(it.arguments[0] as NodeKey).id] }
        whenever(graphStore.neighbourhood(any(), any())).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            answer(it.arguments[0] as Collection<NodeKey>, it.arguments[1] as NeighbourStep)
        }
        whenever(impactQueries.placements(any(), any())).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val keys = invocation.arguments[0] as Collection<NodeKey>
            keys.associateWith { key ->
                edges.filter { it.type == "TO_ENVIRONMENT" && it.from == key }.map { nodes.getValue(it.to.id) }
            }
        }
    }

    private fun pack(
        template: String = "change-impact",
        budget: Int = 50,
        start: GraphNode = settlement,
        asOf: Instant? = null,
    ): ContextPack = service.contextPack(ContextPackQuery(start.key, template, budget, asOf))

    /** Three dependants, one of them deployed twice to production, owned by ledger; and something upstream. */
    private fun changeImpactGraph(): Map<String, GraphNode> {
        val consumer1 = repository("consumer-1")
        val consumer2 = repository("consumer-2")
        val consumer3 = repository("consumer-3")
        val common = repository("common-lib")
        val ledger = node("Team:ledger")
        edge("DEPENDS_ON", consumer1, settlement, mapOf("kind" to "library", "manifest" to "package.json"))
        edge("DEPENDS_ON", consumer2, settlement, mapOf("kind" to "api"))
        edge("DEPENDS_ON", consumer3, consumer1, mapOf("kind" to "library"))
        edge("DEPENDS_ON", settlement, common, mapOf("kind" to "library"))
        edge("OWNED_BY", consumer1, ledger)
        val oldArtifact = node("Artifact:ghcr.io/acme/consumer-1@sha256:old", mapOf("name" to "acme/consumer-1"))
        val newArtifact = node("Artifact:ghcr.io/acme/consumer-1@sha256:new", mapOf("name" to "acme/consumer-1"))
        edge("BUILT_FROM", oldArtifact, consumer1, mapOf("commitSha" to "c1"))
        edge("BUILT_FROM", newArtifact, consumer1, mapOf("commitSha" to "c2"))
        val superseded = deployment("superseded", "2026-09-01T10:00:00Z")
        val current = deployment("current", "2026-09-02T10:00:00Z")
        edge("DEPLOYED_TO", oldArtifact, superseded)
        edge("DEPLOYED_TO", newArtifact, current)
        edge("TO_ENVIRONMENT", superseded, production)
        edge("TO_ENVIRONMENT", current, production)
        return mapOf(
            "consumer1" to consumer1,
            "consumer2" to consumer2,
            "consumer3" to consumer3,
            "common" to common,
            "ledger" to ledger,
            "oldArtifact" to oldArtifact,
            "newArtifact" to newArtifact,
            "superseded" to superseded,
            "current" to current,
        )
    }

    @Test
    fun `an unknown template is refused naming the ones there are, and nothing is walked`() {
        val error = assertThrows<InvalidQueryParameterException> { pack(template = "everything") }

        assertEquals("template", error.field)
        assertTrue(error.message!!.contains("change-impact") && error.message!!.contains("data-consumers"), error.message)
        verify(graphStore, never()).neighbourhood(any(), any())
    }

    @Test
    fun `a start of a type the registry does not declare is refused naming startId`() {
        val error =
            assertThrows<InvalidQueryParameterException> {
                service.contextPack(
                    ContextPackQuery(NodeKey("Galaxy", "x"), "change-impact", 5),
                )
            }

        assertEquals("startId", error.field)
    }

    @Test
    fun `a start the template does not start from is refused, naming the types it does`() {
        val bucket = node("CloudResource:aws:arn:aws:s3:::ledger")

        val error = assertThrows<InvalidQueryParameterException> { pack(start = bucket) }

        assertEquals("startId", error.field)
        assertTrue(error.message!!.contains("Repository"), error.message)
        verify(graphStore, never()).neighbourhood(any(), any())
    }

    @Test
    fun `a start the graph does not hold is not found`() {
        val missing = GraphNode(NodeKey("Repository", "github.com/acme/nowhere"), emptyMap(), stated)

        assertThrows<NodeNotFoundException> { pack(start = missing) }
    }

    @Test
    fun `change impact holds the dependants, their owners and their current deployments, and nothing upstream`() {
        val graph = changeImpactGraph()

        val pack = pack()

        val ids = pack.nodes.map { it.node.id }.toSet()
        assertEquals(
            setOf(
                "consumer1",
                "consumer2",
                "consumer3",
                "ledger",
                "oldArtifact",
                "newArtifact",
                "current",
            ).map { graph.getValue(it).id }.toSet() +
                production.id,
            ids,
        )
        assertFalse(graph.getValue("superseded").id in ids)
        assertFalse(graph.getValue("common").id in ids)
        assertEquals(settlement.id, pack.start.node.id)
        assertEquals(0, pack.start.distance)
        assertEquals(ids.size, pack.reached)
        assertFalse(pack.truncated)
        assertEquals(0, pack.cut)
    }

    @Test
    fun `each node is at the distance of its nearest path, which explains it`() {
        val graph = changeImpactGraph()

        val byId = pack().nodes.associateBy { it.node.id }

        assertEquals(1, byId.getValue(graph.getValue("consumer1").id).distance)
        assertEquals(2, byId.getValue(graph.getValue("consumer3").id).distance)
        assertEquals(2, byId.getValue(graph.getValue("ledger").id).distance)
        val current = byId.getValue(graph.getValue("current").id)
        assertEquals(3, current.distance)
        assertEquals(listOf("DEPENDED_ON_BY", "BUILDS", "DEPLOYED_TO"), current.via.map { it.edge })
        assertEquals(settlement.id, current.via.first().from)
        assertEquals(4, byId.getValue(production.id).distance)
        assertEquals(listOf("DEPENDED_ON_BY", "OWNED_BY"), byId.getValue(graph.getValue("ledger").id).via.map { it.edge })
    }

    @Test
    fun `a deployment is weighted by the environment it runs in, with #87's score`() {
        val graph = changeImpactGraph()

        val current = pack().nodes.single { it.node.id == graph.getValue("current").id }

        assertEquals(EnvironmentTier.PRODUCTION, current.tier)
        assertEquals(ImpactScorer.score(3, EnvironmentTier.PRODUCTION, pathMatched = false), current.score)
        assertEquals(EnvironmentTier.PRODUCTION, pack().nodes.single { it.node.id == production.id }.tier)
    }

    @Test
    fun `the edges are those between the nodes kept, the start among them, each once and in id order`() {
        val graph = changeImpactGraph()

        val pack = pack()

        val ids = pack.edges.map { it.id }
        assertEquals(ids.sorted(), ids)
        assertEquals(ids.distinct(), ids)
        val consumer1 = graph.getValue("consumer1")
        assertTrue("DEPENDS_ON:${consumer1.id}>${settlement.id}" in ids, "$ids")
        assertTrue("OWNED_BY:${consumer1.id}>${graph.getValue("ledger").id}" in ids, "$ids")
        assertTrue("TO_ENVIRONMENT:${graph.getValue("current").id}>${production.id}" in ids, "$ids")
        assertTrue(ids.none { graph.getValue("superseded").id in it }, "$ids")
        assertTrue(ids.none { graph.getValue("common").id in it }, "$ids")
        val dependsOn = pack.edges.single { it.id == "DEPENDS_ON:${consumer1.id}>${settlement.id}" }
        assertEquals("package.json", dependsOn.manifest)
        assertEquals("DEPENDED_ON_BY", dependsOn.inverse)
        val builtFrom = pack.edges.first { it.edge.type == "BUILT_FROM" }
        assertEquals(builtFrom.edge.props["commitSha"], builtFrom.commitSha)
    }

    @Test
    fun `the budget keeps the nearest and counts what it cut`() {
        repeat(35) { index -> edge("DEPENDS_ON", repository("dependant-%02d".format(index)), settlement, mapOf("kind" to "api")) }

        val pack = pack(budget = 10)

        assertEquals(10, pack.nodes.size)
        assertEquals(35, pack.reached)
        assertEquals(25, pack.cut)
        assertTrue(pack.truncated)
        assertEquals((0 until 10).map { "Repository:github.com/acme/dependant-%02d".format(it) }, pack.nodes.map { it.node.id })
        assertTrue(pack.edges.all { it.edge.from.id in pack.nodes.map { n -> n.node.id } }, "an edge to a node cut is not kept")
    }

    @Test
    fun `at one distance the more confident path comes first`() {
        val guessed = repository("a-guessed")
        val stated = repository("z-stated")
        edge("DEPENDS_ON", guessed, settlement, mapOf("kind" to "api"), confidence = 0.6)
        edge("DEPENDS_ON", stated, settlement, mapOf("kind" to "api"))

        val pack = pack()

        assertEquals(listOf(stated.id, guessed.id), pack.nodes.map { it.node.id })
        assertEquals(0.6, pack.nodes.last().confidence)
        assertTrue(pack.nodes.last().inferred)
    }

    @Test
    fun `a cycle back to the start neither returns the start nor walks forever`() {
        val mutual = repository("mutual")
        edge("DEPENDS_ON", mutual, settlement, mapOf("kind" to "api"))
        edge("DEPENDS_ON", settlement, mutual, mapOf("kind" to "api"))

        val pack = pack()

        assertEquals(listOf(mutual.id), pack.nodes.map { it.node.id })
    }

    @Test
    fun `every fact carries a provenance summary, stale judged now`() {
        val consumer = node("Repository:github.com/acme/web", provenance = fresh)
        edge(
            "DEPENDS_ON",
            consumer,
            settlement,
            mapOf("kind" to "library", "manifest" to "package.json"),
            provenance = fresh.copy(confidence = 0.9),
        )

        val pack = pack()

        val summary = pack.nodes.single().provenance
        assertEquals("github", summary.source)
        assertEquals(now, summary.observedAt)
        assertFalse(summary.stale)
        // The start was stated a month ago by a source with a week's window.
        assertTrue(pack.start.provenance.stale)
        assertNull(pack.start.provenance.observedAt)
        val edge = pack.edges.single()
        assertEquals(0.9, edge.provenance.confidence)
        assertEquals("github", edge.provenance.source)
    }

    @Test
    fun `data consumers follow only data dependencies, with their owners`() {
        val bucket = node("CloudResource:aws:arn:aws:s3:::ledger", mapOf("name" to "ledger", "provider" to "aws"))
        val reconciler = repository("reconciler")
        val admin = repository("ledger-admin")
        val finance = node("Team:finance")
        edge("DEPENDS_ON", reconciler, bucket, mapOf("kind" to "data"))
        edge("DEPENDS_ON", admin, bucket, mapOf("kind" to "api"))
        edge("OWNED_BY", reconciler, finance)

        val pack = pack(template = "data-consumers", start = bucket)

        assertEquals(setOf(reconciler.id, finance.id), pack.nodes.map { it.node.id }.toSet())
        val steps = argumentCaptor<NeighbourStep>()
        verify(graphStore, atLeastOnce()).neighbourhood(any(), steps.capture())
        assertTrue(steps.allValues.any { it.where == mapOf("kind" to "data") && it.direction == Direction.INCOMING }, "${steps.allValues}")
    }

    @Test
    fun `incident triage reaches the owning repository's lineage and its api dependencies' current deployments`() {
        val database = node("CloudResource:aws:arn:aws:rds:eu-west-1:1:db:orders", mapOf("name" to "orders", "provider" to "aws"))
        val orders = repository("orders")
        val pricing = repository("pricing")
        val jsonUtils = repository("json-utils")
        val pipeline = node("Pipeline:github-actions:github.com/acme/orders:.github/workflows/ci.yml", mapOf("name" to "CI"))
        val ci = node("ConfigurationItem:servicenow:acme:ci-orders", mapOf("ciName" to "orders"))
        edge("OWNS_RESOURCE", orders, database, mapOf("rule" to "iac"))
        edge("HAS_PIPELINE", orders, pipeline)
        edge("RELATES_TO_CI", orders, ci)
        edge("DEPENDS_ON", orders, pricing, mapOf("kind" to "api"))
        edge("DEPENDS_ON", orders, jsonUtils, mapOf("kind" to "library"))
        val ordersArtifact = node("Artifact:ghcr.io/acme/orders@sha256:o", mapOf("name" to "acme/orders"))
        val pricingArtifact = node("Artifact:ghcr.io/acme/pricing@sha256:p", mapOf("name" to "acme/pricing"))
        edge("BUILT_FROM", ordersArtifact, orders, mapOf("commitSha" to "o1"))
        edge("BUILT_FROM", pricingArtifact, pricing, mapOf("commitSha" to "p1"))
        val ordersDeployment = deployment("orders-prod", "2026-09-04T10:00:00Z")
        val pricingDeployment = deployment("pricing-prod", "2026-09-05T10:00:00Z")
        val pricingFailed = deployment("pricing-failed", "2026-09-06T10:00:00Z", status = "FAILED")
        edge("DEPLOYED_TO", ordersArtifact, ordersDeployment)
        edge("DEPLOYED_TO", pricingArtifact, pricingDeployment)
        edge("DEPLOYED_TO", pricingArtifact, pricingFailed)

        val pack = pack(template = "incident-triage", start = database)

        val ids = pack.nodes.map { it.node.id }.toSet()
        assertEquals(
            setOf(
                orders,
                pipeline,
                ci,
                ordersArtifact,
                ordersDeployment,
                pricing,
                pricingArtifact,
                pricingDeployment,
            ).map { it.id }.toSet(),
            ids,
        )
        assertTrue(pack.edges.any { it.edge.type == "OWNS_RESOURCE" && it.rule == "iac" }, "${pack.edges.map { it.id }}")
    }

    @Test
    fun `as of an instant the start is read as it was then, and every step is asked as of it`() {
        changeImpactGraph()
        val asOf = Instant.parse("2026-09-10T00:00:00Z")

        val pack = pack(asOf = asOf)

        assertEquals(asOf, pack.asOf)
        verify(graphStore).findNode(settlement.key, asOf)
        val steps = argumentCaptor<NeighbourStep>()
        verify(graphStore, atLeastOnce()).neighbourhood(any(), steps.capture())
        assertTrue(steps.allValues.all { it.asOf == asOf }, "${steps.allValues}")
    }

    @Test
    fun `now, every step reads the current graph`() {
        changeImpactGraph()

        pack()

        val steps = argumentCaptor<NeighbourStep>()
        verify(graphStore, atLeastOnce()).neighbourhood(any(), steps.capture())
        assertTrue(steps.allValues.all { it.asOf == null }, "${steps.allValues}")
    }

    @Test
    fun `a step the store cut short marks the pack truncated`() {
        changeImpactGraph()
        whenever(graphStore.neighbourhood(any(), any())).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            answer(it.arguments[0] as Collection<NodeKey>, it.arguments[1] as NeighbourStep).copy(truncated = true)
        }

        assertTrue(pack().truncated)
    }

    @Test
    fun `the same graph gives the same pack, whatever order the store answers in`() {
        changeImpactGraph()
        val first = pack()
        edges.reverse()

        assertEquals(first, pack())
    }
}
