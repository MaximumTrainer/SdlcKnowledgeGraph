package com.repodatagraph.adapter.`in`.security

import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * The one refusal for a token without a scope a request needs (#116, #117), whether the gate finds
 * it missing before the request runs or the application finds a write's source needs a scope the
 * token lacks:
 *
 * ```
 * 403 {"error": "insufficient scope", "required": [...], "held": [...]}
 * WWW-Authenticate: Bearer error="insufficient_scope", scope="..."
 * ```
 *
 * plus the `scope.refused` security event. Built here so the two places that refuse cannot drift.
 */
object InsufficientScope {
    const val REFUSAL = "insufficient scope"
    const val CHALLENGE_HEADER = "WWW-Authenticate"

    private const val UNKNOWN = "unknown"

    /** The body: what the request needed and the graph scopes the token held, each sorted. */
    fun body(
        required: List<String>,
        held: List<String>,
    ): Map<String, Any> = mapOf("error" to REFUSAL, "required" to required.sorted(), "held" to held.sorted())

    /** RFC 6750's challenge, naming the scopes the request needed. */
    fun challenge(required: List<String>): String = "Bearer error=\"insufficient_scope\", scope=\"${required.sorted().joinToString(" ")}\""

    /** Who was refused, as provenance would name them: a service's registered name, a user's subject. */
    fun principalOf(authentication: Authentication?): String =
        (authentication as? ServicePrincipalAuthenticationToken)?.registration?.name
            ?: (authentication as? JwtAuthenticationToken)?.token?.subject
            ?: UNKNOWN
}
