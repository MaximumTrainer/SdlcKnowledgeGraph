package com.repodatagraph.adapter.`in`.rest.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.repodatagraph.domain.model.ContextPack
import com.repodatagraph.domain.model.PackEdge
import com.repodatagraph.domain.model.PackNode
import com.repodatagraph.domain.model.ProvenanceSummary
import io.swagger.v3.oas.annotations.media.Schema
import java.util.SortedMap

/**
 * `POST /api/v1/context-pack` (#96). Every field but `asOf` is required; each is read as given and
 * refused naming it when missing or out of bounds.
 */
data class ContextPackRequest(
    @field:Schema(description = "The node to start from, as Type:key", example = "Repository:github.com/acme/payments")
    val startId: String? = null,
    @field:Schema(
        description = "The traversal template to walk, one of those GET /api/v1/ontology lists under templates",
        example = "change-impact",
    )
    val template: String? = null,
    @field:Schema(description = "The most nodes the pack may hold, the start left out: 1 to 500", minimum = "1", maximum = "500")
    val budget: Int? = null,
    @field:Schema(
        description = "Read the graph as it was at this instant (ISO-8601), rather than now",
        example = "2026-09-10T00:00:00Z",
    )
    val asOf: String? = null,
)

/** A fact's provenance in one line (#96, FR-4); `stale` is judged now, against its source's freshness window. */
data class ProvenanceSummaryResponse(
    val source: String,
    val observedAt: String?,
    val confidence: Double,
    val inferred: Boolean,
    val stale: Boolean,
) {
    companion object {
        fun from(summary: ProvenanceSummary) =
            ProvenanceSummaryResponse(
                source = summary.source,
                observedAt = summary.observedAt?.toString(),
                confidence = summary.confidence,
                inferred = summary.inferred,
                stale = summary.stale,
            )
    }
}

/** A node of a pack, its properties in key order so the same pack is always the same bytes. */
data class ContextPackNodeResponse(
    val id: String,
    val type: String,
    val key: String,
    val label: String,
    val distance: Int,
    val confidence: Double,
    val inferred: Boolean,
    val score: Double,
    val tier: String,
    val props: SortedMap<String, Any?>,
    val via: List<PathStepResponse>,
    val provenance: ProvenanceSummaryResponse,
) {
    companion object {
        fun from(node: PackNode) =
            ContextPackNodeResponse(
                id = node.node.id,
                type = node.node.type,
                key = node.node.key.key,
                label = node.label,
                distance = node.distance,
                confidence = node.confidence,
                inferred = node.inferred,
                score = node.score,
                tier = node.tier.wire,
                props = node.node.props.toSortedMap(),
                via = node.via.map(PathStepResponse::from),
                provenance = ProvenanceSummaryResponse.from(node.provenance),
            )
    }
}

/** An edge of a pack, with the evidence it rests on where it has any: absent, not null, where it has none. */
data class ContextPackEdgeResponse(
    val id: String,
    val type: String,
    val inverse: String,
    val from: String,
    val to: String,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val manifest: String?,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val rule: String?,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val commitSha: String?,
    val props: SortedMap<String, Any?>,
    val provenance: ProvenanceSummaryResponse,
) {
    companion object {
        fun from(edge: PackEdge) =
            ContextPackEdgeResponse(
                id = edge.id,
                type = edge.edge.type,
                inverse = edge.inverse,
                from = edge.edge.from.id,
                to = edge.edge.to.id,
                manifest = edge.manifest,
                rule = edge.rule,
                commitSha = edge.commitSha,
                props = edge.edge.props.toSortedMap(),
                provenance = ProvenanceSummaryResponse.from(edge.provenance),
            )
    }
}

/**
 * The bounded subgraph a task needs (#96): the start, then at most `budget` nodes nearest and most
 * confident first, the edges between them, and how many of the `reached` nodes the budget `cut`.
 */
data class ContextPackResponse(
    val template: String,
    val budget: Int,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val asOf: String?,
    val scoring: ScoringResponse,
    val start: ContextPackNodeResponse,
    val reached: Int,
    val truncated: Boolean,
    val cut: Int,
    val nodes: List<ContextPackNodeResponse>,
    val edges: List<ContextPackEdgeResponse>,
) {
    companion object {
        fun from(pack: ContextPack) =
            ContextPackResponse(
                template = pack.template,
                budget = pack.budget,
                asOf = pack.asOf?.toString(),
                scoring = ScoringResponse.CURRENT,
                start = ContextPackNodeResponse.from(pack.start),
                reached = pack.reached,
                truncated = pack.truncated,
                cut = pack.cut,
                nodes = pack.nodes.map(ContextPackNodeResponse::from),
                edges = pack.edges.map(ContextPackEdgeResponse::from),
            )
    }
}
