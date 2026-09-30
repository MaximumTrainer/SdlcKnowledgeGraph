package com.repodatagraph.adapter.`in`.security

import com.repodatagraph.domain.exception.SourceNotPermittedException
import com.repodatagraph.domain.port.out.SourceWriteAuthorization
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component

/**
 * Whether the principal behind the current request may state facts as a source system (#117), read
 * from the graph scopes on the token it authenticated with, as [ScopeGate] reads them.
 *
 * A write can only get here with a token: the anonymous read-only mode refuses every write before it
 * reaches a controller (#118). One that somehow has none holds no scope, and is refused rather than
 * waved through.
 *
 * A refusal is [SourceNotPermittedException], which [ScopeRefusalAdvice] answers with the 403 every
 * missing scope gets.
 */
@Component
class ScopeSourceWriteAuthorization : SourceWriteAuthorization {
    override fun authorize(sourceSystem: String) {
        val authentication = SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken
        val held = authentication?.let { GrantedScopes.graphScopesOf(it.token.claims) }.orEmpty()
        val required = SourceScopes.requiredFor(sourceSystem)
        if (!held.containsAll(required)) throw SourceNotPermittedException(sourceSystem, required, held)
    }
}
