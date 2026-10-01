package com.repodatagraph.application.policy

import com.repodatagraph.domain.policy.AgentActionDecision
import com.repodatagraph.domain.policy.AuthzAction
import com.repodatagraph.domain.policy.AuthzRequest
import com.repodatagraph.domain.policy.AuthzResource
import com.repodatagraph.domain.policy.PolicyStatus
import com.repodatagraph.domain.policy.ResourceKind
import com.repodatagraph.domain.policy.decideWithOwnership
import com.repodatagraph.domain.port.`in`.PolicyUseCase
import com.repodatagraph.domain.port.`in`.ResourceOwners
import com.repodatagraph.domain.port.out.CurrentSubject
import com.repodatagraph.domain.port.out.PolicyDecisionPoint
import org.springframework.stereotype.Service

/**
 * Puts the caller's questions to the policy (#30 FR8, #95 FR-4).
 *
 * `explain` asks the same question the gate asks, with the same owners, so its answer is the one the
 * caller would get. It only ever explains the caller's own access: asking about someone else would
 * tell the asker what that subject may do.
 *
 * `evaluate` looks each fact up in the graph ([GraphAgentFacts]) and gives the agent-actions policy
 * what the graph says about it, never what the agent claimed.
 */
@Service
class PolicyService(
    private val policy: PolicyDecisionPoint,
    private val subjects: CurrentSubject,
    private val owners: ResourceOwners,
    private val facts: GraphAgentFacts,
) : PolicyUseCase {
    override fun status(): PolicyStatus = policy.status()

    override fun explain(
        action: AuthzAction,
        type: String?,
        key: String?,
    ): PolicyUseCase.Explanation {
        val subject = subjects.current()
        val resource = AuthzResource(ResourceKind.NODE, type = type, key = key, filtered = key == null)
        return PolicyUseCase.Explanation(subject, policy.decideWithOwnership(AuthzRequest(subject, action, resource), owners))
    }

    override fun evaluate(
        action: String,
        factIds: List<String>,
    ): PolicyUseCase.Evaluation {
        val subject = subjects.current()
        val found = factIds.distinct().map(facts::fact)
        val decision: AgentActionDecision = policy.agentAction(action, subject, found)
        return PolicyUseCase.Evaluation(subject, decision, found)
    }
}
