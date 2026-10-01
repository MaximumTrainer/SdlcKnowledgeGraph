package com.repodatagraph.application.links

import com.repodatagraph.application.connector.DeltaResult
import com.repodatagraph.application.connector.RunStatus
import com.repodatagraph.application.connector.SyncRunRecorder
import com.repodatagraph.application.links.rules.ManualLinkRule
import com.repodatagraph.domain.exception.CandidateDecidedException
import com.repodatagraph.domain.exception.CandidateNotFoundException
import com.repodatagraph.domain.exception.ManualLinkExistsException
import com.repodatagraph.domain.exception.ManualLinkNotFoundException
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.model.CandidateLink
import com.repodatagraph.domain.model.CandidatePage
import com.repodatagraph.domain.model.CandidateQuery
import com.repodatagraph.domain.model.CandidateStatus
import com.repodatagraph.domain.model.Direction
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.LinkScope
import com.repodatagraph.domain.model.LinkedRepository
import com.repodatagraph.domain.model.LinkedResource
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.OwnerLink
import com.repodatagraph.domain.model.Principal
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.model.ResolutionStarted
import com.repodatagraph.domain.ontology.EnvironmentAliases
import com.repodatagraph.domain.port.`in`.LinkUseCase
import com.repodatagraph.domain.port.out.CurrentPrincipal
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.LinkQueries
import com.repodatagraph.domain.port.out.connector.SyncMode
import com.repodatagraph.observability.LogEvents
import com.repodatagraph.observability.SyncErrorKind
import com.repodatagraph.observability.SyncMetrics
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * The link engine's use cases (#28): resolutions, run as sync runs of `link-engine`, and the
 * decisions a person makes about what they proposed.
 *
 * A resolution is recorded RUNNING and handed to [executor], which runs one at a time, so a caller
 * is answered at once and two resolutions never interleave their writes. Its sync run says what it
 * wrote, said again and closed, as a connector's does.
 *
 * Accepting a candidate, or stating a link by hand, makes a manual OWNS_RESOURCE - the principal's
 * word, at 1.0 - closes every other owner the engine or a person stated for the resource, and
 * supersedes the resource's other open candidates. Rejecting one leaves it as a tombstone. Each
 * decision records who made it and when.
 */
