package com.repodatagraph.adapter.`in`.security

import com.repodatagraph.config.AuthProperties
import com.repodatagraph.domain.exception.SourceNotPermittedException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Instant

/**
 * The source check reads the scopes on the token the request authenticated with (#117): a write
 * naming a source needs what [SourceScopes] says, and a refusal carries what it needed and what the
 * token held, for the 403 to report.
 */
class ScopeSourceWriteAuthorizationTest {
    private val enabled = ScopeSourceWriteAuthorization(AuthProperties(disabled = false, issuerUri = "https://issuer.example.test"))

    @AfterEach
    fun clear() {
        SecurityContextHolder.clearContext()
    }

    private fun holding(scope: String) {
        val jwt =
            Jwt
                .withTokenValue("t")
                .header("alg", "RS256")
                .subject("dan")
                .claim("scope", scope)
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

    @Test
    fun `a token holding the source's scope may write as it`() {
        holding("graph:read graph:write graph:write:github")

        assertDoesNotThrow { enabled.authorize("github") }
        assertDoesNotThrow { enabled.authorize("manual") }
    }

    @Test
    fun `a token without the source's scope is refused, with what it needed and the graph scopes it held`() {
        holding("openid graph:write graph:read graph:write:github")

        val refusal = assertThrows<SourceNotPermittedException> { enabled.authorize("aws") }

        assertEquals("aws", refusal.sourceSystem)
        assertEquals(listOf("graph:write", "graph:write:aws"), refusal.required)
        assertEquals(listOf("graph:read", "graph:write", "graph:write:github"), refusal.held)
    }

    @Test
    fun `scopes in an scp array count like the scope string`() {
        val jwt =
            Jwt
                .withTokenValue("t")
                .header("alg", "RS256")
                .subject("svc")
                .claim("scp", listOf("graph:write", "graph:write:servicenow"))
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)

        assertDoesNotThrow { enabled.authorize("servicenow") }
    }

    @Test
    fun `with authentication on, a write with no token holds nothing, so even manual is refused`() {
        val refusal = assertThrows<SourceNotPermittedException> { enabled.authorize("manual") }

        assertEquals(listOf("graph:write"), refusal.required)
        assertEquals(emptyList<String>(), refusal.held)
    }

    @Test
    fun `the development bypass checks no source scopes`() {
        val bypass = ScopeSourceWriteAuthorization(AuthProperties(disabled = true))

        assertDoesNotThrow { bypass.authorize("aws") }
    }
}
