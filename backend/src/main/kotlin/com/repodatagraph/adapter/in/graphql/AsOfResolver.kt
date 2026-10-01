package com.repodatagraph.adapter.`in`.graphql

import com.repodatagraph.adapter.`in`.security.PolicyReads
import com.repodatagraph.adapter.out.policy.WasmPolicyDecisionPoint
import com.repodatagraph.application.freshness.FactFreshness
import com.repodatagraph.config.PolicyProperties
import com.repodatagraph.domain.exception.InvalidQueryParameterException
import com.repodatagraph.domain.model.Direction
import com.repodatagraph.domain.model.asOfParameter
import com.repodatagraph.domain.model.nodeIdParameter
import com.repodatagraph.domain.policy.FilterItem
import com.repodatagraph.domain.port.`in`.EdgeUseCase
import com.repodatagraph.domain.port.`in`.NodeUseCase
import com.repodatagraph.domain.port.out.PolicyDecisionPoint
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller
import java.time.Instant

/**
 * The GraphQL query root's reads of one node and its relationships, now or as of an instant (#93,
 * FR-4): the same answers as `GET /api/v1/nodes/{type}/{key}` and `GET /api/v1/edges`, so an agent
 * on either API can ask what the graph held when a decision was made. A malformed asOf is a
 * BAD_REQUEST naming the argument, as it is a 400 over REST.
 *
 * Both are filtered by the authorisation policy (#30 FR5), as their REST equivalents are: a node of
 * a type above the caller's clearance reads as absent - null, or no relationship to it - and a
 * property above it is left out. These are the only query fields a caller not cleared for everything
 * may ask for ([com.repodatagraph.adapter.in.security.RouteAuthz.FILTERED_GRAPHQL_FIELDS]).
 *
 * Also where `Provenance.stale` is computed (#93, FR-1), for every Provenance the schema returns.
 */
@Controller
class AsOfResolver(
    private val nodeUseCase: NodeUseCase,
    private val edgeUseCase: EdgeUseCase,
    private val freshness: FactFreshness,
    policy: PolicyDecisionPoint = WasmPolicyDecisionPoint.classpathDefault(),
    properties: PolicyProperties = PolicyProperties(),
) {
    private val reads = PolicyReads(policy, properties)

    @QueryMapping
    fun node(
        @Argument id: String,
        @Argument asOf: String?,
    ): Map<String, Any?>? {
        val key = nodeIdParameter("id", id)
        val instant = asOfParameter(asOf)
        val node = nodeUseCase.get(key.type, key.key, instant) ?: return null
        val result = reads.filter(listOf(FilterItem(node.id, node.type)))
        return if (result.visible(node.id)) GraphNodeView.of(node.copy(props = reads.redact(result, node.id, node.props))) else null
    }

    @QueryMapping
    fun edges(
        @Argument nodeId: String,
        @Argument direction: String,
        @Argument edgeType: String?,
        @Argument asOf: String?,
    ): List<Map<String, Any?>> {
        val key = nodeIdParameter("nodeId", nodeId)
        val instant = asOfParameter(asOf)
        val found = edgeUseCase.forNode(key.type, key.key, directionOf(direction), edgeType, instant)
        val result = reads.filter(found.map { FilterItem(it.other.id, it.other.type) } + FilterItem(key.id, key.type))
        if (!result.visible(key.id)) return emptyList()
        return found.filter { result.visible(it.other.id) }.map { edge ->
            mapOf(
                "type" to edge.type,
                "inverse" to edge.inverse,
                "direction" to edge.direction.name,
                "displayName" to edge.displayName,
                "other" to GraphNodeView.of(edge.other.copy(props = reads.redact(result, edge.other.id, edge.other.props))),
                "provenance" to GraphNodeView.provenance(edge.provenance),
            )
        }
    }

    @SchemaMapping(typeName = "Provenance", field = "stale")
    fun stale(provenance: Map<String, Any?>): Boolean =
        freshness.stale(
            sourceSystem = provenance["sourceSystem"].toString(),
            ingestedAt = Instant.parse(provenance["ingestedAt"].toString()),
            validTo = (provenance["validTo"] as? String)?.let(Instant::parse),
        )

    private fun directionOf(value: String): Direction =
        when (value.lowercase()) {
            "in" -> Direction.INCOMING
            "out" -> Direction.OUTGOING
            "both" -> Direction.BOTH
            else -> throw InvalidQueryParameterException("direction", "direction must be one of in, out, both, was '$value'")
        }
}
