package com.repodatagraph.config

/**
 * Routes that are sent as a POST but only read (#87): a query whose input is a structured body rather
 * than a query string. Exact paths, POST only.
 *
 * One list, read by the three places that decide what a POST may do, so they cannot disagree: the
 * scope policy asks `graph:read` rather than `graph:write` of them, [ReadOnlyGuard] lets them through
 * on a read-only instance, and the anonymous read-only mode answers them for anyone. A route belongs
 * here only if its handler writes nothing at all - being listed is what exempts it from every guard a
 * write meets.
 */
object ReadsOverPost {
    val PATHS: Set<String> =
        setOf(
            // Impact analysis of a change: a repository, paths, a sha and bounds.
            "/api/v1/impact",
            // A context pack (#96): a start node, a template, a budget and an instant.
            "/api/v1/context-pack",
        )
}
