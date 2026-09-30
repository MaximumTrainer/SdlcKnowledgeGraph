package com.repodatagraph.adapter.`in`.security

/**
 * The graph scopes a token holds (#116).
 *
 * OAuth 2 puts a token's scopes in `scope`, one space-separated string (RFC 6749, RFC 9068), which
 * is what Keycloak issues; some issuers (Entra ID, Okta) use `scp`, an array. Both are read, and a
 * token carrying both holds the union. Only scopes named `graph:...` are kept, because the rest
 * (`openid`, `profile`, `email`) mean nothing to the API and a refusal should not list them.
 */
object GrantedScopes {
    private const val PREFIX = "graph:"
    private val CLAIMS = listOf("scope", "scp")
    private val WHITESPACE = Regex("\\s+")

    /** The graph scopes in [claims], sorted, each once. */
    fun graphScopesOf(claims: Map<String, Any?>): List<String> =
        CLAIMS
            .flatMap { values(claims[it]) }
            .filter { it.startsWith(PREFIX) && it.length > PREFIX.length }
            .distinct()
            .sorted()

    /** The scopes of [required] that [held] lacks: empty when the token may go ahead. */
    fun missing(
        required: Set<GraphScope>,
        held: Collection<String>,
    ): Set<GraphScope> = required.filterTo(linkedSetOf()) { it.value !in held }

    private fun values(claim: Any?): List<String> =
        when (claim) {
            is String -> claim.split(WHITESPACE).filter { it.isNotEmpty() }
            is Collection<*> -> claim.filterNotNull().flatMap { values(it.toString()) }
            else -> emptyList()
        }
}
