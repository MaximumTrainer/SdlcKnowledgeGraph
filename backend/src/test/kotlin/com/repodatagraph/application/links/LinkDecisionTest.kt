package com.repodatagraph.application.links

import com.repodatagraph.domain.model.CandidateStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The confidence rules (#28, FR3, FR4) as a pure decision: the strongest proposal at or above the
 * threshold owns the resource, every other one that strong is a conflict, a tie owns nothing, and
 * anything below the threshold is only a candidate.
 */
class LinkDecisionTest {
    private fun p(
        repo: String,
        confidence: Double,
        rule: String = "tag",
    ) = LinkProposal("github.com/acme/$repo", confidence, rule, mapOf("of" to repo))

    @Test
    fun `nothing proposed decides nothing`() {
        val decision = LinkDecision.decide(emptyList(), THRESHOLD)

        assertNull(decision.owner)
        assertEquals(emptyList<LinkDecision.Candidate>(), decision.candidates)
    }

    @Test
    fun `one proposal at or above the threshold owns the resource`() {
        val decision = LinkDecision.decide(listOf(p("payments", 0.5)), THRESHOLD)

        assertEquals(p("payments", 0.5), decision.owner)
        assertEquals(emptyList<LinkDecision.Candidate>(), decision.candidates)
    }

    @Test
    fun `below the threshold a proposal is a pending candidate`() {
        val decision = LinkDecision.decide(listOf(p("billing", 0.4, "naming")), THRESHOLD)

        assertNull(decision.owner)
        assertEquals(listOf(LinkDecision.Candidate(p("billing", 0.4, "naming"), CandidateStatus.PENDING)), decision.candidates)
    }

    @Test
    fun `the strongest wins and every other strong proposal is a conflict, weak ones pending`() {
        val decision = LinkDecision.decide(listOf(p("billing", 0.7, "iac"), p("payments", 0.95), p("ledger", 0.4, "naming")), THRESHOLD)

        assertEquals(p("payments", 0.95), decision.owner)
        assertEquals(
            listOf(
                LinkDecision.Candidate(p("billing", 0.7, "iac"), CandidateStatus.CONFLICT),
                LinkDecision.Candidate(p("ledger", 0.4, "naming"), CandidateStatus.PENDING),
            ),
            decision.candidates,
        )
    }

    @Test
    fun `a tie at the top owns nothing and makes both conflicts`() {
        val decision = LinkDecision.decide(listOf(p("payments", 0.7, "iac"), p("billing", 0.7, "iac")), THRESHOLD)

        assertNull(decision.owner)
        assertEquals(setOf(CandidateStatus.CONFLICT), decision.candidates.map { it.status }.toSet())
        assertEquals(setOf("github.com/acme/payments", "github.com/acme/billing"), decision.candidates.map { it.proposal.repoKey }.toSet())
    }

    @Test
    fun `a manual owner wins a tie, and is never made a candidate`() {
        val decision = LinkDecision.decide(listOf(p("payments", 1.0), p("billing", 1.0, "manual")), THRESHOLD)

        assertEquals(p("billing", 1.0, "manual"), decision.owner)
        assertEquals(listOf(LinkDecision.Candidate(p("payments", 1.0), CandidateStatus.CONFLICT)), decision.candidates)
    }

    @Test
    fun `two manual owners both stand, and neither is a candidate`() {
        val decision =
            LinkDecision.decide(
                listOf(p("payments", 1.0, "manual"), p("billing", 1.0, "manual"), p("ledger", 0.4, "naming")),
                THRESHOLD,
            )

        assertEquals("manual", decision.owner?.rule)
        assertEquals(listOf("github.com/acme/ledger"), decision.candidates.map { it.proposal.repoKey })
    }

    private companion object {
        const val THRESHOLD = 0.5
    }
}
