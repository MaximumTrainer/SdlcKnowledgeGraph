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
 * A bearer token's `sub` is the subject: stable for the life of the account, unlike a username a user
 * or an administrator can change. Everything else - the development bypass, a public endpoint, a
 * background thread with no request - is [Principal.ANONYMOUS]. Under authentication a write can
 * only get here with a token, so the anonymous case is the bypass in practice.
 */
@Component
class SecurityContextPrincipal : CurrentPrincipal {
    override fun current(): Principal =
        when (val authentication = SecurityContextHolder.getContext().authentication) {
            is JwtAuthenticationToken ->
                authentication.token.subject
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Principal(it, PrincipalType.USER) }
                    ?: Principal.ANONYMOUS
            else -> Principal.ANONYMOUS
        }
}
