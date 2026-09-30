package com.repodatagraph.application.impact

import com.repodatagraph.domain.model.CandidatePath
import com.repodatagraph.domain.model.EnvironmentTier
import com.repodatagraph.domain.model.ImpactHit
import com.repodatagraph.domain.model.ImpactScoring
import com.repodatagraph.domain.model.NodeKey

/**
 * Scores and orders the hits of a change-impact answer, scoring version [ImpactScoring.VERSION]
 * (#87, FR4 and FR5).
 *
 * A hit's distance is its nearest path, not #21's most confident one: how far a change has to travel
 * is the length of the shortest way there. The order is total - score descending, then hops
 * ascending, then node id ascending - so two identical questions get the same list whatever order the
 * store returned the paths in, and a context pack built from it is the same pack.
 */
object ImpactScorer {
    fun score(
        hops: Int,
        tier: EnvironmentTier,
        pathMatched: Boolean,
    ): Double {
        val weight = ImpactScoring.TIER_WEIGHTS.getValue(tier)
        val boost = if (pathMatched) ImpactScoring.PATH_MATCH_BOOST else 1.0
        return (1.0 / (1 + hops) * weight * boost).coerceAtMost(1.0)
    }

    val ORDER: Comparator<ImpactHit> =
        compareByDescending<ImpactHit> { it.score }
            .thenBy { it.hops }
            .thenBy { it.node.id }

    fun rank(hits: Collection<ImpactHit>): List<ImpactHit> = hits.sortedWith(ORDER)

    /**
     * One path per node [candidates] reach from [root], the root itself left out: the shortest, then
     * the most confident, then stated before inferred, then a stable spelling of the path.
     */
    fun nearest(
        root: NodeKey,
        candidates: List<CandidatePath>,
    ): List<CandidatePath> =
        candidates
            .filter { it.target.key != root }
            .groupBy { it.target.id }
            .toSortedMap()
            .values
            .map { paths -> paths.minWith(NEAREST_FIRST) }

    private val NEAREST_FIRST: Comparator<CandidatePath> =
        compareBy<CandidatePath> { it.steps.size }
            .thenByDescending { PathConfidence.of(it.steps) }
            .thenBy { path -> path.steps.any { it.inferred } }
            .thenBy { path -> path.steps.joinToString("|") { "${it.edge}>${it.to}" } }
}
