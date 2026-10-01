package com.repodatagraph.adapter.out.policy

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.repodatagraph.domain.exception.PolicyUnavailableException
import com.repodatagraph.domain.policy.AgentActionDecision
import com.repodatagraph.domain.policy.AgentFact
import com.repodatagraph.domain.policy.AuthzRequest
import com.repodatagraph.domain.policy.Decision
import com.repodatagraph.domain.policy.FilterRequest
import com.repodatagraph.domain.policy.FilterResult
import com.repodatagraph.domain.policy.PolicyStatus
import com.repodatagraph.domain.policy.Subject
import com.repodatagraph.domain.port.out.PolicyDecisionPoint
import com.repodatagraph.observability.LogEvents
import com.styra.opa.wasm.OpaPolicy
import com.styra.opa.wasm.OpaPolicyPool
import java.time.Clock
import java.time.Instant

/**
 * Open Policy Agent, in the API's own process (ADR-0020): the Rego bundle under policy/, compiled to
 * WebAssembly by `opa build --target wasm`, evaluated by opa-java-wasm on a pure-Java WebAssembly
 * runtime. The same compiled policy decides on every instance - the dogfood's single small machine
 * included - with no second service to run, reach or keep in step.
 *
 * A compiled policy instance is not safe to share between threads, so a small pool of them is kept.
 * The pool clears an instance's memory, its data included, when it is handed back, so the bundle's
 * data is loaded into the instance for each evaluation. An evaluation that fails, or answers nothing, is
 * [PolicyUnavailableException]: the caller refuses the request rather than answer it unchecked, and
 * the instance it failed on is thrown away.
 */
