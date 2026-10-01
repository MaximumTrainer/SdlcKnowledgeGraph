package com.repodatagraph.application.links

import com.repodatagraph.application.links.rules.ManualLinkRule
import com.repodatagraph.domain.model.CandidateStatus
import com.repodatagraph.domain.model.Direction
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.LinkScope
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.observability.LogEvents
import java.time.Clock
import java.time.Instant

/** What one resolution did: edges written, said again, closed; resources a rule failed on; resources looked at. */
data class ResolutionTotals(
    val written: Int = 0,
    val unchanged: Int = 0,
    val closed: Int = 0,
    val failed: Int = 0,
    val resources: Int = 0,
) {
    operator fun plus(other: ResolutionTotals) =
        ResolutionTotals(
            written + other.written,
            unchanged + other.unchanged,
            closed + other.closed,
            failed + other.failed,
            resources + other.resources,
        )
}

/**
 * Decides, for each cloud resource in scope, which repository owns it (#28, FR1 to FR5), and keeps
 * the graph's OWNS_RESOURCE and CANDIDATE_LINK edges in step with that decision.
 *
 * Every rule proposes; per repository the strongest proposal stands; [LinkDecision] picks the owner
 * and the candidates. The engine then writes only what moved, so a second run over the same evidence
 * writes nothing but the owner's `resolvedAt`:
 * - it manages only the owners it wrote itself (source `link-engine`). A person's owner is the
 *   manual rule's evidence and is never touched; an owner another source states is left alone, and
 *   its repository is not proposed over it.
 * - an owner or candidate nothing proposes any more is closed with `validTo`, never deleted, so the
 *   graph still says what held and when; one proposed again is opened again.
 * - a rejected or superseded candidate is a tombstone: while the evidence hashes the same it is not
 *   proposed, so a person's decision is not undone by the next run. New evidence reopens it.
 *
 * A rule that throws loses only its resource for this run, which is counted as failed and left as
 * it was: deciding on part of the evidence could close an owner that is right.
 */
