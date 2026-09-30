package com.repodatagraph.domain.port.out

import com.repodatagraph.domain.model.Principal

/**
 * Who the current operation is being done for. The application asks this rather than reading a
 * security context, so the rule that a write records its writer does not depend on how the caller
 * authenticated.
 */
fun interface CurrentPrincipal {
    fun current(): Principal
}