@Suppress("TooManyFunctions", "LongParameterList")
class LinkService(
    private val engine: LinkResolutionEngine,
    private val graphStore: GraphStore,
    private val queries: LinkQueries,
    private val runs: SyncRunRecorder,
    private val metrics: SyncMetrics,
    private val principal: CurrentPrincipal,
    private val environments: EnvironmentAliases,
    private val executor: Executor,
    private val clock: Clock,
) : LinkUseCase {
    private val inFlight = AtomicInteger()

    init {
        metrics.register(
            connector = LinkResolutionEngine.SOURCE,
            sourceSystem = LinkResolutionEngine.SOURCE,
            modes = setOf(SyncMode.FULL, SyncMode.INCREMENTAL),
            running = { inFlight.get() > 0 },
            lastSuccess = { null },
        )
    }

    override fun resolve(scope: LinkScope): ResolutionStarted {
        val runId = UUID.randomUUID().toString()
        val startedAt = Instant.now(clock)
        val mode = scope.mode
        record(runId, RunStatus.RUNNING, DeltaResult(), startedAt, mode, null)
        inFlight.incrementAndGet()
        executor.execute { run(runId, scope, mode, startedAt) }
        return ResolutionStarted(runId, mode)
    }

    private fun run(
        runId: String,
        scope: LinkScope,
        mode: SyncMode,
        startedAt: Instant,
    ) {
        try {
            val (status, totals, error) =
                try {
                    val found = engine.resolve(GraphLinkContext(graphStore, queries, environments), runId, scope)
                    val status = if (found.failed > 0) RunStatus.PARTIAL else RunStatus.SUCCESS
                    Triple(status, found, null)
                } catch (
                    // The graph unreachable mid-run, most likely. The run says so rather than staying
                    // RUNNING for ever; the next run, scheduled or asked for, is the retry.
                    @Suppress("TooGenericExceptionCaught") failure: Exception,
                ) {
                    LogEvents.connectorRunFailed(LinkResolutionEngine.SOURCE, runId, failure)
                    metrics.error(LinkResolutionEngine.SOURCE, SyncErrorKind.RUN)
                    Triple(RunStatus.FAILED, ResolutionTotals(failed = 1), failure.message)
                }
            val delta =
                DeltaResult(
                    edgesUpserted = totals.written + totals.closed,
                    tombstones = totals.closed,
                    written = totals.written,
                    unchanged = totals.unchanged,
                    failed = totals.failed,
                )
            val took = Duration.between(startedAt, Instant.now(clock))
            metrics.runFinished(LinkResolutionEngine.SOURCE, mode, status.name, took, 0, delta.edgesUpserted, delta.tombstones)
            LogEvents.syncFinished(
                LinkResolutionEngine.SOURCE,
                runId,
                mode.name,
                status.name,
                took.toMillis().toInt(),
                0,
                delta.edgesUpserted,
                delta.tombstones,
            )
            if (status == RunStatus.SUCCESS) metrics.succeeded(LinkResolutionEngine.SOURCE, Instant.now(clock))
            record(runId, status, delta, startedAt, mode, error)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private fun record(
        runId: String,
        status: RunStatus,
        totals: DeltaResult,
        startedAt: Instant,
        mode: SyncMode,
        error: String?,
    ) = runs.recordSourceRun(runId, LinkResolutionEngine.SOURCE, mode, status, totals, startedAt, error)

    override fun candidates(query: CandidateQuery): CandidatePage = queries.candidates(query)

    override fun accept(id: String): OwnerLink {
        val candidate = openCandidate(id)
        val resource = node(RESOURCE, candidate.resource.key)
        val repository = node(GraphLinkContext.REPOSITORY, candidate.repository.key)
        val by = principal.current()
        val owner = stateOwner(resource, repository, candidate.evidence, by)
        LogEvents.linksCandidateAccepted(id, resource.key.key, repository.key.key, by.subject)
        return owner
    }

    override fun reject(id: String): CandidateLink {
        val candidate = openCandidate(id)
        val edge = candidateEdge(candidate)
        val by = principal.current()
        val now = Instant.now(clock)
        graphStore.upsertEdge(
            edge.copy(
                props =
                    edge.props +
                        mapOf(
                            STATUS to CandidateStatus.REJECTED.wireName,
                            "rejectedBy" to by.subject,
                            "rejectedAt" to now,
                        ),
            ),
        )
        LogEvents.linksCandidateRejected(id, candidate.resource.key, candidate.repository.key, by.subject)
        return candidate.copy(status = CandidateStatus.REJECTED, rejectedBy = by.subject, rejectedAt = now)
    }

    override fun link(
        resourceKey: String,
        repoKey: String,
    ): OwnerLink {
        val resourceAt = NodeKey(RESOURCE, resourceKey)
        val repositoryAt = NodeKey(GraphLinkContext.REPOSITORY, repoKey)
        val resource = graphStore.findNode(resourceAt)
        val repository = graphStore.findNode(repositoryAt)
        if (resource == null || repository == null) {
            throw NodeNotFoundException(listOfNotNull(resourceAt.takeIf { resource == null }, repositoryAt.takeIf { repository == null }))
        }
        val existing = graphStore.findEdge(LinkResolutionEngine.OWNS_RESOURCE, repositoryAt, resourceAt)
        if (existing != null && existing.provenance.current && ManualLinkRule.isManual(existing)) {
            throw ManualLinkExistsException(resourceKey, repoKey)
        }
        val by = principal.current()
        val owner = stateOwner(resource, repository, emptyMap(), by)
        LogEvents.linksManualStated(resourceKey, repoKey, by.subject)
        return owner
    }

    override fun unlink(
        resourceKey: String,
        repoKey: String,
    ) {
        val existing =
            graphStore
                .findEdge(LinkResolutionEngine.OWNS_RESOURCE, NodeKey(GraphLinkContext.REPOSITORY, repoKey), NodeKey(RESOURCE, resourceKey))
                ?.takeIf { it.provenance.current && ManualLinkRule.isManual(it) }
                ?: throw ManualLinkNotFoundException(resourceKey, repoKey)
        graphStore.upsertEdge(existing.copy(provenance = existing.provenance.copy(validTo = Instant.now(clock))))
        LogEvents.linksManualClosed(resourceKey, repoKey, principal.current().subject)
    }

    /**
     * Writes [repository] as the manual owner of [resource], closes every other owner the engine or a
     * person stated, marks the pair's candidate accepted and closes it, and supersedes the resource's
     * other open candidates.
     */
    private fun stateOwner(
        resource: GraphNode,
        repository: GraphNode,
        evidence: Map<String, String>,
        by: Principal,
    ): OwnerLink {
        val now = Instant.now(clock)
        graphStore
            .findEdges(resource.key, Direction.INCOMING, LinkResolutionEngine.OWNS_RESOURCE)
            .map { it.edge }
            .filter { it.from != repository.key && it.provenance.current }
            .filter { LinkResolutionEngine.isEngine(it) || ManualLinkRule.isManual(it) }
            .forEach { rival -> graphStore.upsertEdge(rival.copy(provenance = rival.provenance.copy(validTo = now))) }

        graphStore.upsertEdge(
            GraphEdge(
                LinkResolutionEngine.OWNS_RESOURCE,
                repository.key,
                resource.key,
                mapOf(
                    "rule" to LinkDecision.MANUAL,
                    "evidence" to EvidenceHash.encode(evidence).ifEmpty { null },
                    "acceptedBy" to by.subject,
                    "acceptedAt" to now,
                    LinkResolutionEngine.RESOLVED_AT to null,
                ),
                Provenance.manual(now, by),
            ),
        )

        graphStore
            .findEdges(resource.key, Direction.OUTGOING, LinkResolutionEngine.CANDIDATE_LINK)
            .map { it.edge }
            .filter { it.provenance.current && it.props[STATUS] in OPEN }
            .forEach { candidate ->
                val accepted = candidate.to == repository.key
                val status = if (accepted) CandidateStatus.ACCEPTED else CandidateStatus.SUPERSEDED
                graphStore.upsertEdge(
                    candidate.copy(
                        props = candidate.props + (STATUS to status.wireName),
                        provenance = if (accepted) candidate.provenance.copy(validTo = now) else candidate.provenance,
                    ),
                )
            }

        return OwnerLink(
            resource = linkedResource(resource),
            repository = LinkedRepository(repository.key.key, repository.props["name"]?.toString()),
            rule = LinkDecision.MANUAL,
            confidence = Provenance.FULL_CONFIDENCE,
            inferred = false,
            evidence = evidence,
            acceptedBy = by.subject,
            sourceSystem = Provenance.MANUAL,
        )
    }

    private fun openCandidate(id: String): CandidateLink {
        val candidate = queries.candidate(id) ?: throw CandidateNotFoundException(id)
        if (!candidate.status.open) throw CandidateDecidedException(id, candidate.status)
        return candidate
    }

    private fun candidateEdge(candidate: CandidateLink): GraphEdge =
        graphStore.findEdge(
            LinkResolutionEngine.CANDIDATE_LINK,
            NodeKey(RESOURCE, candidate.resource.key),
            NodeKey(GraphLinkContext.REPOSITORY, candidate.repository.key),
        ) ?: throw CandidateNotFoundException(candidate.id)

    private fun node(
        type: String,
        key: String,
    ): GraphNode = graphStore.findNode(NodeKey(type, key)) ?: throw NodeNotFoundException(listOf(NodeKey(type, key)))

    companion object {
        private const val RESOURCE = LinkResolutionEngine.CLOUD_RESOURCE
        private const val STATUS = "status"
        private val OPEN = CandidateStatus.OPEN.map { it.wireName }.toSet()

        fun linkedResource(node: GraphNode) =
            LinkedResource(
                node.key.key,
                node.props["name"]?.toString(),
                node.props["provider"]?.toString(),
                node.props["accountId"]?.toString(),
            )
    }
}
