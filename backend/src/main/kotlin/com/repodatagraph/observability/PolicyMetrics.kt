package com.repodatagraph.observability

import com.repodatagraph.domain.policy.SubjectKind
import io.micrometer.core.instrument.MeterRegistry

/**
 * Counts the authorisation policy's answers to requests (#30 FR6), published as
 * `sdlc_authz_decisions_total{kind, decision}`: the kind of caller (user, service, agent,
 * anonymous) and whether it was allowed or denied. Both labels are closed sets, so the series stay
 * few however many callers there are. A rise in `kind="agent",decision="deny"` is an agent trying
 * what it may not.
 */
class PolicyMetrics(
    private val meters: MeterRegistry,
) {
    fun decided(
        kind: SubjectKind,
        allowed: Boolean,
    ) = meters.counter(DECISIONS, KIND, kind.wireName, DECISION, if (allowed) ALLOW else DENY).increment()

    private companion object {
        const val DECISIONS = "sdlc.authz.decisions"
        const val KIND = "kind"
        const val DECISION = "decision"
        const val ALLOW = "allow"
        const val DENY = "deny"
    }
}
