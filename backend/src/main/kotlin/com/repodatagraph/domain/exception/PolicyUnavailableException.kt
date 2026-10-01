package com.repodatagraph.domain.exception

/**
 * The policy could not be evaluated. The request is refused with 503 `policy_unavailable`, reads as
 * well as writes: an answer the policy has not checked is not one the API gives (ADR-0020).
 */
class PolicyUnavailableException(
    val entrypoint: String,
    cause: Throwable,
) : RuntimeException("the policy could not be evaluated at $entrypoint", cause)
