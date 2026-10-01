package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.application.links.EvidenceHash
import com.repodatagraph.domain.model.CandidateLink
import com.repodatagraph.domain.model.CandidatePage
import com.repodatagraph.domain.model.CandidateQuery
import com.repodatagraph.domain.model.CandidateStatus
import com.repodatagraph.domain.model.DeploymentEvidence
import com.repodatagraph.domain.model.LinkedRepository
import com.repodatagraph.domain.model.LinkedResource
import com.repodatagraph.domain.model.TouchedKeys
import com.repodatagraph.domain.port.out.LinkQueries
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Repository

/**
 * The link engine's reads in Neo4j (#28). Labels and relationship types are constants and every value
 * a request carries is a parameter; an absent filter is a null the WHERE clause lets through. Only
 * current facts are read: a closed candidate is history, not something to review.
 */
@Repository
class Neo4jLinkQueries(
    private val neo4jClient: Neo4jClient,
) : LinkQueries {
    override fun candidates(query: CandidateQuery): CandidatePage {
        val parameters =
            mapOf(
                "statuses" to query.statuses.map { it.wireName },
                "provider" to query.provider,
                "minConfidence" to query.minConfidence,
                "search" to
                    query.search
                        ?.trim()
                        ?.lowercase()
                        ?.ifEmpty { null },
            )
        val total =
            neo4jClient
                .query("$MATCH_CANDIDATES $FILTER RETURN count(c) AS total")
                .bindAll(parameters)
                .fetchAs(Long::class.javaObjectType)
                .one()
                .orElse(0L)
        // Ties are broken by id, so a page boundary is the same boundary every time it is asked for.
        val items =
            neo4jClient
                .query(
                    """
                    $MATCH_CANDIDATES $FILTER
                    RETURN $PROJECTION
                    ORDER BY c.prov_confidence DESC, c.createdAt DESC, c.candidateId
                    SKIP ${'$'}skip LIMIT ${'$'}limit
                    """.trimIndent(),
                ).bindAll(parameters + mapOf("skip" to query.page.toLong() * query.size, "limit" to query.size.toLong()))
                .fetch()
                .all()
                .map(::toCandidate)
        return CandidatePage(items, total, query.page, query.size)
    }

    override fun candidate(id: String): CandidateLink? =
        neo4jClient
            .query("$MATCH_CANDIDATES WHERE c.candidateId = ${'$'}id AND c.prov_validTo IS NULL RETURN $PROJECTION LIMIT 1")
            .bindAll(mapOf("id" to id))
            .fetch()
            .one()
            .map(::toCandidate)
            .orElse(null)

    override fun deploymentsTargeting(resourceKey: String): List<DeploymentEvidence> =
        neo4jClient
            .query(
                """
                MATCH (d:Deployment)
                WHERE ${'$'}key IN coalesce(d.targetResourceKeys, []) AND d.prov_validTo IS NULL
                MATCH (a:Artifact)-[deployed:DEPLOYED_TO]->(d)
                WHERE deployed.prov_validTo IS NULL
                MATCH (a)-[built:BUILT_FROM]->(repo:Repository)
                WHERE built.prov_validTo IS NULL AND repo.prov_validTo IS NULL
                OPTIONAL MATCH (d)-[:TO_ENVIRONMENT]->(e:Environment)
                RETURN DISTINCT d.key AS deployment, a.key AS artifact, repo.key AS repository,
                       e.key AS environment, d.deployedAt AS deployedAt
                ORDER BY deployedAt DESC, deployment
                """.trimIndent(),
            ).bindAll(mapOf("key" to resourceKey))
            .fetch()
            .all()
            .map { row ->
                DeploymentEvidence(
                    deploymentKey = row["deployment"].toString(),
                    artifactKey = row["artifact"].toString(),
                    repoKey = row["repository"].toString(),
                    environment = row["environment"]?.toString(),
                    deployedAt = ProvenanceMapper.instant(row["deployedAt"]),
                )
            }

    override fun touchedBy(runId: String): TouchedKeys {
        val rows =
            neo4jClient
                .query(
                    """
                    MATCH (run:SyncRun { key: ${'$'}runId })-[:PRODUCED]->(n)
                    RETURN labels(n) AS labels, n.key AS key, n.repoKey AS repoKey, n.targetResourceKeys AS targets
                    """.trimIndent(),
                ).bindAll(mapOf("runId" to runId))
                .fetch()
                .all()
        val resources = mutableSetOf<String>()
        val repositories = mutableSetOf<String>()
        rows.forEach { row ->
            val labels = (row["labels"] as? Collection<*>).orEmpty().map { it.toString() }
            val key = row["key"]?.toString() ?: return@forEach
            when {
                "Repository" in labels -> repositories += key
                "CloudResource" in labels -> resources += key
                "IacFile" in labels -> row["repoKey"]?.toString()?.let { repositories += it }
                "Deployment" in labels -> resources += (row["targets"] as? Collection<*>).orEmpty().map { it.toString() }
            }
        }
        return TouchedKeys(resources, repositories)
    }

    @Suppress("UNCHECKED_CAST")
    private fun toCandidate(row: Map<String, Any?>): CandidateLink {
        val resource = row["resource"] as Map<String, Any?>
        val repository = row["repository"] as Map<String, Any?>
        val candidate = row["candidate"] as Map<String, Any?>
        return CandidateLink(
            id = candidate["candidateId"].toString(),
            resource =
                LinkedResource(
                    key = resource["key"].toString(),
                    name = resource["name"]?.toString(),
                    provider = resource["provider"]?.toString(),
                    accountId = resource["accountId"]?.toString(),
                ),
            repository = LinkedRepository(repository["key"].toString(), repository["name"]?.toString()),
            confidence = ProvenanceMapper.fromProperties(candidate).confidence,
            rule = candidate["rule"].toString(),
            evidence = EvidenceHash.decodeStored(candidate["evidence"]),
            status = CandidateStatus.fromWireName(candidate["status"].toString()),
            createdAt = ProvenanceMapper.instant(candidate["createdAt"]),
            rejectedBy = candidate["rejectedBy"]?.toString(),
            rejectedAt = ProvenanceMapper.instant(candidate["rejectedAt"]),
        )
    }

    private companion object {
        const val MATCH_CANDIDATES = "MATCH (r:CloudResource)-[c:CANDIDATE_LINK]->(repo:Repository)"

        const val FILTER = """
            WHERE c.prov_validTo IS NULL AND c.status IN ${'$'}statuses
              AND (${'$'}provider IS NULL OR r.provider = ${'$'}provider)
              AND (${'$'}minConfidence IS NULL OR c.prov_confidence >= ${'$'}minConfidence)
              AND (${'$'}search IS NULL
                   OR toLower(r.key) CONTAINS ${'$'}search
                   OR toLower(coalesce(r.name, '')) CONTAINS ${'$'}search
                   OR toLower(repo.key) CONTAINS ${'$'}search)
        """

        const val PROJECTION =
            "r { .key, .name, .provider, .accountId } AS resource, repo { .key, .name } AS repository, " +
                "c { .* } AS candidate"
    }
}
