package com.repodatagraph.adapter.`in`.security

import com.repodatagraph.domain.model.Principal
import com.repodatagraph.domain.model.PrincipalType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Instant

/** Who the application is acting for, read from the request's security context (#114, FR-3). */
class SecurityContextPrincipalTest {
    private val principal = SecurityContextPrincipal()

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    private fun signIn(subject: String) {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("preferred_username", "someone-else")
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

    @Test
    fun `a bearer token's subject is the principal, as a user`() {
        signIn("dan")

        assertEquals(Principal("dan", PrincipalType.USER), principal.current())
    }

    @Test
    fun `the subject is used, not a display name the user can change`() {
        signIn("f3b2c1")

        assertEquals("f3b2c1", principal.current().subject)
    }

    @Test
    fun `no authentication at all is anonymous`() {
        assertEquals(Principal.ANONYMOUS, principal.current())
    }

    @Test
    fun `Spring's anonymous token is anonymous too`() {
        SecurityContextHolder.getContext().authentication =
            AnonymousAuthenticationToken("key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))

        assertEquals(Principal.ANONYMOUS, principal.current())
    }
}
