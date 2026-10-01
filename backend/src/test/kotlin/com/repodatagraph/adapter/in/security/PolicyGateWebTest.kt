package com.repodatagraph.adapter.`in`.security

import com.repodatagraph.adapter.`in`.rest.NodeController
import com.repodatagraph.adapter.`in`.rest.NodeRestExceptionHandler
import com.repodatagraph.application.freshness.FactFreshness
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.model.ServicePrincipal
import com.repodatagraph.domain.model.ServicePrincipalKind
import com.repodatagraph.domain.port.`in`.NodeUseCase
import com.repodatagraph.domain.port.`in`.ResourceOwners
import com.repodatagraph.domain.port.`in`.ServicePrincipalUseCase
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * The HTTP contract of the authorisation policy beyond scopes (#30, #95 FR-1): a refusal by a role,
 * a type above the caller's clearance, or an agent's limits is
 * `403 {error: "policy denied", policy, reason}`, before the request reaches the application; a
 * property above the clearance is taken out of what a read returns.
 *
 * Roles apply only to a token that carries the roles claim (`sdlc_roles`); one without it is judged
 * by its scopes alone (ScopeGateWebTest, DefaultPolicyParityTest).
 */
@WebMvcTest(
    controllers = [NodeController::class],
    properties = ["sdlc.auth.issuer-uri=https://issuer.example.test/realms/sdlc"],
)
@Import(SecurityConfig::class, NodeRestExceptionHandler::class)
class PolicyGateWebTest {
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
    private lateinit var servicePrincipals: ServicePrincipalUseCase

    @MockitoBean
    private lateinit var owners: ResourceOwners

    private fun userToken(vararg claims: Pair<String, Any>): String {
        val jwt =
            Jwt
                .withTokenValue("user")
                .header("alg", "RS256")
                .subject("vera")
                .issuer("https://issuer.example.test/realms/sdlc")
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
                .claims { it.putAll(claims) }
                .build()
        whenever(jwtDecoder.decode("user")).thenReturn(jwt)
        return "Bearer user"
    }

    private fun agentToken(scope: String): String {
        whenever(servicePrincipals.resolve("incident-bot")).thenReturn(
            ServicePrincipal("incident-bot", "sre", null, "dan", Instant.parse("2026-01-01T00:00:00Z"), kind = ServicePrincipalKind.AGENT),
        )
        val jwt =
            Jwt
                .withTokenValue("agent")
                .header("alg", "RS256")
                .subject("service-account-incident-bot")
                .claim("azp", "incident-bot")
                .claim("preferred_username", "service-account-incident-bot")
                .claim("scope", scope)
                .issuer("https://issuer.example.test/realms/sdlc")
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
                .build()
        whenever(jwtDecoder.decode("agent")).thenReturn(jwt)
        return "Bearer agent"
    }

    private val viewerWithWriteScope =
        arrayOf<Pair<String, Any>>("scope" to "graph:read graph:write", "sdlc_roles" to listOf("viewer"))

    private fun update(
        bearer: String,
        path: String = "/api/v1/nodes/Repository/github.com/acme/payments",
    ) = mockMvc.perform(
        put(path)
            .header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"props":{"defaultBranch":"main"}}"""),
    )

    @Test
    fun `a role that may not do what the scopes allow is refused, naming the rule and why`() {
        update(userToken(*viewerWithWriteScope))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("policy denied"))
            .andExpect(jsonPath("$.policy").value("roles"))
            .andExpect(jsonPath("$.reason").value("the role viewer may not update Repository"))

        verify(nodeUseCase, never()).update(any(), any(), any(), any(), anyOrNull())
    }

    @Test
    fun `a member of a team that owns the node may curate it whatever their role`() {
        whenever(owners.ownerTeams("Repository", "github.com/acme/payments")).thenReturn(setOf("payments"))
        val at = Instant.parse("2026-09-01T00:00:00Z")
        whenever(nodeUseCase.update(any(), any(), any(), any(), anyOrNull())).thenReturn(
            GraphNode(
                NodeKey("Repository", "github.com/acme/payments"),
                provenance = Provenance(sourceSystem = "manual", ingestedAt = at, validFrom = at),
            ),
        )

        update(userToken(*viewerWithWriteScope, "groups" to listOf("/payments")))
            .andExpect(status().is2xxSuccessful)
    }

    @Test
    fun `a missing scope is still the refusal it always was, whatever the roles`() {
        update(userToken("scope" to "graph:read", "sdlc_roles" to listOf("admin")))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("insufficient scope"))
            .andExpect(jsonPath("$.required[0]").value("graph:write"))
    }

    @Test
    fun `a type above the reader's clearance is refused before it is read`() {
        mockMvc
            .perform(
                get("/api/v1/nodes/ServicePrincipal/ci")
                    .header("Authorization", userToken("scope" to "graph:read", "sdlc_roles" to listOf("viewer"))),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.policy").value("sensitivity"))
            .andExpect(jsonPath("$.reason").value("ServicePrincipal is restricted, above what the subject is cleared for (internal)"))

        verify(nodeUseCase, never()).get(any(), any(), anyOrNull())
    }

    @Test
    fun `a property above the reader's clearance is taken out of the node and named`() {
        val at = Instant.parse("2026-09-01T00:00:00Z")
        whenever(nodeUseCase.get(eq("Team"), eq("platform"), anyOrNull())).thenReturn(
            GraphNode(
                NodeKey("Team", "platform"),
                mapOf("name" to "platform", "email" to "platform@acme.example"),
                Provenance(sourceSystem = "manual", ingestedAt = at, validFrom = at),
            ),
        )

        mockMvc
            .perform(
                get("/api/v1/nodes/Team/platform").header("Authorization", userToken("scope" to "graph:read", "sdlc_roles" to "viewer")),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.props.name").value("platform"))
            .andExpect(jsonPath("$.props.email").doesNotExist())
            .andExpect(jsonPath("$.redacted[0]").value("email"))
        mockMvc
            .perform(get("/api/v1/nodes/Team/platform").header("Authorization", userToken("scope" to "graph:read")))
            .andExpect(jsonPath("$.props.email").value("platform@acme.example"))
            .andExpect(jsonPath("$.redacted").doesNotExist())
    }

    @Test
    fun `an agent may never administer the graph, whatever scopes it holds`() {
        mockMvc
            .perform(post("/api/v1/lifecycle/migrations/apply").header("Authorization", agentToken("graph:read graph:write graph:admin")))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.policy").value("agents"))
            .andExpect(jsonPath("$.reason").value("agents may not perform admin actions"))
    }
}
