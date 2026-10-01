package com.repodatagraph.adapter.`in`.security

import com.repodatagraph.config.PolicyProperties
import com.repodatagraph.domain.model.ServicePrincipal
import com.repodatagraph.domain.model.ServicePrincipalKind
import com.repodatagraph.domain.policy.Subject
import com.repodatagraph.domain.policy.SubjectKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Instant

/** The subject the policy judges (#30 FR1, FR7), read from the request's token. */
class PolicySubjectsTest {
    private val properties = PolicyProperties()

    private fun jwt(vararg claims: Pair<String, Any>): Jwt =
        Jwt
            .withTokenValue("t")
            .header("alg", "RS256")
            .subject("dan")
            .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
            .expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
            .claims { it.putAll(claims) }
            .build()

    private fun registration(
        name: String,
        ownedBy: String,
        kind: ServicePrincipalKind = ServicePrincipalKind.SERVICE,
    ) = ServicePrincipal(name, ownedBy, null, "dan", Instant.parse("2026-01-01T00:00:00Z"), kind = kind)

    @Test
    fun `a user without a roles claim has no roles at all, not an empty list`() {
        val subject = PolicySubjects.of(JwtAuthenticationToken(jwt("scope" to "graph:read openid")), properties)

        assertEquals("dan", subject.id)
        assertEquals(SubjectKind.USER, subject.kind)
        assertEquals(setOf("graph:read"), subject.scopes)
        assertNull(subject.roles)
    }

    @Test
    fun `roles are read from the configured claim, as a list or a string`() {
        val listed = PolicySubjects.of(JwtAuthenticationToken(jwt("sdlc_roles" to listOf("viewer", "curator"))), properties)
        val spaced = PolicySubjects.of(JwtAuthenticationToken(jwt("sdlc_roles" to "viewer, curator")), properties)
        val renamed = PolicySubjects.of(JwtAuthenticationToken(jwt("roles" to "admin")), PolicyProperties(rolesClaim = "roles"))

        assertEquals(listOf("viewer", "curator"), listed.roles)
        assertEquals(listOf("viewer", "curator"), spaced.roles)
        assertEquals(listOf("admin"), renamed.roles)
    }

    @Test
    fun `teams are the groups claim, read as team keys`() {
        val subject = PolicySubjects.of(JwtAuthenticationToken(jwt("groups" to listOf("/Platform", "payments"))), properties)

        assertEquals(setOf("platform", "payments"), subject.teams)
    }

    @Test
    fun `a service principal is its registered name, an agent when registered as one, of its owning team`() {
        val token = jwt("scope" to "graph:read", "azp" to "incident-bot")
        val agent =
            PolicySubjects.of(
                ServicePrincipalAuthenticationToken(token, registration("incident-bot", "SRE", ServicePrincipalKind.AGENT)),
                properties,
            )
        val service =
            PolicySubjects.of(ServicePrincipalAuthenticationToken(token, registration("github-connector", "platform")), properties)

        assertEquals(Subject("incident-bot", SubjectKind.AGENT, setOf("graph:read"), null, setOf("sre")), agent)
        assertEquals(SubjectKind.SERVICE, service.kind)
    }

    @Test
    fun `no authentication is the anonymous reader`() {
        assertEquals(Subject.ANONYMOUS, PolicySubjects.of(null, properties))
    }
}
