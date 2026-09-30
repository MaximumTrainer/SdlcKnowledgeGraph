package com.repodatagraph.domain.model

import java.time.Instant

/**
 * A connector or an agent the graph knows as a principal of its own (#115, ADR-0005).
 *
 * The identity provider vouches that a token came from a client; this registration is what makes that
 * client someone the graph will act for, and says which team answers for it. A client the provider
 * trusts but nobody registered is refused, so adding a client to the identity provider is not by
 * itself a way in.
 *
 * Deregistering ends the registration rather than deleting it: facts the service wrote still name it,
 * and the record of who it was and who owned it has to outlive it for them to mean anything.
 *
 * @param name the client id its tokens carry (`azp`, or `client_id`); the registration's key
 * @param ownedBy the key of the Team that answers for it, recorded on its writes as onBehalfOfTeam
 * @param registeredBy the subject of the user who registered it
 * @param validFrom when it was registered
 * @param validTo when it was deregistered; null while it is current
 */
data class ServicePrincipal(
    val name: String,
    val ownedBy: String,
    val description: String?,
    val registeredBy: String?,
    val validFrom: Instant,
    val validTo: Instant? = null,
) {
    /** True while the registration lets its client in. */
    val current: Boolean get() = validTo == null

    /** Who this registration's client is while it acts: its name, a service, for its team. */
    fun asPrincipal(): Principal = Principal(name, PrincipalType.SERVICE, onBehalfOfTeam = ownedBy)

    companion object {
        /** The node type a registration is stored as, a meta type of the ontology. */
        const val NODE_TYPE = "ServicePrincipal"

        /** Where registrations are written, the only place they may be. */
        const val API_PATH = "/api/v1/service-principals"
    }
}

/** What a user asks to register: a client id, the Team key that owns it, and what it is for. */
data class ServicePrincipalRegistration(
    val name: String,
    val ownedBy: String,
    val description: String? = null,
)
