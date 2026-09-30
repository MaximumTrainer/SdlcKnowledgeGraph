package com.repodatagraph.domain.model

/**
 * Who the application is acting for (ADR-0005): the subject an identity provider vouched for, and
 * what kind of caller that is.
 *
 * A person is a user (#114), named by their token's subject. A connector or an agent is a service
 * (#115), named by its registration and acting for the team that owns it, [onBehalfOfTeam]. A fact
 * a scheduled connector run wrote still names no principal: that run is not a request anyone made.
 * There is no anonymous principal (#118): a write through the API always has a writer it can name.
 */
data class Principal(
    val subject: String,
    val type: PrincipalType,
    val onBehalfOfTeam: String? = null,
) {
    init {
        require(subject.isNotBlank()) { "a principal needs a subject" }
    }
}

/** The kinds of principal, published under their wire names in provenance. */
enum class PrincipalType(
    val wireName: String,
) {
    USER("user"),
    SERVICE("service"),
}
