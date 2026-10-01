package com.repodatagraph.application.links

import com.repodatagraph.application.connector.SyncRunRecorder
import com.repodatagraph.application.links.LinkFixtures.manualOwner
import com.repodatagraph.application.links.LinkFixtures.repository
import com.repodatagraph.application.links.LinkFixtures.resource
import com.repodatagraph.application.links.rules.ManualLinkRule
import com.repodatagraph.domain.exception.CandidateDecidedException
import com.repodatagraph.domain.exception.CandidateNotFoundException
import com.repodatagraph.domain.exception.ManualLinkExistsException
import com.repodatagraph.domain.exception.ManualLinkNotFoundException
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.model.CandidateStatus
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.LinkScope
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Principal
import com.repodatagraph.domain.model.PrincipalType
import com.repodatagraph.domain.port.out.connector.SyncMode
import com.repodatagraph.observability.SyncMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executor

/**
 * The link engine's use cases (#28, FR6, FR9 to FR11): a resolution run as a sync run, and the
 * decisions a person makes about what it proposed, each recorded with who made it and when.
 */
class LinkServiceTest {
    private val payments = repository("github.com/acme/payments")
    private val billing = repository("github.com/acme/billing")
    private val ledger = repository("github.com/acme/ledger")
    private val queue = resource("aws:arn:aws:sqs:eu-west-1:1:billing-prod", name = "billing-prod")
    private val store = LinkFixtures.store(payments, billing, ledger, queue)
    private val queries = FakeLinkQueries(store)

