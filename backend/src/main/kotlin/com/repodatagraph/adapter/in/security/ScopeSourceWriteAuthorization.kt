package com.repodatagraph.adapter.`in`.security

import com.repodatagraph.adapter.out.policy.WasmPolicyDecisionPoint
import com.repodatagraph.config.PolicyProperties
import com.repodatagraph.domain.exception.SourceNotPermittedException
import com.repodatagraph.domain.policy.AuthzAction
import com.repodatagraph.domain.policy.AuthzRequest
import com.repodatagraph.domain.policy.AuthzResource
import com.repodatagraph.domain.policy.Decision
import com.repodatagraph.domain.policy.ResourceKind
import com.repodatagraph.domain.policy.StatedProvenanceClaim
import com.repodatagraph.domain.port.out.PolicyDecisionPoint
import com.repodatagraph.domain.port.out.SourceWriteAuthorization
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component

/**
 * Whether the principal behind the current request may state facts as a source system (#117), asked
 * of the authorisation policy (#95 FR-3). A write through the API is recorded at confidence 1.0 and
 * not inferred, so naming a source other than `manual` claims to be its system of record, which needs
 * `graph:write:<source>`: the rule `provenance.confidence` in policy/sdlc/authz/provenance.rego.
 *
 * A write can only get here with a token: the anonymous read-only mode refuses every write before it
 * reaches a controller (#118). One that somehow has none holds no scope, and is refused rather than
 * waved through.
 *
 * A refusal for a scope is [SourceNotPermittedException], which [ScopeRefusalAdvice] answers with the
 * 403 every missing scope gets, naming the rule that refused it. The policy defaults to the bundle
 * the API was built with, for a test slice or a unit test that builds this on its own.
 */
@Component
class ScopeSourceWriteAuthorization(
    private val policy: PolicyDecisionPoint = WasmPolicyDecisionPoint.classpathDefault(),
    private val properties: PolicyProperties = PolicyProperties(),
) : SourceWriteAuthorization {
    override fun authorize(sourceSystem: String) {
        val authentication = SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken
        val claim = StatedProvenanceClaim(sourceSystem, confidence = SYSTEM_OF_RECORD, inferred = false)
        val subject = PolicySubjects.of(authentication, properties)
        val decision =
            policy.decide(
                AuthzRequest(
                    subject = subject,
                    action = AuthzAction.CREATE,
                    resource = AuthzResource(ResourceKind.NODE, provenance = claim),
                ),
            )
        // Only the rules about scopes and systems of record are this question's. The rest - roles,
        // ownership, an agent's limits - were already decided for the request itself by the gate,
        // which knew its action and the node's owners; asking them again here would not.
        if (decision.allow || decision.policy !in OWN_RULES) return
        val held = authentication?.let { GrantedScopes.graphScopesOf(it.token.claims) }.orEmpty()
        throw SourceNotPermittedException(sourceSystem, decision.required, held, decision.policy)
    }

    private companion object {
        const val SYSTEM_OF_RECORD = 1.0
        val OWN_RULES = setOf(Decision.SCOPES, Decision.PROVENANCE)
    }
}
