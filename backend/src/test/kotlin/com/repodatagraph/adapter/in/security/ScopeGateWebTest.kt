package com.repodatagraph.adapter.`in`.security

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.repodatagraph.adapter.`in`.rest.NodeController
import com.repodatagraph.adapter.`in`.rest.NodeRestExceptionHandler
import com.repodatagraph.adapter.`in`.rest.SeedIngestController
import com.repodatagraph.domain.model.NodePage
import com.repodatagraph.domain.model.ServicePrincipal
import com.repodatagraph.domain.port.`in`.NodeUseCase
import com.repodatagraph.domain.port.`in`.SeedIngestUseCase
import com.repodatagraph.domain.port.`in`.ServicePrincipalUseCase
import com.repodatagraph.observability.EventLog
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * The HTTP contract of least privilege (#116): which graph scope a request needs, and the 403 a
 * token without it gets back.
 *
 * The refusal is `{error: "insufficient scope", required, held}`, in the same shape as every other
 * refusal the API gives: `required` is what the request needs, `held` the graph scopes the token
 * carries (and nothing else it carries), both sorted. GraphQL is refused the same way, as an HTTP
 * 403 before the document is executed, so a caller handles one kind of refusal whichever API it uses.
 *
 * The checks run in order: no valid token is a 401, an unregistered client a 403 naming it (#115),
 * and only a principal the API knows has its scopes compared.
 */
@WebMvcTest(
    controllers = [NodeController::class, SeedIngestController::class],
    properties = [
        "sdlc.auth.issuer-uri=https://issuer.example.test/realms/sdlc",
    ],
)
@Import(SecurityConfig::class, NodeRestExceptionHandler::class)
class ScopeGateWebTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @MockitoBean
    private lateinit var nodeUseCase: NodeUseCase

    @MockitoBean
    private lateinit var seedIngestUseCase: SeedIngestUseCase

    @MockitoBean
    private lateinit var servicePrincipals: ServicePrincipalUseCase

    /** A user's token carrying [claims], one of which is normally `scope`. */
    private fun userToken(vararg claims: Pair<String, Any>): String {
        val jwt =
            Jwt
                .withTokenValue("user")
                .header("alg", "RS256")
                .subject("dan")
                .issuer("https://issuer.example.test/realms/sdlc")
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
                .claims { it.putAll(claims) }
                .build()
        whenever(jwtDecoder.decode("user")).thenReturn(jwt)
        return "Bearer user"
    }

    /** What Keycloak issues a confidential client for the client-credentials grant. */
    private fun serviceToken(
        clientId: String,
        scope: String,
    ): String {
        val jwt =
            Jwt
                .withTokenValue("service")
                .header("alg", "RS256")
                .subject("0b6f3c1e-service-account")
                .claim("azp", clientId)
                .claim("preferred_username", "service-account-$clientId")
                .claim("scope", scope)
                .issuer("https://issuer.example.test/realms/sdlc")
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
                .build()
        whenever(jwtDecoder.decode("service")).thenReturn(jwt)
        return "Bearer service"
    }

    private fun ResultActions.refusedFor(
        required: List<String>,
        held: List<String>,
    ): ResultActions =
        andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("insufficient scope"))
            .andExpect(jsonPath("$.required").value(equalTo(required)))
            .andExpect(jsonPath("$.held").value(equalTo(held)))

    private fun createTeam(bearer: String) =
        mockMvc.perform(
            post("/api/v1/nodes/Team")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"props":{"name":"platform"}}"""),
        )

    private fun graphQl(
        bearer: String,
        document: String,
    ) = mockMvc.perform(
        post("/graphql")
            .header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"query":${com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(document)}}"""),
    )

    @Test
    fun `a write with a read-only token is refused, naming what it needed and what the token held`() {
        createTeam(userToken("scope" to "openid profile graph:read"))
            .refusedFor(required = listOf("graph:write"), held = listOf("graph:read"))

        verify(nodeUseCase, never()).create(any(), any(), any())
    }

    @Test
    fun `PUT and DELETE need graph write too`() {
        val bearer = userToken("scope" to "graph:read")

        mockMvc
            .perform(
                put("/api/v1/nodes/Team/platform")
                    .header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"props":{"name":"platform"}}"""),
            ).refusedFor(required = listOf("graph:write"), held = listOf("graph:read"))
        mockMvc
            .perform(delete("/api/v1/nodes/Team/platform").header("Authorization", bearer))
            .refusedFor(required = listOf("graph:write"), held = listOf("graph:read"))
    }

    @Test
    fun `a read with a read-only token reaches the API`() {
        whenever(nodeUseCase.list(eq("Repository"), any(), anyOrNull())).thenReturn(NodePage(emptyList(), null))

        mockMvc
            .perform(get("/api/v1/nodes/Repository").header("Authorization", userToken("scope" to "graph:read")))
            .andExpect(status().isOk)
    }

    @Test
    fun `a read with a token holding no graph scope is refused, and held is empty`() {
        mockMvc
            .perform(get("/api/v1/nodes/Repository").header("Authorization", userToken("scope" to "openid profile email")))
            .refusedFor(required = listOf("graph:read"), held = emptyList())

        verify(nodeUseCase, never()).list(any(), any(), anyOrNull())
    }

    @Test
    fun `a write scope alone does not grant reads`() {
        mockMvc
            .perform(get("/api/v1/nodes/Repository").header("Authorization", userToken("scope" to "graph:write")))
            .refusedFor(required = listOf("graph:read"), held = listOf("graph:write"))
    }

    @Test
    fun `scopes in an scp array are honoured like the scope string`() {
        whenever(nodeUseCase.list(eq("Repository"), any(), anyOrNull())).thenReturn(NodePage(emptyList(), null))

        mockMvc
            .perform(get("/api/v1/nodes/Repository").header("Authorization", userToken("scp" to listOf("graph:read"))))
            .andExpect(status().isOk)
    }

    @Test
    fun `held lists only the graph scopes, sorted`() {
        mockMvc
            .perform(
                get("/api/v1/nodes/Repository")
                    .header("Authorization", userToken("scope" to "openid graph:write email graph:admin")),
            ).refusedFor(required = listOf("graph:read"), held = listOf("graph:admin", "graph:write"))
    }

    @Test
    fun `a registered service principal is held to its scopes like a user`() {
        whenever(servicePrincipals.resolve("triage-agent"))
            .thenReturn(ServicePrincipal("triage-agent", "team-payments", null, "dan", Instant.parse("2026-01-01T00:00:00Z")))

        createTeam(serviceToken("triage-agent", "profile email graph:read"))
            .refusedFor(required = listOf("graph:write"), held = listOf("graph:read"))
    }

    @Test
    fun `an unregistered client is refused as unregistered, before its scopes are looked at`() {
        whenever(servicePrincipals.resolve("rogue-agent")).thenReturn(null)

        createTeam(serviceToken("rogue-agent", "profile email"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("unregistered service principal"))
    }

    @Test
    fun `no token is still a 401, not a 403`() {
        mockMvc
            .perform(get("/api/v1/nodes/Repository"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("authentication required"))
    }

    @Test
    fun `the ingest endpoints keep their own token and need no scope`() {
        whenever(seedIngestUseCase.seed(anyOrNull(), any()))
            .thenReturn(
                com.repodatagraph.domain.port.`in`.SeedIngestOutcome
                    .Accepted(created = true, nodes = 1, edges = 0),
            )

        mockMvc
            .perform(
                post("/api/v1/ingest/seed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"nodes":[]}""")
                    .header("Authorization", "Bearer ingest-token"),
            ).andExpect(status().isAccepted)
    }

    @Test
    fun `a GraphQL mutation with a read-only token is refused with the same 403`() {
        graphQl(userToken("scope" to "graph:read"), """mutation { deleteRepository(id: "r1") }""")
            .refusedFor(required = listOf("graph:write"), held = listOf("graph:read"))
    }

    @Test
    fun `a GraphQL query with a read-only token is let through`() {
        // No GraphQL handler is started in this slice, so getting past the gate means a 404.
        graphQl(userToken("scope" to "graph:read"), "{ repositories { id } }")
            .andExpect(status().`is`(not(403)))
    }

    @Test
    fun `a GraphQL query with no graph scope is refused`() {
        graphQl(userToken("scope" to "openid"), "query Repos { repositories { id } }")
            .refusedFor(required = listOf("graph:read"), held = emptyList())
    }

    @Test
    fun `a GraphQL document holding a query and a mutation needs both scopes`() {
        graphQl(
            userToken("scope" to "graph:read"),
            """query Repos { repositories { id } } mutation Drop { deleteRepository(id: "r1") }""",
        ).refusedFor(required = listOf("graph:read", "graph:write"), held = listOf("graph:read"))
    }

    @Test
    fun `a GraphQL document that cannot be read as queries only needs graph write as well`() {
        graphQl(userToken("scope" to "graph:read"), "mutation { this is not graphql")
            .refusedFor(required = listOf("graph:read", "graph:write"), held = listOf("graph:read"))
    }

    @Test
    fun `a refusal is logged as a security warning`() {
        val events = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger("${EventLog.LOGGER_PREFIX}.scope.refused") as Logger
        logger.addAppender(events)
        try {
            createTeam(userToken("scope" to "graph:read"))
        } finally {
            logger.detachAppender(events)
        }

        assertEquals(listOf(Level.WARN), events.list.map { it.level })
    }
}
