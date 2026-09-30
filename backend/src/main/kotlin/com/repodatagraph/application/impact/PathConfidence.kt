package com.repodatagraph.application.impact

import com.repodatagraph.domain.model.AffectedNode
import com.repodatagraph.domain.model.CandidatePath
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.PathStep

/** The affected nodes to list, the counts by type, and whether the listing was cut short. */
data class Ranking(
    val affected: List<AffectedNode>,
    val byType: Map<String, Int>,
    val truncated: Boolean,
)

/**
 * How much a path is worth, and which path explains a node (#21, FR3).
 *
 * A path is as trustworthy as all of its edges together, so its confidence is the product of theirs.
 * Of several paths to one node the most confident explains it, the shorter one breaking a tie, and the
 * node is inferred only when that path holds an inferred edge: a node reached for certain is not made
 * doubtful by a guess that also reaches it.
 */
object PathConfidence {
    const val EXCLUDED = "excluded"

    fun of(steps: List<PathStep>): Double = steps.fold(1.0) { confidence, step -> confidence * step.confidence }

    /**
     * The nodes [candidates] reach from [root], each by its best path; those below [minConfidence]
     * counted as excluded, and at most [cap] listed, nearest first, then most confident, then by id.
     */
    fun rank(
        root: NodeKey,
        candidates: List<CandidatePath>,
        minConfidence: Double,
        cap: Int,
    ): Ranking {
        val best =
            candidates
                .filter { it.target.key != root }
                .groupBy { it.target.id }
                .values
                .map { paths -> paths.minWith(BEST_FIRST) }
                .map { path ->
                    AffectedNode(
                        node = path.target,
                        distance = path.steps.size,
                        confidence = of(path.steps),
                        inferred = path.steps.any { it.inferred },
                        path = path.steps,
                    )
                }
        val (kept, excluded) = best.partition { it.confidence >= minConfidence }
        val ordered = kept.sortedWith(compareBy<AffectedNode> { it.distance }.thenByDescending { it.confidence }.thenBy { it.node.id })
        val byType =
            kept
                .groupingBy { it.node.type }
                .eachCount()
                .toSortedMap() + (EXCLUDED to excluded.size)

        return Ranking(affected = ordered.take(cap), byType = byType, truncated = ordered.size > cap)
    }

    /** Most confident, then shortest, then stated before inferred, then a stable order. */
    private val BEST_FIRST: Comparator<CandidatePath> =
        compareByDescending<CandidatePath> { of(it.steps) }
            .thenBy { it.steps.size }
            .thenBy { path -> path.steps.any { it.inferred } }
            .thenBy { path -> path.steps.joinToString("|") { "${it.edge}>${it.to}" } }
}
