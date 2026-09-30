package com.repodatagraph.adapter.`in`.security

import com.repodatagraph.adapter.`in`.rest.NodeController
import com.repodatagraph.adapter.`in`.rest.NodeRestExceptionHandler
import com.repodatagraph.adapter.`in`.rest.SeedIngestController
import com.repodatagraph.domain.model.NodePage
import com.repodatagraph.domain.port.`in`.NodeUseCase
import com.repodatagraph.domain.port.`in`.SeedIngestOutcome
import com.repodatagraph.domain.port.`in`.SeedIngestUseCase
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * The HTTP contract of the gate in front of the API (#114): what a caller without a valid token gets
 * back, and the paths that keep their own mechanism.
 *
 * The 401 is a JSON body with the same `error` field every other refusal has, plus the
 * `WWW-Authenticate: Bearer` challenge, so the web interface can tell "sign in again" from any other
 * failure. The token itself is decoded by a mock; that the real decoder trusts only the issuer's keys
 * is the acceptance suite's job, against a real Keycloak.
 */
@WebMvcTest(
    controllers = [NodeController::class, SeedIngestController::class],
    properties = [
        "sdlc.auth.disabled=false",
        "sdlc.auth.issuer-uri=https://issuer.example.test/realms/sdlc",
    ],
)
@Import(SecurityConfig::class, NodeRestExceptionHandler::class)
class AuthGateWebTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @MockitoBean
    private lateinit var nodeUseCase: NodeUseCase

    @MockitoBean
    private lateinit var seedIngestUseCase: SeedIngestUseCase

    private fun jwt(subject: String) =
        Jwt
            .withTokenValue("good")
            .header("alg", "RS256")
            .subject(subject)
            .issuer("https://issuer.example.test/realms/sdlc")
            .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
            .expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
            .build()

    @Test
    fun `a request without a token answers 401 with a JSON error and a Bearer challenge`() {
        mockMvc
            .perform(get("/api/v1/nodes/Repository"))
            .andExpect(status().isUnauthorized)
            .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")))
            .andExpect(jsonPath("$.error").value("authentication required"))
    }

    @Test
    fun `a token the decoder rejects answers the same 401`() {
        whenever(jwtDecoder.decode("forged")).thenThrow(BadJwtException("signature does not verify"))

        mockMvc
            .perform(get("/api/v1/nodes/Repository").header("Authorization", "Bearer forged"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("authentication required"))
    }

    @Test
    fun `the refusal does not echo why the token was rejected`() {
        whenever(jwtDecoder.decode("forged")).thenThrow(BadJwtException("signature does not verify"))

        mockMvc
            .perform(get("/api/v1/nodes/Repository").header("Authorization", "Bearer forged"))
            .andExpect(jsonPath("$.message").doesNotExist())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("signature"))))
    }

    @Test
    fun `a valid token reaches the API`() {
        whenever(jwtDecoder.decode("good")).thenReturn(jwt("dan"))
        whenever(nodeUseCase.list(eq("Repository"), any(), anyOrNull())).thenReturn(NodePage(emptyList(), null))

        mockMvc
            .perform(get("/api/v1/nodes/Repository").header("Authorization", "Bearer good"))
            .andExpect(status().isOk)
    }

    @Test
    fun `the ingest endpoints keep their own token, which is not decoded as a JWT`() {
        whenever(seedIngestUseCase.seed(anyOrNull(), any())).thenReturn(SeedIngestOutcome.Accepted(created = true, nodes = 1, edges = 0))

        mockMvc
            .perform(
                post("/api/v1/ingest/seed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"nodes":[]}""")
                    .header("Authorization", "Bearer ingest-token"),
            ).andExpect(status().isAccepted)

        verify(seedIngestUseCase).seed(eq("Bearer ingest-token"), any())
    }
}
