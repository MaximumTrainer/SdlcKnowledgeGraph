package com.repodatagraph.adapter.`in`.security

import com.repodatagraph.config.PolicyProperties
import com.repodatagraph.domain.model.ServicePrincipalKind
import com.repodatagraph.domain.policy.Subject
import com.repodatagraph.domain.policy.SubjectKind
import com.repodatagraph.domain.port.out.CurrentSubject
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component

/**
 * The subject the policy judges (#30 FR1, FR7), read from the authentication the request carries.
 *
 * - A user is their token's `sub`; a service principal its registered name, and an agent when it was
 *   registered as one. Provenance names them the same way ([SecurityContextPrincipal]).
 * - The scopes are the token's graph scopes, read as [GrantedScopes] reads them for the scope check.
 * - The roles are the claim [PolicyProperties.rolesClaim] names, a list or a space- or
 *   comma-separated string. A token without the claim has no roles at all - not an empty list - and
 *   is judged by its scopes alone.
 * - The teams are the claim [PolicyProperties.groupsClaim] names, each read as a team key: a leading
 *   `/` (Keycloak's group path) dropped and lower-cased, as a Team's key is. A service principal also
 *   belongs to the team that owns it.
 *
 * No authentication at all is the anonymous reader of an instance with no identity provider.
 */
object PolicySubjects {
    private val SEPARATORS = Regex("[\\s,]+")

    fun of(
        authentication: Authentication?,
        properties: PolicyProperties,
    ): Subject =
        when (authentication) {
            is ServicePrincipalAuthenticationToken ->
                Subject(
                    id = authentication.registration.name,
                    kind = if (authentication.registration.kind == ServicePrincipalKind.AGENT) SubjectKind.AGENT else SubjectKind.SERVICE,
                    scopes = scopesOf(authentication),
                    roles = rolesOf(authentication, properties),
                    teams = teamsOf(authentication, properties) + authentication.registration.ownedBy.lowercase(),
                )
            is JwtAuthenticationToken ->
                Subject(
                    id = authentication.token.subject?.takeIf { it.isNotBlank() } ?: UNKNOWN,
                    kind = SubjectKind.USER,
                    scopes = scopesOf(authentication),
                    roles = rolesOf(authentication, properties),
                    teams = teamsOf(authentication, properties),
                )
            else -> Subject.ANONYMOUS
        }

    private fun scopesOf(authentication: JwtAuthenticationToken): Set<String> =
        GrantedScopes.graphScopesOf(authentication.token.claims).toSortedSet()

    private fun rolesOf(
        authentication: JwtAuthenticationToken,
        properties: PolicyProperties,
    ): List<String>? {
        val claims = authentication.token.claims
        if (!claims.containsKey(properties.rolesClaim)) return null
        return values(claims[properties.rolesClaim]).distinct()
    }

    private fun teamsOf(
        authentication: JwtAuthenticationToken,
        properties: PolicyProperties,
    ): Set<String> =
        values(authentication.token.claims[properties.groupsClaim])
            .map { it.removePrefix("/").lowercase() }
            .filter { it.isNotBlank() }
            .toSortedSet()

    private fun values(claim: Any?): List<String> =
        when (claim) {
            is String -> claim.split(SEPARATORS).filter { it.isNotEmpty() }
            is Collection<*> -> claim.filterNotNull().flatMap { values(it.toString()) }
            else -> emptyList()
        }

    private const val UNKNOWN = "unknown"
}

/** The subject of the request being served, for the application's own questions to the policy. */
@Component
class SecurityContextSubject(
    private val properties: PolicyProperties,
) : CurrentSubject {
    override fun current(): Subject = PolicySubjects.of(SecurityContextHolder.getContext().authentication, properties)
}
