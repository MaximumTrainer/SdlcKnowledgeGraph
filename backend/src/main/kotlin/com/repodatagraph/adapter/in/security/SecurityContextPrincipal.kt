package com.repodatagraph.adapter.`in`.security

import com.repodatagraph.domain.model.Principal
import com.repodatagraph.domain.model.PrincipalType
import com.repodatagraph.domain.port.out.CurrentPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component

/**
 * The principal a request authenticated as, read from Spring Security's context (#114, FR-3).
 *
 * A user's bearer token's `sub` is the subject: stable for the life of the account, unlike a username
 * a user or an administrator can change. A service principal is its registered name, which is its
 * client id, rather than the `sub` of the service account behind it, which means nothing to a reader
 * of provenance (#115).
 *
 * There is no anonymous principal (#118). A write can only get here with a token: the gate refuses
 * one without, and the anonymous read-only mode refuses every write before it reaches a controller.
 * So nobody authenticated - a public endpoint, a background thread, a token with no subject - means
 * the gate let through something it should not have, and the write fails rather than record a writer
 * nobody can be held to.
 */
@Component
class SecurityContextPrincipal : CurrentPrincipal {
    override fun current(): Principal =
        when (val authentication = SecurityContextHolder.getContext().authentication) {
            // A client the registry vouched for (#115): its registered name, acting for its team.
            is ServicePrincipalAuthenticationToken -> authentication.registration.asPrincipal()
            is JwtAuthenticationToken ->
                authentication.token.subject
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Principal(it, PrincipalType.USER) }
                    ?: unauthenticated()
            else -> unauthenticated()
        }

    private fun unauthenticated(): Nothing = error("a write reached the graph with no authenticated principal")
}
