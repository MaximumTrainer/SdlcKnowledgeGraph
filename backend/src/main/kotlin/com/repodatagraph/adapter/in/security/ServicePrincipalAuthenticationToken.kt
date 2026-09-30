package com.repodatagraph.adapter.`in`.security

import com.repodatagraph.domain.model.ServicePrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * A client-credentials token the registry vouched for (#115): still the bearer token, so anything
 * that reads the JWT keeps working, now carrying the registration that names the service and its
 * team. [ServicePrincipalGate] puts it in the security context; [SecurityContextPrincipal] reads it.
 */
class ServicePrincipalAuthenticationToken(
    jwt: Jwt,
    val registration: ServicePrincipal,
    authorities: Collection<org.springframework.security.core.GrantedAuthority> = emptyList(),
) : JwtAuthenticationToken(jwt, authorities, registration.name)
