package com.repodatagraph.adapter.`in`.security

/**
 * Tells a machine's token from a person's, and names the client that holds it (#115).
 *
 * A token is a service's when no user took part in issuing it - the OAuth 2 client-credentials
 * grant. Two marks are recognised, and nothing else:
 *
 * - Keycloak issues such a token for the client's service account, whose `preferred_username` is
 *   `service-account-<client id>`. Keycloak reserves that prefix for service accounts, so no person
 *   can sign in under it.
 * - An issuer following RFC 9068 makes the token's subject the client id itself when no resource
 *   owner is involved.
 *
 * `azp` or `client_id` alone is not enough: a user's token names the client they signed in through
 * too (Keycloak's `azp` is `sdlc-ui` for everyone who signs in to the web interface, and RFC 9068
 * puts `client_id` in every token).
 *
 * The client is `azp`, then `client_id`, then - for a Keycloak token carrying neither - the service
 * account's name without its prefix.
 */
object ServiceTokens {
    const val SERVICE_ACCOUNT_PREFIX = "service-account-"

    /** The client id of a service's token, or null when the token is a user's. */
    fun clientIdOf(claims: Map<String, Any?>): String? {
        val client = CLIENT_CLAIMS.firstNotNullOfOrNull { claim -> claims[claim]?.toString()?.takeIf { it.isNotBlank() } }
        val username = claims["preferred_username"]?.toString()
        return when {
            username != null && username.startsWith(SERVICE_ACCOUNT_PREFIX) ->
                client ?: username.removePrefix(SERVICE_ACCOUNT_PREFIX).takeIf { it.isNotBlank() }
            client != null && claims["sub"]?.toString() == client -> client
            else -> null
        }
    }

    private val CLIENT_CLAIMS = listOf("azp", "client_id")
}
