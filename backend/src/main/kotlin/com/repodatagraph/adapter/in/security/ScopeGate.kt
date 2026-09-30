package com.repodatagraph.adapter.`in`.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.repodatagraph.config.ReplayedBodyRequest
import com.repodatagraph.observability.LogEvents
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.UrlPathHelper

/**
 * Least privilege (#116): a request goes ahead only when its token holds every graph scope
 * [ScopePolicy] declares for it, and is otherwise refused with
 *
 * ```
 * 403 {"error": "insufficient scope", "required": ["graph:write"], "held": ["graph:read"]}
 * ```
 *
 * `required` is everything the request needs and `held` the graph scopes the token carries, both
 * sorted, so a caller can see what to ask its identity provider for. The `WWW-Authenticate` header
 * says the same in RFC 6750's terms.
 *
 * GraphQL is refused the same way, as an HTTP 403 before the document runs, rather than as an error
 * inside a 200: a caller handles one refusal whichever API it uses, and a mutation it may not make
 * is never partly executed.
 *
 * Runs after [ServicePrincipalGate], so the order of refusals is fixed: no valid token is a 401,
 * an unregistered client a 403 naming it, and only a principal the API knows has its scopes
 * compared. A request without a bearer token passes untouched: it is either on the public allowlist
 * or about to be refused with a 401.
 *
 * Not a Spring bean, so the servlet container does not also run it outside the security chain.
 */
class ScopeGate(
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val authentication = SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken
        if (authentication == null) {
            chain.doFilter(request, response)
            return
        }

        // Decoded and normalised the way Spring MVC finds the handler, so the policy judges the
        // route that will actually answer.
        val path = PATHS.getPathWithinApplication(request)
        var forwarded = request
        val required =
            when (val requirement = ScopePolicy.requirementFor(request.method, path)) {
                is RouteRequirement.Scopes -> requirement.scopes
                RouteRequirement.ByGraphQlOperation -> {
                    val (inspected, document) = graphQlDocument(request)
                    forwarded = inspected
                    GraphQlScopes.required(document)
                }
                is RouteRequirement.Public, null -> emptySet()
            }

        val held = GrantedScopes.graphScopesOf(authentication.token.claims)
        if (GrantedScopes.missing(required, held).isEmpty()) {
            chain.doFilter(forwarded, response)
            return
        }
        val needed = required.map { it.value }.sorted()
        LogEvents.scopeRefused(principalOf(authentication), request.method, needed, held)
        refuse(response, needed, held)
    }

    /**
     * The document a GraphQL request carries, and the request to hand on in its place: a POST's body
     * has to be read to be inspected, so the handler is given the same bytes again. Null when there
     * is no document to read, the body is not the JSON Spring GraphQL accepts, or it is too large to
     * inspect - all of which [GraphQlScopes] treats as needing both scopes.
     */
    private fun graphQlDocument(request: HttpServletRequest): Pair<HttpServletRequest, String?> {
        if (request.method != "POST") {
            val upgrade = request.getHeaders("Upgrade").toList().any { it.contains("websocket", ignoreCase = true) }
            return request to (if (upgrade) null else request.getParameter("query"))
        }
        val read = request.inputStream.readNBytes(MAX_INSPECTED_BODY_BYTES + 1)
        return if (read.size > MAX_INSPECTED_BODY_BYTES) {
            ReplayedBodyRequest(request, read, request.inputStream) to null
        } else {
            ReplayedBodyRequest(request, read) to queryIn(read)
        }
    }

    private fun queryIn(body: ByteArray): String? =
        runCatching { objectMapper.readTree(body) }
            .getOrNull()
            ?.takeIf { it.isObject }
            ?.path("query")
            ?.takeIf { it.isTextual }
            ?.asText()

    /** Who was refused, as provenance would name them: a service's registered name, a user's subject. */
    private fun principalOf(authentication: JwtAuthenticationToken): String =
        (authentication as? ServicePrincipalAuthenticationToken)?.registration?.name
            ?: authentication.token.subject
            ?: UNKNOWN

    private fun refuse(
        response: HttpServletResponse,
        required: List<String>,
        held: List<String>,
    ) {
        response.status = HttpStatus.FORBIDDEN.value()
        response.setHeader("WWW-Authenticate", "Bearer error=\"insufficient_scope\", scope=\"${required.joinToString(" ")}\"")
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(response.outputStream, mapOf("error" to REFUSAL, "required" to required, "held" to held))
    }

    companion object {
        const val REFUSAL = "insufficient scope"

        /** A GraphQL body larger than this is not read into memory to be inspected; it needs both scopes. */
        const val MAX_INSPECTED_BODY_BYTES = 256 * 1024

        private const val UNKNOWN = "unknown"
        private val PATHS = UrlPathHelper.defaultInstance
    }
}