class LinkResolutionEngine(
    private val rules: List<LinkRule>,
    private val threshold: Double,
    private val graphStore: GraphStore,
    private val clock: Clock,
) {
    fun resolve(
        ctx: LinkContext,
        runId: String,
        scope: LinkScope,
    ): ResolutionTotals {
        var totals = ResolutionTotals()
        forEachResource(scope) { resource ->
            totals += resolveOne(resource, ctx, runId, scope)
        }
        return totals
    }

    private fun resolveOne(
        resource: GraphNode,
        ctx: LinkContext,
        runId: String,
        scope: LinkScope,
    ): ResolutionTotals {
        val proposals =
            try {
                rules.flatMap { it.evaluate(resource, ctx) }.filter { ctx.repository(it.repoKey) != null }
            } catch (
                // A rule reads evidence someone else wrote, in whatever shape they wrote it. One that
                // cannot read a resource must not stop the rest from being resolved.
                @Suppress("TooGenericExceptionCaught") failure: Exception,
            ) {
                LogEvents.linksRuleFailed(runId, resource.key.key, failure)
                null
            }
        val edges = Edges.of(graphStore, resource)
        return when {
            proposals == null -> ResolutionTotals(failed = 1)
            !inScope(resource, proposals, edges, scope) -> ResolutionTotals()
            else -> apply(resource, proposals, edges, runId) + ResolutionTotals(resources = 1)
        }
    }

    private fun inScope(
        resource: GraphNode,
        proposals: List<LinkProposal>,
        edges: Edges,
        scope: LinkScope,
    ): Boolean {
        val repositories = scope.repositories
        val involved = proposals.map { it.repoKey } + edges.owners.map { it.from.key } + edges.candidates.map { it.to.key }
        return (scope.resourceKeys.isEmpty() && repositories.isEmpty()) ||
            resource.key.key in scope.resourceKeys ||
            involved.any { it in repositories }
    }

    private fun apply(
        resource: GraphNode,
        proposals: List<LinkProposal>,
        edges: Edges,
        runId: String,
    ): ResolutionTotals {
        val now = Instant.now(clock)
        val foreign = edges.owners.filter { it.provenance.current && !isEngine(it) && !ManualLinkRule.isManual(it) }.map { it.from.key }
        val best =
            proposals
                .filter { it.repoKey !in foreign }
                .groupBy { it.repoKey }
                .map { (_, forRepo) ->
                    forRepo.maxWith(compareBy<LinkProposal> { it.confidence }.thenBy { it.rule == LinkDecision.MANUAL })
                }
        val candidateByRepo = edges.candidates.associateBy { it.to.key }
        val tombstoned =
            best
                .filter { it.rule != LinkDecision.MANUAL }
                .filter { proposal -> candidateByRepo[proposal.repoKey]?.let { isTombstoneFor(it, proposal) } == true }
                .map { it.repoKey }
                .toSet()
        val decision = LinkDecision.decide(best.filter { it.repoKey !in tombstoned }, threshold)

        var totals = writeOwner(resource, decision.owner, edges, runId, now)
        totals += writeCandidates(resource, decision.candidates, candidateByRepo, tombstoned, runId, now)
        return totals
    }

    private fun writeOwner(
        resource: GraphNode,
        owner: LinkProposal?,
        edges: Edges,
        runId: String,
        now: Instant,
    ): ResolutionTotals {
        var totals = ResolutionTotals()
        val inferred = owner?.takeIf { it.rule != LinkDecision.MANUAL }
        if (inferred != null) {
            val existing = edges.owners.firstOrNull { it.from.key == inferred.repoKey && isEngine(it) }
            totals +=
                if (existing != null && existing.provenance.current && sameOwner(existing, inferred)) {
                    // Said again: only when it was last found moves, so the history stays one fact.
                    graphStore.upsertEdge(existing.copy(props = existing.props + (RESOLVED_AT to now)))
                    ResolutionTotals(unchanged = 1)
                } else {
                    graphStore.upsertEdge(
                        GraphEdge(
                            OWNS_RESOURCE,
                            GraphLinkContext.key(inferred.repoKey),
                            resource.key,
                            mapOf(
                                "rule" to inferred.rule,
                                "evidence" to EvidenceHash.encode(inferred.evidence),
                                RESOLVED_AT to now,
                                "acceptedBy" to null,
                                "acceptedAt" to null,
                            ),
                            provenance(inferred.confidence, runId, now),
                        ),
                    )
                    ResolutionTotals(written = 1)
                }
        }
        edges.owners
            .filter { isEngine(it) && it.provenance.current && it.from.key != inferred?.repoKey }
            .forEach { stale ->
                graphStore.upsertEdge(stale.copy(provenance = stale.provenance.copy(validTo = now)))
                totals += ResolutionTotals(closed = 1)
            }
        return totals
    }

    @Suppress("LongParameterList")
    private fun writeCandidates(
        resource: GraphNode,
        wanted: List<LinkDecision.Candidate>,
        existing: Map<String, GraphEdge>,
        tombstoned: Set<String>,
        runId: String,
        now: Instant,
    ): ResolutionTotals {
        var totals = ResolutionTotals()
        val wantedRepos = wanted.map { it.proposal.repoKey }.toSet()
        existing.values.filter { it.provenance.current }.forEach { edge ->
            when (edge.to.key) {
                in tombstoned -> totals += ResolutionTotals(unchanged = 1)
                !in wantedRepos -> {
                    graphStore.upsertEdge(edge.copy(provenance = edge.provenance.copy(validTo = now)))
                    totals += ResolutionTotals(closed = 1)
                }
            }
        }
        wanted.forEach { candidate ->
            val proposal = candidate.proposal
            val before = existing[proposal.repoKey]
            val hash = EvidenceHash.of(proposal.rule, proposal.evidence)
            totals +=
                if (before != null && before.provenance.current && sameCandidate(before, candidate, hash)) {
                    ResolutionTotals(unchanged = 1)
                } else {
                    val createdAt = before?.takeIf { it.provenance.current }?.props?.get(CREATED_AT) ?: now
                    graphStore.upsertEdge(
                        GraphEdge(
                            CANDIDATE_LINK,
                            resource.key,
                            GraphLinkContext.key(proposal.repoKey),
                            mapOf(
                                "candidateId" to EvidenceHash.candidateId(resource.key.key, proposal.repoKey),
                                "status" to candidate.status.wireName,
                                "rule" to proposal.rule,
                                "evidence" to EvidenceHash.encode(proposal.evidence),
                                EVIDENCE_HASH to hash,
                                CREATED_AT to createdAt,
                                "rejectedBy" to null,
                                "rejectedAt" to null,
                            ),
                            provenance(proposal.confidence, runId, now),
                        ),
                    )
                    ResolutionTotals(written = 1)
                }
        }
        return totals
    }

    private fun provenance(
        confidence: Double,
        runId: String,
        now: Instant,
    ) = Provenance(
        sourceSystem = SOURCE,
        ingestedAt = now,
        confidence = confidence,
        inferred = true,
        validFrom = now,
        syncRunId = runId,
    )

    /** Every current CloudResource the scope's provider and account allow, a page at a time. */
    private fun forEachResource(
        scope: LinkScope,
        action: (GraphNode) -> Unit,
    ) {
        val filter = listOfNotNull(scope.provider?.let { "provider" to it }, scope.accountId?.let { "accountId" to it }).toMap()
        var after: String? = null
        do {
            val page = graphStore.findNodes(CLOUD_RESOURCE, filter, after, GraphLinkContext.PAGE)
            page.filter { it.provenance.current }.forEach(action)
            after = page.lastOrNull()?.key?.key
        } while (page.size == GraphLinkContext.PAGE)
    }

    /** A resource's owners, from repositories, and its candidates, current or not. */
    private data class Edges(
        val owners: List<GraphEdge>,
        val candidates: List<GraphEdge>,
    ) {
        companion object {
            fun of(
                store: GraphStore,
                resource: GraphNode,
            ) = Edges(
                owners =
                    store
                        .findEdges(resource.key, Direction.INCOMING, OWNS_RESOURCE)
                        .map { it.edge }
                        .filter { it.from.type == GraphLinkContext.REPOSITORY },
                candidates = store.findEdges(resource.key, Direction.OUTGOING, CANDIDATE_LINK).map { it.edge },
            )
        }
    }

    companion object {
        const val SOURCE = "link-engine"
        const val OWNS_RESOURCE = "OWNS_RESOURCE"
        const val CANDIDATE_LINK = "CANDIDATE_LINK"
        const val CLOUD_RESOURCE = "CloudResource"
        const val RESOLVED_AT = "resolvedAt"
        const val CREATED_AT = "createdAt"
        const val EVIDENCE_HASH = "evidenceHash"

        fun isEngine(edge: GraphEdge) = edge.provenance.sourceSystem == SOURCE
    }
}

private fun sameOwner(
    edge: GraphEdge,
    proposal: LinkProposal,
) = edge.props["rule"] == proposal.rule &&
    EvidenceHash.decodeStored(edge.props["evidence"]) == proposal.evidence &&
    edge.provenance.confidence == proposal.confidence

private fun sameCandidate(
    edge: GraphEdge,
    candidate: LinkDecision.Candidate,
    hash: String,
) = edge.props["status"] == candidate.status.wireName &&
    edge.props["rule"] == candidate.proposal.rule &&
    edge.props[LinkResolutionEngine.EVIDENCE_HASH] == hash &&
    edge.provenance.confidence == candidate.proposal.confidence

private fun isTombstoneFor(
    edge: GraphEdge,
    proposal: LinkProposal,
): Boolean {
    val status = CandidateStatus.entries.firstOrNull { it.wireName == edge.props["status"] } ?: return false
    return status.tombstone && edge.props[LinkResolutionEngine.EVIDENCE_HASH] == EvidenceHash.of(proposal.rule, proposal.evidence)
}
