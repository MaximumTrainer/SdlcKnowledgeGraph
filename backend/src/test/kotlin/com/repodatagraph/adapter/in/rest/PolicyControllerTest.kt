package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.policy.AgentActionDecision
import com.repodatagraph.domain.policy.AgentFact
import com.repodatagraph.domain.policy.AuthzAction
import com.repodatagraph.domain.policy.Decision
import com.repodatagraph.domain.policy.PolicyStatus
import com.repodatagraph.domain.policy.ResourceKind
import com.repodatagraph.domain.policy.Subject
import com.repodatagraph.domain.policy.SubjectKind
import com.repodatagraph.domain.port.`in`.PolicyUseCase
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * The authorisation policy as a caller may ask about it (#30 FR8, #95 FR-4): the policy in force,
 * what it decides for the caller, and whether it lets an agent act on the facts it cites.
 */
@WebMvcTest(PolicyController::class)
class PolicyControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var useCase: PolicyUseCase

    private val viewer = Subject("vera", SubjectKind.USER, setOf("graph:read"), roles = listOf("viewer"))

    private fun ask(
        path: String,
        body: String,
    ) = mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))

    @Test
    fun `the policy in force names its revision, engine and fail mode`() {
        whenever(useCase.status()).thenReturn(
            PolicyStatus(
                "sdlc-authz",
                "sdlc-authz-1.0.0",
                Instant.parse("2026-10-01T12:00:00Z"),
                "embedded-wasm",
                "UP",
                "closed",
                "classpath",
            ),
        )

        mockMvc
            .perform(get("/api/v1/policy"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.revision").value("sdlc-authz-1.0.0"))
            .andExpect(jsonPath("$.loadedAt").value("2026-10-01T12:00:00Z"))
            .andExpect(jsonPath("$.engine").value("embedded-wasm"))
            .andExpect(jsonPath("$.failMode").value("closed"))
            .andExpect(jsonPath("$.source").doesNotExist())
    }

    @Test
    fun `an explanation gives the verdict, the rule, the reason and the caller as the policy saw them`() {
        whenever(useCase.explain(AuthzAction.UPDATE, "Repository", null)).thenReturn(
            PolicyUseCase.Explanation(
                viewer,
                Decision(false, "roles", "the role viewer may not update Repository", listOf("graph:write"), emptyList(), "internal"),
            ),
        )

        ask("/api/v1/policy/explain", """{"action":"update","resource":{"type":"Repository"}}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.allow").value(false))
            .andExpect(jsonPath("$.policy").value("roles"))
            .andExpect(jsonPath("$.reason").value("the role viewer may not update Repository"))
            .andExpect(jsonPath("$.required[0]").value("graph:write"))
            .andExpect(jsonPath("$.clearance").value("internal"))
            .andExpect(jsonPath("$.subject.id").value("vera"))
            .andExpect(jsonPath("$.subject.roles[0]").value("viewer"))
    }

    @Test
    fun `an action the policy does not decide is a bad request naming the field`() {
        ask("/api/v1/policy/explain", """{"action":"obliterate"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.field").value("action"))

        verify(useCase, never()).explain(any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `an agent's question is answered with the reasons and the facts as the graph holds them`() {
        val agent = Subject("incident-bot", SubjectKind.AGENT, setOf("graph:read"))
        val inferred = AgentFact("CAUSED_BY:Incident:INC1>Deployment:d2", true, ResourceKind.EDGE, "CAUSED_BY", 0.6, true, 3)
        val reason =
            "inferred cause: CAUSED_BY:Incident:INC1>Deployment:d2 was inferred by a rule at confidence 0.6, " +
                "not reported by its system of record"
        whenever(useCase.evaluate("rollback", listOf(inferred.id))).thenReturn(
            PolicyUseCase.Evaluation(agent, AgentActionDecision(false, "agent-actions", "rollback", listOf(reason)), listOf(inferred)),
        )

        ask("/api/v1/policy/evaluate", """{"action":"rollback","facts":["${inferred.id}"]}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.allow").value(false))
            .andExpect(jsonPath("$.policy").value("agent-actions"))
            .andExpect(jsonPath("$.reasons[0]").value(reason))
            .andExpect(jsonPath("$.facts[0].inferred").value(true))
            .andExpect(jsonPath("$.facts[0].confidence").value(0.6))
            .andExpect(jsonPath("$.subject.kind").value("agent"))
    }

    @Test
    fun `an evaluation names its action`() {
        ask("/api/v1/policy/evaluate", """{"facts":["Deployment:d2"]}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.field").value("action"))
    }
}
