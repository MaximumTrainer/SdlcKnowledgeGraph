package com.repodatagraph.adapter.`in`.rest.dto

import com.repodatagraph.domain.model.ChangeImpactResult
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.ImpactHit
import com.repodatagraph.domain.model.ImpactScoring
import com.repodatagraph.domain.model.Provenance
import io.swagger.v3.oas.annotations.media.Schema
import java.util.SortedMap

/** `POST /api/v1/impact` (#87). Every field optional but `repositoryKey`; absent ones take their defaults. */
data class ChangeImpactRequest(
    @field:Schema(
        description = "Key of the Repository that is about to change, e.g. github.com/acme/payments",
        example = "github.com/acme/payments",
    )
    val repositoryKey: String? = null,
    @field:Schema(description = "Paths in the repository the change touches; read against its manifest and IaC index where it has one")
    val paths: List<String?>? = null,
    @field:Schema(description = "The commit of the change. Answered with changeScope unknown until Change nodes exist (#85)")
    val sha: String? = null,
    @field:Schema(description = "Hops to walk, 1 to 4", defaultValue = "2", minimum = "1", maximum = "4")
    val depth: Int? = null,
    @field:Schema(description = "Hits to return, 1 to 500", defaultValue = "50", minimum = "1", maximum = "500")
    val limit: Int? = null,
)

/** Which formula produced the scores, so a consumer can notice it changed (#87, FR4). */
data class ScoringResponse(
    val version: String,
    val formula: String,
    val tierWeights: SortedMap<String, Double>,
    val pathMatchBoost: Double,
) {
    companion object {
        val CURRENT =
            ScoringResponse(
                version = ImpactScoring.VERSION,
                formula = ImpactScoring.FORMULA,
                tierWeights = ImpactScoring.TIER_WEIGHTS.mapKeys { it.key.wire }.toSortedMap(),
                pathMatchBoost = ImpactScoring.PATH_MATCH_BOOST,
            )
    }
}

/** An environment a hit runs in, with the tier it was weighted by. */
data class EnvironmentRef(
    val id: String,
    val key: String,
    val tier: String,
)

/** Where the node came from: the provenance a consumer cites beside the path. */
data class CitationProvenance(
    val sourceSystem: String,
    val sourceId: String?,
    val syncRunId: String?,
    val confidence: Double,
    val inferred: Boolean,
    val observedAt: String?,
    val validFrom: String,
) {
    companion object {
        fun from(provenance: Provenance) =
            CitationProvenance(
                sourceSystem = provenance.sourceSystem,
                sourceId = provenance.sourceId,
                syncRunId = provenance.syncRunId,
                confidence = provenance.confidence,
                inferred = provenance.inferred,
                observedAt = provenance.observedAt?.toString(),
                validFrom = provenance.validFrom.toString(),
            )
    }
}

data class CitationResponse(
    val nodeKey: String,
    val edgePath: List<PathStepResponse>,
    val provenance: CitationProvenance,
)

/** A node and its properties, the properties in key order so the same node is always the same bytes. */
data class HitNodeView(
    val id: String,
    val type: String,
    val key: String,
    val props: SortedMap<String, Any?>,
) {
    companion object {
        fun from(node: GraphNode) = HitNodeView(node.id, node.type, node.key.key, node.props.toSortedMap())
    }
}

data class ImpactHitResponse(
    val node: HitNodeView,
    val hops: Int,
    val score: Double,
    val confidence: Double,
    val inferred: Boolean,
    val tier: String,
    val environment: EnvironmentRef?,
    val pathMatched: Boolean,
    val owners: List<OwnerResponse>,
    val citation: CitationResponse,
) {
    companion object {
        fun from(hit: ImpactHit) =
            ImpactHitResponse(
                node = HitNodeView.from(hit.node),
                hops = hit.hops,
                score = hit.score,
                confidence = hit.confidence,
                inferred = hit.inferred,
                tier = hit.tier.wire,
                environment = hit.environment?.let { EnvironmentRef(it.id, it.key.key, hit.tier.wire) },
                pathMatched = hit.pathMatched,
                owners = hit.owners.map(OwnerResponse::from),
                citation =
                    CitationResponse(
                        nodeKey = hit.citation.nodeKey,
                        edgePath = hit.citation.edgePath.map(PathStepResponse::from),
                        provenance = CitationProvenance.from(hit.citation.provenance),
                    ),
            )
    }
}

/**
 * The ranked answer (#87): at most `limit` hits in a total order, `truncated` when there were more
 * (never how many, FR6), and what the path filter and the sha could and could not do.
 */
data class ChangeImpactResponse(
    val repository: NodeRef,
    val depth: Int,
    val limit: Int,
    val scoring: ScoringResponse,
    val pathFilter: String,
    val matchedPaths: List<String>,
    val changeScope: String,
    val truncated: Boolean,
    val hits: List<ImpactHitResponse>,
) {
    companion object {
        fun from(result: ChangeImpactResult) =
            ChangeImpactResponse(
                repository = NodeRef.from(result.repository),
                depth = result.depth,
                limit = result.limit,
                scoring = ScoringResponse.CURRENT,
                pathFilter = result.pathFilter.wire,
                matchedPaths = result.matchedPaths,
                changeScope = result.changeScope.wire,
                truncated = result.truncated,
                hits = result.hits.map(ImpactHitResponse::from),
            )
    }
}
