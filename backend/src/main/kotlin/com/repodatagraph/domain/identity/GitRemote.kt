package com.repodatagraph.domain.identity

/**
 * A git remote reduced to the three things that identify it, plus the one form we store.
 *
 * `host/org/name` is the Repository's identity key (docs/ONTOLOGY.md): the same repository named by
 * GitHub, a cloud tag and a CMDB reduces to this, which is what stops one repository becoming three
 * nodes that no traversal can reconcile.
 */
data class GitRemote(
    val host: String,
    val org: String,
    val name: String,
) {
    /** The identity key, and what `GET /api/v1/repositories/by-key` is keyed on. */
    val key: String get() = "$host/$org/$name"

    /** The one form stored on the node, whatever form the caller supplied. */
    val canonicalUrl: String get() = "https://$host/$org/$name"
}
