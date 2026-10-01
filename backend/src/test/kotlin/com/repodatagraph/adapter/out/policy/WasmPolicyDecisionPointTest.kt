package com.repodatagraph.adapter.out.policy

import com.repodatagraph.domain.exception.PolicyUnavailableException
import com.repodatagraph.domain.policy.AgentFact
import com.repodatagraph.domain.policy.AuthzAction
import com.repodatagraph.domain.policy.AuthzRequest
import com.repodatagraph.domain.policy.AuthzResource
import com.repodatagraph.domain.policy.FilterItem
import com.repodatagraph.domain.policy.FilterRequest
import com.repodatagraph.domain.policy.ResourceKind
import com.repodatagraph.domain.policy.StatedProvenanceClaim
import com.repodatagraph.domain.policy.Subject
import com.repodatagraph.domain.policy.SubjectKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * The policy bundle the API ships, evaluated in-process (#30, #95, ADR-0020). These pin the Java side
 * of the bargain: the input it builds, and how it reads the answer. What the rules decide is the Rego
 * unit tests' job (the `_test.rego` files under policy/sdlc); here only enough of it to show the answer arrives whole.
 */
class WasmPolicyDecisionPointTest {
    private val policy = WasmPolicyDecisionPoint.classpathDefault()

    private fun user(
        vararg scopes: String,
        roles: List<String>? = null,
    ) = Subject("alice", SubjectKind.USER, scopes.toSet(), roles)

    private fun node(type: String? = null) = AuthzResource(ResourceKind.NODE, type = type)

    @Test
    fun `a write with a read token is refused for its scope, naming what it needs`() {
        val decision = policy.decide(AuthzRequest(user("graph:read"), AuthzAction.CREATE, node()))

        assertFalse(decision.allow)
        assertEquals("scopes", decision.policy)
        assertEquals(listOf("graph:write"), decision.required)
        assertEquals("create needs graph:write; the token holds graph:read", decision.reason)
    }

    @Test
    fun `a token without roles is judged by its scopes and cleared for everything`() {
        val decision = policy.decide(AuthzRequest(user("graph:read"), AuthzAction.READ, node("ServicePrincipal")))

        assertTrue(decision.allow)
        assertEquals("restricted", decision.clearance)
    }

    @Test
    fun `a role narrows what the scopes allow, and the refusal names it`() {
        val decision =
            policy.decide(AuthzRequest(user("graph:read", "graph:write", roles = listOf("viewer")), AuthzAction.UPDATE, node("Repository")))

        assertFalse(decision.allow)
        assertEquals("roles", decision.policy)
        assertEquals("the role viewer may not update Repository", decision.reason)
    }

    @Test
    fun `a property above the reader's clearance is to be redacted`() {
        val decision = policy.decide(AuthzRequest(user("graph:read", roles = listOf("viewer")), AuthzAction.READ, node("Team")))

        assertTrue(decision.allow)
        assertEquals(listOf("email"), decision.redact)
        assertEquals("internal", decision.clearance)
    }

    @Test
    fun `a source stated at full confidence needs its scope`() {
        val claim = StatedProvenanceClaim("github", 1.0, inferred = false)
        val decision =
            policy.decide(
                AuthzRequest(user("graph:read", "graph:write"), AuthzAction.CREATE, AuthzResource(ResourceKind.NODE, provenance = claim)),
            )

        assertFalse(decision.allow)
        assertEquals("provenance.confidence", decision.policy)
        assertEquals(listOf("graph:write", "graph:write:github"), decision.required)
    }

    @Test
    fun `the filter hides types above the clearance and names what to redact from the rest`() {
        val result =
            policy.filter(
                FilterRequest(
                    user("graph:read", roles = listOf("viewer")),
                    listOf(FilterItem("Team:platform", "Team"), FilterItem("ServicePrincipal:ci", "ServicePrincipal")),
                ),
            )

        assertEquals(mapOf("Team:platform" to listOf("email")), result.allowed)
        assertEquals(listOf("ServicePrincipal:ci"), result.denied)
    }

    @Test
    fun `the agent-actions policy refuses a rollback on an inferred cause, saying so`() {
        val decision =
            policy.agentAction(
                "rollback",
                Subject("incident-bot", SubjectKind.AGENT, setOf("graph:read")),
                listOf(
                    AgentFact("Deployment:d1", true, ResourceKind.NODE, "Deployment", 1.0, false, 5, true, "production"),
                    AgentFact("CAUSED_BY:Incident:i1>Deployment:d1", true, ResourceKind.EDGE, "CAUSED_BY", 0.6, true, 5),
                ),
            )

        assertFalse(decision.allow)
        assertEquals("agent-actions", decision.policy)
        assertEquals(
            listOf(
                "inferred cause: CAUSED_BY:Incident:i1>Deployment:d1 was inferred by a rule at confidence 0.6, " +
                    "not reported by its system of record",
            ),
            decision.reasons,
        )
    }

    @Test
    fun `it names the revision in force and that it fails closed`() {
        val status = policy.status()

        assertEquals("sdlc-authz-1.0.0", status.revision)
        assertEquals("embedded-wasm", status.engine)
        assertEquals("closed", status.failMode)
    }

    @Test
    fun `many threads may ask at once`() {
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val answers =
                pool
                    .invokeAll(
                        (1..ASKS).map { index ->
                            Callable {
                                val scopes = if (index % 2 == 0) arrayOf("graph:read", "graph:write") else arrayOf("graph:read")
                                policy.decide(AuthzRequest(user(*scopes), AuthzAction.CREATE, node())).allow == (index % 2 == 0)
                            }
                        },
                    ).map { it.get() }

            assertTrue(answers.all { it })
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `a policy that cannot be evaluated is unavailable, not an allow`() {
        val closed = WasmPolicyDecisionPoint(PolicyBundle.fromClasspath(), poolSize = 1)
        closed.close()

        val failure =
            assertThrows<PolicyUnavailableException> {
                closed.decide(AuthzRequest(user("graph:read"), AuthzAction.READ, node()))
            }
        assertEquals("sdlc/authz/decision", failure.entrypoint)
    }

    @Test
    fun `a bundle without its data is not loaded at all`() {
        val bundle = PolicyBundle.fromClasspath()

        val failure =
            assertThrows<IllegalStateException> { WasmPolicyDecisionPoint(PolicyBundle(bundle.wasm, "{}", bundle.revision, "test")) }
        assertTrue(failure.message!!.contains("holds no policy data"), failure.message)
    }

    private companion object {
        const val THREADS = 8
        const val ASKS = 200
    }
}
