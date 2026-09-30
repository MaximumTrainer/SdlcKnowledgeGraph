package com.repodatagraph.domain.port.`in`

import com.repodatagraph.domain.model.ServicePrincipal
import com.repodatagraph.domain.model.ServicePrincipalRegistration

/** The registry that turns an identity provider's client into a principal the graph knows (#115). */
interface ServicePrincipalUseCase {
    /**
     * Registers a client, current from now, recorded as registered by the current user.
     *
     * @throws com.repodatagraph.domain.exception.UserPrincipalRequiredException if a service asks
     * @throws com.repodatagraph.domain.exception.ServicePrincipalValidationException for a name that
     *   cannot be a client id, or a blank owner
     * @throws com.repodatagraph.domain.exception.UnknownOwningTeamException if no Team has that key
     * @throws com.repodatagraph.domain.exception.ServicePrincipalExistsException if the name already
     *   has a current registration
     */
    fun register(registration: ServicePrincipalRegistration): ServicePrincipal

    /** Every registration, current and ended, in name order. */
    fun list(): List<ServicePrincipal>

    /**
     * Ends a registration now, keeping the record. Ending one already ended changes nothing.
     *
     * @throws com.repodatagraph.domain.exception.UserPrincipalRequiredException if a service asks
     * @throws com.repodatagraph.domain.exception.ServicePrincipalNotFoundException if never registered
     */
    fun deregister(name: String): ServicePrincipal

    /** The current registration for a client id, or null when it has none - never, or no longer. */
    fun resolve(clientId: String): ServicePrincipal?
}
