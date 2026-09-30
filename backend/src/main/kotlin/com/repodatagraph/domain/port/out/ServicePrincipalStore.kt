package com.repodatagraph.domain.port.out

import com.repodatagraph.domain.model.ServicePrincipal

/**
 * Where service principal registrations are kept (#115).
 *
 * A port of its own rather than more of [GraphStore], for the reason [SyncRunStore] is: a
 * registration is read by name on every request a service makes and listed whole, current and ended
 * together, and its validity lives in its provenance rather than in its properties.
 */
interface ServicePrincipalStore {
    /** The registration with this name, current or ended, or null when there has never been one. */
    fun find(name: String): ServicePrincipal?

    /** Every registration, current and ended, in name order. */
    fun findAll(): List<ServicePrincipal>

    /** Creates or replaces the registration with this name, and returns it as saved. */
    fun save(principal: ServicePrincipal): ServicePrincipal
}
