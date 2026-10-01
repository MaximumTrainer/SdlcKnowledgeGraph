package com.repodatagraph.application.policy

import com.repodatagraph.adapter.out.policy.WasmPolicyDecisionPoint
import com.repodatagraph.domain.policy.AgentFact
import com.repodatagraph.domain.policy.AuthzAction
import com.repodatagraph.domain.policy.Subject
import com.repodatagraph.domain.policy.SubjectKind
import com.repodatagraph.domain.port.`in`.ResourceOwners
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions

/**
 * The caller's own questions to the policy (#30 FR8, #95 FR-4), answered as the gate would answer
 * them: owning a node is looked up only when it could change the answer.
 */
class PolicyServiceTest {
    private val policy = WasmPolicyDecisionPoint.classpathDefault()
    private val facts: GraphAgentFacts =
        mock {
            on { fact(any()) } doAnswer { AgentFact(it.getArgument(0), found = false) }
        }

    private fun service(
        subject: Subject,
        owners: ResourceOwners = ResourceOwners { _, _ -> emptySet() },
    ) = PolicyService(policy, { subject }, owners, facts)

    private val curatorOfPayments =
        Subject("cora", SubjectKind.USER, setOf("graph:read", "graph:write"), roles = listOf("viewer"), teams = setOf("payments"))

    @Test
    fun `it explains the caller's own access, with the rule that decided`() {
        val viewer = Subject("vera", SubjectKind.USER, setOf("graph:read", "graph:write"), roles = listOf("viewer"))

        val explanation = service(viewer).explain(AuthzAction.UPDATE, "Repository", null)

        assertEquals(viewer, explanation.subject)
        assertFalse(explanation.decision.allow)
        assertEquals("roles", explanation.decision.policy)
    }

    @Test
    fun `a member of a team that owns the node may curate it whatever their role`() {
        val owners =
            ResourceOwners { type, key ->
                if (type == "Repository" &&
                    key == "github.com/acme/payments"
                ) {
                    setOf("payments")
                } else {
                    emptySet()
                }
            }

        val owned = service(curatorOfPayments, owners).explain(AuthzAction.UPDATE, "Repository", "github.com/acme/payments")
        val other = service(curatorOfPayments, owners).explain(AuthzAction.UPDATE, "Repository", "github.com/acme/web")

        assertTrue(owned.decision.allow, owned.decision.reason)
        assertEquals("a member of a team that owns it may act on it as a curator", owned.decision.reason)
        assertFalse(other.decision.allow)
    }

    @Test
    fun `owners are not looked up when owning could not change the answer`() {
        val owners: ResourceOwners = mock()
        val unroled = Subject("dan", SubjectKind.USER, setOf("graph:read", "graph:write"), teams = setOf("payments"))

        service(unroled, owners).explain(AuthzAction.UPDATE, "Repository", "github.com/acme/payments")
        service(curatorOfPayments, owners).explain(AuthzAction.UPDATE, "Repository", null)

        verifyNoInteractions(owners)
    }

    @Test
    fun `an agent's evaluation is decided on the facts as the graph holds them`() {
        val agent = Subject("incident-bot", SubjectKind.AGENT, setOf("graph:read"))

        val evaluation = service(agent).evaluate("rollback", listOf("Deployment:made-up"))

        verify(facts).fact("Deployment:made-up")
        verify(facts, never()).fact("anything else")
        assertFalse(evaluation.decision.allow)
        assertEquals("agent-actions", evaluation.decision.policy)
    }
}
