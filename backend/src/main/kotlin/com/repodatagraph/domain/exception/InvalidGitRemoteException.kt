package com.repodatagraph.domain.exception

/**
 * Thrown when a string offered as a git remote is not one.
 *
 * Separate from [com.repodatagraph.domain.ontology.IdentityResolutionException] because the two mean
 * different things to a caller: this one says the input is wrong and can be corrected, which is a
 * 400. Identity resolution failing says the node type has no rule, which is ours to fix.
 */
class InvalidGitRemoteException(
    val reason: String,
    val input: String,
) : IllegalArgumentException("'$input' is not a git remote: $reason")
