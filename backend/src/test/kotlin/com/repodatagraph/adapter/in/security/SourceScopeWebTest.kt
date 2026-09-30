package com.repodatagraph.adapter.`in`.security

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.repodatagraph.adapter.`in`.rest.NodeController
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.model.ServicePrincipal
import com.repodatagraph.domain.port.`in`.NodeUseCase
import com.repodatagraph.domain.port.`in`.ServicePrincipalUseCase
import com.repodatagraph.domain.port.out.SourceWriteAuthorization
import com.repodatagraph.observability.EventLog
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * The HTTP contract of source-scoped writes (#117): a write naming a source other than `manual`
 * needs `graph:write:<source>` on top of `graph:write`, and a token without it gets back the very
 * 403 a missing `graph:write` gets (#116) - same body, same challenge, same security event.
 *
 * The node use case is a stand-in that asks the real [SourceWriteAuthorization] adapter, as the
 * application service does, so what is under test is the adapter reading the token Spring Security
 * authenticated and the refusal it turns into.
 */
@WebMvcTest(
    controllers = [NodeController::class],
    properties = [
        "sdlc.auth.issuer-uri=https://issuer.example.test/realms/sdlc",
    ],
)
@Import(SecurityConfig::class, ScopeSourceWriteAuthorization::class)
class SourceScopeWebTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var authorization: SourceWriteAuthorization

    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @MockitoBean
    private lateinit var nodeUseCase: NodeUseCase

    @MockitoBean
    private lateinit var servicePrincipals: ServicePrincipalUseCase

    @BeforeEach
    fun statesThroughTheAuthorization() {
        whenever(nodeUseCase.create(any(), any(), any())).thenAnswer {
            val type = it.getArgument<String>(0)
            val source = it.getArgument<String>(2)
            authorization.authorize(source)
            val now = Instant.parse("2026-01-01T00:00:00Z")
            GraphNode(
                NodeKey(type, "platform"),
                mapOf("name" to "platform"),
                Provenance(sourceSystem = source, ingestedAt = now, validFrom = now),
            )
        }
        whenever(servicePrincipals.resolve("github-connector"))
            .thenReturn(ServicePrincipal("github-connector", "team-platform", null, "dan", Instant.parse("2026-01-01T00:00:00Z")))
    }

    private fun userToken(scope: String): String {
        val jwt =
            Jwt
                .withTokenValue("user")
                .header("alg", "RS256")
                .subject("dan")
                .claim("scope", scope)
                .issuer("https://issuer.example.test/realms/sdlc")
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
                .build()
        whenever(jwtDecoder.decode("user")).thenReturn(jwt)
        return "Bearer user"
    }

    private fun connectorToken(scope: String): String {
        val jwt =
            Jwt
                .withTokenValue("service")
                .header("alg", "RS256")
                .subject("0b6f3c1e-service-account")
                .claim("azp", "github-connector")
                .claim("preferred_username", "service-account-github-connector")
                .claim("scope", scope)
                .issuer("https://issuer.example.test/realms/sdlc")
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
                .build()
        whenever(jwtDecoder.decode("service")).thenReturn(jwt)
        return "Bearer service"
    }

    private fun createTeam(
        bearer: String,
        sourceSystem: String?,
    ): ResultActions {
        val provenance = sourceSystem?.let { ""","provenance":{"sourceSystem":"$it"}""" }.orEmpty()
        return mockMvc.perform(
            post("/api/v1/nodes/Team")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"props":{"name":"platform"}$provenance}"""),
        )
    }

    private fun ResultActions.refusedFor(
        required: List<String>,
        held: List<String>,
    ): ResultActions =
        andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("insufficient scope"))
            .andExpect(jsonPath("$.required").value(equalTo(required)))
            .andExpect(jsonPath("$.held").value(equalTo(held)))
            .andExpect(header().string("WWW-Authenticate", "Bearer error=\"insufficient_scope\", scope=\"${required.joinToString(" ")}\""))

    @Test
    fun `a connector holding its own source scope writes as that source`() {
        createTeam(connectorToken("graph:read graph:write graph:write:github"), "github")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.provenance.sourceSystem").value("github"))
    }

    @Test
    fun `a connector writing as another source is refused, naming both scopes it needed`() {
        createTeam(connectorToken("profile graph:read graph:write graph:write:github"), "aws")
            .refusedFor(
                required = listOf("graph:write", "graph:write:aws"),
                held = listOf("graph:read", "graph:write", "graph:write:github"),
            )
    }

    @Test
    fun `a user cannot write as a system of record`() {
        createTeam(userToken("openid graph:read graph:write"), "github")
            .refusedFor(required = listOf("graph:write", "graph:write:github"), held = listOf("graph:read", "graph:write"))
    }

    @Test
    fun `manual needs graph write and nothing more`() {
        createTeam(userToken("graph:write"), "manual").andExpect(status().isCreated)
        createTeam(userToken("graph:write"), null).andExpect(status().isCreated)
    }

    @Test
    fun `a source scope does not stand in for graph write`() {
        // graph:write is the gate's: the write never reaches the source check without it.
        createTeam(connectorToken("graph:read graph:write:github"), "github")
            .refusedFor(required = listOf("graph:write"), held = listOf("graph:read", "graph:write:github"))
    }

    @Test
    fun `a source scope for one source is not a prefix match for another`() {
        createTeam(connectorToken("graph:write graph:write:github"), "github-actions")
            .refusedFor(
                required = listOf("graph:write", "graph:write:github-actions"),
                held = listOf("graph:write", "graph:write:github"),
            )
    }

    @Test
    fun `a refusal is logged as the same security warning`() {
        val events = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger("${EventLog.LOGGER_PREFIX}.scope.refused") as Logger
        logger.addAppender(events)
        try {
            createTeam(userToken("graph:read graph:write"), "aws")
        } finally {
            logger.detachAppender(events)
        }

        assertEquals(listOf(Level.WARN), events.list.map { it.level })
    }
}
