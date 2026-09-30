package com.repodatagraph.adapter.`in`.security

import com.repodatagraph.config.ReadsOverPost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import org.springframework.util.ClassUtils
import org.springframework.web.servlet.mvc.method.RequestMappingInfo
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.lang.reflect.Method

/**
 * No route is left unguarded (#116, FR-4). Every handler any controller in the application maps is
 * found by reading its mapping the way Spring MVC does, and [ScopePolicy] must declare a requirement
 * for it or list it as public. A controller added without thinking about scopes fails here, before
 * it can serve anyone.
 *
 * GraphQL is one route, so its operations are checked on their own: every query field the resolvers
 * serve needs graph:read, and every mutation field graph:write.
 */
class ScopePolicyCoverageTest {
    private data class Route(
        val method: String,
        val pattern: String,
    ) {
        val samplePath: String get() = pattern.replace(VARIABLE, "sample")

        override fun toString() = "$method $pattern"
    }

    /** Exposes how Spring MVC reads a handler method's mapping, without starting an application. */
    private class Mappings : RequestMappingHandlerMapping() {
        fun of(
            method: Method,
            type: Class<*>,
        ): RequestMappingInfo? = getMappingForMethod(method, type)
    }

    private val controllers: List<Class<*>> =
        ClassPathScanningCandidateComponentProvider(false)
            .apply { addIncludeFilter(AnnotationTypeFilter(Controller::class.java)) }
            .findCandidateComponents("com.repodatagraph")
            .map { ClassUtils.forName(checkNotNull(it.beanClassName), javaClass.classLoader) }
            // The application's own controllers, not the ones tests declare to drive a filter.
            .filter { it.protectionDomain.codeSource.location == ScopePolicy::class.java.protectionDomain.codeSource.location }

    private val routes: List<Route> by lazy {
        val mappings = Mappings()
        controllers
            .flatMap { type ->
                type.methods.mapNotNull { mappings.of(it, type) }.flatMap { info ->
                    val methods =
                        info.methodsCondition.methods
                            .map { it.name }
                            .ifEmpty { ANY_METHOD }
                    info.patternValues.flatMap { pattern -> methods.map { Route(it, pattern) } }
                }
            }.distinct()
    }

    @Test
    fun `the walk finds the application's routes`() {
        assertTrue(Route("POST", "/api/v1/nodes/{type}") in routes, "routes found: $routes")
        assertTrue(Route("GET", "/api/v1/service-principals") in routes, "routes found: $routes")
    }

    @Test
    fun `the graph view's neighbourhood is mapped, and is a read`() {
        assertTrue(Route("GET", "/api/v1/graph/neighbourhood") in routes, "routes found: $routes")
        assertEquals(RouteRequirement.Scopes(setOf(GraphScope.READ)), ScopePolicy.requirementFor("GET", "/api/v1/graph/neighbourhood"))
    }

    @Test
    fun `the repository lookup by provider id is mapped, and is a read (#88)`() {
        assertTrue(Route("GET", "/api/v1/repositories/by-provider/{provider}/{providerId}") in routes, "routes found: $routes")
        assertEquals(
            RouteRequirement.Scopes(setOf(GraphScope.READ)),
            ScopePolicy.requirementFor("GET", "/api/v1/repositories/by-provider/github/123456"),
        )
    }

    @Test
    fun `the change lineage queries are mapped, and are reads (#85)`() {
        listOf("/api/v1/work-items/deployments", "/api/v1/deployments/work-items").forEach { path ->
            assertTrue(Route("GET", path) in routes, "routes found: $routes")
            assertEquals(RouteRequirement.Scopes(setOf(GraphScope.READ)), ScopePolicy.requirementFor("GET", path))
        }
    }

