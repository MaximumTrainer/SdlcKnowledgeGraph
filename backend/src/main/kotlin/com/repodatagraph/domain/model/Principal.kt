package com.repodatagraph.domain.model

/**
 * Who the application is acting for (ADR-0005): the subject an identity provider vouched for, and
 * what kind of caller that is.
 *
 * Only people exist for now (#114). Connectors and agents become principals of their own kinds in
 * AUTH-2; until then a fact a connector wrote names no principal at all rather than a made-up one.
 */
data class Principal(
    val subject: String,
    val type: PrincipalType,
) {
    init {
        require(subject.isNotBlank()) { "a principal needs a subject" }
    }

    companion object {
        /** Whoever calls an instance running with the development bypass (AUTH_DISABLED=true). */
        val ANONYMOUS = Principal("anonymous", PrincipalType.USER)
    }
}

/** The kinds of principal, published under their wire names in provenance. */
enum class PrincipalType(
    val wireName: String,
) {
    USER("user"),
}
