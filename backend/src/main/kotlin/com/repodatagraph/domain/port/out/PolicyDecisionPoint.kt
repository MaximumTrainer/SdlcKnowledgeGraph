package com.repodatagraph.domain.port.out

import com.repodatagraph.domain.policy.AgentActionDecision
import com.repodatagraph.domain.policy.AgentFact
import com.repodatagraph.domain.policy.AuthzRequest
import com.repodatagraph.domain.policy.Decision
import com.repodatagraph.domain.policy.FilterRequest
import com.repodatagraph.domain.policy.FilterResult
import com.repodatagraph.domain.policy.PolicyStatus
import com.repodatagraph.domain.policy.Subject

/**
 * The one place authorisation is decided (#30 FR1, #95 FR-1, ADR-0020). REST, GraphQL and the agent
 * tools to come (#31) all ask it, so they cannot disagree.
 *
 * Every method throws [com.repodatagraph.domain.exception.PolicyUnavailableException] when the
 * policy cannot be evaluated; callers refuse the request rather than answer it unchecked.
 */
interface PolicyDecisionPoint {
    /** May the subject do the action to the resource, and if so what must be redacted. */
    fun decide(request: AuthzRequest): Decision

    /** Which of a read's nodes the subject may see, and what to remove from each. */
    fun filter(request: FilterRequest): FilterResult

    /** The agent-actions policy (#95 FR-4): may [subject] take [action] on the strength of [facts]. */
    fun agentAction(
        action: String,
        subject: Subject,
        facts: List<AgentFact>,
    ): AgentActionDecision

    /** Which policy is in force. */
    fun status(): PolicyStatus
}
