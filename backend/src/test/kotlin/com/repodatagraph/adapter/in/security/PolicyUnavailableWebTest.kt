package com.repodatagraph.adapter.`in`.security

import com.repodatagraph.adapter.`in`.rest.NodeController
import com.repodatagraph.application.freshness.FactFreshness
import com.repodatagraph.domain.exception.PolicyUnavailableException
import com.repodatagraph.domain.port.`in`.NodeUseCase
import com.repodatagraph.domain.port.`in`.ServicePrincipalUseCase
import com.repodatagraph.domain.port.out.PolicyDecisionPoint
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * The policy fails closed (ADR-0020): when it cannot be evaluated, a request is refused with
 * `503 {"error": "policy_unavailable"}` - a read as well as a write - and never reaches the graph.
 */
@WebMvcTest(
    controllers = [NodeController::class],
    properties = ["sdlc.auth.issuer-uri=https://issuer.example.test/realms/sdlc"],
)
@Import(SecurityConfig::class)
class PolicyUnavailableWebTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @MockitoBean
    private lateinit var nodeUseCase: NodeUseCase

    @MockitoBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var factFreshness: FactFreshness

    @MockitoBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var servicePrincipals: ServicePrincipalUseCase

    @MockitoBean
    private lateinit var policy: PolicyDecisionPoint

    @BeforeEach
    fun brokenPolicy() {
        whenever(policy.decide(any())).thenThrow(PolicyUnavailableException("sdlc/authz/decision", IllegalStateException("broken")))
        val jwt =
            Jwt
                .withTokenValue("user")
                .header("alg", "RS256")
                .subject("dan")
                .claim("scope", "graph:read graph:write")
                .issuer("https://issuer.example.test/realms/sdlc")
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
                .build()
        whenever(jwtDecoder.decode("user")).thenReturn(jwt)
    }

    @Test
    fun `a read the policy cannot decide is refused as unavailable`() {
        mockMvc
            .perform(get("/api/v1/nodes/Repository").header("Authorization", "Bearer user"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(content().json("""{"error":"policy_unavailable"}""", true))

        verify(nodeUseCase, never()).list(any(), any(), anyOrNull())
    }

    @Test
    fun `a write the policy cannot decide is refused as unavailable`() {
        mockMvc
            .perform(
                post("/api/v1/nodes/Team")
                    .header("Authorization", "Bearer user")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"props":{"name":"platform"}}"""),
            ).andExpect(status().isServiceUnavailable)
            .andExpect(content().json("""{"error":"policy_unavailable"}""", true))
    }
}
