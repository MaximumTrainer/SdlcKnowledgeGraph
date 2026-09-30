package com.repodatagraph.adapter.`in`.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.repodatagraph.domain.port.`in`.ServicePrincipalUseCase
import com.repodatagraph.observability.LogEvents
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.filter.OncePerRequestFilter

/**
 * The second half of the gate for machines (#115, FR-2): a token the identity provider signed for a
 * client is only let in once a user has registered that client as a service principal, and while
 * the registration is current.
 *
 * Runs after the bearer token has been validated, on every request that presented one - so on
 * everything under /api and /graphql except the ingest endpoints, whose token is never decoded. A
 * user's token passes untouched and is never looked up. A service's is either refused with a 403
 * naming its client, or replaced in the security context by one carrying its registration, which
 * is what every write it makes is attributed to.
 *
 * 403 rather than 401: the token is good, and signing in again would get the same token. What is
 * missing is a decision only a user can make.
 *
 * Not a Spring bean, so the servlet container does not also run it outside the security chain.
 */
class ServicePrincipalGate(
    private val registry: ServicePrincipalUseCase,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val authentication = SecurityContextHolder.getContext().authentication
        val clientId =
            (authentication as? JwtAuthenticationToken)
                ?.takeUnless { it is ServicePrincipalAuthenticationToken }
                ?.let { ServiceTokens.clientIdOf(it.token.claims) }
        if (authentication !is JwtAuthenticationToken || clientId == null) {
            chain.doFilter(request, response)
            return
        }

        val registration = registry.resolve(clientId)
        if (registration == null) {
            LogEvents.principalRefused(clientId)
            refuse(response, clientId)
            return
        }

        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = ServicePrincipalAuthenticationToken(authentication.token, registration, authentication.authorities)
        SecurityContextHolder.setContext(context)
        chain.doFilter(request, response)
    }

    private fun refuse(
        response: HttpServletResponse,
        clientId: String,
    ) {
        response.status = HttpStatus.FORBIDDEN.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(response.outputStream, mapOf("error" to REFUSAL, "clientId" to clientId))
    }

    companion object {
        const val REFUSAL = "unregistered service principal"
    }
}
