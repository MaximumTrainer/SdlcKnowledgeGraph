package com.repodatagraph.adapter.`in`.security

import com.repodatagraph.config.ReadsOverPost
import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser

/**
 * What every request needs (#116, FR-1 to FR-4), declared once per route family rather than on each
 * handler, so a new endpoint is covered the moment it is mapped under a family and a reviewer reads
 * the whole policy in one place. `ScopePolicyCoverageTest` walks every mapped handler and fails while
 * one falls outside every family here.
 *
 * - Everything under `/api/v1` needs `graph:read` to read (GET, HEAD) and `graph:write` to change
 *   anything (POST, PUT, PATCH, DELETE). That includes the service principal registry, which is
 *   additionally for users only (#115). Changing anything under `/api/v1/lifecycle` - applying
 *   ontology migrations, running the archive - needs `graph:admin` as well (#33). The exception is [ReadsOverPost]: a query sent as a POST
 *   because its input is a body, which reads only and so needs `graph:read` (#87).
 * - `/graphql` needs what the operations in its document need: `graph:read` for a query,
 *   `graph:write` for a mutation, both for a document holding both ([GraphQlScopes]).
 * - The [PUBLIC] allowlist needs no graph scope, and most of it no token either ([SecurityConfig]
 *   permits it). Each entry says why.
 *
 * The public families are checked first, so the ontology is public although it is also under
 * `/api/v1`. A path no family covers - an actuator endpoint other than the public ones, the GraphiQL
 * page - still needs a valid token, as it did before scopes; it just needs no particular scope.
 */
object ScopePolicy {
    /** Some methods (every method when empty) on some path patterns, and what they need. */
    class Family(
        val methods: Set<String>,
        val patterns: List<String>,
        val requirement: RouteRequirement,
    ) {
        private val parsed: List<PathPattern> = patterns.map(PathPatternParser.defaultInstance::parse)

        fun matches(
            method: String,
            path: String,
        ): Boolean {
            if (methods.isNotEmpty() && method !in methods) return false
            val container = PathContainer.parsePath(path)
            return parsed.any { it.matches(container) }
        }
    }

    private val READS = setOf("GET", "HEAD")
    private val WRITES = setOf("POST", "PUT", "PATCH", "DELETE")

    /** Answered without a graph scope, and - except for a token presented anyway - without a token. */
    val PUBLIC: List<Family> =
        listOf(
            Family(
                emptySet(),
                listOf("/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus"),
                RouteRequirement.Public("the platform's probes and the scraper hold no token (docs/DEPLOYMENT.md D1, D2, D9)"),
            ),
            Family(
                emptySet(),
                listOf("/api-docs", "/api-docs/**", "/api-docs.yaml", "/swagger-ui.html", "/swagger-ui/**"),
                RouteRequirement.Public("the API's description, not the data in it"),
            ),
            Family(
                emptySet(),
                listOf("/error"),
                RouteRequirement.Public("where the servlet container forwards a failure of a request already let in"),
            ),
            Family(
                READS,
                listOf("/api/v1/ontology", "/api/v1/ontology/**"),
                RouteRequirement.Public("the model, not the data in it: any caller may discover the schema (ADR-0005, #116 FR-3)"),
            ),
            Family(
                setOf("POST"),
                listOf("/api/v1/ingest/**"),
                RouteRequirement.Public("guarded by its own ingest token, never decoded as a JWT (docs/DEPLOYMENT.md D6)"),
            ),
            Family(
                setOf("POST"),
                listOf("/api/v1/webhooks/*"),
                RouteRequirement.Public("guarded by the sender's signature (ADR-0005)"),
            ),
        )

    /** The route families that need a graph scope. */
    val SCOPED: List<Family> =
        listOf(
            Family(READS, listOf("/api/v1/**"), RouteRequirement.Scopes(setOf(GraphScope.READ))),
            // Before the writes: the first family that matches decides.
            Family(setOf("POST"), ReadsOverPost.PATHS.toList(), RouteRequirement.Scopes(setOf(GraphScope.READ))),
            // Applying migrations and running the archive change the graph as a whole (#33).
            Family(WRITES, listOf("/api/v1/lifecycle/**"), RouteRequirement.Scopes(setOf(GraphScope.WRITE, GraphScope.ADMIN))),
            Family(WRITES, listOf("/api/v1/**"), RouteRequirement.Scopes(setOf(GraphScope.WRITE))),
            Family(setOf("GET", "POST"), listOf("/graphql"), RouteRequirement.ByGraphQlOperation),
        )

    /** What a [method] request to [path] needs, or null when no family declares it. */
    fun requirementFor(
        method: String,
        path: String,
    ): RouteRequirement? {
        val normalised = method.uppercase()
        return (PUBLIC + SCOPED).firstOrNull { it.matches(normalised, path) }?.requirement
    }
}
