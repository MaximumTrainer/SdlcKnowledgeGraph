package com.repodatagraph.domain.port.out

/**
 * Whether the current principal may state facts as a source system (#117). The application asks
 * this rather than reading scopes, so the rule that a connector asserts only its own system's facts
 * does not depend on how the principal proved what it may do.
 */
fun interface SourceWriteAuthorization {
    /** @throws com.repodatagraph.domain.exception.SourceNotPermittedException when it may not */
    fun authorize(sourceSystem: String)
}
