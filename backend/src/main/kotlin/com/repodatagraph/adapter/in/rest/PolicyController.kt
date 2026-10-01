package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.adapter.`in`.rest.dto.PolicyEvaluateRequest
import com.repodatagraph.adapter.`in`.rest.dto.PolicyEvaluateResponse
import com.repodatagraph.adapter.`in`.rest.dto.PolicyExplainRequest
import com.repodatagraph.adapter.`in`.rest.dto.PolicyExplainResponse
import com.repodatagraph.adapter.`in`.rest.dto.PolicyStatusResponse
import com.repodatagraph.domain.exception.InvalidQueryParameterException
import com.repodatagraph.domain.policy.AuthzAction
import com.repodatagraph.domain.port.`in`.PolicyUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The authorisation policy, as a caller may ask about it (#30 FR8, #95 FR-4): which policy is in
 * force, what it decides for the caller, and whether an agent may act on the facts it cites. Every
 * answer is about the caller; none tells one subject what another may do.
 *
 * The two questions are reads sent as a POST (ReadsOverPost): they change nothing, and need only
 * `graph:read`.
 */
@RestController
@RequestMapping(PolicyController.PATH)
@Tag(name = "Policy", description = "The authorisation policy in force, and what it decides for the caller")
class PolicyController(
    private val useCase: PolicyUseCase,
) {
    @GetMapping
    @Operation(operationId = "getPolicy", summary = "The authorisation policy in force: its revision, engine and fail mode")
    fun status(): PolicyStatusResponse = PolicyStatusResponse.from(useCase.status())

    @PostMapping("/explain")
    @Operation(
        operationId = "explainPolicy",
        summary = "What the policy decides for the caller doing an action to a node type or node, by which rule and why",
    )
    fun explain(
        @RequestBody request: PolicyExplainRequest,
    ): PolicyExplainResponse {
        val action =
            request.action?.let(AuthzAction::fromWireName)
                ?: throw InvalidQueryParameterException("action", "action must be one of ${AuthzAction.entries.map { it.wireName }}")
        val type = request.resource?.type?.takeIf { it.isNotBlank() }
        val key = request.resource?.key?.takeIf { it.isNotBlank() }
        if (key != null && type == null) throw InvalidQueryParameterException("resource.type", "resource.type is required with a key")
        return PolicyExplainResponse.from(useCase.explain(action, type, key))
    }

    @PostMapping("/evaluate")
    @Operation(
        operationId = "evaluateAgentAction",
        summary = "Whether the agent-actions policy lets the caller take an action on the graph facts it cites",
    )
    fun evaluate(
        @RequestBody request: PolicyEvaluateRequest,
    ): PolicyEvaluateResponse {
        val action =
            request.action?.takeIf { it.isNotBlank() }
                ?: throw InvalidQueryParameterException("action", "action is required, such as rollback")
        val facts = request.facts.orEmpty().filter { it.isNotBlank() }
        if (facts.size > MAX_FACTS) throw InvalidQueryParameterException("facts", "at most $MAX_FACTS facts may be cited")
        return PolicyEvaluateResponse.from(useCase.evaluate(action, facts))
    }

    companion object {
        const val PATH = "/api/v1/policy"
        const val MAX_FACTS = 50
    }
}
