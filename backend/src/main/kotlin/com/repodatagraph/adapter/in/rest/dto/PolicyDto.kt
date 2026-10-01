package com.repodatagraph.adapter.`in`.rest.dto

import com.repodatagraph.domain.policy.AgentFact
import com.repodatagraph.domain.policy.PolicyStatus
import com.repodatagraph.domain.policy.Subject
import com.repodatagraph.domain.port.`in`.PolicyUseCase
import java.time.Instant

/** `GET /api/v1/policy` (#30 FR8): which policy is in force, and how it is evaluated. */
data class PolicyStatusResponse(
    val name: String,
    /** The bundle's revision, from its .manifest: what an audit names as the policy in force. */
    val revision: String,
    /** When this instance loaded it. */
    val loadedAt: Instant,
    /** `embedded-wasm`: OPA's compiled policy, evaluated in the API's own process (ADR-0020). */
    val engine: String,
    /** `UP` while the policy can be evaluated. */
    val status: String,
    /** `closed`: a request the policy cannot be asked about is refused, read or write. */
    val failMode: String,
) {
    companion object {
        fun from(status: PolicyStatus) =
            PolicyStatusResponse(status.name, status.revision, status.loadedAt, status.engine, status.status, status.failMode)
    }
}

/** What `POST /api/v1/policy/explain` is asked: an action, and the node type or node it is on. */
data class PolicyExplainRequest(
    val action: String? = null,
    val resource: PolicyResourceRequest? = null,
)

data class PolicyResourceRequest(
    val type: String? = null,
    val key: String? = null,
)

/** The caller as the policy sees them: who, what kind, and what it judges them by. */
data class PolicySubjectResponse(
    val id: String,
    val kind: String,
    val scopes: List<String>,
    /** Null when the token carries no roles claim, and is judged by its scopes alone. */
    val roles: List<String>?,
    val teams: List<String>,
) {
    companion object {
        fun from(subject: Subject) =
            PolicySubjectResponse(subject.id, subject.kind.wireName, subject.scopes.sorted(), subject.roles, subject.teams.sorted())
    }
}

/** The policy's answer for the caller (#30 FR8): allowed or not, by which rule and why. */
data class PolicyExplainResponse(
    val allow: Boolean,
    /** The rule that refused it - scopes, roles, agents, sensitivity, ... - or `allow`. */
    val policy: String,
    val reason: String,
    /** The scopes the action needs, whether or not the token holds them. */
    val required: List<String>,
    /** The properties of the type the caller would see taken out. */
    val redact: List<String>,
    /** The most sensitive level the caller is cleared for. */
    val clearance: String,
    val subject: PolicySubjectResponse,
) {
    companion object {
        fun from(explanation: PolicyUseCase.Explanation) =
            with(explanation.decision) {
                PolicyExplainResponse(allow, policy, reason, required, redact, clearance, PolicySubjectResponse.from(explanation.subject))
            }
    }
}

/** What `POST /api/v1/policy/evaluate` is asked (#95 FR-4): an action and the ids of the facts it rests on. */
data class PolicyEvaluateRequest(
    val action: String? = null,
    val facts: List<String>? = null,
)

/** A fact as the graph holds it, as the policy was given it. */
data class PolicyFactResponse(
    val id: String,
    val found: Boolean,
    val type: String?,
    val confidence: Double?,
    val inferred: Boolean?,
    val ageMinutes: Long?,
) {
    companion object {
        fun from(fact: AgentFact) = PolicyFactResponse(fact.id, fact.found, fact.type, fact.confidence, fact.inferred, fact.ageMinutes)
    }
}

/** The agent-actions policy's answer (#95 FR-4, FR-5): whether the action may be taken, and why. */
data class PolicyEvaluateResponse(
    val allow: Boolean,
    val policy: String,
    val action: String,
    /** Every condition that failed when refused; what satisfied it when allowed. */
    val reasons: List<String>,
    val facts: List<PolicyFactResponse>,
    val subject: PolicySubjectResponse,
) {
    companion object {
        fun from(evaluation: PolicyUseCase.Evaluation) =
            with(evaluation.decision) {
                PolicyEvaluateResponse(
                    allow,
                    policy,
                    action,
                    reasons,
                    evaluation.facts.map(PolicyFactResponse::from),
                    PolicySubjectResponse.from(evaluation.subject),
                )
            }
    }
}