    @Test
    fun `a node read by key is a read, and changing it by key is a write (#85)`() {
        assertTrue(Route("GET", "/api/v1/nodes/{type}/by-key") in routes, "routes found: $routes")
        assertEquals(
            RouteRequirement.Scopes(setOf(GraphScope.READ)),
            ScopePolicy.requirementFor("GET", "/api/v1/nodes/ExternalWorkItem/by-key"),
        )
        assertEquals(
            RouteRequirement.Scopes(setOf(GraphScope.WRITE)),
            ScopePolicy.requirementFor("PUT", "/api/v1/nodes/ExternalWorkItem/by-key"),
        )
        assertEquals(
            RouteRequirement.Scopes(setOf(GraphScope.WRITE)),
            ScopePolicy.requirementFor("DELETE", "/api/v1/nodes/ExternalWorkItem/by-key"),
        )
    }

    @Test
    fun `how far behind each source is is mapped, and is a read (#93)`() {
        assertTrue(Route("GET", "/api/v1/freshness") in routes, "routes found: $routes")
        assertEquals(RouteRequirement.Scopes(setOf(GraphScope.READ)), ScopePolicy.requirementFor("GET", "/api/v1/freshness"))
    }

    @Test
    fun `the GraphQL reads as of an instant are query fields, so reads (#93)`() {
        val queries =
            controllers
                .flatMap { it.methods.toList() }
                .filter { it.isAnnotationPresent(QueryMapping::class.java) }
                .map { it.name }
        assertTrue(queries.containsAll(listOf("node", "edges")), "query fields: $queries")
    }

    @Test
    fun `every mapped route has a scope requirement or is on the public allowlist`() {
        val undeclared = routes.filter { ScopePolicy.requirementFor(it.method, it.samplePath) == null }

        assertEquals(emptyList<Route>(), undeclared, "routes with no declared requirement")
    }

    @Test
    fun `every read under the API needs graph read and every write graph write, unless it is public`() {
        assertTrue(Route("POST", "/api/v1/lifecycle/archive") in routes, "the archive is mapped: $routes")
        assertTrue(Route("POST", "/api/v1/impact") in routes, "the read over POST is mapped: $routes")
        val wrong =
            routes.filter { it.pattern.startsWith("/api/v1/") }.mapNotNull { route ->
                val requirement = ScopePolicy.requirementFor(route.method, route.samplePath)
                val read = route.method in READ_METHODS || (route.method == "POST" && route.pattern in ReadsOverPost.PATHS)
                val administrative = !read && route.pattern.startsWith(LIFECYCLE)
                val expected =
                    when {
                        read -> setOf(GraphScope.READ)
                        // Archiving and migrating (#33) are writes that need graph:admin as well.
                        administrative -> setOf(GraphScope.WRITE, GraphScope.ADMIN)
                        else -> setOf(GraphScope.WRITE)
                    }
                when (requirement) {
                    is RouteRequirement.Public -> null
                    is RouteRequirement.Scopes -> if (requirement.scopes == expected) null else "$route needs ${requirement.scopes}"
                    else -> "$route is $requirement"
                }
            }

        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun `the GraphQL endpoint is declared, by the operations its document holds`() {
        assertEquals(RouteRequirement.ByGraphQlOperation, ScopePolicy.requirementFor("POST", "/graphql"))
    }

    @Test
    fun `every GraphQL query field needs graph read and every mutation field graph write`() {
        val resolvers = controllers.flatMap { it.methods.toList() }
        val queries = resolvers.filter { it.isAnnotationPresent(QueryMapping::class.java) }.map { it.name }
        val mutations = resolvers.filter { it.isAnnotationPresent(MutationMapping::class.java) }.map { it.name }
        assertTrue(queries.isNotEmpty() && mutations.isNotEmpty(), "queries $queries, mutations $mutations")

        queries.forEach { assertEquals(setOf(GraphScope.READ), GraphQlScopes.required("{ $it }"), "query $it") }
        mutations.forEach { assertEquals(setOf(GraphScope.WRITE), GraphQlScopes.required("mutation { $it }"), "mutation $it") }
    }

    private companion object {
        const val LIFECYCLE = "/api/v1/lifecycle/"
        val ANY_METHOD = listOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
        val READ_METHODS = setOf("GET", "HEAD")
        val VARIABLE = Regex("\\{\\*?[^}]+}")
    }
}