class WasmPolicyDecisionPoint(
    private val bundle: PolicyBundle,
    poolSize: Int = DEFAULT_POOL_SIZE,
    clock: Clock = Clock.systemUTC(),
) : PolicyDecisionPoint,
    AutoCloseable {
    private val mapper = ObjectMapper()
    private val data: JsonNode = mapper.readTree(bundle.data)
    private val loadedAt: Instant = Instant.now(clock)
    private val pool: OpaPolicyPool = OpaPolicyPool.create({ instance() }, poolSize)

    init {
        // A bundle without its data would decide with no scopes to ask for, so it is not loaded at all.
        check(
            data
                .path("sdlc")
                .path("config")
                .path("action_scopes")
                .isObject,
        ) {
            "${bundle.source} holds no policy data (data.sdlc.config); rebuild it with node scripts/opa.mjs build"
        }
        // Compiled once up front, so a bundle that cannot be loaded stops the application at startup
        // instead of failing the first request.
        pool.borrow().use { }
        LogEvents.policyLoaded(bundle.revision, bundle.source)
    }

    private fun instance(): OpaPolicy =
        OpaPolicy
            .builder()
            .withPolicy(bundle.wasm)
            .build()

    override fun decide(request: AuthzRequest): Decision {
        val input =
            mapOf(
                "subject" to subject(request.subject),
                "action" to request.action.wireName,
                "resource" to
                    mapOf(
                        "kind" to request.resource.kind.wireName,
                        "type" to request.resource.type,
                        "key" to request.resource.key,
                        "filtered" to request.resource.filtered,
                        "ownerTeams" to request.resource.ownerTeams.sorted(),
                        "provenance" to
                            request.resource.provenance?.let {
                                mapOf("sourceSystem" to it.sourceSystem, "confidence" to it.confidence, "inferred" to it.inferred)
                            },
                    ).filterValues { it != null },
                "context" to request.context,
            )
        val result = evaluate(DECISION, input)
        return Decision(
            allow = result.path("allow").asBoolean(false),
            policy = result.path("policy").asText(""),
            reason = result.path("reason").asText(""),
            required = result.path("required").map { it.asText() },
            redact = result.path("redact").map { it.asText() },
            clearance = result.path("clearance").asText(""),
        )
    }

    override fun filter(request: FilterRequest): FilterResult {
        if (request.items.isEmpty()) return FilterResult.EMPTY
        val input =
            mapOf(
                "subject" to subject(request.subject),
                "action" to "read",
                "resources" to request.items.map { mapOf("id" to it.id, "type" to it.type) },
                "context" to request.context,
            )
        val result = evaluate(FILTER, input)
        val allowed = linkedMapOf<String, List<String>>()
        result.path("allowed").properties().forEach { (id, redact) -> allowed[id] = redact.map { it.asText() } }
        return FilterResult(allowed, result.path("denied").map { it.asText() })
    }

    override fun agentAction(
        action: String,
        subject: Subject,
        facts: List<AgentFact>,
    ): AgentActionDecision {
        val input =
            mapOf(
                "action" to action,
                "subject" to subject(subject),
                "facts" to
                    facts.map { fact ->
                        mapOf(
                            "id" to fact.id,
                            "found" to fact.found,
                            "kind" to fact.kind?.wireName,
                            "type" to fact.type,
                            "confidence" to fact.confidence,
                            "inferred" to fact.inferred,
                            "ageMinutes" to fact.ageMinutes,
                            "priorArtifact" to fact.priorArtifact,
                            "environment" to fact.environment,
                        ).filterValues { it != null }
                    },
            )
        val result = evaluate(AGENT_ACTIONS, input)
        return AgentActionDecision(
            allow = result.path("allow").asBoolean(false),
            policy = result.path("policy").asText(AGENT_ACTIONS_POLICY),
            action = result.path("action").asText(action),
            reasons = result.path("reasons").map { it.asText() },
        )
    }

    override fun status(): PolicyStatus =
        PolicyStatus(
            name = NAME,
            revision = bundle.revision,
            loadedAt = loadedAt,
            engine = ENGINE,
            status = UP,
            failMode = FAIL_MODE,
            source = bundle.source,
        )

    override fun close() = pool.close()

    private fun subject(subject: Subject): Map<String, Any?> =
        mapOf(
            "id" to subject.id,
            "kind" to subject.kind.wireName,
            "scopes" to subject.scopes.sorted(),
            "roles" to subject.roles,
            "teams" to subject.teams.sorted(),
            "attributes" to subject.attributes,
        ).filterValues { it != null }

    /** An instance to evaluate [entrypoint] on; an interrupted wait or a closed pool is a failure. */
    private fun borrow(entrypoint: String): OpaPolicyPool.Loan =
        try {
            pool.borrow()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw unavailable(entrypoint, interrupted)
        } catch (closed: IllegalStateException) {
            throw unavailable(entrypoint, closed)
        }

    /** The value of [entrypoint] for [input]; anything but exactly one result is a failure. */
    @Suppress("TooGenericExceptionCaught")
    private fun evaluate(
        entrypoint: String,
        input: Any,
    ): JsonNode {
        val loan = borrow(entrypoint)
        try {
            val answer =
                loan
                    .policy()
                    .data(data)
                    .entrypoint(entrypoint)
                    .evaluate(mapper.writeValueAsString(input))
            val result = mapper.readTree(answer).path(0).path("result")
            check(result.isObject) { "$entrypoint was undefined for its input" }
            loan.close()
            return result
        } catch (failure: Exception) {
            // An instance that failed mid-evaluation may hold half a state; it is not reused.
            loan.discard()
            throw unavailable(entrypoint, failure)
        }
    }

    private fun unavailable(
        entrypoint: String,
        cause: Throwable,
    ): PolicyUnavailableException {
        LogEvents.policyUnavailable(entrypoint, cause)
        return PolicyUnavailableException(entrypoint, cause)
    }

    companion object {
        const val NAME = "sdlc-authz"
        const val ENGINE = "embedded-wasm"
        const val UP = "UP"

        /** Nothing is answered unchecked: a policy that cannot be evaluated refuses (ADR-0020). */
        const val FAIL_MODE = "closed"
        const val DECISION = "sdlc/authz/decision"
        const val FILTER = "sdlc/authz/filter"
        const val AGENT_ACTIONS = "sdlc/agent_actions/decision"
        const val AGENT_ACTIONS_POLICY = "agent-actions"
        const val DEFAULT_POOL_SIZE = 4

        private val classpathDefault: WasmPolicyDecisionPoint by lazy { WasmPolicyDecisionPoint(PolicyBundle.fromClasspath()) }

        /**
         * The bundle the API is built with, compiled once per JVM and shared. Compiling it takes about
         * a second, and test slices that build part of the application - every one that imports the
         * security configuration - would otherwise each pay that for the same immutable policy.
         */
        fun classpathDefault(): WasmPolicyDecisionPoint = classpathDefault
    }
}
