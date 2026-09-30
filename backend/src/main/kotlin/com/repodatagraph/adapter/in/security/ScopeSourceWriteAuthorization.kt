package com.repodatagraph.adapter.`in`.security

import com.repodatagraph.config.AuthProperties
import com.repodatagraph.domain.exception.SourceNotPermittedException
import com.repodatagraph.domain.port.out.SourceWriteAuthorization
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component

/**
 * Whether the principal behind the current request may state facts as a source system (#117), read
 * from the graph scopes on the token it authenticated with, as [ScopeGate] reads them.
 *
 * With the development bypass (AUTH_DISABLED=true) no scopes are checked, as none are anywhere else.
 * With authentication on, a write can only get here with a token; one that somehow has none holds no
 * scope, and is refused rather than waved through.
 *
 * A refusal is [SourceNotPermittedException], which [ScopeRefusalAdvice] answers with the 403 every
 * missing scope gets.
 */
@Component
class ScopeSourceWriteAuthorization(
    private val auth: AuthProperties,
) : SourceWriteAuthorization {
    override fun authorize(sourceSystem: String) {
        if (auth.disabled) return
        val authentication = SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken
        val held = authentication?.let { GrantedScopes.graphScopesOf(it.token.claims) }.orEmpty()
        val required = SourceScopes.requiredFor(sourceSystem)
        if (!held.containsAll(required)) throw SourceNotPermittedException(sourceSystem, required, held)
    }
}
