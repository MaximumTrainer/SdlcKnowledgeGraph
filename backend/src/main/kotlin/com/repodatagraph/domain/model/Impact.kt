package com.repodatagraph.domain.model

import com.repodatagraph.domain.exception.InvalidQueryParameterException

/** Which way a blast radius is walked: along the way a change travels, or back against it. */
enum class ImpactDirection {
    DOWNSTREAM,
    UPSTREAM,
    ;

    companion object {
        fun parse(value: String): ImpactDirection =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw InvalidQueryParameterException("direction", "direction must be downstream or upstream, was '$value'")
    }
}

/**
 * What an impact question asks (#21, FR1): from which node, how many hops, how confident a path must
 * be to count, and which way. Bounded here, so no caller can ask for an unbounded walk.
 */
data class ImpactSpec(
    val nodeId: NodeKey,
    val depth: Int = DEFAULT_DEPTH,
    val minConfidence: Double = DEFAULT_MIN_CONFIDENCE,
    val direction: ImpactDirection = ImpactDirection.DOWNSTREAM,
) {
    init {
        if (depth !in MIN_DEPTH..MAX_DEPTH) {
            throw InvalidQueryParameterException("depth", "depth must be between $MIN_DEPTH and $MAX_DEPTH, was $depth")
        }
        if (minConfidence.isNaN() || minConfidence !in 0.0..1.0) {
            throw InvalidQueryParameterException("minConfidence", "minConfidence must be between 0.0 and 1.0, was $minConfidence")
        }
    }

    companion object {
        const val DEFAULT_DEPTH = 3
        const val MIN_DEPTH = 1
        const val MAX_DEPTH = 5
        const val DEFAULT_MIN_CONFIDENCE = 0.5

        /** At most this many affected nodes are listed; the rest are counted and the answer marked truncated. */
        const val MAX_AFFECTED = 1000

        /** Reads a spec from query parameters as given, each absent one taking its default. */
        fun of(
            nodeId: String?,
            depth: String?,
            minConfidence: String?,
            direction: String?,
        ): ImpactSpec =
            ImpactSpec(
                nodeId = nodeIdParameter("nodeId", nodeId),
                depth =
                    depth?.let {
                        it.trim().toIntOrNull()
                            ?: throw InvalidQueryParameterException("depth", "depth must be a whole number, was '$it'")
                    } ?: DEFAULT_DEPTH,
                minConfidence =
                    minConfidence?.let {
                        it.trim().toDoubleOrNull()
                            ?: throw InvalidQueryParameterException("minConfidence", "minConfidence must be a number, was '$it'")
                    } ?: DEFAULT_MIN_CONFIDENCE,
                direction = direction?.let(ImpactDirection::parse) ?: ImpactDirection.DOWNSTREAM,
            )
    }
}

/**
 * A node id given as a query parameter: `Type:key`, the form every node's `id` has. Anything else is
 * refused naming [field], rather than being looked up and reported missing.
 */
fun nodeIdParameter(
    field: String,
    value: String?,
): NodeKey {
    if (value.isNullOrBlank()) throw InvalidQueryParameterException(field, "$field is required, as Type:key")
    return runCatching { NodeKey.parse(value.trim()) }.getOrNull()
        ?: throw InvalidQueryParameterException(field, "$field must be a node id of the form Type:key, was '$value'")
}

/**
 * One hop of an explanation: the edge as it was walked - its own name along its stored direction, its
 * declared inverse against it - between two node ids, with that edge's provenance confidence.
 */
data class PathStep(
    val edge: String,
    val from: String,
    val to: String,
    val confidence: Double,
    val inferred: Boolean,
)

/** One way the traversal reached [target]. Several may reach the same node; the best one explains it. */
data class CandidatePath(
    val target: GraphNode,
    val steps: List<PathStep>,
)

/** What the store found: every path, and whether it stopped before finding them all. */
data class PathSearch(
    val paths: List<CandidatePath>,
    val truncated: Boolean,
)

/**
 * The edges one walk may take (#21, FR2), built from the registry and never written in a query:
 * [forward] edges are followed as stored, [inverse] ones against it, mapped to the name that reading
 * has.
 */
data class Traversal(
    val forward: Set<String>,
    val inverse: Map<String, String>,
) {
    val edgeTypes: Set<String> get() = forward + inverse.keys

    /** The name a step along [type] has: its own when walked as stored, its inverse otherwise. */
    fun nameOf(
        type: String,
        alongStoredDirection: Boolean,
    ): String = if (alongStoredDirection) type else inverse[type] ?: type
}

/** A node the change reaches, with the path that explains it. */
data class AffectedNode(
    val node: GraphNode,
    val distance: Int,
    val confidence: Double,
    val inferred: Boolean,
    val path: List<PathStep>,
)

/**
 * The blast radius (#21, FR1): the nodes reached, nearest first, and counts by type. `byType` counts
 * every node found at or above the confidence asked for, even past the listing cap, and `excluded`
 * counts those below it.
 */
data class ImpactResult(
    val root: GraphNode,
    val depth: Int,
    val direction: ImpactDirection,
    val minConfidence: Double,
    val truncated: Boolean,
    val affected: List<AffectedNode>,
    val byType: Map<String, Int>,
)
