package com.repodatagraph.application.links

import com.repodatagraph.domain.model.CandidateLink
import com.repodatagraph.domain.model.CandidatePage
import com.repodatagraph.domain.model.CandidateQuery
import com.repodatagraph.domain.model.CandidateStatus
import com.repodatagraph.domain.model.DeploymentEvidence
import com.repodatagraph.domain.model.LinkedRepository
import com.repodatagraph.domain.model.LinkedResource
import com.repodatagraph.domain.model.TouchedKeys
import com.repodatagraph.domain.port.out.LinkQueries
import java.time.Instant

/** [LinkQueries] over an [InMemoryGraphStore]: what the Neo4j adapter answers, by scanning. */
class FakeLinkQueries(
    private val store: InMemoryGraphStore,
) : LinkQueries {
    val touched = mutableMapOf<String, TouchedKeys>()

    override fun candidates(query: CandidateQuery): CandidatePage {
        val all = all().filter { it.status in query.statuses }
        return CandidatePage(all.drop(query.page * query.size).take(query.size), all.size.toLong())
    }

    override fun candidate(id: String): CandidateLink? = all().firstOrNull { it.id == id }

    override fun deploymentsTargeting(resourceKey: String): List<DeploymentEvidence> = emptyList()

    override fun touchedBy(runId: String): TouchedKeys = touched[runId] ?: TouchedKeys()

    private fun all(): List<CandidateLink> =
        store
            .allEdges("CANDIDATE_LINK")
            .filter { it.provenance.current }
            .map { edge ->
                val resource = store.nodes.getValue(edge.from)
                val repository = store.nodes.getValue(edge.to)
                CandidateLink(
                    id = edge.props["candidateId"].toString(),
                    resource =
                        LinkedResource(
                            resource.key.key,
                            resource.props["name"].toString(),
                            resource.props["provider"].toString(),
                            resource.props["accountId"]?.toString(),
                        ),
                    repository = LinkedRepository(repository.key.key, repository.props["name"].toString()),
                    confidence = edge.provenance.confidence,
                    rule = edge.props["rule"].toString(),
                    evidence = EvidenceHash.decode((edge.props["evidence"] as? List<*>).orEmpty().map { it.toString() }),
                    status = CandidateStatus.fromWireName(edge.props["status"].toString()),
                    createdAt = edge.props["createdAt"] as? Instant,
                    rejectedBy = edge.props["rejectedBy"]?.toString(),
                    rejectedAt = edge.props["rejectedAt"] as? Instant,
                )
            }
}
