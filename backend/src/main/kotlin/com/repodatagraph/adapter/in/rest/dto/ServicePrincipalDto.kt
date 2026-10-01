package com.repodatagraph.adapter.`in`.rest.dto

import com.repodatagraph.domain.model.ServicePrincipal

/**
 * A registration a user asks for (#115, FR-2). Every field is nullable so that a missing one is
 * refused with a 400 naming it rather than a deserialisation failure that names nothing.
 */
data class ServicePrincipalRequest(
    val name: String? = null,
    val ownedBy: String? = null,
    val description: String? = null,
    /** `service` (the default) or `agent` (#30). */
    val kind: String? = null,
)

/**
 * A registration as the API shows it (#115, FR-4): times are ISO-8601 instants, and `validTo` is
 * null while the registration is current.
 */
data class ServicePrincipalResponse(
    val name: String,
    val ownedBy: String,
    val description: String?,
    val registeredBy: String?,
    val validFrom: String,
    val validTo: String?,
    /** `service` or `agent` (#30). */
    val kind: String,
) {
    companion object {
        fun from(principal: ServicePrincipal) =
            ServicePrincipalResponse(
                name = principal.name,
                ownedBy = principal.ownedBy,
                description = principal.description,
                registeredBy = principal.registeredBy,
                validFrom = principal.validFrom.toString(),
                validTo = principal.validTo?.toString(),
                kind = principal.kind.wireName,
            )
    }
}

/** Every registration, current and ended, in name order. */
data class ServicePrincipalListResponse(
    val items: List<ServicePrincipalResponse>,
)
