package com.repodatagraph.domain.port.out

import com.repodatagraph.domain.policy.Subject

/**
 * Who the current request is for, as the policy judges them (#30 FR1, FR7): kind, scopes, roles and
 * teams. The application asks this rather than reading a security context, as it does for
 * [CurrentPrincipal].
 */
fun interface CurrentSubject {
    fun current(): Subject
}
