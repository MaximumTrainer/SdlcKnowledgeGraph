package com.repodatagraph.domain.model

import com.repodatagraph.domain.exception.InvalidQueryParameterException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/**
 * What a context pack asks (#96, FR-1): the node to start from, the registry template whose walk
 * to take, how many nodes the pack may hold, and optionally the instant to read the graph as of.
 * Bounded here, so no caller can ask for an unbounded pack, and a bad field is refused naming it
 * before anything is walked.
 */
data class ContextPackQuery(
    val startId: NodeKey,
    val template: String,
    val budget: Int,
    val asOf: Instant? = null,
) {
    init {
        if (template.isBlank()) throw InvalidQueryParameterException(TEMPLATE, "template is required")
        if (budget !in MIN_BUDGET..MAX_BUDGET) {
            throw InvalidQueryParameterException(BUDGET, "budget must be between $MIN_BUDGET and $MAX_BUDGET, was $budget")
        }
    }

    companion object {
        const val MIN_BUDGET = 1
        const val MAX_BUDGET = 500
        private const val START_ID = "startId"
        private const val TEMPLATE = "template"
        private const val BUDGET = "budget"
        private const val AS_OF = "asOf"

        /** Reads a query from a request body as given. Every field but [asOf] is required: an agent sizes its own pack. */
        fun of(
            startId: String?,
            template: String?,
            budget: Int?,
            asOf: String?,
        ): ContextPackQuery {
            val key = startKey(startId)
            val name = template?.trim().orEmpty()
            if (name.isEmpty()) throw InvalidQueryParameterException(TEMPLATE, "template is required")
            budget ?: throw InvalidQueryParameterException(
                BUDGET,
                "budget is required: the most nodes the pack may hold, between $MIN_BUDGET and $MAX_BUDGET",
            )
            return ContextPackQuery(key, name, budget, asOf?.let(::instant))
        }

        private fun startKey(startId: String?): NodeKey {
            val id = startId?.trim().orEmpty()
            if (id.isEmpty()) throw InvalidQueryParameterException(START_ID, "startId is required, as Type:key")
            return runCatching { NodeKey.parse(id) }.getOrNull()
                ?: throw InvalidQueryParameterException(START_ID, "startId must be a node id of the form Type:key, was '$id'")
        }

        private fun instant(value: String): Instant =
            try {
                Instant.parse(value.trim())
            } catch (_: DateTimeParseException) {
                throw InvalidQueryParameterException(AS_OF, "asOf must be an ISO-8601 instant such as 2026-09-10T00:00:00Z, was '$value'")
            }
    }
}

/**
 * A fact's provenance in the one line an agent reads (#96, FR-4): where it came from, when that
 * source last saw it, how sure it is and whether it was guessed, and whether it is past its source's
 * freshness window now. The full envelope stays behind GET /api/v1/nodes.
 */
data class ProvenanceSummary(
    val source: String,
    val observedAt: Instant?,
    val confidence: Double,
    val inferred: Boolean,
    val stale: Boolean,
)

/**
 * A node of a pack: how far from the start its nearest path is and that path ([via]), how sure the
 * path is, and #87's impact score for it in the environment it runs in ([tier]).
 */
data class PackNode(
    val node: GraphNode,
    val label: String,
    val distance: Int,
    val confidence: Double,
    val inferred: Boolean,
    val score: Double,
    val tier: EnvironmentTier,
    val via: List<PathStep>,
    val provenance: ProvenanceSummary,
)

/**
 * An edge of a pack, between two nodes it holds, with the evidence it rests on (#96, FR-4): the
 * manifest a dependency was read from, the rule that proposed an inferred link, the commit an
 * artifact was built at - each where the edge has one.
 */
data class PackEdge(
    val edge: GraphEdge,
    val inverse: String,
    val provenance: ProvenanceSummary,
) {
    /** Stable across packs and requests, spelled as the graph view's edge ids are (#9), so #79 can cite it. */
    val id: String get() = "${edge.type}:${edge.from.id}>${edge.to.id}"

    val manifest: String? get() = edge.props["manifest"]?.toString()

    val rule: String? get() = edge.props["rule"]?.toString()

    val commitSha: String? get() = edge.props["commitSha"]?.toString()
}

/**
 * The bounded subgraph a task needs (#96): the [start], then at most [budget] of the [reached] nodes
 * its template walked to, nearest and most confident first, and the edges between them. [truncated]
 * says the pack is not the whole walk, the budget cutting [cut] nodes or the walk itself stopping
 * short; [asOf], when given, is the instant the graph was read as of.
 */
data class ContextPack(
    val template: String,
    val start: PackNode,
    val budget: Int,
    val asOf: Instant?,
    val reached: Int,
    val truncated: Boolean,
    val cut: Int,
    val nodes: List<PackNode>,
    val edges: List<PackEdge>,
)

/**
 * Which deployments are current (#96, FR-2): of those given, the latest that succeeded in each
 * environment, by `deployedAt`, the id breaking a tie. A failed or unfinished deployment replaced
 * nothing; one after [select]'s instant had not happened yet; one with no `deployedAt` cannot be
 * placed in time and is never current.
 */
object CurrentDeployments {
    private const val SUCCESS = "SUCCESS"

    fun select(
        deployments: List<GraphNode>,
        asOf: Instant?,
    ): Set<String> =
        deployments
            .asSequence()
            .filter { it.props["status"]?.toString() == SUCCESS }
            .mapNotNull { node -> deployedAt(node.props["deployedAt"])?.let { node to it } }
            .filter { (_, at) -> asOf == null || !at.isAfter(asOf) }
            .groupBy { (node, _) -> (node.props["environmentKey"] ?: node.props["environmentId"])?.toString() }
            .values
            .map { dated -> dated.maxWith(compareBy<Pair<GraphNode, Instant>> { it.second }.thenBy { it.first.id }).first.id }
            .toSet()

    private fun deployedAt(value: Any?): Instant? =
        when (value) {
            is Instant -> value
            is OffsetDateTime -> value.toInstant()
            is ZonedDateTime -> value.toInstant()
            is String -> runCatching { Instant.parse(value) }.getOrNull()
            else -> null
        }
}
