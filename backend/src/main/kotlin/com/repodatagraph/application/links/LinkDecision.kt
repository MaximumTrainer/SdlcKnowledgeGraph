package com.repodatagraph.application.links

import com.repodatagraph.domain.model.CandidateStatus

/**
 * The confidence rules (#28, FR3, FR4) as a pure decision over one resource's proposals, at most one
 * per repository.
 *
 * A manual proposal - a person's word - owns the resource whatever else is proposed, and is never a
 * candidate. Otherwise the strongest proposal at or above the threshold owns it, unless another is
 * as strong: a tie owns nothing, since choosing would be a guess. Every other proposal at or above
 * the threshold is a conflict for a person to settle, and every one below it a pending candidate.
 */
object LinkDecision {
    const val MANUAL = "manual"

    data class Candidate(
        val proposal: LinkProposal,
        val status: CandidateStatus,
    )

    data class Decision(
        val owner: LinkProposal?,
        val candidates: List<Candidate>,
    )

    fun decide(
        proposals: List<LinkProposal>,
        threshold: Double,
    ): Decision {
        val manual = proposals.filter { it.rule == MANUAL }
        val inferred = proposals.filter { it.rule != MANUAL }
        val strong = inferred.filter { it.confidence >= threshold }.sortedByDescending { it.confidence }
        val tie = strong.size > 1 && strong[0].confidence == strong[1].confidence
        val owner =
            when {
                manual.isNotEmpty() -> manual.first()
                tie || strong.isEmpty() -> null
                else -> strong.first()
            }
        val candidates =
            inferred
                .filter { it != owner }
                .map { Candidate(it, if (it.confidence >= threshold) CandidateStatus.CONFLICT else CandidateStatus.PENDING) }
        return Decision(owner, candidates)
    }
}
