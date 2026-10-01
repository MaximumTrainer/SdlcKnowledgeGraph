package com.repodatagraph.application

import com.repodatagraph.domain.exception.ServicePrincipalExistsException
import com.repodatagraph.domain.exception.ServicePrincipalNotFoundException
import com.repodatagraph.domain.exception.ServicePrincipalValidationException
import com.repodatagraph.domain.exception.UnknownOwningTeamException
import com.repodatagraph.domain.exception.UserPrincipalRequiredException
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.PrincipalType
import com.repodatagraph.domain.model.ServicePrincipal
import com.repodatagraph.domain.model.ServicePrincipalRegistration
import com.repodatagraph.domain.port.`in`.ServicePrincipalUseCase
import com.repodatagraph.domain.port.out.CurrentPrincipal
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.ServicePrincipalStore
import com.repodatagraph.observability.LogEvents
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * The service principal registry (#115, FR-2 and FR-4).
 *
 * Whether a registered name really is a client of the identity provider is not checked here: that
 * would take the provider's admin API and a credential for it. It does not need to be. A
 * registration only ever lets in a token the provider signed for a client of that name, so one
 * whose client never exists admits nobody, and is visible in the listing for someone to remove.
 *
 * The owner, on the other hand, must be a Team the graph holds, because it is stamped on every write
 * the service makes as the team that answers for it, and a key nothing resolves to answers for nothing.
 */
@Service
class ServicePrincipalService(
    private val store: ServicePrincipalStore,
    private val graphStore: GraphStore,
    private val currentPrincipal: CurrentPrincipal,
    private val clock: Clock,
) : ServicePrincipalUseCase {
    override fun register(registration: ServicePrincipalRegistration): ServicePrincipal {
        val user = requireUser()
        validate(registration)
        if (graphStore.findNode(NodeKey(TEAM, registration.ownedBy)) == null) {
            throw UnknownOwningTeamException(registration.ownedBy)
        }
        if (store.find(registration.name)?.current == true) throw ServicePrincipalExistsException(registration.name)

        // A name registered before and since deregistered starts afresh: a new owner, a new
        // registrar, current from now.
        return store
            .save(
                ServicePrincipal(
                    name = registration.name,
                    ownedBy = registration.ownedBy,
                    description = registration.description?.takeIf { it.isNotBlank() },
                    registeredBy = user,
                    validFrom = Instant.now(clock),
                    kind = registration.kind,
                ),
            ).also { LogEvents.principalRegistered(it.name, it.ownedBy, user) }
    }

    override fun list(): List<ServicePrincipal> = store.findAll()

    override fun deregister(name: String): ServicePrincipal {
        val user = requireUser()
        val existing = store.find(name) ?: throw ServicePrincipalNotFoundException(name)
        if (!existing.current) return existing
        return store
            .save(existing.copy(validTo = Instant.now(clock)))
            .also { LogEvents.principalDeregistered(it.name, user) }
    }

    override fun resolve(clientId: String): ServicePrincipal? = store.find(clientId)?.takeIf { it.current }

    /** The current principal's subject, when it is a person; a service may not manage the registry. */
    private fun requireUser(): String {
        val principal = currentPrincipal.current()
        if (principal.type != PrincipalType.USER) throw UserPrincipalRequiredException()
        return principal.subject
    }

    private fun validate(registration: ServicePrincipalRegistration) {
        if (!CLIENT_ID.matches(registration.name)) {
            throw ServicePrincipalValidationException(
                "name",
                "must be the client id its tokens carry: letters, digits and . _ : @ -, starting with a letter or digit, " +
                    "at most $MAX_NAME characters",
            )
        }
        if (registration.ownedBy.isBlank()) throw ServicePrincipalValidationException("ownedBy", "must be the key of a Team")
    }

    private companion object {
        const val TEAM = "Team"
        const val MAX_NAME = 255

        /**
         * What a client id may look like here. Narrower than what an identity provider allows - no
         * spaces, slashes or URLs - because the name is also a path segment of this API and a key in
         * the graph, and every client this project ships or documents fits it.
         */
        val CLIENT_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:@-]{0,${MAX_NAME - 1}}$")
    }
}
