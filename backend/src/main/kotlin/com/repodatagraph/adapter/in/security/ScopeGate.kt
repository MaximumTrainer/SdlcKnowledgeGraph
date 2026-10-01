package com.repodatagraph.adapter.`in`.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.repodatagraph.config.AuthMode
import com.repodatagraph.config.PolicyProperties
import com.repodatagraph.config.ReplayedBodyRequest
import com.repodatagraph.domain.exception.PolicyUnavailableException
import com.repodatagraph.domain.policy.AuthzAction
import com.repodatagraph.domain.policy.AuthzRequest
import com.repodatagraph.domain.policy.Decision
import com.repodatagraph.domain.policy.Subject
import com.repodatagraph.domain.policy.decideWithOwnership
import com.repodatagraph.domain.port.`in`.ResourceOwners
import com.repodatagraph.domain.port.out.PolicyDecisionPoint
import com.repodatagraph.observability.LogEvents
import com.repodatagraph.observability.PolicyMetrics
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
 * Every request under the API is put to the authorisation policy (#30 FR5, #95 FR-1, ADR-0020) and
 * goes ahead only when it allows it.
 *
 * The question is a subject ([PolicySubjects]), an action and a resource ([RouteAuthz]), asked once
 * for each thing the request does - a GraphQL document holding a query and a mutation is asked about
 * both. The shipped policy grants exactly what the scopes of #116 grant, so a refusal for want of a
 * scope is the refusal the API has always given:
 *
 * ```
 * 403 {"error": "insufficient scope", "required": ["graph:write"], "held": ["graph:read"]}
 * ```
 *
 * `required` is everything the request needs and `held` the graph scopes the token carries, both
 * sorted, with RFC 6750's `WWW-Authenticate` challenge. A refusal by any other rule - a role, an
 * agent's limits, a type above the caller's clearance - names the rule and why ([PolicyRefusal]):
 *
 * ```
 * 403 {"error": "policy denied", "policy": "agents", "reason": "agents may not perform admin actions"}
 * ```
 *
 * A policy that cannot be evaluated refuses the request, read or write, with
 * `503 {"error": "policy_unavailable"}`: nothing is answered unchecked.
 *
 * GraphQL is refused before the document runs, rather than as an error inside a 200, so a mutation
 * the caller may not make is never partly executed.
 *
 * Runs after [ServicePrincipalGate], so the order of refusals is fixed: no valid token is a 401,
 * an unregistered client a 403 naming it, and only a principal the API knows is put to the policy.
 * A request without a bearer token passes untouched - it is on the public allowlist or about to be
 * refused with a 401 - except on an instance with no identity provider, where it is the anonymous
 * reader and is put to the policy as such. Routes on the public allowlist are not put to it at all.
 *
 * Not a Spring bean, so the servlet container does not also run it outside the security chain.
 * [SecurityConfig] hands it the policy, and the rest of its collaborators only where the
 * application has them.
 */
class ScopeGate(
    private val objectMapper: ObjectMapper,
    private val policy: PolicyDecisionPoint,
    private val properties: PolicyProperties = PolicyProperties(),
    private val owners: ResourceOwners? = null,
    private val metrics: PolicyMetrics? = null,
    private val mode: AuthMode = AuthMode.OIDC,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val authentication = SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken
        // Decoded and normalised the way Spring MVC finds the handler, so the policy judges the
        // route that will actually answer.
        val path = PATHS.getPathWithinApplication(request)
        val requirement = ScopePolicy.requirementFor(request.method, path)
        val untouched = authentication == null && mode != AuthMode.ANONYMOUS_READ_ONLY
        if (untouched || requirement == null || requirement is RouteRequirement.Public) {
            chain.doFilter(request, response)
            return
        }

        var forwarded = request
        var document: String? = null
        if (requirement == RouteRequirement.ByGraphQlOperation) {
            val (inspected, read) = graphQlDocument(request)
            forwarded = inspected
            document = read
        }

        val subject = PolicySubjects.of(authentication, properties)
        val resource = RouteAuthz.resource(request.method, path, { request.getParameter(it) }, document)
        val context = mapOf("mode" to mode.wireName, "method" to request.method)
        val decisions =
            try {
                RouteAuthz.actions(request.method, path, requirement, document).map { action ->
                    action to policy.decideWithOwnership(AuthzRequest(subject, action, resource, context), owners)
                }
            } catch (_: PolicyUnavailableException) {
                null
            }
        if (decisions == null) {
            unavailable(response)
        } else {
            answer(Answer(forwarded, response, chain, authentication, subject), decisions)
        }
    }

    /** What the request is let through to, or refused with. */
    private class Answer(
        val request: HttpServletRequest,
        val response: HttpServletResponse,
        val chain: FilterChain,
        val authentication: JwtAuthenticationToken?,
        val subject: Subject,
    )

    private fun answer(
        answer: Answer,
        decisions: List<Pair<AuthzAction, Decision>>,
    ) {
        val refusal = decisions.firstOrNull { it.second.refusedForScope } ?: decisions.firstOrNull { !it.second.allow }
        metrics?.decided(answer.subject.kind, allowed = refusal == null)
        when {
            refusal == null -> answer.chain.doFilter(answer.request, answer.response)
            refusal.second.refusedForScope -> {
                val needed = decisions.flatMap { it.second.required }.distinct().sorted()
                val held = answer.authentication?.let { GrantedScopes.graphScopesOf(it.token.claims) }.orEmpty()
                LogEvents.scopeRefused(InsufficientScope.principalOf(answer.authentication), answer.request.method, needed, held)
                refuse(answer.response, needed, held)
            }
            else -> {
                val (action, decision) = refusal
                LogEvents.policyDenied(answer.subject.id, answer.subject.kind.wireName, action.wireName, decision.policy)
                PolicyRefusal.write(objectMapper, answer.response, decision)
            }
        }
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

    private fun refuse(
        response: HttpServletResponse,
        required: List<String>,
        held: List<String>,
    ) {
        response.status = HttpStatus.FORBIDDEN.value()
        response.setHeader(InsufficientScope.CHALLENGE_HEADER, InsufficientScope.challenge(required))
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(response.outputStream, InsufficientScope.body(required, held))
    }

    private fun unavailable(response: HttpServletResponse) {
        response.status = HttpStatus.SERVICE_UNAVAILABLE.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(response.outputStream, PolicyRefusal.UNAVAILABLE_BODY)
    }

    companion object {
        const val REFUSAL = InsufficientScope.REFUSAL

        /** A GraphQL body larger than this is not read into memory to be inspected; it needs both scopes. */
        const val MAX_INSPECTED_BODY_BYTES = 256 * 1024
        private val PATHS = UrlPathHelper.defaultInstance
    }
}
