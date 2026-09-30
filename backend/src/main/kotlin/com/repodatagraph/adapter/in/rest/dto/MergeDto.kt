package com.repodatagraph.adapter.`in`.rest.dto

import com.repodatagraph.application.freshness.FactFreshness
import com.repodatagraph.domain.identity.EdgeMoves
import com.repodatagraph.domain.identity.MergeOutcome
import io.swagger.v3.oas.annotations.media.Schema

/** A merge asked for (#98): the node to merge into, and whether only to preview it. */
data class MergeRequest(
    @field:Schema(
        description = "The node to merge into: a key of the same type, or a full Type:key id",
        example = "github.com/acme/payments-service",
    )
    val into: String? = null,
    @field:Schema(description = "Preview the merge: everything is done and then rolled back, so nothing changes")
    val dryRun: Boolean = false,
)

/** What a merge did, or with dryRun would do, as ids and the node that stays (#98). */
data class MergeResponse(
    val dryRun: Boolean,
    @field:Schema(description = "The id of the node merged, which is retired as merged and whose key now resolves to the node merged into")
    val from: String,
    @field:Schema(description = "The id of the node merged into, which stays")
    val into: String,
    val node: NodeResponse,
    @field:Schema(description = "Properties the node merged into took from the merged node, which it lacked")
    val gained: List<String>,
    @field:Schema(description = "Properties both held with different values, where the value of the node merged into stays")
    val kept: List<String>,
    val edges: EdgeMovesResponse,
    @field:Schema(description = "Every key the node merged into has been known by, now including the merged node's")
    val previousKeys: List<String>,
    @field:Schema(description = "Nodes merged earlier into the merged node, now pointing at the node merged into")
    val redirected: Int,
) {
    companion object {
        fun from(
            outcome: MergeOutcome,
            freshness: FactFreshness,
        ) = MergeResponse(
            dryRun = outcome.dryRun,
            from = outcome.from.id,
            into = outcome.into.id,
            node = NodeResponse.from(outcome.node, freshness),
            gained = outcome.gained,
            kept = outcome.kept,
            edges = EdgeMovesResponse.from(outcome.edges),
            previousKeys = outcome.previousKeys,
            redirected = outcome.redirected,
        )
    }
}

/** The merged node's relationships: moved, collapsed into one the node merged into had, or dropped as a loop. */
data class EdgeMovesResponse(
    val moved: Int,
    val collapsed: Int,
    val dropped: Int,
) {
    companion object {
        fun from(moves: EdgeMoves) = EdgeMovesResponse(moves.moved, moves.collapsed, moves.dropped)
    }
}
