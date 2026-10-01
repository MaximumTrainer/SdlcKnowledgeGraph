package com.repodatagraph.adapter.`in`.security

import com.repodatagraph.adapter.out.policy.WasmPolicyDecisionPoint
import com.repodatagraph.domain.policy.AuthzAction
import com.repodatagraph.domain.policy.AuthzRequest
import com.repodatagraph.domain.policy.AuthzResource
import com.repodatagraph.domain.policy.Decision
import com.repodatagraph.domain.policy.ResourceKind
import com.repodatagraph.domain.policy.Subject
import com.repodatagraph.domain.policy.SubjectKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.stereotype.Controller
import org.springframework.util.ClassUtils
import org.springframework.web.servlet.mvc.method.RequestMappingInfo
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.lang.reflect.Method

/**
 * The shipped policy is not a breaking change (#30, ADR-0020): for every route the application maps,
 * and every combination of graph scopes a token could hold, the policy allows exactly what the scope
 * check of #116 allowed, and refuses the rest naming the same scopes.
 *
 * The routes are found as [ScopePolicyCoverageTest] finds them, so a route added later is held to
 * the same answer. The caller has no roles claim, as every caller before roles existed had none.
 */
class DefaultPolicyParityTest {
    private val policy = WasmPolicyDecisionPoint.classpathDefault()

    private class Mappings : RequestMappingHandlerMapping() {
        fun of(
            method: Method,
            type: Class<*>,
        ): RequestMappingInfo? = getMappingForMethod(method, type)
    }

    private val routes: List<Pair<String, String>> by lazy {
        val mappings = Mappings()
        ClassPathScanningCandidateComponentProvider(false)
            .apply { addIncludeFilter(AnnotationTypeFilter(Controller::class.java)) }
            .findCandidateComponents("com.repodatagraph")
            .map { ClassUtils.forName(checkNotNull(it.beanClassName), javaClass.classLoader) }
            .filter { it.protectionDomain.codeSource.location == ScopePolicy::class.java.protectionDomain.codeSource.location }
            .flatMap { type ->
                type.methods.mapNotNull { mappings.of(it, type) }.flatMap { info ->
                    val methods =
                        info.methodsCondition.methods
                            .map { it.name }
                            .ifEmpty { listOf("GET", "POST") }
                    info.patternValues.flatMap { pattern -> methods.map { it to pattern.replace(VARIABLE, "sample") } }
                }
            }.distinct()
    }

    private val scopeSets: List<Set<String>> =
        listOf(
            emptySet(),
            setOf("graph:read"),
            setOf("graph:write"),
            setOf("graph:read", "graph:write"),
            setOf("graph:read", "graph:write", "graph:admin"),
            setOf("graph:admin"),
            setOf("graph:write:github"),
        )

    private fun decide(
        method: String,
        path: String,
        requirement: RouteRequirement,
        scopes: Set<String>,
        document: String? = null,
    ): List<Decision> {
        val subject = Subject("dan", SubjectKind.USER, scopes)
        val resource = RouteAuthz.resource(method, path, { "Repository:sample" }, document)
        return RouteAuthz.actions(method, path, requirement, document).map { policy.decide(AuthzRequest(subject, it, resource)) }
    }

    @Test
    fun `every REST route is allowed by the policy exactly when its scopes are held`() {
        var compared = 0
        routes.forEach { (method, path) ->
            val requirement = ScopePolicy.requirementFor(method, path) as? RouteRequirement.Scopes ?: return@forEach
            val needed = requirement.scopes.map { it.value }.toSortedSet()
            scopeSets.forEach { held ->
                val decisions = decide(method, path, requirement, held)
                val allowedByScopes = held.containsAll(needed)
                val label = "$method $path with $held"
                assertEquals(allowedByScopes, decisions.all { it.allow }, "$label: ${decisions.map { it.reason }}")
                if (!allowedByScopes) {
                    val refusal = decisions.first { !it.allow }
                    assertEquals(Decision.SCOPES, refusal.policy, label)
                    assertEquals(needed.toList(), decisions.flatMap { it.required }.distinct().sorted(), label)
                }
                compared++
            }
        }
        assertTrue(compared > 100, "only $compared route and scope combinations were compared")
    }

    @Test
    fun `GraphQL is allowed exactly when the scopes its operations need are held`() {
        val documents =
            listOf(
                "{ node(id: \"Repository:x\") { id } }",
                "{ repositories { id } }",
                "mutation { createRepository(input: {url: \"https://github.com/acme/x\"}) { id } }",
                "query Q { repositories { id } } mutation M { deleteRepository(id: \"x\") }",
                "not graphql at all",
            )
        documents.forEach { document ->
            val needed = GraphQlScopes.required(document).map { it.value }.toSortedSet()
            scopeSets.forEach { held ->
                val decisions = decide("POST", "/graphql", RouteRequirement.ByGraphQlOperation, held, document)
                assertEquals(held.containsAll(needed), decisions.all { it.allow }, "$document with $held")
            }
        }
    }

    @Test
    fun `a caller without roles is cleared for everything, so nothing is hidden or redacted`() {
        val decision =
            policy.decide(
                AuthzRequest(
                    Subject("dan", SubjectKind.USER, setOf("graph:read")),
                    AuthzAction.READ,
                    AuthzResource(ResourceKind.NODE, type = "Team"),
                ),
            )

        assertTrue(decision.allow)
        assertEquals("restricted", decision.clearance)
        assertEquals(emptyList<String>(), decision.redact)
    }

    private companion object {
        val VARIABLE = Regex("\\{\\*?[^}]+}")
    }
}
