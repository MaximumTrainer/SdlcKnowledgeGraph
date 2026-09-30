package com.repodatagraph.adapter.`in`.graphql

import com.repodatagraph.domain.model.ContextPackQuery
import com.repodatagraph.domain.model.PackEdge
import com.repodatagraph.domain.model.PackNode
import com.repodatagraph.domain.model.ProvenanceSummary
import com.repodatagraph.domain.port.`in`.ContextPackUseCase
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

/**
 * `Query.contextPack` (#96): the same pack as `POST /api/v1/context-pack`, read from the same use
 * case, so the two APIs cannot answer one question differently. A bad field is a BAD_REQUEST naming
 * it, and a start node the graph does not hold NOT_FOUND, as over REST.
 */
@Controller
class ContextPackResolver(
    private val useCase: ContextPackUseCase,
) {
    @QueryMapping
    fun contextPack(
        @Argument input: Map<String, Any?>,
    ): Map<String, Any?> {
        val query =
            ContextPackQuery.of(
                startId = input["startId"] as? String,
                template = input["template"] as? String,
                budget = input["budget"] as? Int,
                asOf = input["asOf"] as? String,
            )
        val pack = useCase.contextPack(query)
        return mapOf(
            "template" to pack.template,
            "budget" to pack.budget,
            "asOf" to pack.asOf?.toString(),
            "scoring" to GraphNodeView.scoring(),
            "start" to node(pack.start),
            "reached" to pack.reached,
            "truncated" to pack.truncated,
            "cut" to pack.cut,
            "nodes" to pack.nodes.map(::node),
            "edges" to pack.edges.map(::edge),
        )
    }

    private fun node(node: PackNode): Map<String, Any?> =
        mapOf(
            "id" to node.node.id,
            "type" to node.node.type,
            "key" to node.node.key.key,
            "label" to node.label,
            "distance" to node.distance,
            "confidence" to node.confidence,
            "inferred" to node.inferred,
            "score" to node.score,
            "tier" to node.tier.wire,
            "node" to GraphNodeView.of(node.node),
            "via" to node.via,
            "provenance" to summary(node.provenance),
        )

    private fun edge(edge: PackEdge): Map<String, Any?> =
        mapOf(
            "id" to edge.id,
            "type" to edge.edge.type,
            "inverse" to edge.inverse,
            "from" to edge.edge.from.id,
            "to" to edge.edge.to.id,
            "manifest" to edge.manifest,
            "rule" to edge.rule,
            "commitSha" to edge.commitSha,
            "provenance" to summary(edge.provenance),
        )

    private fun summary(summary: ProvenanceSummary): Map<String, Any?> =
        mapOf(
            "source" to summary.source,
            "observedAt" to summary.observedAt?.toString(),
            "confidence" to summary.confidence,
            "inferred" to summary.inferred,
            "stale" to summary.stale,
        )
}
