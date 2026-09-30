package com.repodatagraph.domain.exception

/** A registration field is missing or cannot mean anything, named so the caller knows what to fix. */
class ServicePrincipalValidationException(
    val field: String,
    override val message: String,
) : RuntimeException(message)

/** The owner named is not the key of a Team the graph holds (#115). */
class UnknownOwningTeamException(
    val ownedBy: String,
) : RuntimeException("no team '$ownedBy'")

/** The name already has a current registration. A deregistered one may be registered again. */
class ServicePrincipalExistsException(
    val name: String,
) : RuntimeException("service principal '$name' is already registered")

/** No registration, current or ended, has this name. */
class ServicePrincipalNotFoundException(
    val name: String,
) : RuntimeException("no service principal '$name'")

/**
 * Registering and deregistering are a person's decision (#115, FR-2): a service cannot make another
 * client a principal, nor keep itself one.
 */
class UserPrincipalRequiredException : RuntimeException("only a user may manage service principals")
