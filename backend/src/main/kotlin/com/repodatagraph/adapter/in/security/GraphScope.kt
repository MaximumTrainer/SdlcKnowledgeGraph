package com.repodatagraph.adapter.`in`.security

/**
 * The OAuth 2 scopes that decide what a principal may do to the graph (#116, ADR-0005): one to read
 * it and one to change it. Deliberately coarse. Per-source write scopes are AUTH-4, and anything
 * finer than that is policy (#95), not scopes.
 */
enum class GraphScope(
    val value: String,
) {
    READ("graph:read"),
    WRITE("graph:write"),
}

/** What a request must present, as [ScopePolicy] declares it for the request's route family. */
sealed interface RouteRequirement {
    /** Every one of [scopes]. */
    data class Scopes(
        val scopes: Set<GraphScope>,
    ) : RouteRequirement

    /** Whatever the operations in the GraphQL document need ([GraphQlScopes]). */
    data object ByGraphQlOperation : RouteRequirement

    /** No graph scope: the route is on the public allowlist, for [reason]. */
    data class Public(
        val reason: String,
    ) : RouteRequirement
}