    private val now = Instant.parse("2026-10-01T09:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val dan = Principal("dan", PrincipalType.USER)

    private class Stub : LinkRule {
        override val name = "naming"
        var says: List<LinkProposal> = emptyList()

        override fun evaluate(
            resource: GraphNode,
            ctx: LinkContext,
        ) = says
    }

    private val naming = Stub()
    private val engine = LinkResolutionEngine(listOf(ManualLinkRule(), naming), 0.5, store, clock)

    /** Runs what it is given at once, so a resolution has finished when resolve returns. */
    private val direct = Executor { it.run() }

    private fun service(executor: Executor) =
        LinkService(
            engine = engine,
            graphStore = store,
            queries = queries,
            runs = SyncRunRecorder(store, clock),
            metrics = SyncMetrics(SimpleMeterRegistry(), clock),
            principal = { dan },
            environments = LinkFixtures.ENVIRONMENTS,
            executor = executor,
            clock = clock,
        )

    private val service = service(direct)

    private fun proposal(
        repo: GraphNode,
        confidence: Double,
    ) = LinkProposal(repo.key.key, confidence, "naming", mapOf("name" to "billing-prod"))

    private fun candidateId(repo: GraphNode) = EvidenceHash.candidateId(queue.key.key, repo.key.key)

    private fun owner(repo: GraphNode): GraphEdge? = store.edge("OWNS_RESOURCE", repo.key, queue.key)

    private fun candidate(repo: GraphNode): GraphEdge? = store.edge("CANDIDATE_LINK", queue.key, repo.key)

    private fun proposeBillingAndLedger() {
        naming.says = listOf(proposal(billing, 0.4), proposal(ledger, 0.4))
        service.resolve(LinkScope())
    }

    @Test
    fun `a resolution is a FULL sync run of the link engine, recorded from RUNNING to SUCCESS`() {
        naming.says = listOf(proposal(billing, 0.4))

        val started = service.resolve(LinkScope())

        assertEquals(SyncMode.FULL, started.mode)
        val run = store.findNode(NodeKey("SyncRun", started.syncRunId))!!
        assertEquals("link-engine", run.props["connector"])
        assertEquals("link-engine", run.props["sourceSystem"])
        assertEquals("FULL", run.props["mode"])
        assertEquals("SUCCESS", run.props["status"])
        assertEquals(1, run.props["edgesUpserted"])
        assertEquals(1, run.props["written"])
        assertEquals(started.syncRunId, candidate(billing)!!.provenance.syncRunId)
    }

    @Test
    fun `a scoped resolution is an INCREMENTAL run`() {
        val started = service.resolve(LinkScope(provider = "aws"))

        assertEquals(SyncMode.INCREMENTAL, started.mode)
        assertEquals("INCREMENTAL", store.findNode(NodeKey("SyncRun", started.syncRunId))!!.props["mode"])
    }

    @Test
    fun `a run waits its turn, and reads as RUNNING until it has one`() {
        val queued = mutableListOf<Runnable>()
        val later = service(Executor { queued += it })

        val started = later.resolve(LinkScope())

        assertEquals("RUNNING", store.findNode(NodeKey("SyncRun", started.syncRunId))!!.props["status"])
        queued.single().run()
        assertEquals("SUCCESS", store.findNode(NodeKey("SyncRun", started.syncRunId))!!.props["status"])
    }

    @Test
    fun `accepting a candidate states a manual owner, closes rivals and supersedes the other candidates`() {
        naming.says = listOf(proposal(payments, 0.9), proposal(billing, 0.4), proposal(ledger, 0.4))
        service.resolve(LinkScope())
        assertTrue(owner(payments)!!.provenance.inferred)

        val accepted = service.accept(candidateId(billing))

        val stated = owner(billing)!!
        assertEquals("manual", stated.props["rule"])
        assertEquals("dan", stated.props["acceptedBy"])
        assertEquals(now, stated.props["acceptedAt"])
        assertEquals(listOf("name=billing-prod"), stated.props["evidence"])
        assertEquals("manual", stated.provenance.sourceSystem)
        assertEquals(1.0, stated.provenance.confidence)
        assertFalse(stated.provenance.inferred)
        assertEquals("dan", stated.provenance.writtenBy)
        assertEquals("manual", accepted.rule)
        assertEquals("dan", accepted.acceptedBy)

        assertFalse(owner(payments)!!.provenance.current)
        assertEquals("accepted", candidate(billing)!!.props["status"])
        assertFalse(candidate(billing)!!.provenance.current)
        assertEquals("superseded", candidate(ledger)!!.props["status"])
        assertTrue(candidate(ledger)!!.provenance.current)
    }

    @Test
    fun `an accepted link survives later resolutions unchanged`() {
        proposeBillingAndLedger()
        service.accept(candidateId(billing))
        val stated = owner(billing)

        service.resolve(LinkScope())

        assertEquals(stated, owner(billing))
        assertEquals("superseded", candidate(ledger)!!.props["status"])
    }

    @Test
    fun `rejecting a candidate records who and when, and keeps it as a tombstone`() {
        proposeBillingAndLedger()

        val rejected = service.reject(candidateId(billing))

        assertEquals(CandidateStatus.REJECTED, rejected.status)
        assertEquals("dan", rejected.rejectedBy)
        val edge = candidate(billing)!!
        assertEquals("rejected", edge.props["status"])
        assertEquals("dan", edge.props["rejectedBy"])
        assertEquals(now, edge.props["rejectedAt"])
        assertTrue(edge.provenance.current)
        assertNull(owner(billing))
    }

    @Test
    fun `an unknown candidate is not found, and a decided one cannot be decided again`() {
        proposeBillingAndLedger()
        service.reject(candidateId(billing))

        assertThrows<CandidateNotFoundException> { service.accept("nope") }
        assertThrows<CandidateNotFoundException> { service.reject("nope") }
        val refused = assertThrows<CandidateDecidedException> { service.accept(candidateId(billing)) }
        assertEquals(CandidateStatus.REJECTED, refused.status)
        assertThrows<CandidateDecidedException> { service.reject(candidateId(billing)) }
    }

    @Test
    fun `a manual link is stated by the principal, and refused a second time`() {
        val stated = service.link(queue.key.key, billing.key.key)

        val edge = owner(billing)!!
        assertEquals("manual", edge.props["rule"])
        assertEquals("manual", edge.provenance.sourceSystem)
        assertEquals("dan", edge.provenance.writtenBy)
        assertFalse(edge.provenance.inferred)
        assertEquals("manual", stated.rule)
        assertThrows<ManualLinkExistsException> { service.link(queue.key.key, billing.key.key) }
    }

    @Test
    fun `a manual link to or from a node the graph lacks is not found`() {
        assertThrows<NodeNotFoundException> { service.link(queue.key.key, "github.com/acme/nowhere") }
        assertThrows<NodeNotFoundException> { service.link("aws:arn:aws:s3:::nowhere", billing.key.key) }
    }

    @Test
    fun `a manual link replaces an inferred one for the same pair, and closes an inferred rival`() {
        naming.says = listOf(LinkProposal(payments.key.key, 0.9, "naming", emptyMap()))
        service.resolve(LinkScope())

        service.link(queue.key.key, billing.key.key)

        assertFalse(owner(payments)!!.provenance.current)
        assertTrue(owner(billing)!!.provenance.current)
    }

    @Test
    fun `closing a manual link sets its validTo, and closing one that is not there is not found`() {
        store.upsertEdge(manualOwner(billing.key, queue.key))

        service.unlink(queue.key.key, billing.key.key)

        assertEquals(now, owner(billing)!!.provenance.validTo)
        assertThrows<ManualLinkNotFoundException> { service.unlink(queue.key.key, billing.key.key) }
        assertThrows<ManualLinkNotFoundException> { service.unlink(queue.key.key, payments.key.key) }
    }

    @Test
    fun `the candidates are listed from the queries`() {
        proposeBillingAndLedger()

        val page =
            service.candidates(
                com.repodatagraph.domain.model
                    .CandidateQuery(),
            )

        assertEquals(2L, page.totalElements)
    }
}
