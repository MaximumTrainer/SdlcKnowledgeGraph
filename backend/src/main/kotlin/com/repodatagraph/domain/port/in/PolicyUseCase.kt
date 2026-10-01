package com.repodatagraph.domain.port.`in`

import com.repodatagraph.domain.policy.AgentActionDecision
import com.repodatagraph.domain.policy.AgentFact
import com.repodatagraph.domain.policy.AuthzAction
import com.repodatagraph.domain.policy.Decision
import com.repodatagraph.domain.policy.PolicyStatus
import com.repodatagraph.domain.policy.Subject

/**
 * Questions about the authorisation policy (#30 FR8, #95 FR-4), each about the caller: which policy
 * is in force, what it would decide for them, and whether it lets an agent act on given facts.
 */
interface PolicyUseCase {
    fun status(): PolicyStatus

    /** What the policy decides for the caller doing [action] to a node of [type], or the one keyed [key]. */
    fun explain(
        action: AuthzAction,
        type: String?,
        key: String?,
    ): Explanation

    /** Whether the agent-actions policy lets the caller take [action] on the graph facts [factIds]. */
    fun evaluate(
        action: String,
        factIds: List<String>,
    ): Evaluation

    data class Explanation(
        val subject: Subject,
        val decision: Decision,
    )

    data class Evaluation(
        val subject: Subject,
        val decision: AgentActionDecision,
        /** Each fact as the graph holds it: what the policy was given, not what was claimed. */
        val facts: List<AgentFact>,
    )
}
