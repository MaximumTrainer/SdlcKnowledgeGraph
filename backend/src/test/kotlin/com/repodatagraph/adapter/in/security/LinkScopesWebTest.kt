package com.repodatagraph.adapter.`in`.security

import com.repodatagraph.adapter.`in`.rest.LinkController
import com.repodatagraph.adapter.`in`.rest.RestExceptionHandler
import com.repodatagraph.domain.model.CandidateLink
import com.repodatagraph.domain.model.CandidatePage
import com.repodatagraph.domain.model.CandidateStatus
import com.repodatagraph.domain.model.LinkedRepository
import com.repodatagraph.domain.model.LinkedResource
import com.repodatagraph.domain.port.`in`.LinkUseCase
import com.repodatagraph.domain.port.`in`.ServicePrincipalUseCase
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
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
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * Who may review the link engine's work (#28): anyone who may read the graph lists the candidates,
 * and only a caller who may write it starts a resolution, accepts or rejects a candidate, or states
 * or closes a manual link. A refused decision never reaches the engine.
 */
@WebMvcTest(
    controllers = [LinkController::class],
    properties = ["sdlc.auth.issuer-uri=https://issuer.example.test/realms/sdlc"],
)
@Import(SecurityConfig::class, RestExceptionHandler::class)
class LinkScopesWebTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @MockitoBean
    private lateinit var links: LinkUseCase

    @MockitoBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var servicePrincipals: ServicePrincipalUseCase

    private fun token(scope: String): String {
        val jwt =
            Jwt
                .withTokenValue("user")
                .header("alg", "RS256")
                .subject("dan")
                .issuer("https://issuer.example.test/realms/sdlc")
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
                .claim("scope", scope)
                .build()
        whenever(jwtDecoder.decode("user")).thenReturn(jwt)
        return "Bearer user"
    }

    private fun ResultActions.refusedForWrite(): ResultActions =
        andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("insufficient scope"))
            .andExpect(jsonPath("$.required").value(equalTo(listOf("graph:write"))))

    @Test
    fun `a reader lists the candidates`() {
        whenever(links.candidates(any())).thenReturn(CandidatePage(emptyList(), totalElements = 0))

        mockMvc
            .perform(get("/api/v1/links/candidates").header("Authorization", token("graph:read")))
            .andExpect(status().isOk)
    }

    @Test
    fun `a reader may not accept, reject, resolve or state a link`() {
        val reader = token("graph:read")

        mockMvc.perform(post("/api/v1/links/candidates/c1/accept").header("Authorization", reader)).refusedForWrite()
        mockMvc.perform(post("/api/v1/links/candidates/c1/reject").header("Authorization", reader)).refusedForWrite()
        mockMvc.perform(post("/api/v1/links/resolve").header("Authorization", reader)).refusedForWrite()
        mockMvc
            .perform(
                post("/api/v1/links/manual")
                    .header("Authorization", reader)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"resourceKey":"aws:arn:aws:s3:::logs","repoKey":"github.com/acme/payments"}"""),
            ).refusedForWrite()
        mockMvc
            .perform(
                delete("/api/v1/links/manual")
                    .param("resourceKey", "aws:arn:aws:s3:::logs")
                    .param("repoKey", "github.com/acme/payments")
                    .header("Authorization", reader),
            ).refusedForWrite()

        verify(links, never()).accept(any())
        verify(links, never()).reject(any())
        verify(links, never()).resolve(any())
        verify(links, never()).link(any(), any())
        verify(links, never()).unlink(any(), any())
    }

    @Test
    fun `no token at all is a 401, not a decision`() {
        mockMvc.perform(post("/api/v1/links/candidates/c1/accept")).andExpect(status().isUnauthorized)

        verify(links, never()).accept(any())
    }

    @Test
    fun `a writer may decide`() {
        whenever(links.reject("c1")).thenReturn(
            CandidateLink(
                id = "c1",
                resource = LinkedResource("aws:arn:aws:s3:::logs", "logs", "aws", null),
                repository = LinkedRepository("github.com/acme/payments", "payments"),
                confidence = 0.4,
                rule = "naming",
                evidence = emptyMap(),
                status = CandidateStatus.REJECTED,
                createdAt = null,
                rejectedBy = "dan",
            ),
        )

        mockMvc
            .perform(post("/api/v1/links/candidates/c1/reject").header("Authorization", token("graph:read graph:write")))
            .andExpect(status().isOk)

        verify(links).reject("c1")
    }
}
