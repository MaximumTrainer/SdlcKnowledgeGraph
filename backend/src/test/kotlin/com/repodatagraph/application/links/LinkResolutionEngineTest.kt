package com.repodatagraph.application.links

import com.repodatagraph.application.links.LinkFixtures.manualOwner
import com.repodatagraph.application.links.LinkFixtures.repository
import com.repodatagraph.application.links.LinkFixtures.resource
import com.repodatagraph.application.links.rules.ManualLinkRule
import com.repodatagraph.application.links.rules.TagLinkRule
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.LinkScope
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * The link engine (#28, FR3 to FR5): what a resolution writes, and what a second one does not.
 *
 * The rules are stand-ins whose evidence a test changes between runs, except where a real rule
 * reads the graph; the store is in memory, with the Neo4j store's merge and validity semantics.
 */
class LinkResolutionEngineTest {
    private val payments = repository("github.com/acme/payments")
    private val billing = repository("github.com/acme/billing")
    private val ledger = repository("github.com/acme/ledger")
    private val queue = resource("aws:arn:aws:sqs:eu-west-1:1:billing-prod", name = "billing-prod")
    private val bucket = resource("aws:arn:aws:s3:::acme-payments-logs", name = "acme-payments-logs", accountId = "2")
    private val store = LinkFixtures.store(payments, billing, ledger, queue, bucket)

    /** A rule that says whatever the test last told it to, per resource key. */
    private class Stub(
        override val name: String,
    ) : LinkRule {
        val says = mutableMapOf<String, List<LinkProposal>>()

        override fun evaluate(
            resource: GraphNode,
            ctx: LinkContext,
        ): List<LinkProposal> = says[resource.key.key].orEmpty()
    }

    private val tag = Stub("tag")
    private val iac = Stub("iac")
    private val naming = Stub("naming")

    private var now = Instant.parse("2026-10-01T09:00:00Z")
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant(): Instant = now
        }

    private val engine = LinkResolutionEngine(listOf(ManualLinkRule(), tag, iac, naming), THRESHOLD, store, clock)
    private val context = LinkFixtures.FakeContext(store)

    private fun proposal(
        repo: GraphNode,
        confidence: Double,
        rule: String,
        vararg evidence: Pair<String, String>,
    ) = LinkProposal(repo.key.key, confidence, rule, mapOf(*evidence))

    private fun run(scope: LinkScope = LinkScope()): ResolutionTotals =
        engine.resolve(context, "run-${now.epochSecond}", scope).also {
            now +=
                Duration.ofMinutes(1)
        }

    private fun owner(
        repo: GraphNode,
        resource: GraphNode,
    ): GraphEdge? = store.edge("OWNS_RESOURCE", repo.key, resource.key)

    private fun candidate(
        resource: GraphNode,
        repo: GraphNode,
    ): GraphEdge? = store.edge("CANDIDATE_LINK", resource.key, repo.key)

    @Test
    fun `the strongest proposal at or above the threshold is written as an inferred owner, with its explanation`() {
        tag.says[queue.key.key] = listOf(proposal(billing, 0.95, "tag", "tag" to "repo", "value" to "github.com/acme/billing"))

        val totals = run()

        val edge = owner(billing, queue)!!
        assertEquals("tag", edge.props["rule"])
        assertEquals(listOf("tag=repo", "value=github.com/acme/billing"), edge.props["evidence"])
        assertEquals(Instant.parse("2026-10-01T09:00:00Z"), edge.props["resolvedAt"])
        assertEquals("link-engine", edge.provenance.sourceSystem)
        assertEquals(0.95, edge.provenance.confidence)
        assertTrue(edge.provenance.inferred)
        assertEquals("run-${Instant.parse("2026-10-01T09:00:00Z").epochSecond}", edge.provenance.syncRunId)
        assertNull(edge.provenance.validTo)
        assertEquals(emptyList<GraphEdge>(), store.allEdges("CANDIDATE_LINK"))
        assertEquals(1, totals.written)
    }

    @Test
    fun `a proposal below the threshold is a pending candidate, keyed by a stable id, and owns nothing`() {
        naming.says[queue.key.key] = listOf(proposal(billing, 0.4, "naming", "name" to "billing-prod"))

        run()

        val edge = candidate(queue, billing)!!
        assertEquals("pending", edge.props["status"])
        assertEquals("naming", edge.props["rule"])
        assertEquals(EvidenceHash.candidateId(queue.key.key, billing.key.key), edge.props["candidateId"])
        assertEquals(EvidenceHash.of("naming", mapOf("name" to "billing-prod")), edge.props["evidenceHash"])
        assertEquals(Instant.parse("2026-10-01T09:00:00Z"), edge.props["createdAt"])
        assertEquals(0.4, edge.provenance.confidence)
        assertTrue(edge.provenance.inferred)
        assertEquals(emptyList<GraphEdge>(), store.allEdges("OWNS_RESOURCE"))
    }

    @Test
    fun `the highest of several rules for one repository is the one kept`() {
        naming.says[queue.key.key] = listOf(proposal(billing, 0.4, "naming"))
        iac.says[queue.key.key] = listOf(proposal(billing, 0.7, "iac", "path" to "infra/main.tf"))

        run()

        assertEquals("iac", owner(billing, queue)!!.props["rule"])
        assertNull(candidate(queue, billing))
    }

    @Test
    fun `two strong repositories make the stronger the owner and the other a conflict`() {
        tag.says[queue.key.key] = listOf(proposal(payments, 0.95, "tag"))
        iac.says[queue.key.key] = listOf(proposal(billing, 0.7, "iac"))

        run()

        assertEquals("tag", owner(payments, queue)!!.props["rule"])
        assertNull(owner(billing, queue))
        assertEquals("conflict", candidate(queue, billing)!!.props["status"])
    }

    @Test
    fun `a tie owns nothing and leaves two conflicts`() {
        iac.says[queue.key.key] = listOf(proposal(payments, 0.7, "iac"), proposal(billing, 0.7, "iac"))

        run()

        assertEquals(emptyList<GraphEdge>(), store.allEdges("OWNS_RESOURCE"))
        assertEquals(listOf("conflict", "conflict"), store.allEdges("CANDIDATE_LINK").map { it.props["status"] })
    }

    @Test
    fun `a manual owner is never overwritten or closed, and stronger inferences become conflicts`() {
        val stated = manualOwner(ledger.key, queue.key)
        store.upsertEdge(stated)
        tag.says[queue.key.key] = listOf(proposal(payments, 0.95, "tag"))

        run()

        assertEquals(stated, owner(ledger, queue))
        assertNull(owner(payments, queue))
        assertEquals("conflict", candidate(queue, payments)!!.props["status"])
    }

    @Test
    fun `an owner another source states is left alone, and its repository is not proposed over it`() {
        val reported = GraphEdge("OWNS_RESOURCE", billing.key, queue.key, emptyMap(), Provenance.stated("aws", LinkFixtures.T0))
        store.upsertEdge(reported)
        naming.says[queue.key.key] = listOf(proposal(billing, 0.4, "naming"))

        run()

        assertEquals(reported, owner(billing, queue))
        assertNull(candidate(queue, billing))
    }

    @Test
    fun `a second run over the same evidence writes no new edge and changes nothing but resolvedAt`() {
        tag.says[queue.key.key] = listOf(proposal(payments, 0.95, "tag"))
        iac.says[queue.key.key] = listOf(proposal(billing, 0.7, "iac"))
        naming.says[bucket.key.key] = listOf(proposal(ledger, 0.4, "naming"))
        run()
        val owners = store.allEdges("OWNS_RESOURCE")
        val candidates = store.allEdges("CANDIDATE_LINK")

        val totals = run()

        assertEquals(candidates, store.allEdges("CANDIDATE_LINK"))
        val again = store.allEdges("OWNS_RESOURCE")
        assertEquals(owners.map { it.provenance }, again.map { it.provenance })
        assertEquals(owners.map { it.props - "resolvedAt" }, again.map { it.props - "resolvedAt" })
        assertEquals(Instant.parse("2026-10-01T09:01:00Z"), again.single().props["resolvedAt"])
        assertEquals(0, totals.written)
        assertEquals(0, totals.closed)
        assertEquals(3, totals.unchanged)
    }

    @Test
    fun `an owner whose evidence is gone is closed, not deleted`() {
        tag.says[queue.key.key] = listOf(proposal(payments, 0.95, "tag"))
        run()
        tag.says.clear()

        val totals = run()

        val closed = owner(payments, queue)!!
        assertEquals(Instant.parse("2026-10-01T09:01:00Z"), closed.provenance.validTo)
        assertEquals(Instant.parse("2026-10-01T09:00:00Z"), closed.provenance.validFrom)
        assertEquals(1, totals.closed)
    }

    @Test
    fun `an owner whose evidence weakens below the threshold is closed and becomes a candidate`() {
        tag.says[queue.key.key] = listOf(proposal(billing, 0.95, "tag"))
        run()
        tag.says.clear()
        naming.says[queue.key.key] = listOf(proposal(billing, 0.4, "naming"))

        run()

        assertFalse(owner(billing, queue)!!.provenance.current)
        assertEquals("pending", candidate(queue, billing)!!.props["status"])
    }

    @Test
    fun `a closed owner whose evidence returns is opened again`() {
        tag.says[queue.key.key] = listOf(proposal(payments, 0.95, "tag"))
        run()
        tag.says.clear()
        run()
        tag.says[queue.key.key] = listOf(proposal(payments, 0.95, "tag"))

        run()

        val reopened = owner(payments, queue)!!
        assertTrue(reopened.provenance.current)
        assertEquals(Instant.parse("2026-10-01T09:02:00Z"), reopened.provenance.validFrom)
    }

    @Test
    fun `a candidate nothing proposes any more is closed`() {
        naming.says[queue.key.key] = listOf(proposal(billing, 0.4, "naming"))
        run()
        naming.says.clear()

        run()

        assertFalse(candidate(queue, billing)!!.provenance.current)
    }

    @Test
    fun `a rejected candidate stays rejected on the same evidence, and is proposed again when it changes`() {
        naming.says[queue.key.key] = listOf(proposal(billing, 0.4, "naming", "name" to "billing-prod"))
        run()
        val rejected =
            candidate(queue, billing)!!.let {
                it.copy(props = it.props + mapOf("status" to "rejected", "rejectedBy" to "dan", "rejectedAt" to LinkFixtures.T0))
            }
        store.upsertEdge(rejected)

        val quiet = run()

        assertEquals(rejected.props, candidate(queue, billing)!!.props)
        assertEquals(0, quiet.written)

        naming.says[queue.key.key] = listOf(proposal(billing, 0.4, "naming", "name" to "billing-production"))
        run()

        val reopened = candidate(queue, billing)!!
        assertEquals("pending", reopened.props["status"])
        assertNull(reopened.props["rejectedBy"])
        assertNull(reopened.props["rejectedAt"])
    }

    @Test
    fun `a rejected strong proposal is not made the owner while its evidence holds`() {
        iac.says[queue.key.key] = listOf(proposal(payments, 0.7, "iac"), proposal(billing, 0.7, "iac"))
        run()
        val rejected = candidate(queue, billing)!!.let { it.copy(props = it.props + ("status" to "rejected")) }
        store.upsertEdge(rejected)

        run()

        assertEquals("iac", owner(payments, queue)!!.props["rule"])
        assertNull(owner(billing, queue))
        assertEquals("rejected", candidate(queue, billing)!!.props["status"])
        assertFalse(candidate(queue, payments)!!.provenance.current)
    }

    @Test
    fun `a superseded candidate stays superseded while its evidence holds`() {
        tag.says[queue.key.key] = listOf(proposal(payments, 0.95, "tag"))
        store.upsertEdge(manualOwner(billing.key, queue.key))
        run()
        val superseded = candidate(queue, payments)!!.let { it.copy(props = it.props + ("status" to "superseded")) }
        store.upsertEdge(superseded)

        run()

        assertEquals("superseded", candidate(queue, payments)!!.props["status"])
    }

    @Test
    fun `the real tag rule, run twice, writes the owner once`() {
        val tagged = resource("aws:arn:aws:lambda:eu-west-1:1:function:payments-api", tags = listOf("repo=github.com/acme/payments"))
        store.upsertNode(tagged)
        val real = LinkResolutionEngine(listOf(ManualLinkRule(), TagLinkRule()), THRESHOLD, store, clock)

        real.resolve(context, "first", LinkScope())
        val writes = store.edgeWrites
        real.resolve(context, "second", LinkScope())

        assertEquals("tag", owner(payments, tagged)!!.props["rule"])
        assertEquals(writes + 1, store.edgeWrites, "the second run should only have touched resolvedAt")
    }

    @Test
    fun `a scope by provider and account resolves only the resources it names`() {
        naming.says[queue.key.key] = listOf(proposal(billing, 0.4, "naming"))
        naming.says[bucket.key.key] = listOf(proposal(ledger, 0.4, "naming"))

        val totals = run(LinkScope(provider = "aws", accountId = "2"))

        assertNull(candidate(queue, billing))
        assertNotNull(candidate(bucket, ledger))
        assertEquals(1, totals.resources)
    }

    @Test
    fun `a scope by repository resolves only the resources that repository is or was proposed for`() {
        naming.says[queue.key.key] = listOf(proposal(billing, 0.4, "naming"))
        naming.says[bucket.key.key] = listOf(proposal(ledger, 0.4, "naming"))

        run(LinkScope(repoKey = "github.com/acme/ledger"))

        assertNull(candidate(queue, billing))
        assertNotNull(candidate(bucket, ledger))
    }

    @Test
    fun `a scope of touched resources and repositories resolves either`() {
        naming.says[queue.key.key] = listOf(proposal(billing, 0.4, "naming"))
        naming.says[bucket.key.key] = listOf(proposal(ledger, 0.4, "naming"))

        run(LinkScope(resourceKeys = setOf(queue.key.key)))

        assertNotNull(candidate(queue, billing))
        assertNull(candidate(bucket, ledger))
    }

    @Test
    fun `a retired resource is not resolved`() {
        store.upsertNode(queue.copy(provenance = queue.provenance.copy(validTo = LinkFixtures.T0)))
        naming.says[queue.key.key] = listOf(proposal(billing, 0.4, "naming"))

        run()

        assertNull(candidate(queue, billing))
    }

    @Test
    fun `a proposal for a repository the graph does not hold is dropped`() {
        naming.says[queue.key.key] = listOf(LinkProposal("github.com/acme/ghost", 0.4, "naming", emptyMap()))

        run()

        assertEquals(emptyList<GraphEdge>(), store.allEdges())
    }

    @Test
    fun `a rule that fails is counted against its resource, and the others are still resolved`() {
        val failing =
            object : LinkRule {
                override val name = "iac"

                override fun evaluate(
                    resource: GraphNode,
                    ctx: LinkContext,
                ): List<LinkProposal> = if (resource.key == queue.key) error("cannot read") else emptyList()
            }
        naming.says[bucket.key.key] = listOf(proposal(ledger, 0.4, "naming"))
        val fragile = LinkResolutionEngine(listOf(failing, naming), THRESHOLD, store, clock)

        val totals = fragile.resolve(context, "run", LinkScope())

        assertEquals(1, totals.failed)
        assertNotNull(candidate(bucket, ledger))
        assertEquals(NodeKey("CloudResource", bucket.key.key), candidate(bucket, ledger)!!.from)
    }

    private companion object {
        const val THRESHOLD = 0.5
    }
}
